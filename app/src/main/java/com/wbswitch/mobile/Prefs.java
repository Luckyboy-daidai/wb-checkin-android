package com.wbswitch.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 应用设置。 */
public final class Prefs {

    private static final String FILE = "settings";
    public static final String KEY_AUTO_ENABLED = "auto_enabled";
    public static final String KEY_HOUR = "hour";
    public static final String KEY_MINUTE = "minute";
    public static final String KEY_JITTER_MIN = "jitter_min";
    public static final String KEY_TOKEN_KEEP_ALIVE = "token_keep_alive";
    public static final String KEY_TIMES = "times";
    public static final String KEY_EXCLUDED_ACCOUNTS = "excluded_accounts";
    public static final String KEY_FIRST_OPEN_CHECKIN = "first_open_checkin";
    public static final String KEY_LAST_OPEN_CHECKIN_DATE = "last_open_checkin_date";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean autoEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_AUTO_ENABLED, false);
    }

    public static void setAutoEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply();
    }

    public static int hour(Context ctx) { return sp(ctx).getInt(KEY_HOUR, 9); }

    public static int minute(Context ctx) { return sp(ctx).getInt(KEY_MINUTE, 0); }

    public static void setTime(Context ctx, int hour, int minute) {
        sp(ctx).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply();
    }

    public static int jitterMin(Context ctx) { return sp(ctx).getInt(KEY_JITTER_MIN, 10); }

    public static void setJitterMin(Context ctx, int minutes) {
        sp(ctx).edit().putInt(KEY_JITTER_MIN, minutes).apply();
    }

    public static boolean tokenKeepAliveEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_TOKEN_KEEP_ALIVE, false);
    }

    public static void setTokenKeepAliveEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_TOKEN_KEEP_ALIVE, enabled).apply();
    }

    /** 返回所有签到时间点；未设置时返回单条 09:00 作为默认值。 */
    public static List<TimePoint> times(Context ctx) {
        String raw = sp(ctx).getString(KEY_TIMES, "");
        List<TimePoint> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(new TimePoint(o.optInt("hour", 9), o.optInt("minute", 0)));
            }
        } catch (Exception ignored) {
        }
        if (list.isEmpty()) {
            list.add(new TimePoint(sp(ctx).getInt(KEY_HOUR, 9), sp(ctx).getInt(KEY_MINUTE, 0)));
        }
        Collections.sort(list);
        return list;
    }

    public static void setTimes(Context ctx, List<TimePoint> times) {
        JSONArray arr = new JSONArray();
        for (TimePoint t : times) {
            JSONObject o = new JSONObject();
            try {
                o.put("hour", t.hour);
                o.put("minute", t.minute);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        sp(ctx).edit().putString(KEY_TIMES, arr.toString()).apply();
    }

    /** 被排除自动签到的账号 id 列表。 */
    public static List<String> excludedAccounts(Context ctx) {
        String raw = sp(ctx).getString(KEY_EXCLUDED_ACCOUNTS, "");
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (!v.isEmpty()) list.add(v);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void setExcludedAccounts(Context ctx, List<String> ids) {
        JSONArray arr = new JSONArray();
        for (String id : ids) arr.put(id);
        sp(ctx).edit().putString(KEY_EXCLUDED_ACCOUNTS, arr.toString()).apply();
    }

    /** 是否在每天首次打开 APP 时自动签到（默认开启）。 */
    public static boolean firstOpenCheckinEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_FIRST_OPEN_CHECKIN, true);
    }

    public static void setFirstOpenCheckinEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_FIRST_OPEN_CHECKIN, enabled).apply();
    }

    /** 上次首次打开签到的日期（yyyy-MM-dd），用于判断今天是否已签过。 */
    public static String lastOpenCheckinDate(Context ctx) {
        return sp(ctx).getString(KEY_LAST_OPEN_CHECKIN_DATE, "");
    }

    public static void setLastOpenCheckinDate(Context ctx, String date) {
        sp(ctx).edit().putString(KEY_LAST_OPEN_CHECKIN_DATE, date).apply();
    }

    public static final String KEY_EXPIRE_REMINDER = "expire_reminder";
    public static final String KEY_EXPIRE_CHECK_DATE = "expire_check_date";
    public static final String KEY_EXPIRE_CACHE = "expire_cache";

    /** 积分到期提醒开关（默认开启）。 */
    public static boolean expireReminderEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_EXPIRE_REMINDER, true);
    }

    public static void setExpireReminderEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_EXPIRE_REMINDER, enabled).apply();
    }

    /** 上次检查积分到期的日期（yyyy-MM-dd），保证每天首次打开只检查一次。 */
    public static String expireCheckDate(Context ctx) {
        return sp(ctx).getString(KEY_EXPIRE_CHECK_DATE, "");
    }

    public static void setExpireCheckDate(Context ctx, String date) {
        sp(ctx).edit().putString(KEY_EXPIRE_CHECK_DATE, date).apply();
    }

    /** 积分到期缓存（首页提示条与通知的数据源，JSON）。 */
    public static String expireCache(Context ctx) {
        return sp(ctx).getString(KEY_EXPIRE_CACHE, "");
    }

    public static void setExpireCache(Context ctx, String json) {
        sp(ctx).edit().putString(KEY_EXPIRE_CACHE, json).apply();
    }

    public static final String KEY_ONBOARDING_DONE = "onboarding_done";

    /** 首次启动的说明书/隐私协议弹窗是否已完成（点过"我已阅读并同意"）。 */
    public static boolean isOnboardingDone(Context ctx) {
        return sp(ctx).getBoolean(KEY_ONBOARDING_DONE, false);
    }

    public static void setOnboardingDone(Context ctx, boolean done) {
        sp(ctx).edit().putBoolean(KEY_ONBOARDING_DONE, done).apply();
    }

    /** 签到时间点值对象。 */
    public static class TimePoint implements Comparable<TimePoint> {
        public final int hour;
        public final int minute;

        public TimePoint(int hour, int minute) {
            this.hour = hour;
            this.minute = minute;
        }

        public int toMinutes() {
            return hour * 60 + minute;
        }

        @Override
        public int compareTo(TimePoint other) {
            return Integer.compare(toMinutes(), other.toMinutes());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TimePoint)) return false;
            TimePoint t = (TimePoint) o;
            return hour == t.hour && minute == t.minute;
        }

        @Override
        public int hashCode() {
            return toMinutes();
        }
    }
}
