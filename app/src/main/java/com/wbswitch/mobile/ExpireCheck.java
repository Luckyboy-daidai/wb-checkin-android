package com.wbswitch.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 积分到期检查（每天首次打开 APP 触发；积分页加载后也会刷新缓存）：
 * 只读查询（与积分页完全同一套请求，无重试、无并发），把 7 天内将要过期
 * 的积分写入本地缓存（首页提示条数据源），并维护一条系统通知——
 * 有则发、无则撤（积分用掉后提醒及时消失）。不做任何签到类写操作。
 */
public final class ExpireCheck {

    private static final String CHANNEL_ID = "credits_expire";
    private static final int NOTIFY_ID = 1002;
    private static final int MAX_LINES = 3;

    private ExpireCheck() {}

    /**
     * 刷新到期缓存并同步通知。
     * @param allowNotify 每天首次打开时传 true（允许发通知）；
     *                    积分页加载后传 false（只更新缓存与提示条，不发通知）。
     * @return 数据是否可用（至少一个账号查询成功）。全部失败（如断网）时
     *         返回 false 且不动缓存，调用方应清除当日标记、下次打开重试。
     */
    public static boolean refresh(Context ctx, boolean allowNotify) {
        try {
            // 清理 v1.4.2 遗留的 20:00 定时任务（本版已改为首开触发）
            try {
                WorkManager.getInstance(ctx).cancelUniqueWork("expire_check_daily");
            } catch (Exception ignored) {}
            List<JSONObject> items = new ArrayList<>();
            int attempted = 0, okCount = 0;
            for (JSONObject acc : AccountStore.loadAccounts(ctx)) {
                // 已标记需重新登录或缺少续期凭证的账号跳过：查询也必然失败，
                // 避免无意义的 401 流量（账号风控红线之一）
                if (acc.optBoolean("needs_relogin", false)) continue;
                if (acc.optString("refresh_token", "").isEmpty()) continue;
                attempted++;
                try {
                    JSONObject r = Api.fetchCredits(ctx, acc);
                    if (!r.optBoolean("ok", false)) continue;
                    okCount++;
                    double expiring = r.optDouble("expiringSoonRemaining", 0);
                    if (expiring <= 0) continue;
                    Long soonest = r.isNull("soonestExpireAt")
                            ? null : r.optLong("soonestExpireAt", 0);
                    JSONObject item = new JSONObject();
                    item.put("name", AccountStore.displayName(acc));
                    item.put("amount", formatAmount(expiring));
                    if (soonest != null && soonest > 0) {
                        item.put("dateMs", (long) soonest);
                        item.put("date", formatDay(soonest));
                    }
                    items.add(item);
                } catch (Exception ignored) {
                    // 单账号失败不影响其它账号
                }
            }
            // 全部账号查询失败（如断网）时不动缓存：积分可能仍在过期中，
            // 误清缓存会让首页提示条提前消失
            boolean usable = okCount > 0 || attempted == 0;
            if (!usable) return false;
            saveCache(ctx, items, System.currentTimeMillis());
            if (items.isEmpty()) {
                // 没有过期积分（已用掉/已过期）→ 撤回已发出的提醒
                cancelNotify(ctx);
                return true;
            }
            if (allowNotify) postNotify(ctx, items);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 关闭提醒开关时调用：撤回已发出的到期提醒通知。 */
    public static void cancelReminder(Context ctx) {
        cancelNotify(ctx);
    }

    /**
     * 用积分页刚查到的结果刷新缓存（不发通知）——积分用掉后提示条与通知
     * 及时消失，不必等到下一次首开检查。
     */
    public static void updateCacheFromResults(Context ctx, List<JSONObject> results) {
        try {
            List<JSONObject> items = new ArrayList<>();
            int attempted = 0, okCount = 0;
            for (JSONObject r : results) {
                attempted++;
                if (!r.optBoolean("ok", false)) continue;
                okCount++;
                double expiring = r.optDouble("expiringSoonRemaining", 0);
                if (expiring <= 0) continue;
                Long soonest = r.isNull("soonestExpireAt")
                        ? null : r.optLong("soonestExpireAt", 0);
                JSONObject item = new JSONObject();
                item.put("name", r.optString("accountName", "账号"));
                item.put("amount", formatAmount(expiring));
                if (soonest != null && soonest > 0) {
                    item.put("dateMs", (long) soonest);
                    item.put("date", formatDay(soonest));
                }
                items.add(item);
            }
            // 全部查询失败时不更新缓存，避免误清仍在生效的到期提醒
            if (attempted > 0 && okCount == 0) return;
            saveCache(ctx, items, System.currentTimeMillis());
            if (items.isEmpty()) cancelNotify(ctx);
        } catch (Exception ignored) {}
    }

    /** 读取缓存里的过期账号列表（首页提示条数据源，可能为空）。 */
    public static List<JSONObject> loadCacheItems(Context ctx) {
        List<JSONObject> items = new ArrayList<>();
        try {
            JSONObject cache = new JSONObject(Prefs.expireCache(ctx));
            JSONArray arr = cache.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) items.add(o);
                }
            }
        } catch (Exception ignored) {}
        return items;
    }

    private static void saveCache(Context ctx, List<JSONObject> items, long at) {
        try {
            JSONObject cache = new JSONObject();
            cache.put("updatedAt", at);
            cache.put("items", new JSONArray(items));
            Prefs.setExpireCache(ctx, cache.toString());
        } catch (Exception ignored) {}
    }

    private static void postNotify(Context ctx, List<JSONObject> items) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.expire_channel),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        StringBuilder lines = new StringBuilder();
        int shown = Math.min(MAX_LINES, items.size());
        for (int i = 0; i < shown; i++) {
            JSONObject it = items.get(i);
            if (lines.length() > 0) lines.append('\n');
            String date = it.optString("date", "");
            if (!date.isEmpty()) {
                lines.append(ctx.getString(R.string.expire_notify_line,
                        it.optString("name", "账号"), it.optString("amount", ""), date));
            } else {
                lines.append(ctx.getString(R.string.expire_notify_line_undated,
                        it.optString("name", "账号"), it.optString("amount", "")));
            }
        }
        if (items.size() > shown) {
            lines.append('\n').append(ctx.getString(R.string.expire_notify_more,
                    items.size() - shown));
        }
        Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(ctx.getString(R.string.expire_notify_title))
                .setContentText(lines.toString())
                .setStyle(new Notification.BigTextStyle().bigText(lines.toString()))
                .setContentIntent(contentIntent(ctx))
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIFY_ID, n);
        } catch (SecurityException ignored) {}
    }

    private static void cancelNotify(Context ctx) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFY_ID);
    }

    public static PendingIntent contentIntent(Context ctx) {
        Intent intent = new Intent(ctx, CreditsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 与积分页同一套数字格式：整数不加小数、带零头保留两位。 */
    private static String formatAmount(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.005) {
            return String.format(Locale.getDefault(), "%,d", Math.round(v));
        }
        String s = String.format(Locale.US, "%,.2f", v);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String formatDay(long tsMs) {
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(tsMs));
    }
}
