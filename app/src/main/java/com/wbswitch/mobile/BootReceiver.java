package com.wbswitch.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机重启调度（WorkManager 的任务在系统重启后也可能丢失追加期，这里兜底重排）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Context appCtx = context.getApplicationContext();
            AutoScheduler.scheduleNext(appCtx);
            TokenKeepAliveScheduler.scheduleNext(appCtx);
        }
    }
}
