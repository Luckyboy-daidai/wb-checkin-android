package com.wbswitch.mobile;

import android.content.Context;

import org.json.JSONObject;

import java.util.List;

/**
 * token 保活的一次性执行逻辑（与调度解耦，供多处复用）。
 *
 * 当前唯一触发点是「每天首次打开 App 时执行一次」（MainActivity 的
 * maybeFirstOpenKeepAlive 在后台线程调用）；历史遗留的
 * {@link TokenKeepAliveWorker} 也调用同一份逻辑，保证行为一致。
 *
 * 设计约定：
 * - 遍历全部账号，跳过 refresh_token 为空的账号（无法刷新）；
 * - 每个账号的异常独立兜底，绝不向外抛异常（调用方在后台线程，崩溃会拖垮进程）；
 * - 只统计成功/失败数量，不做 UI 交互，由调用方决定是否提示。
 */
public final class TokenKeepAlive {

    /** 无可刷新的账号（全部缺少 refresh token）时的统计结果。 */
    public static final Stats EMPTY_STATS = new Stats(0, 0, 0);

    private TokenKeepAlive() {}

    /**
     * 执行一次 token 保活：为所有带 refresh_token 的账号刷新凭证。
     *
     * @param ctx 任意 Context，内部会取 applicationContext，可安全在后台线程调用
     * @return 本次执行的统计结果，永不为 null；本方法不抛异常
     */
    public static Stats runOnce(Context ctx) {
        if (ctx == null) return EMPTY_STATS;
        Context appCtx;
        try {
            appCtx = ctx.getApplicationContext();
        } catch (Exception e) {
            return EMPTY_STATS;
        }
        if (appCtx == null) appCtx = ctx;

        List<JSONObject> accounts;
        try {
            accounts = AccountStore.loadAccounts(appCtx);
        } catch (Exception e) {
            // 账号库读取失败（文件损坏等）时不阻塞调用方，静默返回空统计
            return EMPTY_STATS;
        }
        if (accounts == null || accounts.isEmpty()) return EMPTY_STATS;

        int ok = 0;
        int fail = 0;
        int skipped = 0;
        for (JSONObject acc : accounts) {
            if (acc == null) {
                skipped++;
                continue;
            }
            String rt = acc.optString("refresh_token", "").trim();
            if (rt.isEmpty()) {
                // 没有 refresh token 的账号无法刷新，跳过且不计失败
                skipped++;
                continue;
            }
            try {
                JSONObject refreshed = Checkin.refreshToken(appCtx, acc);
                if (refreshed != null && !refreshed.optBoolean("needs_relogin", false)) {
                    ok++;
                } else {
                    // needs_relogin 为 true 或返回为空，都视为失败
                    fail++;
                }
            } catch (Exception e) {
                // 单个账号异常不影响其它账号
                fail++;
            }
        }
        return new Stats(ok, fail, skipped);
    }

    /** 一次保活执行的统计结果。 */
    public static final class Stats {
        /** 刷新成功（凭证已换新）的账号数。 */
        public final int ok;
        /** 刷新失败的账号数（含 needs_relogin）。 */
        public final int fail;
        /** 跳过的账号数（缺少 refresh token）。 */
        public final int skipped;

        public Stats(int ok, int fail, int skipped) {
            this.ok = ok;
            this.fail = fail;
            this.skipped = skipped;
        }

        /** 是否至少成功刷新了一个账号。 */
        public boolean hasSuccess() {
            return ok > 0;
        }

        /** 参与刷新的账号总数（不含跳过）。 */
        public int attempted() {
            return ok + fail;
        }

        @Override
        public String toString() {
            return "Stats{ok=" + ok + ", fail=" + fail + ", skipped=" + skipped + "}";
        }
    }
}
