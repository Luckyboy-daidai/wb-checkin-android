package com.wbswitch.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 派猫猫旅行：状态查询、派发、领奖。
 * 对照桌面版 travel.rs 的简化实现；仅支持国内版。
 */
public final class Travel {

    private static final String PREFIX = "/activity/growth/buddy/travel";
    private static final Map<String, Boolean> RUNNING = new ConcurrentHashMap<>();

    private Travel() {}

    public static boolean supportsTravel(JSONObject account) {
        return !Api.isAi(account.optString("variant", ""));
    }

    private static Map<String, String> travelHeaders(JSONObject account) {
        Map<String, String> h = Api.authHeaders(account);
        h.put("x-client-platform", "web");
        h.put("origin", Api.EP_CN);
        h.put("referer", Api.EP_CN + "/profile/growth-center");
        return h;
    }

    private static JSONObject travelRequest(Context ctx, JSONObject account, String method,
                                            String path, JSONObject body) {
        if (Api.envelopeTokenError(account) != null) {
            return response(-2, Api.envelopeTokenError(account));
        }
        Checkin.ensureFreshToken(ctx, account);
        String url = Api.EP_CN + path;
        Map<String, String> headers = travelHeaders(account);
        JSONObject resp = Api.request(url, method, body, headers);
        if (Api.isUnauthorized(resp) && !account.optString("refresh_token", "").isEmpty()) {
            account = Checkin.refreshToken(ctx, account);
            headers = travelHeaders(account);
            resp = Api.request(url, method, body, headers);
        }
        return resp;
    }

    private static JSONObject response(long code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("code", code);
            o.put("message", message);
        } catch (Exception ignored) {}
        return o;
    }

    public static JSONObject fetchConfig(Context ctx, JSONObject account) {
        JSONObject resp = travelRequest(ctx, account, "GET", PREFIX + "/config", null);
        JSONObject out = new JSONObject();
        try {
            if (Api.codeOk(resp)) {
                JSONObject data = resp.optJSONObject("data");
                if (data == null) data = new JSONObject();
                JSONArray locs = data.optJSONArray("locations");
                JSONArray locations = new JSONArray();
                if (locs != null) {
                    for (int i = 0; i < locs.length(); i++) {
                        JSONObject l = locs.optJSONObject(i);
                        if (l == null) continue;
                        JSONObject n = new JSONObject();
                        n.put("id", l.opt("id"));
                        n.put("name", l.optString("name", ""));
                        locations.put(n);
                    }
                }
                boolean enabled = data.optBoolean("enabled", true) && locations.length() > 0;
                out.put("ok", true);
                out.put("enabled", enabled);
                out.put("locations", locations);
            } else {
                out.put("ok", false);
                out.put("error", respError(resp));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject fetchStatus(Context ctx, JSONObject account) {
        JSONObject resp = travelRequest(ctx, account, "GET", PREFIX + "/status", null);
        JSONObject out = new JSONObject();
        try {
            if (Api.codeOk(resp)) {
                JSONObject data = resp.optJSONObject("data");
                if (data == null) data = new JSONObject();
                String state = data.optString("state", "").trim().toLowerCase();
                if (!state.equals("idle") && !state.equals("traveling") && !state.equals("arrived")) {
                    out.put("ok", false);
                    out.put("error", state.isEmpty() ? "missing travel state" : "unknown travel state: " + state);
                    return out;
                }
                out.put("ok", true);
                out.put("state", state);
                out.put("dailyLimitReached", data.optBoolean("daily_limit_reached", false));
                out.put("recordId", data.optLong("record_id", 0));
                out.put("arriveAt", data.optLong("arrive_at", 0));
                JSONObject loc = data.optJSONObject("location");
                out.put("locationName", loc == null ? "" : loc.optString("name", ""));
                out.put("rewardCredit", data.opt("reward_credit"));
            } else {
                out.put("ok", false);
                out.put("error", respError(resp));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject depart(Context ctx, JSONObject account, Object locationId) {
        JSONObject body = new JSONObject();
        try { body.put("location_id", locationId); } catch (Exception ignored) {}
        JSONObject resp = travelRequest(ctx, account, "POST", PREFIX + "/depart", body);
        JSONObject out = new JSONObject();
        try {
            if (Api.codeOk(resp)) {
                JSONObject data = resp.optJSONObject("data");
                out.put("ok", true);
                out.put("state", data == null ? "traveling" : data.optString("state", "traveling"));
            } else {
                out.put("ok", false);
                out.put("code", resp.optLong("code", -1));
                out.put("message", respError(resp));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject claim(Context ctx, JSONObject account, long recordId) {
        JSONObject body = new JSONObject();
        if (recordId > 0) {
            try { body.put("record_id", recordId); } catch (Exception ignored) {}
        }
        JSONObject resp = travelRequest(ctx, account, "POST", PREFIX + "/claim", body);
        JSONObject out = new JSONObject();
        try {
            if (Api.codeOk(resp)) {
                JSONObject data = resp.optJSONObject("data");
                out.put("ok", true);
                if (data != null) out.put("rewardCredit", data.opt("reward_credit"));
            } else {
                out.put("ok", false);
                out.put("message", respError(resp));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String respError(JSONObject resp) {
        String msg = resp.optString("message", "");
        if (msg.isEmpty()) msg = resp.optString("msg", "");
        if (msg.isEmpty()) msg = "code=" + resp.optLong("code", -1);
        return msg;
    }

    private static String accountKey(JSONObject account) {
        String id = account.optString("id", "");
        return id.isEmpty() ? AccountStore.displayName(account) : id;
    }

    /** 对单个账号执行一次派发或领奖，返回结果对象。 */
    public static JSONObject runForAccount(Context ctx, JSONObject account) {
        if (!supportsTravel(account)) {
            JSONObject o = new JSONObject();
            try {
                o.put("accountId", accountKey(account));
                o.put("ok", true);
                o.put("already", true);
                o.put("skip", "unsupported");
                o.put("message", "国际版账号，跳过");
            } catch (Exception ignored) {}
            return o;
        }

        String key = accountKey(account);
        if (RUNNING.putIfAbsent(key, true) != null) {
            JSONObject o = new JSONObject();
            try {
                o.put("accountId", key);
                o.put("ok", false);
                o.put("message", "该账号正在处理旅行，请稍后再试");
            } catch (Exception ignored) {}
            return o;
        }
        try {
            return syncAccount(ctx, account);
        } finally {
            RUNNING.remove(key);
        }
    }

    private static JSONObject syncAccount(Context ctx, JSONObject account) {
        JSONObject status = fetchStatus(ctx, account);
        JSONObject result = newResult(account, true, false, null, null, "");
        try {
            if (!status.optBoolean("ok", false)) {
                result.put("ok", false);
                result.put("message", status.optString("error", "查询旅行状态失败"));
                return result;
            }
            String state = status.optString("state", "");
            boolean dailyLimit = status.optBoolean("dailyLimitReached", false);
            long recordId = status.optLong("recordId", 0);
            switch (state) {
                case "arrived":
                    return doClaim(ctx, account, recordId, result, status);
                case "traveling":
                    result.put("already", true);
                    result.put("state", "traveling");
                    result.put("message", "已在旅行中");
                    copyStatus(result, status);
                    return result;
                case "idle":
                    if (dailyLimit) {
                        result.put("already", true);
                        result.put("state", "idle");
                        result.put("skip", "daily-limit");
                        result.put("message", "今日已派");
                        // v1.4.8：不再置 claimed=true——本次无动作应计"跳过"；
                        // classifyState 对 idle+daily-limit 同样判 STATE_DAILY_LIMIT，UI 不受影响
                        return result;
                    }
                    return doDepart(ctx, account, result);
                default:
                    result.put("ok", false);
                    result.put("message", "unknown travel state: " + state);
                    return result;
            }
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("message", String.valueOf(e));
            } catch (Exception ignored) {}
            return result;
        }
    }

    private static JSONObject doDepart(Context ctx, JSONObject account, JSONObject result) {
        JSONObject config = fetchConfig(ctx, account);
        if (!config.optBoolean("ok", false)) {
            try {
                result.put("ok", false);
                result.put("skip", "config-error");
                result.put("message", config.optString("error", "读取旅行配置失败"));
            } catch (Exception ignored) {}
            return result;
        }
        JSONArray locations = config.optJSONArray("locations");
        if (locations == null || locations.length() == 0 || !config.optBoolean("enabled", true)) {
            try {
                result.put("ok", false);
                result.put("skip", "no-location");
                result.put("message", "无旅行地点");
            } catch (Exception ignored) {}
            return result;
        }
        for (int i = 0; i < locations.length(); i++) {
            JSONObject loc = locations.optJSONObject(i);
            if (loc == null) continue;
            Object locId = loc.opt("id");
            JSONObject dep = depart(ctx, account, locId);
            if (dep.optBoolean("ok", false)) {
                try {
                    result.put("ok", true);
                    result.put("already", false);
                    result.put("state", dep.optString("state", "traveling"));
                    result.put("message", "");
                    result.put("locationName", loc.optString("name", ""));
                } catch (Exception ignored) {}
                return result;
            }
            String raw = dep.optString("message", "").toLowerCase();
            long code = dep.optLong("code", -1);
            if (raw.contains("already traveling")) {
                try {
                    result.put("ok", true);
                    result.put("already", true);
                    result.put("state", "traveling");
                    result.put("message", "已在旅行中");
                } catch (Exception ignored) {}
                return result;
            }
            if (raw.contains("daily limit") || raw.contains("daily_limit")) {
                try {
                    result.put("ok", true);
                    result.put("already", true);
                    result.put("state", "idle");
                    result.put("skip", "daily-limit");
                    result.put("message", "今日已派");
                    // v1.4.8：同上，本次无动作计"跳过"
                } catch (Exception ignored) {}
                return result;
            }
            if (raw.contains("no active buddy")) {
                try {
                    result.put("ok", false);
                    result.put("skip", "no-buddy");
                    result.put("message", "无 Buddy");
                } catch (Exception ignored) {}
                return result;
            }
            if (!raw.isEmpty()) {
                try {
                    result.put("ok", false);
                    result.put("message", raw.length() > 80 ? raw.substring(0, 80) : raw);
                } catch (Exception ignored) {}
                return result;
            }
        }
        try {
            result.put("ok", false);
            result.put("message", "派发失败");
        } catch (Exception ignored) {}
        return result;
    }

    private static JSONObject doClaim(Context ctx, JSONObject account, long recordId,
                                      JSONObject result, JSONObject status) {
        copyStatus(result, status);
        JSONObject claim = claim(ctx, account, recordId);
        try {
            if (claim.optBoolean("ok", false)) {
                result.put("claimed", true);
                result.put("state", "idle");
                result.put("message", "领取成功");
                if (!claim.isNull("rewardCredit")) {
                    result.put("rewardCredit", claim.opt("rewardCredit"));
                }
            } else {
                String raw = claim.optString("message", "").toLowerCase();
                if (raw.contains("no unclaimed travel") || raw.contains("daily_limit")) {
                    result.put("claimed", true);
                    result.put("state", "idle");
                    result.put("message", "已领取");
                } else if (raw.contains("not arrived yet")) {
                    result.put("state", "arrived");
                    // v1.4.8：本次无动作（状态竞态）应计"跳过"；UI 仍按 arrived 显示可领奖
                    result.put("already", true);
                    result.put("message", "尚未到达");
                } else {
                    result.put("ok", false);
                    result.put("skip", "claim-error");
                    result.put("message", raw.length() > 80 ? raw.substring(0, 80) : raw);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void copyStatus(JSONObject result, JSONObject status) {
        try {
            if (result.optString("locationName", "").isEmpty()) {
                result.put("locationName", status.optString("locationName", ""));
            }
            if (!status.isNull("rewardCredit")) {
                Object rc = status.opt("rewardCredit");
                if (rc instanceof Number && ((Number) rc).doubleValue() != 0) {
                    result.put("rewardCredit", rc);
                }
            }
            long arriveAt = status.optLong("arriveAt", 0);
            if (arriveAt > 0) result.put("arriveAt", arriveAt);
        } catch (Exception ignored) {}
    }

    private static JSONObject newResult(JSONObject account, boolean ok, boolean already,
                                        String skip, String state, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("accountId", accountKey(account));
            o.put("accountName", AccountStore.displayName(account));
            o.put("ok", ok);
            o.put("already", already);
            o.put("skip", skip == null ? JSONObject.NULL : skip);
            o.put("state", state == null ? JSONObject.NULL : state);
            o.put("message", message);
            o.put("claimed", false);
            o.put("rewardCredit", JSONObject.NULL);
            o.put("locationName", "");
            o.put("at", System.currentTimeMillis());
        } catch (Exception ignored) {}
        return o;
    }

    /** 对所有支持旅行的账号执行一次派发/领奖。 */
    public static JSONArray runAll(Context ctx) {
        JSONArray out = new JSONArray();
        List<JSONObject> accounts = AccountStore.loadAccounts(ctx);
        for (JSONObject account : accounts) {
            out.put(runForAccount(ctx, account));
        }
        return out;
    }

    static String formatArriveAt(long arriveAt) {
        if (arriveAt <= 0) return "";
        long ts = arriveAt > 1_000_000_000_000L ? arriveAt : arriveAt * 1000;
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
    }
}
