package com.wbswitch.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 签到逻辑，语义对照 wb-switch-core checkin.rs / refresh.rs：
 * 惰性刷新 → 查状态 → 未签到时提交；「已签到」按成功处理；国际版直接跳过。
 */
public final class Checkin {

    /** 距过期不足该阈值或已过期时刷新（lazy_refresh_hours = 24）。 */
    private static final long LAZY_REFRESH_MS = 24L * 3600 * 1000;

    private static final SimpleDateFormat DAY_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private static final Set<String> RUNNING = new HashSet<>();

    private Checkin() {}

    /** put 的免检包装：这里的键值类型不会触发 JSONException。 */
    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- refresh

    /** 全局刷新锁：App 内线程（签到/状态刷新/积分查询）与 WorkManager 的
     *  定时签到、保活可能同时对同一账号刷新；官方 rotate refresh token 时，
     *  后到的那次会拿旧凭证换到 401，把账号误标"需重新登录"，甚至用旧对象
     *  覆盖刚落盘的新凭证。刷新本身低频，全局串行即可。 */
    private static final Object REFRESH_LOCK = new Object();

    /** 刷新单账号 token；成功落盘，失败标记 needs_relogin（refresh.rs）。 */
    public static JSONObject refreshToken(Context ctx, JSONObject account) {
        synchronized (REFRESH_LOCK) {
            return refreshTokenLocked(ctx, account);
        }
    }

    private static JSONObject refreshTokenLocked(Context ctx, JSONObject account) {
        Context appCtx = ctx.getApplicationContext();
        // 采纳落盘的最新凭证：另一个线程可能刚刚刷新过（refreshedAt 更新），
        // 此时调用方手里的对象已过期，直接用它即可，别再拿旧凭证去请求
        String myId = account.optString("id", "");
        if (!myId.isEmpty()) {
            for (JSONObject latest : AccountStore.loadAccounts(appCtx)) {
                if (!myId.equals(latest.optString("id", ""))) continue;
                Long myAt = Api.normTs(account.opt("refreshedAt"));
                Long latestAt = Api.normTs(latest.opt("refreshedAt"));
                String latestRt = latest.optString("refresh_token", "");
                if (latestAt != null && (myAt == null || latestAt > myAt)
                        && !latestRt.isEmpty()
                        && !latestRt.equals(account.optString("refresh_token", ""))) {
                    copyTokenFields(latest, account);
                }
                break;
            }
        }
        // 采纳后凭证已新鲜（距过期大于懒刷新阈值）就无需再请求
        if (!isTokenStale(account)) {
            return account;
        }
        String rt = account.optString("refresh_token", "");
        if (rt.isEmpty()) {
            put(account, "needs_relogin", true);
            put(account, "needs_relogin_reason", "缺少 refresh token，无法刷新，需重新登录");
            AccountStore.upsertAccount(appCtx, account);
            return account;
        }
        JSONObject resp = Api.request(Api.refreshUrl(account.optString("variant", "cn")),
                "POST", new JSONObject(), Api.refreshHeaders(account, rt));
        if (!Api.codeOk(resp)) {
            String msg = firstMessage(resp, resp.optJSONObject("data"));
            long code = resp.optLong("code", -1);
            // 网络层错误（code=-1）不直接标记需重新登录，避免临时断网导致账号被冻结
            if (code == -1) {
                put(account, "last_refresh_error", "刷新失败(网络异常): " + msg);
                put(account, "last_refresh_error_at", Api.nowMs());
            } else {
                put(account, "needs_relogin", true);
                put(account, "needs_relogin_reason", "刷新失败(code=" + code + "): " + msg);
                account.remove("last_refresh_error");
                account.remove("last_refresh_error_at");
            }
            AccountStore.upsertAccount(appCtx, account);
            return account;
        }
        JSONObject data = resp.optJSONObject("data");
        String newAt = data == null ? "" : firstNonEmpty(
                data.optString("accessToken", ""), data.optString("access_token", ""));
        if (newAt.isEmpty()) {
            put(account, "needs_relogin", true);
            put(account, "needs_relogin_reason", "刷新响应缺少 accessToken");
            AccountStore.upsertAccount(appCtx, account);
            return account;
        }
        put(account, "access_token", newAt);
        String newRt = firstNonEmpty(
                data.optString("refreshToken", ""), data.optString("refresh_token", ""));
        if (!newRt.isEmpty()) put(account, "refresh_token", newRt);
        Long exp = data == null ? null : firstTs(data.opt("expiresAt"), data.opt("expires_at"));
        if (exp == null && data != null) {
            long in = data.optLong("expiresIn", 0);
            if (in > 0) exp = Api.nowMs() + in * 1000;
        }
        if (exp != null) put(account, "expiresAt", exp);
        Long rtExp = data == null ? null
                : firstTs(data.opt("refreshExpiresAt"), data.opt("refresh_expires_at"));
        if (rtExp == null) {
            JSONObject authRaw = account.optJSONObject("auth_raw");
            if (authRaw != null) rtExp = Api.normTs(authRaw.opt("refreshExpiresAt"));
        }
        if (rtExp == null && data != null) {
            long in = data.optLong("refreshExpiresIn", 0);
            if (in > 0) rtExp = Api.nowMs() + in * 1000;
        }
        if (rtExp != null) put(account, "refreshExpiresAt", rtExp);
        put(account, "needs_relogin", false);
        account.remove("needs_relogin_reason");
        account.remove("last_refresh_error");
        account.remove("last_refresh_error_at");
        put(account, "refreshedAt", Api.nowMs());
        AccountStore.upsertAccount(appCtx, account);
        return account;
    }

    /** 惰性刷新：无过期时间 / 已过期 / 24 小时内过期 且有 refresh token。 */
    public static JSONObject ensureFreshToken(Context ctx, JSONObject account) {
        if (isTokenStale(account)) {
            String rt = account.optString("refresh_token", "");
            if (!rt.isEmpty()) {
                return refreshToken(ctx, account);
            }
        }
        return account;
    }

    /** 无过期时间 / 已过期 / 距过期不足 24 小时 视为需要刷新。 */
    private static boolean isTokenStale(JSONObject account) {
        Long exp = Api.normTs(account.opt("expiresAt"));
        return exp == null || Api.nowMs() >= exp || exp - Api.nowMs() < LAZY_REFRESH_MS;
    }

    /** 把落盘账号的凭证字段拷到调用方对象上（仅 token 相关，其余字段不动）。 */
    private static void copyTokenFields(JSONObject from, JSONObject to) {
        for (String key : new String[]{"access_token", "refresh_token",
                "expiresAt", "refreshExpiresAt", "refreshedAt"}) {
            if (from.has(key)) {
                try { to.put(key, from.opt(key)); } catch (Exception ignored) {}
            }
        }
        if (!from.optBoolean("needs_relogin", false)) {
            put(to, "needs_relogin", false);
            to.remove("needs_relogin_reason");
        }
    }

    // ---------------------------------------------------------------- checkin

    /** 单次签到请求：未授权且有 refresh token 时刷新一次并重试（checkin_request_once）。 */
    private static JSONObject checkinRequestOnce(String path, Context ctx, JSONObject account) {
        String envErr = Api.envelopeTokenError(account);
        if (envErr != null) {
            JSONObject o = new JSONObject();
            put(o, "code", -2);
            put(o, "message", envErr);
            return o;
        }
        String url = Api.endpoint(account.optString("variant", "cn")) + path;
        Map<String, String> headers = Api.authHeaders(account);
        JSONObject resp = Api.request(url, "POST", new JSONObject(), headers);
        String rt = account.optString("refresh_token", "");
        if (Api.isUnauthorized(resp) && !rt.isEmpty()) {
            JSONObject refreshed = refreshToken(ctx, account);
            // 刷新失败（needs_relogin=true）时不再用旧 token 重试，避免浪费一次必然 401 的请求
            if (refreshed.optBoolean("needs_relogin", false)) {
                return resp;
            }
            resp = Api.request(url, "POST", new JSONObject(), Api.authHeaders(refreshed));
        }
        return resp;
    }

    /** 签到相关请求：只有 404 才回落到下一个路径候选（checkin_request）。 */
    private static JSONObject checkinRequest(Context ctx, JSONObject account, String suffix) {
        List<String> paths = Api.billingPaths(account.optString("variant", "cn"), suffix);
        JSONObject last = null;
        for (int i = 0; i < paths.size(); i++) {
            JSONObject resp = checkinRequestOnce(paths.get(i), ctx, account);
            if (i + 1 == paths.size() || !Api.isRouteMissing(resp)) return resp;
            last = resp;
        }
        JSONObject o = new JSONObject();
        put(o, "code", -1);
        put(o, "message", "无可用签到路径");
        return last == null ? o : last;
    }

    /** 查询签到状态：checkin-activity-status 仅在 404（路由不存在）时回落
     *  checkin-status；401/5xx 等直接返回失败（避免对已失效凭证重复请求），
     *  失败响应携带 code 供上层判定是否需要重新登录。 */
    public static JSONObject getCheckinStatus(Context ctx, JSONObject account) {
        String variant = account.optString("variant", "cn");
        if ("cn".equals(variant)) {
            JSONObject last = null;
            for (String suffix : new String[]{"/checkin-activity-status", "/checkin-status"}) {
                JSONObject resp = checkinRequest(ctx, account, suffix);
                JSONObject status = statusFromResponse(resp);
                if (status != null) return status;
                last = resp;
                if (!Api.isRouteMissing(resp)) break;
            }
            if (last != null) {
                JSONObject o = new JSONObject();
                put(o, "ok", false);
                put(o, "error", firstMessage(last, last.optJSONObject("data")));
                put(o, "code", last.optLong("code", -1));
                return o;
            }
        }
        JSONObject o = new JSONObject();
        put(o, "ok", false);
        put(o, "statusUnsupported", true);
        put(o, "error", "该档位暂无签到状态接口");
        return o;
    }

    private static JSONObject statusFromResponse(JSONObject resp) {
        if (!Api.codeOk(resp)) return null;
        JSONObject data = resp.optJSONObject("data");
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        boolean checked = data != null && (data.optBoolean("today_checked_in", false)
                || data.optBoolean("todayCheckedIn", false));
        put(o, "todayCheckedIn", checked);
        return o;
    }

    /** 执行签到（daily-checkin）；「已签到」按成功处理（perform_checkin）。 */
    public static JSONObject performCheckin(Context ctx, JSONObject account) {
        JSONObject resp = checkinRequest(ctx, account, "/daily-checkin");
        JSONObject o = new JSONObject();
        if (Api.codeOk(resp)) {
            put(o, "ok", true);
            return o;
        }
        String msg = firstMessage(resp, resp.optJSONObject("data"));
        put(o, "error", msg);
        if (msg.contains("已签到") || msg.toLowerCase(Locale.ROOT).contains("repeat")) {
            put(o, "ok", true);
            put(o, "already", true);
        }
        return o;
    }

    /** 对单个账号执行完整签到流程（checkin_account）。返回 {result, error?}。 */
    public static JSONObject checkinAccount(Context ctx, JSONObject accountIn) {
        Context appCtx = ctx.getApplicationContext();
        String key = accountKey(accountIn);
        synchronized (RUNNING) {
            if (!RUNNING.add(key)) {
                return result("error", "该账号正在签到，请稍后再试");
            }
        }
        try {
            if (!Api.supportsCheckin(accountIn.optString("variant", "cn"))) {
                return result("skipped", null);
            }
            JSONObject account = ensureFreshToken(ctx, accountIn);
            String display = AccountStore.displayName(account);
            JSONObject status = getCheckinStatus(ctx, account);
            boolean statusUnsupported = status.optBoolean("statusUnsupported", false);
            if (!statusUnsupported && !status.optBoolean("ok", false)) {
                // 401/403 且账号没有续期凭证：tok en 已被服务端拒绝且无法自愈，
                // 明确标记需要重新登录，而不是一直误导性地显示"去签到"
                long code = status.optLong("code", -1);
                boolean unauthorized = code == 401 || code == 403
                        || status.optString("error", "").contains("401");
                if (unauthorized && account.optString("refresh_token", "").isEmpty()) {
                    put(account, "needs_relogin", true);
                    put(account, "needs_relogin_reason", "登录凭证已被服务端拒绝且无法自动换新，请删除账号后重新登录");
                    AccountStore.upsertAccount(appCtx, account);
                }
                return result("error", status.optString("error", "查询签到状态失败"));
            }
            if (!statusUnsupported && status.optBoolean("todayCheckedIn", false)) {
                return result("already", null);
            }
            JSONObject res = performCheckin(ctx, account);
            String entryResult = res.optBoolean("ok", false)
                    ? (res.optBoolean("already", false) ? "already" : "success")
                    : "error";
            JSONObject entry = new JSONObject();
            put(entry, "ts", Api.nowMs());
            put(entry, "accountId", account.optString("id", ""));
            put(entry, "email", display);
            put(entry, "variant", account.optString("variant", "cn"));
            put(entry, "result", entryResult);
            if ("error".equals(entryResult)) {
                put(entry, "error", res.optString("error", ""));
            }
            AccountStore.addCheckinLog(appCtx, entry);
            return result(entryResult,
                    "error".equals(entryResult) ? res.optString("error", "") : null);
        } catch (Exception e) {
            // 捕获 RuntimeException（如磁盘满/IO错误导致日志保存失败），避免异常被线程池吞掉
            return result("error", "签到异常: " + e.getMessage());
        } finally {
            synchronized (RUNNING) {
                RUNNING.remove(key);
            }
        }
    }

    /** 对全部账号签到，返回逐账号结果行（run_checkin_all）。 */
    public static JSONArray checkinAll(Context ctx) {
        return checkinAll(ctx, java.util.Collections.emptyList());
    }

    /** 对全部账号签到，可排除指定账号 id（run_checkin_all）。 */
    public static JSONArray checkinAll(Context ctx, java.util.List<String> excludeIds) {
        JSONArray rows = new JSONArray();
        java.util.Set<String> excludeSet = excludeIds == null
                ? java.util.Collections.emptySet()
                : new java.util.HashSet<>(excludeIds);
        for (JSONObject acc : AccountStore.loadAccounts(ctx)) {
            if (excludeSet.contains(acc.optString("id", ""))) continue;
            JSONObject r = checkinAccount(ctx, acc);
            JSONObject row = new JSONObject();
            put(row, "email", AccountStore.displayName(acc));
            put(row, "result", r.optString("result", "error"));
            if (r.has("error")) put(row, "error", r.optString("error", ""));
            rows.put(row);
        }
        return rows;
    }

    public static String dateStr(long tsMs) {
        return DAY_FMT.format(new Date(tsMs));
    }

    public static String accountKey(JSONObject account) {
        String id = account.optString("id", "");
        return id.isEmpty() ? AccountStore.displayName(account) : id;
    }

    private static JSONObject result(String result, String error) {
        JSONObject o = new JSONObject();
        put(o, "result", result);
        if (error != null && !error.isEmpty()) put(o, "error", error);
        return o;
    }

    private static String firstMessage(JSONObject resp, JSONObject data) {
        String msg = resp.optString("message", "");
        if (msg.isEmpty()) msg = resp.optString("msg", "");
        if (msg.isEmpty() && data != null) msg = data.optString("message", "");
        return msg.isEmpty() ? "code=" + resp.optLong("code", -1) : msg;
    }

    private static Long firstTs(Object a, Object b) {
        Long ts = Api.normTs(a);
        return ts != null ? ts : Api.normTs(b);
    }

    private static String firstNonEmpty(String a, String b) {
        return a.isEmpty() ? b : a;
    }
}
