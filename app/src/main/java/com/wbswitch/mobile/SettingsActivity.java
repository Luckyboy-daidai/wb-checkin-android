package com.wbswitch.mobile;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 底部 Tab 导航
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.nav_settings);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_settings) {
                return true;
            } else if (id == R.id.nav_checkin) {
                // CLEAR_TOP 复用栈底的 MainActivity：standard 启动模式不带 flags 会不断堆新实例
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                return true;
            } else if (id == R.id.nav_logs) {
                startActivity(new Intent(this, LogsActivity.class));
                finish();
                return true;
            } else if (id == R.id.nav_credits) {
                startActivity(new Intent(this, CreditsActivity.class));
                finish();
                return true;
            }
            return false;
        });

        MaterialSwitch swAuto = findViewById(R.id.sw_auto);
        TextView tvTime = findViewById(R.id.tv_time);
        TextView tvJitter = findViewById(R.id.tv_jitter);
        View rowTime = findViewById(R.id.row_time);
        View rowJitter = findViewById(R.id.row_jitter);
        MaterialSwitch swKeepAlive = findViewById(R.id.sw_token_keep_alive);
        MaterialSwitch swFirstOpenCheckin = findViewById(R.id.sw_first_open_checkin);
        View rowData = findViewById(R.id.row_data);
        View rowHelp = findViewById(R.id.row_help);
        View rowPrivacy = findViewById(R.id.row_privacy);

        swAuto.setChecked(Prefs.autoEnabled(this));
        swAuto.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setAutoEnabled(this, checked);
            setTimeRowsEnabled(rowTime, rowJitter, checked);
            if (checked) {
                AutoScheduler.rescheduleNow(this);
                // 国产 ROM（小米/华为/OPPO/vivo）省电策略会杀掉 WorkManager 定时任务，
                // 引导用户把本应用加入电池优化白名单，自动签到才能到点可靠执行
                requestIgnoreBatteryOptimizationsIfNeeded();
                Toast.makeText(this, R.string.settings_auto_on, Toast.LENGTH_SHORT).show();
            } else {
                AutoScheduler.cancel(this);
                Toast.makeText(this, R.string.settings_auto_off, Toast.LENGTH_SHORT).show();
            }
        });
        setTimeRowsEnabled(rowTime, rowJitter, Prefs.autoEnabled(this));

        swKeepAlive.setChecked(Prefs.tokenKeepAliveEnabled(this));
        swKeepAlive.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setTokenKeepAliveEnabled(this, checked);
            if (checked) {
                TokenKeepAliveScheduler.scheduleNext(this);
                Toast.makeText(this, R.string.settings_token_keep_alive_on, Toast.LENGTH_SHORT).show();
            } else {
                TokenKeepAliveScheduler.cancel(this);
                Toast.makeText(this, R.string.settings_token_keep_alive_off, Toast.LENGTH_SHORT).show();
            }
        });

        swFirstOpenCheckin.setChecked(Prefs.firstOpenCheckinEnabled(this));
        swFirstOpenCheckin.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setFirstOpenCheckinEnabled(this, checked);
            Toast.makeText(this, checked
                            ? R.string.settings_first_open_checkin_on
                            : R.string.settings_first_open_checkin_off,
                    Toast.LENGTH_SHORT).show();
        });

        MaterialSwitch swExpireReminder = findViewById(R.id.sw_expire_reminder);
        swExpireReminder.setChecked(Prefs.expireReminderEnabled(this));
        swExpireReminder.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setExpireReminderEnabled(this, checked);
            if (!checked) ExpireCheck.cancelReminder(this);
            Toast.makeText(this, checked
                            ? R.string.settings_expire_on
                            : R.string.settings_expire_off,
                    Toast.LENGTH_SHORT).show();
        });

        updateTimeLabel(tvTime);
        updateJitterLabel(tvJitter);

        rowTime.setOnClickListener(v -> {
            startActivity(new Intent(this, TimeSettingsActivity.class));
        });

        rowJitter.setOnClickListener(v -> {
            String[] options = {"0", "5", "10", "15", "30", "60"};
            int selected = 0;
            for (int i = 0; i < options.length; i++) {
                if (String.valueOf(Prefs.jitterMin(this)).equals(options[i])) selected = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_jitter_title)
                    .setSingleChoiceItems(options, selected, (d, which) -> {
                        Prefs.setJitterMin(this, Integer.parseInt(options[which]));
                        updateJitterLabel(tvJitter);
                        AutoScheduler.rescheduleNow(this);
                        d.dismiss();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        rowData.setOnClickListener(v -> {
            startActivity(new Intent(this, ExportImportActivity.class));
        });

        // 帮助与协议：内置文档阅读页
        rowHelp.setOnClickListener(v -> startActivity(new Intent(this, DocsActivity.class)
                .putExtra(DocsActivity.EXTRA_DOC, DocsActivity.DOC_HELP)));
        rowPrivacy.setOnClickListener(v -> startActivity(new Intent(this, DocsActivity.class)
                .putExtra(DocsActivity.EXTRA_DOC, DocsActivity.DOC_PRIVACY)));

        // 开发者宣传卡：点击复制昵称，方便朋友间传播时搜索关注
        findViewById(R.id.row_promo).setOnClickListener(v -> {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("promo",
                        getString(R.string.settings_developer_name)));
                Toast.makeText(this, R.string.settings_promo_copy_toast,
                        Toast.LENGTH_SHORT).show();
            }
        });

        // 版本号运行时读取，发版无需手改文案
        TextView tvVersionValue = findViewById(R.id.tv_version_value);
        try {
            tvVersionValue.setText(getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            tvVersionValue.setText("");
        }
    }

    /**
     * 请求把本应用加入电池优化白名单（弹系统确认框）。
     * 已在白名单或 ROM 不支持该入口时静默跳过，不打扰用户。
     */
    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            // 个别 ROM 没有该系统入口，忽略即可，不影响其它功能
        }
    }

    private void updateTimeLabel(TextView tv) {
        List<Prefs.TimePoint> times = Prefs.times(this);
        int excludedCount = Prefs.excludedAccounts(this).size();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.size(); i++) {
            Prefs.TimePoint t = times.get(i);
            if (i > 0) sb.append(" · ");
            sb.append(String.format(Locale.US, "%02d:%02d", t.hour, t.minute));
        }
        sb.append("　");
        sb.append(getString(excludedCount == 0
                        ? R.string.settings_time_exclude_none
                        : R.string.settings_time_exclude_summary,
                excludedCount));
        tv.setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        TextView tvTime = findViewById(R.id.tv_time);
        if (tvTime != null) updateTimeLabel(tvTime);
    }

    private void updateJitterLabel(TextView tv) {
        int j = Prefs.jitterMin(this);
        tv.setText(j == 0 ? getString(R.string.settings_jitter_off)
                : getString(R.string.settings_jitter_fmt, j));
    }

    private void setTimeRowsEnabled(View rowTime, View rowJitter, boolean enabled) {
        rowTime.setEnabled(enabled);
        rowJitter.setEnabled(enabled);
        rowTime.setAlpha(enabled ? 1.0f : 0.4f);
        rowJitter.setAlpha(enabled ? 1.0f : 0.4f);
    }
}
