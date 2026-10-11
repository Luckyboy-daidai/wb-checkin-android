package com.wbswitch.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OAuth 网页登录（oauth.rs 流程）：
 * 申请 state → 浏览器授权 → 轮询 token → 换账号信息 → 落库。
 *
 * 三步进度卡 UI：
 * - 步骤 1：正在向服务器申请登录（请求中 → 完成）
 * - 步骤 2：在浏览器里完成授权（当前步 → 成功/失败）
 * - 步骤 3：保存账号（待执行 → 完成）
 */
public class LoginActivity extends AppCompatActivity {

    // 两条线程：轮询循环会长期占用一条线程（每 3 秒轮询、最长 10 分钟），
    // 「立即检测」必须在另一条线程上即时执行；单线程池会让它在队列里排队，
    // 按钮卡在"正在检测…"直到轮询结束。重复保存由 done.compareAndSet 防护。
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicBoolean done = new AtomicBoolean(false);

    private TextView step1Dot, step1Title, step1Sub;
    private TextView step2Dot, step2Title, step2Sub;
    private TextView step3Dot, step3Title, step3Sub;
    private View stepLine1, stepLine2;
    private MaterialButton btnOpen, btnRetry, btnCheckNow;
    private View progress;
    private TextView tvError;

    private String variant = "cn";
    private volatile String state = "";
    private volatile String authUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        variant = getIntent().getStringExtra("variant");
        if (variant == null) variant = "cn";

        if (savedInstanceState != null) {
            String savedVariant = savedInstanceState.getString("variant");
            if (savedVariant != null) variant = savedVariant;
            state = savedInstanceState.getString("state", "");
            authUrl = savedInstanceState.getString("authUrl", "");
            done.set(savedInstanceState.getBoolean("done", false));
        }
        step1Dot = findViewById(R.id.step_1_dot);
        step1Title = findViewById(R.id.step_1_title);
        step1Sub = findViewById(R.id.step_1_sub);
        step2Dot = findViewById(R.id.step_2_dot);
        step2Title = findViewById(R.id.step_2_title);
        step2Sub = findViewById(R.id.step_2_sub);
        step3Dot = findViewById(R.id.step_3_dot);
        step3Title = findViewById(R.id.step_3_title);
        step3Sub = findViewById(R.id.step_3_sub);
        stepLine1 = findViewById(R.id.step_line_1);
        stepLine2 = findViewById(R.id.step_line_2);
        btnOpen = findViewById(R.id.btn_open_auth);
        btnCheckNow = findViewById(R.id.btn_check_now);
        btnRetry = findViewById(R.id.btn_retry);
        progress = findViewById(R.id.progress);
        tvError = findViewById(R.id.tv_error);

        btnOpen.setOnClickListener(v -> openAuthUrl());
        btnCheckNow.setOnClickListener(v -> checkNow());
        btnRetry.setOnClickListener(v -> startLogin());

        if (!state.isEmpty() && !done.get()) {
            // 恢复到步骤 2（等待授权）
            setStep2Waiting();
            pollLoop();
        } else {
            startLogin();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("variant", variant);
        outState.putString("state", state);
        outState.putString("authUrl", authUrl);
        outState.putBoolean("done", done.get());
    }

    // ===== 状态更新方法 =====

    private void setStepRequesting() {
        // 步骤 1：当前（蓝色实心 + 光晕）
        step1Dot.setBackgroundResource(R.drawable.bg_step_dot_current);
        step1Dot.setText("");
        step1Title.setTextColor(ContextCompat.getColor(this, R.color.ink));
        step1Sub.setText(R.string.login_step_1_sub_request);
        step1Sub.setTextColor(ContextCompat.getColor(this, R.color.ink_2));

        // 步骤 2、3：待执行（灰色）
        setStepPending(step2Dot, step2Title, step2Sub, 2,
                R.string.login_step_2_title, R.string.login_step_2_sub);
        setStepPending(step3Dot, step3Title, step3Sub, 3,
                R.string.login_step_3_title, R.string.login_step_3_sub);

        stepLine1.setBackgroundColor(0xFFDEE8FA);
        stepLine2.setBackgroundColor(0xFFDEE8FA);

        progress.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);
        btnOpen.setVisibility(View.GONE);
        btnCheckNow.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);
    }

    private void setStep2Waiting() {
        // 步骤 1：完成（绿色 + 对勾）
        setStepDone(step1Dot, step1Title, step1Sub,
                R.string.login_step_1_title, R.string.login_step_1_sub_done);

        // 步骤 2：当前（蓝色实心 + 光晕）
        step2Dot.setBackgroundResource(R.drawable.bg_step_dot_current);
        step2Dot.setText("");
        step2Title.setTextColor(ContextCompat.getColor(this, R.color.ink));
        step2Title.setText(R.string.login_step_2_title);
        step2Sub.setTextColor(ContextCompat.getColor(this, R.color.ink_2));
        step2Sub.setText(R.string.login_step_2_sub);

        // 步骤 3：待执行
        setStepPending(step3Dot, step3Title, step3Sub, 3,
                R.string.login_step_3_title, R.string.login_step_3_sub);

        stepLine1.setBackgroundColor(ContextCompat.getColor(this, R.color.ok));
        stepLine2.setBackgroundColor(0xFFDEE8FA);

        progress.setVisibility(View.GONE);
        tvError.setVisibility(View.GONE);
        btnOpen.setVisibility(View.VISIBLE);
        btnCheckNow.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(View.GONE);
    }

    private void setStepSuccess() {
        // 全部完成
        setStepDone(step1Dot, step1Title, step1Sub,
                R.string.login_step_1_title, R.string.login_step_1_sub_done);
        setStepDone(step2Dot, step2Title, step2Sub,
                R.string.login_step_2_title, R.string.login_step_2_sub);
        setStepDone(step3Dot, step3Title, step3Sub,
                R.string.login_step_3_title, R.string.login_step_ok);

        stepLine1.setBackgroundColor(ContextCompat.getColor(this, R.color.ok));
        stepLine2.setBackgroundColor(ContextCompat.getColor(this, R.color.ok));

        progress.setVisibility(View.GONE);
        tvError.setVisibility(View.GONE);
        btnOpen.setVisibility(View.GONE);
        btnCheckNow.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);
    }

    private void setStepError(String message) {
        // 步骤 1：完成（绿色）
        setStepDone(step1Dot, step1Title, step1Sub,
                R.string.login_step_1_title, R.string.login_step_1_sub_done);

        // 步骤 2：错误（红色）
        step2Dot.setBackgroundResource(R.drawable.bg_step_dot_error);
        step2Dot.setText("!");
        step2Dot.setTextColor(ContextCompat.getColor(this, R.color.brand_on_primary));
        step2Title.setTextColor(ContextCompat.getColor(this, R.color.err));
        step2Title.setText(R.string.login_step_2_title);
        step2Sub.setTextColor(ContextCompat.getColor(this, R.color.ink_2));
        step2Sub.setText(R.string.login_step_2_sub);

        // 步骤 3：待执行
        setStepPending(step3Dot, step3Title, step3Sub, 3,
                R.string.login_step_3_title, R.string.login_step_3_sub);

        stepLine1.setBackgroundColor(ContextCompat.getColor(this, R.color.ok));
        stepLine2.setBackgroundColor(0xFFDEE8FA);

        progress.setVisibility(View.GONE);
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
        btnOpen.setVisibility(View.GONE);
        btnCheckNow.setVisibility(View.GONE);
        btnRetry.setVisibility(View.VISIBLE);
    }

    private void setStepPending(TextView dot, TextView title, TextView sub,
                                int num, int titleRes, int subRes) {
        dot.setBackgroundResource(R.drawable.bg_step_dot_pending);
        dot.setText(String.valueOf(num));
        dot.setTextColor(ContextCompat.getColor(this, R.color.ink_2));
        title.setText(titleRes);
        title.setTextColor(ContextCompat.getColor(this, R.color.ink_2));
        sub.setText(subRes);
        sub.setTextColor(ContextCompat.getColor(this, R.color.ink_muted));
    }

    private void setStepDone(TextView dot, TextView title, TextView sub,
                             int titleRes, int subRes) {
        dot.setBackgroundResource(R.drawable.bg_step_dot_done);
        dot.setText("✓");
        dot.setTextColor(ContextCompat.getColor(this, R.color.brand_on_primary));
        title.setText(titleRes);
        title.setTextColor(ContextCompat.getColor(this, R.color.ink));
        sub.setText(subRes);
        sub.setTextColor(ContextCompat.getColor(this, R.color.ink_2));
    }

    // ===== 业务逻辑（以下全部保留原有实现） =====

    private void startLogin() {
        done.set(false);
        setStepRequesting();
        pool.execute(() -> {
            JSONObject o = Api.oauthStart(variant);
            if (o.has("error")) {
                fail(getString(R.string.login_step_fail, o.optString("error", "")));
                return;
            }
            state = o.optString("state", "");
            authUrl = o.optString("authUrl", "");
            runOnUiThread(() -> {
                setStep2Waiting();
                openAuthUrl();
                pollLoop();
            });
        });
    }

    private void openAuthUrl() {
        if (authUrl.isEmpty()) return;
        try {
            // 强制弹出选择器，避免默认在微信内打开导致授权页被拦截、state 无法绑定
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            startActivity(Intent.createChooser(intent, getString(R.string.login_open_with_browser)));
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e), Toast.LENGTH_LONG).show();
        }
    }

    /** 用户手动切回本应用后，立即检测一次 token，给微信/浏览器授权流程一个显式反馈入口。 */
    private void checkNow() {
        if (state == null || state.isEmpty()) return;
        btnCheckNow.setEnabled(false);
        btnCheckNow.setText(R.string.login_checking);
        pool.execute(() -> {
            JSONObject tokenResp = Api.oauthPollToken(variant, state);
            runOnUiThread(() -> {
                btnCheckNow.setEnabled(true);
                btnCheckNow.setText(R.string.btn_check_now);
                if (tokenResp != null) {
                    handleToken(tokenResp);
                } else {
                    Toast.makeText(this, R.string.login_not_yet_authorized, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** 每 3 秒轮询 token，最长 OAUTH_TIMEOUT_SECONDS。 */
    private void pollLoop() {
        pool.execute(() -> {
            long deadline = System.currentTimeMillis() + Api.OAUTH_TIMEOUT_SECONDS * 1000;
            while (!done.get() && System.currentTimeMillis() < deadline && state != null) {
                JSONObject tokenResp = Api.oauthPollToken(variant, state);
                if (tokenResp != null) {
                    handleToken(tokenResp);
                    return;
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!done.get()) {
                fail(getString(R.string.login_step_timeout));
            }
        });
    }

    private void handleToken(JSONObject tokenResp) {
        // 双重轮询时只允许第一个到期的保存账号，防止同一次登录存出两条
        if (!done.compareAndSet(false, true)) return;
        String accessToken = tokenResp.optString("accessToken", "");
        String domain = tokenResp.optString("domain", "");
        if (!Api.domainMatches(variant, domain)) {
            fail(getString(R.string.login_step_fail,
                    "登录响应的 domain（" + domain + "）与所选档位不符"));
            return;
        }
        JSONObject profile = Api.fetchAccountProfile(variant, state, accessToken, domain);
        // 对照 oauth.rs：账号信息响应里的 domain 同样要做档位一致性校验
        if (profile != null && !Api.domainMatches(variant, profile.optString("domain", ""))) {
            fail(getString(R.string.login_step_fail,
                    "账号信息 domain（" + profile.optString("domain", "") + "）与所选档位不符"));
            return;
        }
        JSONObject data = tokenResp.optJSONObject("tokenData");
        JSONObject account = new JSONObject();
        try {
            put(account, "id", AccountStore.newId());
            put(account, "uid", profile == null ? null : profile.opt("uid"));
            put(account, "nickname", profile == null ? null : profile.opt("nickname"));
            put(account, "email", profile == null ? null : profile.opt("email"));
            if (profile != null) {
                put(account, "enterpriseName", profile.opt("enterpriseName"));
                put(account, "enterpriseId", profile.opt("enterpriseId"));
            }
            put(account, "access_token", accessToken);
            put(account, "refresh_token", data == null ? null :
                    firstStr(data, "refreshToken", "refresh_token"));
            put(account, "token_type", data == null ? "Bearer" :
                    firstNonEmpty(data.optString("tokenType", "Bearer"),
                            data.optString("token_type", "Bearer")));
            put(account, "domain", domain);
            put(account, "variant", variant);
            Long exp = data == null ? null :
                    firstTs(data.opt("expiresAt"), data.opt("expires_at"));
            if (exp == null && data != null) {
                long in = data.optLong("expiresIn", 0);
                if (in > 0) exp = Api.nowMs() + in * 1000;
            }
            put(account, "expiresAt", exp);
            Long rtExp = data == null ? null :
                    firstTs(data.opt("refreshExpiresAt"), data.opt("refresh_expires_at"));
            if (rtExp == null && data != null) {
                long in = data.optLong("refreshExpiresIn", 0);
                if (in > 0) rtExp = Api.nowMs() + in * 1000;
            }
            put(account, "refreshExpiresAt", rtExp);
            put(account, "auth_raw", data);
            put(account, "createdAt", Api.nowMs());
            AccountStore.upsertAccount(getApplicationContext(), account);
        } catch (Exception e) {
            fail(getString(R.string.login_step_fail, String.valueOf(e)));
            return;
        }
        runOnUiThread(() -> {
            setStepSuccess();
            Toast.makeText(this, R.string.login_step_ok, Toast.LENGTH_SHORT).show();
            // 100ms 后自动返回
            btnOpen.postDelayed(() -> finish(), 100);
        });
    }

    private void fail(final String message) {
        done.set(true);
        runOnUiThread(() -> setStepError(message));
    }

    private static String firstStr(JSONObject o, String a, String b) {
        String v = o.optString(a, "");
        return v.isEmpty() ? o.optString(b, "") : v;
    }

    /** put 的免检包装：这里的键值类型不会触发 JSONException。 */
    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
        }
    }

    private static Long firstTs(Object a, Object b) {
        Long ts = Api.normTs(a);
        return ts != null ? ts : Api.normTs(b);
    }

    private static String firstNonEmpty(String a, String b) {
        return a.isEmpty() ? b : a;
    }

    @Override
    protected void onDestroy() {
        done.set(true);
        pool.shutdownNow();
        super.onDestroy();
    }
}
