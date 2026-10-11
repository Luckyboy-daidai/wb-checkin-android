package com.wbswitch.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

/** 到点自动签到：跑一轮签到，发结果通知，再排下一次。 */
public class CheckinWorker extends Worker {

    private static final String CHANNEL_ID = "checkin";

    public CheckinWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            JSONArray rows = Checkin.checkinAll(ctx, Prefs.excludedAccounts(ctx));
            if (rows.length() == 0) return Result.success();
            int ok = 0, already = 0, fail = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String result = row.optString("result", "error");
                if ("success".equals(result)) ok++;
                else if ("already".equals(result)) already++;
                else if (!"skipped".equals(result)) fail++;
            }
            notify(ctx, ok, already, fail);
        } catch (Exception e) {
            // 不能把异常抛给 WorkManager 返回 failure：APPEND 链上挂着的次日任务
            // 会随父任务失败被一并取消，自动签到从此断链
        } finally {
            AutoScheduler.scheduleAfterCurrent(ctx);
        }
        return Result.success();
    }

    private void notify(Context ctx, int ok, int already, int fail) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT));
        }
        Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(ctx.getString(R.string.notify_auto_title))
                .setContentText(ctx.getString(R.string.notify_auto_summary, ok, already, fail))
                .setContentIntent(AutoScheduler.mainIntent(ctx))
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(1001, n);
        } catch (SecurityException ignored) {
        }
    }
}
