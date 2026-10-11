package com.wbswitch.mobile;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 每日自动签到调度：WorkManager OneTime 任务对准下一次签到时刻，
 * 跑完后自行排下一个；开机与设置变更时都会重新调度。
 */
public final class AutoScheduler {

    public static final String UNIQUE_WORK = "auto_checkin_daily";

    private AutoScheduler() {}

    /** 计算下一次触发延迟：取所有签到时间中最近的下一个时刻，附加 0～jitter 分钟随机延迟。 */
    public static long nextDelayMs(Context ctx) {
        int jitter = Math.max(0, Prefs.jitterMin(ctx));
        long jitterMs = jitter == 0 ? 0 : new Random().nextInt(jitter + 1) * 60_000L;
        Calendar now = Calendar.getInstance();
        List<Prefs.TimePoint> times = Prefs.times(ctx);
        long minDelay = Long.MAX_VALUE;
        for (Prefs.TimePoint t : times) {
            Calendar target = Calendar.getInstance();
            target.set(Calendar.HOUR_OF_DAY, t.hour);
            target.set(Calendar.MINUTE, t.minute);
            target.set(Calendar.SECOND, 0);
            target.set(Calendar.MILLISECOND, 0);
            if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1);
            long delay = target.getTimeInMillis() - now.getTimeInMillis();
            if (delay < minDelay) minDelay = delay;
        }
        return minDelay == Long.MAX_VALUE ? 0 : minDelay + jitterMs;
    }

    /**
     * 被动重排（开 app / 开机）：KEEP 策略。
     * 已有任务在队（尤其已到期待跑）时绝不替换，避免把当天的签到顶掉。
     */
    public static void scheduleNext(Context ctx) {
        schedule(ctx, ExistingWorkPolicy.KEEP);
    }

    /**
     * Worker 内自排程专用：APPEND 把下一个任务挂接在当前任务之后，当前任务完成即开始
     * 计时。不能用 KEEP —— 同名任务处于 RUNNING 状态时 KEEP 会把新请求整个丢弃，
     * 从 doWork 里排下一天的任务会静默失败，自动签到跑完一次就断链。
     */
    public static void scheduleAfterCurrent(Context ctx) {
        schedule(ctx, ExistingWorkPolicy.APPEND);
    }

    /** 用户主动改设置后的强制重排：REPLACE 让新时间立即生效。 */
    public static void rescheduleNow(Context ctx) {
        schedule(ctx, ExistingWorkPolicy.REPLACE);
    }

    private static void schedule(Context ctx, ExistingWorkPolicy policy) {
        if (!Prefs.autoEnabled(ctx)) return;
        long delay = nextDelayMs(ctx);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CheckinWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .addTag(UNIQUE_WORK)
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_WORK, policy, req);
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_WORK);
    }

    public static PendingIntent mainIntent(Context ctx) {
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
