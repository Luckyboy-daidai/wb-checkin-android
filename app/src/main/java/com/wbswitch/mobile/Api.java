package com.wbswitch.mobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * workbuddy.cn / codebuddy.cn 协议层，语义对照 workbuddy-switch 的
 * wb-switch-core（config.rs http_request / account.rs build_auth_headers /
 * oauth.rs / refresh.rs / checkin.rs）。
 */
public final class Api {

    /** 与桌面版 DEFAULT_HTTP_USER_AGENT 一致：网关按请求特征拦截，不能改。 */
    public static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    public static final String EP_CN = "https://www.codebuddy.cn";
    public static final String EP_AI = "https://www.workbuddy.ai";
    public static final String PLUGIN_PREFIX = "/v2/plugin";
    public static final String CHECKIN_PREFIX_CN = "/v2/billing/meter";
    public static final String CHECKIN_PREFIX_AI = "/billing/meter";
    /** 与桌面版 OAUTH_TIMEOUT_SECONDS 一致。 */
    public static final long OAUTH_TIMEOUT_SECONDS = 600;

    private Api() {}

    public static boolean isAi(String variant) { return "ai".equals(variant); }

    public static String endpoint(String variant) { return isAi(variant) ? EP_AI : EP_CN; }

    public static String oauthPlatform(String variant) {
        return isAi(variant) ? "workbuddy-ai" : "workbuddy";
    }

    public static boolean supportsCheckin(String variant) { return !isAi(variant); }

    /** 签到路径候选：只有 404 才回落到下一个候选（checkin.rs billing_paths）。 */
    public static List<String> billingPaths(String variant, String suffix) {
        List<String> paths = new ArrayList<>();
        if (isAi(variant)) {
            paths.add(CHECKIN_PREFIX_AI + suffix);
            paths.add("/v2" + CHECKIN_PREFIX_AI + suffix);
        } else {
            paths.add(CHECKIN_PREFIX_CN + suffix);
        }
        return paths;
    }

    public static boolean isRouteMissing(JSONObject resp) {
        long code = resp.optLong("code", -1);
        if (code == 404) return true;
        JSONObject data = resp.optJSONObject("data");
        return data != null && data.optLong("code", -1) == 404;
    }

    /** 信封凭据（桌面版 DPAPI 加密）在手机端永远无法解密，直接短路（issue #94）。 */
    public static String envelopeTokenError(JSONObject account) {
        Object at = account.opt("access_token");
        if (at instanceof JSONObject) {
            return "该账号凭据是桌面版加密信封，手机端无法使用，请重新登录";
        }
        return null;
    }

    public static Map<String, String> authHeaders(JSONObject account) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + account.optString("access_token", ""));
        h.put("Accept", "application/json");
        String uid = account.optString("uid", "");
        if (!uid.isEmpty()) h.put("X-User-Id", uid);
        String eid = account.optString("enterpriseId", "");
        if (eid.isEmpty()) eid = account.optString("enterprise_id", "");
        if (!eid.isEmpty()) {
            h.put("X-Enterprise-Id", eid);
            h.put("X-Tenant-Id", eid);
        }
        String domain = account.optString("domain", "");
        if (!domain.isEmpty()) h.put("X-Domain", domain);
        return h;
    }

    /** 通用请求：2xx 且响应体是 JSON 时原样返回；否则合成 {code, message}。 */
    public static JSONObject request(String url, String method, JSONObject body,
                                     Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Content-Type", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            String text = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            try {
                return new JSONObject(text);
            } catch (Exception notJson) {
                JSONObject o = new JSONObject();
                put(o, "code", status);
                put(o, "message", normalizeErrorBody(text));
                return o;
            }
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            put(o, "code", -1);
            put(o, "message", String.valueOf(e));
            return o;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** put 的免检包装：这里的键值类型不会触发 JSONException。 */
    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
        }
    }

    /**
     * 非 JSON 错误体归一化：网关（openresty / APISIX）的 401/5xx 返回整页 HTML，
     * 提取 &lt;title&gt; 作为可读信息，其余截断 500 字符（config.rs normalize_error_body）。
     */
    private static String normalizeErrorBody(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("<")) {
            int start = trimmed.indexOf("<title>");
            int end = trimmed.indexOf("</title>");
            if (start >= 0 && end > start) {
                String title = trimmed.substring(start + 7, end).trim();
                return "服务端返回 HTML 错误页：" + title;
            }
            return "服务端返回 HTML 错误页（无标题）";
        }
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 字符串/数字时间戳统一为毫秒；小于 1e10 视为秒（norm_ts）。 */
    public static Long normTs(Object v) {
        long ts;
        if (v instanceof Number) {
            ts = ((Number) v).longValue();
        } else if (v instanceof String) {
            try {
                ts = (long) Double.parseDouble(((String) v).trim());
            } catch (Exception e) {
                return null;
            }
        } else {
            return null;
        }
        if (ts <= 0) return null;
        if (ts < 10_000_000_000L) ts *= 1000;
        return ts;
    }

    public static long nowMs() { return System.currentTimeMillis(); }

    public static boolean codeOk(JSONObject resp) {
        long code = resp.optLong("code", -1);
        return code == 0 || code == 200;
    }

    public static boolean isUnauthorized(JSONObject resp) {
        long code = resp.optLong("code", -1);
        if (code == 10085) return false; // WAF 指纹拦截，刷新无效
        if (code == 401 || code == 403) return true;
        String msg = (resp.optString("message", "") + resp.optString("msg", "")).toLowerCase();
        for (String k : new String[]{"unauthorized", "401", "登录", "失效", "过期"}) {
            if (msg.contains(k)) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- credits

    private static final String RESOURCE_SUMMARY_PATH = "/billing/meter/get-user-resource-summary";
    private static final String RESOURCE_PAID_PATH = "/billing/meter/get-user-resource-paid-packages";
    private static final String RESOURCE_FREE_PATH = "/billing/meter/get-user-resource-free-packages";
    private static final String LEGACY_RESOURCE_SUFFIX = "/get-user-resource";

    private static final String[] PAID_PACKAGE_CODES = {
            "TCACA_code_002_AkiJS3ZHF5", "TCACA_code_023_4xbGhMrE6q",
            "TCACA_code_026_BaESVICNoi", "TCACA_code_027_0FCGVA6vSa",
            "TCACA_code_009_0XmEQc2xOf", "TCACA_code_038_OhvqZtiPKr",
            "TCACA_code_003_FAnt7lcmRT", "TCACA_code_036_lupO5WgNdG",
    };
    private static final String[] FREE_PACKAGE_CODES = {
            "TCACA_code_008_cfWoLwvjU4", "TCACA_code_007_nzdH5h4Nl0",
            "TCACA_code_028_NtpWi0jzXs", "TCACA_code_029_6wCGEWquYy",
            "TCACA_code_030_BjSt89qTvr", "TCACA_code_001_PqouKr6QWV",
            "TCACA_code_006_DbXS0lrypC", "TCACA_code_035_ArVxJcGDsm",
            "TCACA_code_037_WxOD3MpI2o", "TCACA_code_039_KRcQj7wUat",
            "TCACA_code_040_mi9rCYg46x",
    };

    public static String apiBaseFor(JSONObject account) {
        if (isAi(account.optString("variant", ""))) return EP_AI;
        String domain = account.optString("domain", "").trim().toLowerCase();
        if ("workbuddy.cn".equals(domain) || "www.workbuddy.cn".equals(domain)) {
            return "https://www.workbuddy.cn";
        }
        return EP_CN;
    }

    private static Map<String, String> resourceAuthHeaders(JSONObject account, String origin) {
        Map<String, String> h = authHeaders(account);
        h.put("X-Client-Platform", "web");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Origin", origin);
        h.put("Referer", origin + "/profile/plans-usage");
        return h;
    }

    private static String requestOrigin(String url) {
        for (String origin : new String[]{EP_AI, "https://www.workbuddy.cn", EP_CN}) {
            if (url.equals(origin) || url.startsWith(origin + "/")) return origin;
        }
        return EP_CN;
    }

    public static List<String> creditBillingPaths(String variant, String suffix) {
        List<String> paths = new ArrayList<>();
        if (isAi(variant)) {
            paths.add(suffix);
            paths.add("/v2" + suffix);
        } else {
            paths.add(suffix);
        }
        return paths;
    }

    private static JSONObject postWithAccount(JSONObject account, String url, JSONObject body) {
        Map<String, String> headers = resourceAuthHeaders(account, requestOrigin(url));
        JSONObject resp = request(url, "POST", body, headers);
        if (isTransportError(resp)) {
            resp = request(url, "POST", body, headers);
        }
        return resp;
    }

    private static boolean isTransportError(JSONObject resp) {
        return resp.optLong("code", -1) == -1 && !resp.optString("message", "").trim().isEmpty();
    }

    private static JSONObject postWithFallback(JSONObject account, List<String> paths, JSONObject body) {
        String base = apiBaseFor(account);
        JSONObject last = newResponse(-1, "无可用接口路径");
        for (int i = 0; i < paths.size(); i++) {
            String url = base + paths.get(i);
            JSONObject resp = postWithAccount(account, url, body);
            if (i + 1 == paths.size() || !isRouteMissing(resp)) return resp;
            last = resp;
        }
        return last;
    }

    private static JSONObject newResponse(long code, String message) {
        JSONObject o = new JSONObject();
        put(o, "code", code);
        put(o, "message", message);
        return o;
    }

    private static JSONObject legacyResourceBody() {
        JSONObject body = new JSONObject();
        put(body, "PageNumber", 1);
        put(body, "PageSize", 100);
        put(body, "ProductCode", "p_tcaca");
        put(body, "Status", jsonArray(0, 3));
        return body;
    }

    private static JSONObject paidPackagesBody() {
        JSONObject body = new JSONObject();
        put(body, "PageNumber", 1);
        put(body, "PageSize", 200);
        put(body, "Status", jsonArray(0, 3));
        put(body, "PackageCodes", jsonArray(PAID_PACKAGE_CODES));
        put(body, "NeedRenewInfo", true);
        return body;
    }

    private static JSONObject freePackagesBody() {
        JSONObject body = new JSONObject();
        put(body, "PageNumber", 1);
        put(body, "PageSize", 200);
        put(body, "Status", jsonArray(0, 3));
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        String now = sdf.format(new java.util.Date());
        String start = now.substring(0, 10) + " 00:00:00";
        String end = now.substring(0, 10) + " 23:59:59";
        put(body, "SlicePeriodStartTime", start);
        put(body, "SlicePeriodEndTime", end);
        put(body, "PackageCodes", jsonArray(FREE_PACKAGE_CODES));
        return body;
    }

    private static org.json.JSONArray jsonArray(Object... items) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Object item : items) arr.put(item);
        return arr;
    }

    private static boolean hasResourceAccounts(JSONObject resp) {
        for (String[] path : new String[][]{
                {"data", "Accounts"}, {"data", "data", "Accounts"},
                {"data", "Response", "Data", "Accounts"},
                {"data", "data", "Response", "Data", "Accounts"},
                {"data", "accounts"}, {"data", "data", "accounts"}
        }) {
            if (valueAtPath(resp, path) instanceof org.json.JSONArray) return true;
        }
        return false;
    }

    private static boolean hasResourcePackages(JSONObject resp) {
        for (String[] path : new String[][]{
                {"data", "Packages"}, {"data", "data", "Packages"},
                {"data", "Response", "Data", "Packages"},
                {"data", "data", "Response", "Data", "Packages"},
                {"data", "packages"}, {"data", "data", "packages"}
        }) {
            if (valueAtPath(resp, path) instanceof org.json.JSONArray) return true;
        }
        return false;
    }

    private static Object valueAtPath(JSONObject root, String[] path) {
        Object current = root;
        for (String key : path) {
            if (current instanceof JSONObject) current = ((JSONObject) current).opt(key);
            else return null;
        }
        return current;
    }

    private static List<JSONObject> resourceAccounts(JSONObject resp) {
        for (String[] path : new String[][]{
                {"data", "Accounts"}, {"data", "data", "Accounts"},
                {"data", "Response", "Data", "Accounts"},
                {"data", "data", "Response", "Data", "Accounts"},
                {"data", "accounts"}, {"data", "data", "accounts"}
        }) {
            Object v = valueAtPath(resp, path);
            if (v instanceof org.json.JSONArray) {
                List<JSONObject> list = new ArrayList<>();
                org.json.JSONArray arr = (org.json.JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (item instanceof JSONObject) list.add((JSONObject) item);
                }
                return list;
            }
        }
        return new ArrayList<>();
    }

    private static List<JSONObject> resourcePackages(JSONObject resp) {
        for (String[] path : new String[][]{
                {"data", "Packages"}, {"data", "data", "Packages"},
                {"data", "Response", "Data", "Packages"},
                {"data", "data", "Response", "Data", "Packages"},
                {"data", "packages"}, {"data", "data", "packages"}
        }) {
            Object v = valueAtPath(resp, path);
            if (v instanceof org.json.JSONArray) {
                List<JSONObject> list = new ArrayList<>();
                org.json.JSONArray arr = (org.json.JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (item instanceof JSONObject) list.add((JSONObject) item);
                }
                return list;
            }
        }
        return new ArrayList<>();
    }

    private static Double firstNumber(JSONObject obj, String[] keys) {
        for (String key : keys) {
            Object v = obj.opt(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) {
                try { return Double.parseDouble(((String) v).trim()); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Long parseTimestampMs(Object value) {
        if (value instanceof Number) {
            long ts = ((Number) value).longValue();
            return ts < 10_000_000_000L ? ts * 1000 : ts;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) return null;
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                return sdf.parse(text).getTime();
            } catch (Exception ignored) {}
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
                return sdf.parse(text).getTime() + 86399999;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Long resolveExpireAt(JSONObject raw, long now) {
        Long deductionEnd = parseTimestampMs(firstValue(raw,
                new String[]{"DeductionEndTime", "deductionEndTime", "ExpiredTime", "expiredTime"}));
        Long cycleEnd = parseTimestampMs(firstValue(raw, new String[]{"CycleEndTime", "cycleEndTime"}));
        long overrideMs = 365L * 24 * 3600 * 1000;
        Long expireAt;
        if (deductionEnd != null && cycleEnd != null && deductionEnd - cycleEnd > overrideMs) {
            expireAt = cycleEnd;
        } else if (deductionEnd != null) {
            expireAt = deductionEnd;
        } else {
            expireAt = cycleEnd;
        }
        long farFutureMs = 730L * 24 * 3600 * 1000;
        return expireAt != null && expireAt - now <= farFutureMs ? expireAt : null;
    }

    private static Object firstValue(JSONObject obj, String[] keys) {
        for (String key : keys) {
            if (obj.has(key)) return obj.opt(key);
        }
        return null;
    }

    private static JSONObject resourceSummary(JSONObject raw, long now) {
        JSONObject slice = null;
        Object sliceArr = raw.opt("SlicePeriodUsageDetails");
        if (sliceArr instanceof org.json.JSONArray && ((org.json.JSONArray) sliceArr).length() > 0) {
            Object first = ((org.json.JSONArray) sliceArr).opt(0);
            if (first instanceof JSONObject) slice = (JSONObject) first;
        }
        String[] totalKeys = {"CycleCapacitySizePrecise", "CycleCapacitySize", "CycleTotalCapacity",
                "CapacitySizePrecise", "CapacitySize", "SlicePeriodCapacitySizePrecise", "SlicePeriodCapacitySize"};
        String[] remainingKeys = {"CycleCapacityRemainPrecise", "CycleCapacityRemain", "CycleRemainCapacity",
                "CapacityRemainPrecise", "CapacityRemain", "SlicePeriodCapacityRemainPrecise", "SlicePeriodCapacityRemain"};
        String[] usedKeys = {"CycleCapacityUsedPrecise", "CycleCapacityUsed", "CycleUsedCapacity",
                "CapacityUsedPrecise", "CapacityUsed", "SlicePeriodCapacityUsedPrecise", "SlicePeriodCapacityUsed"};
        Double rawTotal = firstNumber(raw, totalKeys);
        Double rawRemaining = firstNumber(raw, remainingKeys);
        Double rawUsed = firstNumber(raw, usedKeys);
        if (rawTotal == null && slice != null) rawTotal = firstNumber(slice, totalKeys);
        if (rawRemaining == null && slice != null) rawRemaining = firstNumber(slice, remainingKeys);
        if (rawUsed == null && slice != null) rawUsed = firstNumber(slice, usedKeys);

        double total = rawTotal != null ? rawTotal :
                (rawRemaining != null && rawUsed != null ? rawRemaining + rawUsed :
                        (rawRemaining != null ? rawRemaining : (rawUsed != null ? rawUsed : 0.0)));
        total = Math.max(0, total);
        double remaining = rawRemaining != null ? rawRemaining : Math.max(0, total - (rawUsed != null ? rawUsed : 0));
        double used = rawUsed != null ? rawUsed : Math.max(0, total - remaining);

        Long expireAt = resolveExpireAt(raw, now);
        boolean expired = expireAt != null && expireAt <= now;
        long soonMs = 7L * 24 * 3600 * 1000;
        boolean expiringSoon = expireAt != null && expireAt > now && expireAt - now <= soonMs;

        JSONObject out = new JSONObject();
        Object pc = firstValue(raw, new String[]{"PackageCode", "packageCode"});
        Object pn = firstValue(raw, new String[]{"PackageName", "packageName"});
        put(out, "packageCode", pc == null ? JSONObject.NULL : pc);
        put(out, "packageName", pn == null ? JSONObject.NULL : pn);
        put(out, "total", total);
        put(out, "remaining", remaining);
        put(out, "used", used);
        put(out, "status", firstValue(raw, new String[]{"Status", "status"}));
        put(out, "expireAt", expireAt == null ? JSONObject.NULL : expireAt);
        put(out, "expired", expired);
        put(out, "expiringSoon", expiringSoon);
        return out;
    }

    public static JSONObject fetchCredits(Context ctx, JSONObject account) {
        if (envelopeTokenError(account) != null) {
            JSONObject o = new JSONObject();
            put(o, "ok", false);
            put(o, "error", envelopeTokenError(account));
            return o;
        }
        String variant = account.optString("variant", "");
        long now = nowMs();

        if (isAi(variant)) {
            return fetchLegacyCredit(ctx, account, now);
        }

        Checkin.ensureFreshToken(ctx, account); // refresh if needed
        List<String> summaryPaths = creditBillingPaths(variant, RESOURCE_SUMMARY_PATH);
        List<String> paidPaths = creditBillingPaths(variant, RESOURCE_PAID_PATH);
        List<String> freePaths = creditBillingPaths(variant, RESOURCE_FREE_PATH);

        JSONObject summary = postWithFallback(account, summaryPaths, new JSONObject());
        JSONObject paid = postWithFallback(account, paidPaths, paidPackagesBody());
        JSONObject free = postWithFallback(account, freePaths, freePackagesBody());
        boolean refreshAttempted = false;

        if (isUnauthorized(summary) || isUnauthorized(paid) || isUnauthorized(free)) {
            String rt = account.optString("refresh_token", "");
            if (!rt.isEmpty()) {
                account = Checkin.refreshToken(ctx, account);
                summary = retryIfUnauthorized(account, summary, summaryPaths, new JSONObject());
                paid = retryIfUnauthorized(account, paid, paidPaths, paidPackagesBody());
                free = retryIfUnauthorized(account, free, freePaths, freePackagesBody());
                refreshAttempted = true;
            }
        }

        List<JSONObject> resources = normalizedNewResources(summary, paid, free, now);
        if (resources != null) {
            return creditResult(account, resources, now);
        }

        JSONObject fallbackAccount = account;
        JSONObject response = postWithFallback(fallbackAccount,
                creditBillingPaths(variant, CHECKIN_PREFIX_CN + LEGACY_RESOURCE_SUFFIX),
                legacyResourceBody());
        if (isUnauthorized(response) && !refreshAttempted && !fallbackAccount.optString("refresh_token", "").isEmpty()) {
            fallbackAccount = Checkin.refreshToken(ctx, fallbackAccount);
            response = postWithFallback(fallbackAccount,
                    creditBillingPaths(variant, CHECKIN_PREFIX_CN + LEGACY_RESOURCE_SUFFIX),
                    legacyResourceBody());
        }
        return legacyCreditResult(account, response, now);
    }

    private static JSONObject retryIfUnauthorized(JSONObject account, JSONObject resp,
                                                  List<String> paths, JSONObject body) {
        return isUnauthorized(resp) ? postWithFallback(account, paths, body) : resp;
    }

    private static List<JSONObject> normalizedNewResources(JSONObject summary, JSONObject paid,
                                                           JSONObject free, long now) {
        boolean summaryOk = isSuccess(summary) && hasResourcePackages(summary);
        boolean paidOk = isSuccess(paid) && hasResourceAccounts(paid);
        boolean freeOk = isSuccess(free) && hasResourceAccounts(free);
        if (!summaryOk && !paidOk && !freeOk) return null;

        List<JSONObject> summaryResources = new ArrayList<>();
        if (summaryOk) {
            for (JSONObject r : resourcePackages(summary)) summaryResources.add(resourceSummary(r, now));
        }
        List<JSONObject> detailResources = new ArrayList<>();
        if (paidOk) {
            for (JSONObject r : resourceAccounts(paid)) detailResources.add(resourceSummary(r, now));
        }
        if (freeOk) {
            for (JSONObject r : resourceAccounts(free)) detailResources.add(resourceSummary(r, now));
        }
        return mergeResources(summaryResources, detailResources);
    }

    private static List<JSONObject> mergeResources(List<JSONObject> summaryResources, List<JSONObject> detailResources) {
        // 官网"积分/额度"页的数据口径以 get-user-resource-summary 的聚合行为准；
        // paid/free 两个明细接口（尤其免费包按天窗口）会返回聚合行内同一套餐的当日
        // 切片，切片的 packageCode / packageName 与聚合行可能不一致，一旦补充进去就会
        // 双算。因此：summary 非空时只返回 summary，明细仅用来给缺失 code 的聚合行补
        // 分类；summary 为空时才用明细兜底。
        if (!summaryResources.isEmpty()) {
            enrichSummaryFromDetails(summaryResources, detailResources);
            return summaryResources;
        }
        return new ArrayList<>(detailResources);
    }

    /** 用明细行给聚合行补 packageCode / packageName，以便前端按免费/付费分类。 */
    private static void enrichSummaryFromDetails(List<JSONObject> summary, List<JSONObject> details) {
        for (JSONObject s : summary) {
            String code = optResourceString(s, "packageCode");
            String name = optResourceString(s, "packageName");
            if (!code.isEmpty() && (isFreePackageCode(code) || isPaidPackageCode(code))) continue;
            for (JSONObject d : details) {
                String dCode = optResourceString(d, "packageCode");
                String dName = optResourceString(d, "packageName");
                boolean match = (!code.isEmpty() && code.equals(dCode))
                        || (!name.isEmpty() && name.equals(dName));
                if (!match) continue;
                try {
                    if (code.isEmpty() && !dCode.isEmpty()
                            && (isFreePackageCode(dCode) || isPaidPackageCode(dCode))) {
                        s.put("packageCode", dCode);
                    }
                    if (name.isEmpty() && !dName.isEmpty()) {
                        s.put("packageName", dName);
                    }
                } catch (Exception ignored) {}
                break;
            }
        }
    }

    private static String optResourceString(JSONObject obj, String key) {
        if (obj.isNull(key)) return "";
        String v = obj.optString(key, "");
        return v == null ? "" : v.trim();
    }

    private static boolean isFreePackageCode(String code) {
        for (String c : FREE_PACKAGE_CODES) if (c.equals(code)) return true;
        return false;
    }

    private static boolean isPaidPackageCode(String code) {
        for (String c : PAID_PACKAGE_CODES) if (c.equals(code)) return true;
        return false;
    }

    private static JSONObject legacyCreditResult(JSONObject account, JSONObject response, long now) {
        if (isSuccess(response) && hasResourceAccounts(response)) {
            List<JSONObject> resources = new ArrayList<>();
            for (JSONObject r : resourceAccounts(response)) resources.add(resourceSummary(r, now));
            return creditResult(account, resources, now);
        }
        JSONObject o = new JSONObject();
        put(o, "ok", false);
        put(o, "error", creditResponseError(response));
        return o;
    }

    private static JSONObject creditResult(JSONObject account, List<JSONObject> resources, long now) {
        double totalRemaining = 0, totalCapacity = 0, expiringSoonRemaining = 0, expiredRemaining = 0;
        Long soonestExpireAt = null;
        boolean expiringSoon = false, expired = false;
        for (JSONObject r : resources) {
            double remaining = r.optDouble("remaining", 0);
            double total = r.optDouble("total", 0);
            if (r.optBoolean("expired", false)) {
                // 已过期包不计入总额/剩余/最近到期（官网也不展示），只留在过期统计里，
                // 否则一个用完/过期的旧包会把总额撑大、百分比压低，看起来像读错了号
                expiredRemaining += remaining;
                if (remaining > 0) expired = true;
                continue;
            }
            totalRemaining += remaining;
            totalCapacity += total;
            if (r.optBoolean("expiringSoon", false)) expiringSoonRemaining += remaining;
            if (remaining > 0) {
                Object ea = r.opt("expireAt");
                if (ea instanceof Long) {
                    long t = (Long) ea;
                    if (soonestExpireAt == null || t < soonestExpireAt) soonestExpireAt = t;
                }
                if (r.optBoolean("expiringSoon", false)) expiringSoon = true;
            }
        }
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        put(o, "accountId", account.optString("id", ""));
        put(o, "accountName", AccountStore.displayName(account));
        put(o, "updatedAt", now);
        put(o, "totalCapacity", totalCapacity);
        put(o, "totalRemaining", totalRemaining);
        put(o, "expiringSoonRemaining", expiringSoonRemaining);
        put(o, "expiredRemaining", expiredRemaining);
        put(o, "soonestExpireAt", soonestExpireAt == null ? JSONObject.NULL : soonestExpireAt);
        put(o, "expiringSoon", expiringSoon);
        put(o, "expired", expired);
        put(o, "resources", new org.json.JSONArray(resources));
        return o;
    }

    private static String creditResponseError(JSONObject resp) {
        long code = resp.optLong("code", -1);
        JSONObject nested = resp.optJSONObject("data");
        String msg = resp.optString("message", "");
        if (msg.isEmpty()) msg = resp.optString("msg", "");
        if (msg.isEmpty() && nested != null) msg = nested.optString("message", nested.optString("msg", ""));
        if (msg.trim().isEmpty()) msg = "积分查询失败（code=" + code + "）";
        return msg.length() > 160 ? msg.substring(0, 160) : msg;
    }

    private static boolean isSuccess(JSONObject resp) {
        long code = resp.optLong("code", -1);
        if (code == 0 || code == 200) return true;
        if (code != -1) return false;
        return resp.has("data") && resp.optBoolean("ok", true) && resp.optBoolean("success", true);
    }

    private static JSONObject fetchLegacyCredit(Context ctx, JSONObject account, long now) {
        Checkin.ensureFreshToken(ctx, account);
        String variant = account.optString("variant", "");
        List<String> paths = creditBillingPaths(variant, CHECKIN_PREFIX_AI + LEGACY_RESOURCE_SUFFIX);
        JSONObject response = postWithFallback(account, paths, legacyResourceBody());
        if (isUnauthorized(response) && !account.optString("refresh_token", "").isEmpty()) {
            account = Checkin.refreshToken(ctx, account);
            response = postWithFallback(account, paths, legacyResourceBody());
        }
        return legacyCreditResult(account, response, now);
    }

    // ------------------------------------------------------------------ OAuth

    /** 发起登录：申请 state，返回 {state, authUrl}；失败返回 {error}。 */
    public static JSONObject oauthStart(String variant) {
        String url = endpoint(variant) + PLUGIN_PREFIX + "/auth/state?platform=" + oauthPlatform(variant);
        JSONObject resp = request(url, "POST", new JSONObject(), null);
        JSONObject data = resp.optJSONObject("data");
        String state = data == null ? "" : data.optString("state", "");
        if (state.isEmpty()) {
            String snippet = resp.toString();
            snippet = snippet.substring(0, Math.min(300, snippet.length()));
            JSONObject o = new JSONObject();
            put(o, "error", "auth/state 响应缺少 state: " + snippet);
            return o;
        }
        String authUrl = null;
        if (data != null) {
            authUrl = data.optString("authUrl", "");
            if (authUrl.isEmpty()) authUrl = data.optString("auth_url", "");
            if (authUrl.isEmpty()) authUrl = data.optString("url", "");
        }
        if (authUrl == null || authUrl.isEmpty()) {
            authUrl = endpoint(variant) + "/login?state=" + state;
        }
        JSONObject o = new JSONObject();
        put(o, "state", state);
        put(o, "authUrl", authUrl);
        return o;
    }

    /**
     * 轮询一次 token 接口：登录完成时返回 {data, domain}，否则返回 null。
     * data 与账号信息由调用方继续换取。
     */
    public static JSONObject oauthPollToken(String variant, String state) {
        String url = endpoint(variant) + PLUGIN_PREFIX + "/auth/token?state=" + state;
        JSONObject resp = request(url, "GET", null, null);
        if (!codeOk(resp)) return null;
        JSONObject data = resp.optJSONObject("data");
        String accessToken = data == null ? "" : data.optString("accessToken", "");
        if (accessToken.isEmpty()) accessToken = data == null ? "" : data.optString("access_token", "");
        if (accessToken.isEmpty()) return null;
        JSONObject o = new JSONObject();
        put(o, "tokenData", data);
        put(o, "accessToken", accessToken);
        put(o, "domain", data.optString("domain", ""));
        return o;
    }

    /** 档位一致性校验（oauth.rs domain_mismatch_error）。 */
    public static boolean domainMatches(String variant, String domain) {
        String d = domain == null ? "" : domain.trim().toLowerCase();
        if (d.isEmpty()) return true;
        boolean aiDomain = d.endsWith(".workbuddy.ai");
        return isAi(variant) ? aiDomain : !aiDomain;
    }

    /** 拉取账号信息（login/account）。 */
    public static JSONObject fetchAccountProfile(String variant, String state,
                                                 String accessToken, String domain) {
        String url = endpoint(variant) + PLUGIN_PREFIX + "/login/account?state=" + state;
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        if (domain != null && !domain.isEmpty()) headers.put("X-Domain", domain);
        JSONObject resp = request(url, "GET", null, headers);
        return resp.optJSONObject("data");
    }

    // ----------------------------------------------------------------- refresh

    public static String refreshUrl(String variant) {
        return endpoint(variant) + PLUGIN_PREFIX + "/auth/token/refresh";
    }

    public static Map<String, String> refreshHeaders(JSONObject account, String refreshToken) {
        Map<String, String> h = authHeaders(account);
        h.put("X-Refresh-Token", refreshToken);
        // 官方客户端刷新时带该头，缺失时网关判定为其它 client 来源（refresh.rs）。
        h.put("X-Auth-Refresh-Source", "plugin");
        return h;
    }
}
