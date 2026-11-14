package com.wbswitch.mobile;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * 历史遗留的 token 保活定时任务（保留类，避免旧设备队列里的任务反序列化失败）。
 *
 * 当前策略是「每天首次打开 App 时触发一次」，新版本不会再排这个任务；
 * 但升级前已经入队的任务仍可能被系统拉起执行，此时只做一次性刷新，
 * 不再自排下一天，保证不会与首开触发叠加。
 */
public class TokenKeepAliveWorker extends Worker {

    public TokenKeepAliveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // 只跑一次、不重排：保活已改为首开触发
        TokenKeepAlive.runOnce(getApplicationContext());
        return Result.success();
    }
}
