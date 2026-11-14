package com.wbswitch.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机重启调度（WorkManager 的任务在系统重启后也可能丢失追加期，这里兜底重排）。
 * token 保活已改为「每天首次打开 App 时触发」，不再依赖定时任务，开机无需重排；
 * 但覆盖升级后不打开 App 的场景下，旧版本遗留的定时保活仍可能存活，这里一并清理。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Context appCtx = context.getApplicationContext();
            try {
                AutoScheduler.scheduleNext(appCtx);
                // 覆盖升级后不打开 App 的场景，清理遗留定时保活（幂等：无排程时调用无副作用）
                TokenKeepAliveScheduler.cancel(appCtx);
            } catch (Exception ignored) {
                // WorkManager 未初始化等异常场景下忽略：调度失败不影响开机本身
            }
        }
    }
}
