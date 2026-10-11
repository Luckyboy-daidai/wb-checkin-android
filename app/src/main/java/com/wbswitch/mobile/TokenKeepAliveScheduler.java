package com.wbswitch.mobile;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * 每日自动 token 保活调度：固定每天 03:30 刷新一次全部账号 token。
 * 时间早于常见签到时刻，避免到点签到时 token 过期。
 */
public final class TokenKeepAliveScheduler {

    public static final String UNIQUE_WORK = "token_keep_alive_daily";
    private static final int HOUR = 3;
    private static final int MINUTE = 30;

    private TokenKeepAliveScheduler() {}

    /**
     * 被动重排（开 app / 开机 / 设置变更）：REPLACE 策略。
     * 取消旧任务并排上新的，确保下次 03:30 准时触发。
     */
    public static void scheduleNext(Context ctx) {
        if (!Prefs.tokenKeepAliveEnabled(ctx)) {
            cancel(ctx);
            return;
        }
        schedule(ctx, ExistingWorkPolicy.REPLACE);
    }

    /**
     * Worker 内自排程专用：APPEND 把下一个任务挂接在当前任务之后，当前任务完成即开始
     * 计时。不能用 REPLACE —— 同名任务处于 RUNNING 状态时 REPLACE 会把当前任务取消，
     * 从 doWork 里排下一天的任务会导致状态显示为 CANCELLED 而非 SUCCEEDED。
     */
    public static void scheduleAfterCurrent(Context ctx) {
        if (!Prefs.tokenKeepAliveEnabled(ctx)) {
            return;
        }
        schedule(ctx, ExistingWorkPolicy.APPEND);
    }

    private static void schedule(Context ctx, ExistingWorkPolicy policy) {
        long delay = nextDelayMs(ctx);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(TokenKeepAliveWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .addTag(UNIQUE_WORK)
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_WORK, policy, req);
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_WORK);
    }

    /** 计算到下一个 03:30 的延迟。 */
    private static long nextDelayMs(Context ctx) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, HOUR);
        target.set(Calendar.MINUTE, MINUTE);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1);
        return target.getTimeInMillis() - now.getTimeInMillis();
    }
}
