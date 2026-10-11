package com.wbswitch.mobile;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.util.List;

/**
 * 每日自动 token 保活：在签到时间前刷新全部账号 token，降低到点签到时 token 过期的概率。
 */
public class TokenKeepAliveWorker extends Worker {

    public TokenKeepAliveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        List<JSONObject> accounts = AccountStore.loadAccounts(ctx);
        int ok = 0, fail = 0;
        for (JSONObject acc : accounts) {
            String rt = acc.optString("refresh_token", "");
            if (rt.isEmpty()) continue;
            try {
                JSONObject refreshed = Checkin.refreshToken(ctx, acc);
                if (!refreshed.optBoolean("needs_relogin", false)) {
                    ok++;
                } else {
                    fail++;
                }
            } catch (Exception e) {
                fail++;
            }
        }
        // Worker 内部自排程用 APPEND 策略，避免 REPLACE 取消当前正在运行的任务
        TokenKeepAliveScheduler.scheduleAfterCurrent(ctx);
        return Result.success();
    }
}
