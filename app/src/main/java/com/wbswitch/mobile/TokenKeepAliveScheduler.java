package com.wbswitch.mobile;

import android.content.Context;

import androidx.work.WorkManager;

/**
 * token 保活的历史调度入口（仅保留清理能力）。
 *
 * 策略已变更：token 保活改为「每天首次打开 App 时触发一次」（见 MainActivity
 * 的 maybeFirstOpenKeepAlive），不再使用 WorkManager 定时任务。为避免旧版本
 * 升级上来的设备残留旧的定时排程，这里保留 {@link #cancel(Context)}，
 * 由 MainActivity 启动时调用一次做幂等清理；排程方法已全部移除。
 */
public final class TokenKeepAliveScheduler {

    /** 旧版定时任务名：与 {@link TokenKeepAliveWorker} 的历史唯一任务名保持一致。 */
    public static final String UNIQUE_WORK = "token_keep_alive_daily";

    private TokenKeepAliveScheduler() {}

    /** 取消历史遗留的保活定时任务（幂等：没有排程时调用无副作用）。 */
    public static void cancel(Context ctx) {
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_WORK);
        } catch (Exception ignored) {
            // WorkManager 未初始化等异常场景下忽略：保活本身已不依赖定时任务
        }
    }
}
