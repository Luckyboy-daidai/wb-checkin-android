package com.wbswitch.mobile;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private AccountAdapter adapter;
    private RecyclerView recycler;
    private View emptyView;

    // Hero Card
    private TextView tvHeroCountDone;
    private TextView tvHeroCountTotal;
    private TextView tvHeroSub;
    private TextView tvHeroChip;
    private ProgressBar progressRing;
    private TextView tvRingPercent;
    private FrameLayout ringBox;
    private ImageView ivMascot;
    private View btnQuickCheckin;

    private TextView tvAccountsCount;
    private View layoutAccountsHeader;
    private TextView tvTravelSub;
    private LinearLayout expireBanner;
    private TextView tvExpireBanner;
    /** 首启弹窗引用：旋转屏幕重建 Activity 时先关闭，避免窗口泄漏 */
    private AlertDialog onboardingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 日期显示
        TextView tvDate = findViewById(R.id.tv_date);
        SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日 · EEEE", Locale.CHINA);
        String dateStr = dateFmt.format(new Date());
        // 把星期几简化为"周X"格式
        Calendar cal = Calendar.getInstance();
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekDays = {"", "周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        SimpleDateFormat monthDayFmt = new SimpleDateFormat("M月d日", Locale.CHINA);
        tvDate.setText(monthDayFmt.format(new Date()) + " · " + weekDays[dayOfWeek]);

        // 添加按钮（问候区）
        ImageButton btnAdd = findViewById(R.id.btn_add);
        btnAdd.setOnClickListener(v -> pickVariantAndLogin());

        // 空状态添加按钮
        View emptyBtnAdd = findViewById(R.id.empty_btn_add);
        emptyBtnAdd.setOnClickListener(v -> pickVariantAndLogin());

        // 一键签到按钮
        btnQuickCheckin = findViewById(R.id.btn_quick_checkin);
        btnQuickCheckin.setOnClickListener(v -> checkinAll());

        // 旅行卡
        View cardTravel = findViewById(R.id.card_travel);
        cardTravel.setOnClickListener(v -> startActivity(new Intent(this, TravelActivity.class)));
        tvTravelSub = findViewById(R.id.tv_travel_sub);

        // Hero Card 相关
        tvHeroCountDone = findViewById(R.id.tv_hero_count_done);
        tvHeroCountTotal = findViewById(R.id.tv_hero_count_total);
        tvHeroSub = findViewById(R.id.tv_hero_sub);
        tvHeroChip = findViewById(R.id.tv_hero_chip);
        progressRing = findViewById(R.id.progress_ring);
        tvRingPercent = findViewById(R.id.tv_ring_percent);
        ringBox = findViewById(R.id.ring_box);
        ivMascot = findViewById(R.id.iv_mascot);
        expireBanner = findViewById(R.id.expire_banner);
        tvExpireBanner = findViewById(R.id.tv_expire_banner);
        expireBanner.setOnClickListener(v ->
                startActivity(new Intent(this, CreditsActivity.class)));

        // 账号列表
        tvAccountsCount = findViewById(R.id.tv_accounts_count);
        layoutAccountsHeader = findViewById(R.id.layout_accounts_header);

        adapter = new AccountAdapter();
        recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        recycler.setNestedScrollingEnabled(false);
        emptyView = findViewById(R.id.empty_view);

        // 底部导航
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.nav_checkin);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_checkin) {
                return true;
            } else if (id == R.id.nav_credits) {
                startActivity(new Intent(this, CreditsActivity.class));
                return false;
            } else if (id == R.id.nav_logs) {
                startActivity(new Intent(this, LogsActivity.class));
                return false;
            } else if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return false;
            }
            return false;
        });

        // 先弹首启说明书/隐私协议，用户同意后再请求通知权限，
        // 避免 Android 13+ 上系统权限框和 onboarding 弹窗叠加
        maybeShowOnboarding();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadAccounts();
        // 首次打开签到要先于状态刷新提交（单线程池按提交顺序执行），
        // 否则一轮状态查询会把签到压后几十秒
        maybeFirstOpenCheckin();
        // 积分到期检查：每天首次打开一次（异步，完成后刷新首页提示条）
        maybeExpireCheck();
        renderExpireBanner();
        refreshStatusesInBackground();
        // 应用启动时兜底重排自动签到（幂等：KEEP 策略）
        if (Prefs.autoEnabled(this)) AutoScheduler.scheduleNext(this);
    }

    @Override
    protected void onDestroy() {
        // 旋转等配置变化重建时关闭首启弹窗（onCreate 会重新弹出，不影响使用）
        if (onboardingDialog != null && onboardingDialog.isShowing()) {
            onboardingDialog.dismiss();
        }
        pool.shutdownNow();
        super.onDestroy();
    }

    private void reloadAccounts() {
        List<JSONObject> accounts = AccountStore.loadAccounts(this);
        adapter.setData(accounts);
        boolean isEmpty = accounts.size() == 0;
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        layoutAccountsHeader.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        tvAccountsCount.setText(getString(R.string.home_accounts_count, accounts.size()));

        // 更新旅行卡副标题（国内版账号数）
        int cnCount = 0;
        for (JSONObject acc : accounts) {
            if (Api.supportsCheckin(acc.optString("variant", "cn"))) {
                cnCount++;
            }
        }
        tvTravelSub.setText(getString(R.string.home_travel_sub, cnCount));

        // 更新 Hero Card
        updateHeroCard();
    }

    private void updateHeroCard() {
        List<JSONObject> accounts = AccountStore.loadAccounts(this);
        int total = 0;
        int done = 0;

        for (JSONObject acc : accounts) {
            String variant = acc.optString("variant", "cn");
            if (!Api.supportsCheckin(variant)) continue;
            total++;

            JSONObject status = adapter.statusMap.get(acc.optString("id", ""));
            if (status != null && status.optBoolean("ok", false)
                    && status.optBoolean("todayCheckedIn", false)) {
                done++;
            }
        }

        boolean allDone = total > 0 && done == total;
        boolean hasPending = total > 0 && done < total;
        int percent = total == 0 ? 0 : (done * 100 / total);

        // 大数字
        tvHeroCountDone.setText(String.valueOf(done));
        tvHeroCountTotal.setText("/" + total);

        // 圆环尺寸与位置按设计规范 6.4：有 IP（全部签完）时 78dp、右侧让位给 112dp 形象；
        // 无 IP 时 92dp 贴右。不调整的话圆环会被形象整个压住
        float density = getResources().getDisplayMetrics().density;
        int ringSize = (int) ((allDone ? 78f : 92f) * density);
        int ringMarginEnd = (int) ((allDone ? 120f : 0f) * density);
        LinearLayout.LayoutParams ringLp = (LinearLayout.LayoutParams) ringBox.getLayoutParams();
        if (ringLp.width != ringSize || ringLp.getMarginEnd() != ringMarginEnd) {
            ringLp.width = ringSize;
            ringLp.height = ringSize;
            ringLp.setMarginEnd(ringMarginEnd);
            ringBox.setLayoutParams(ringLp);
        }

        // 换 drawable 必须在 setProgress 之前：setProgressDrawable 不继承旧的 level，
        // 顺序反了圆环会显示为空
        progressRing.setProgressDrawable(ContextCompat.getDrawable(this,
                allDone ? R.drawable.progress_ring_ok : R.drawable.progress_ring_brand));
        progressRing.setProgress(percent);
        tvRingPercent.setText(percent + "%");
        tvRingPercent.setTextColor(ContextCompat.getColor(this,
                allDone ? R.color.ok_deep : R.color.brand_deep));

        // 自动签到 chip（独立于签到进度，显示开关状态）
        boolean autoEnabled = Prefs.autoEnabled(this);
        if (autoEnabled) {
            tvHeroChip.setText(R.string.home_auto_on);
            tvHeroChip.setBackgroundResource(R.drawable.bg_badge_ok);
            tvHeroChip.setTextColor(ContextCompat.getColor(this, R.color.ok_deep));
        } else {
            tvHeroChip.setText(R.string.home_auto_off);
            tvHeroChip.setBackgroundResource(R.drawable.bg_badge_gray);
            tvHeroChip.setTextColor(ContextCompat.getColor(this, R.color.ink_2));
        }

        if (allDone) {
            // 全部签完：完成时间取今天最早的一条成功日志（接口本身不返回签到时间）
            String timeStr = todayEarliestDoneTimeFromLogs();
            if (timeStr != null) {
                tvHeroSub.setText(getString(R.string.home_checkin_done, timeStr));
            } else {
                tvHeroSub.setText(R.string.home_checkin_done_plain);
            }
            ivMascot.setVisibility(View.VISIBLE);
            btnQuickCheckin.setVisibility(View.GONE);
        } else if (hasPending) {
            // 有待办
            tvHeroSub.setText(getString(R.string.home_checkin_pending, total - done));
            ivMascot.setVisibility(View.GONE);
            btnQuickCheckin.setVisibility(View.VISIBLE);
        } else {
            // 没有支持签到的账号
            tvHeroSub.setText(R.string.status_unsupported);
            ivMascot.setVisibility(View.GONE);
            btnQuickCheckin.setVisibility(View.GONE);
        }
    }

    /** 今天最早一条成功/已签的本地日志时间（HH:mm）；没有则返回 null。 */
    private String todayEarliestDoneTimeFromLogs() {
        String today = Checkin.dateStr(System.currentTimeMillis());
        long earliest = 0;
        for (JSONObject log : AccountStore.loadLogs(this)) {
            long ts = log.optLong("ts", 0);
            if (ts <= 0 || !today.equals(Checkin.dateStr(ts))) continue;
            String r = log.optString("result", "");
            if (!"success".equals(r) && !"already".equals(r)) continue;
            if (earliest == 0 || ts < earliest) earliest = ts;
        }
        if (earliest == 0) return null;
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(earliest));
    }

    private void refreshStatusesInBackground() {
        // 签到回调可能在 onDestroy 之后才回到 UI 线程，此时线程池已关闭，再 execute 会崩
        if (pool.isShutdown()) return;
        List<JSONObject> accounts = AccountStore.loadAccounts(this);
        pool.execute(() -> {
            for (int i = 0; i < accounts.size(); i++) {
                JSONObject acc = accounts.get(i);
                if (!Api.supportsCheckin(acc.optString("variant", "cn"))) continue;
                JSONObject status = Checkin.getCheckinStatus(this, acc);
                // 按稳定 id 回写：期间账号列表变化也不会把状态写到别的账号上
                String accountId = acc.optString("id", "");
                runOnUiThread(() -> {
                    // Activity 已销毁时不再操作 UI，避免抛异常
                    if (isDestroyed() || isFinishing()) return;
                    adapter.updateStatus(accountId, status);
                    updateHeroCard();
                });
            }
        });
    }

    /**
     * 每天首次打开 APP 时自动签到一次：检查日期标记，今天还没签过就执行一轮签到。
     * 后续再次打开（同一天）不会重复触发。尊重排除账号列表。
     */
    private void maybeFirstOpenCheckin() {
        if (pool.isShutdown()) return;
        if (!Prefs.firstOpenCheckinEnabled(this)) return;
        List<JSONObject> accounts = AccountStore.loadAccounts(this);
        if (accounts.isEmpty()) return;

        boolean hasCheckable = false;
        for (JSONObject acc : accounts) {
            if (Api.supportsCheckin(acc.optString("variant", "cn"))) {
                hasCheckable = true;
                break;
            }
        }
        if (!hasCheckable) return;

        String today = Checkin.dateStr(System.currentTimeMillis());
        if (today.equals(Prefs.lastOpenCheckinDate(this))) return;

        // 先标记今天已触发，防止 onResume 再次调用时重复签到
        Prefs.setLastOpenCheckinDate(this, today);

        List<String> excluded = Prefs.excludedAccounts(this);
        Toast.makeText(this, R.string.first_open_checkin_running, Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            JSONArray rows = Checkin.checkinAll(this, excluded);
            int ok = 0, already = 0, fail = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String result = row.optString("result", "error");
                if ("success".equals(result)) ok++;
                else if ("already".equals(result)) already++;
                else if (!"skipped".equals(result)) fail++;
            }
            final int fOk = ok, fAlready = already, fFail = fail;
            // 全部账号都签到失败（如打开时断网）时清除当日标记，下次打开自动重试；
            // 有任一成功/已签则维持"今天已签"，避免同一天反复自动签到
            if (fOk == 0 && fAlready == 0 && fFail > 0) {
                Prefs.setLastOpenCheckinDate(this, "");
            }
            runOnUiThread(() -> {
                // Activity 已销毁时不再操作 UI，避免抛异常
                if (isDestroyed() || isFinishing()) return;
                String msg = getString(R.string.first_open_checkin_summary, fOk, fAlready, fFail);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                reloadAccounts();
                refreshStatusesInBackground();
            });
        });
    }

    private void pickVariantAndLogin() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = getLayoutInflater().inflate(R.layout.bottom_sheet_variant, null);
        dialog.setContentView(sheet);
        dialog.getBehavior().setPeekHeight((int) (getResources().getDisplayMetrics().density * 340));

        View optionCn = sheet.findViewById(R.id.option_cn);
        View optionAi = sheet.findViewById(R.id.option_ai);
        ImageView radioCn = sheet.findViewById(R.id.radio_cn);
        ImageView radioAi = sheet.findViewById(R.id.radio_ai);
        View btnNext = sheet.findViewById(R.id.btn_next);

        final String[] selected = {"cn"};

        optionCn.setOnClickListener(v -> {
            selected[0] = "cn";
            optionCn.setBackgroundResource(R.drawable.bg_variant_selected);
            optionAi.setBackgroundResource(R.drawable.bg_variant_unselected);
            radioCn.setImageResource(R.drawable.ic_radio_selected);
            radioAi.setImageResource(R.drawable.ic_radio_unselected);
        });

        optionAi.setOnClickListener(v -> {
            selected[0] = "ai";
            optionAi.setBackgroundResource(R.drawable.bg_variant_selected);
            optionCn.setBackgroundResource(R.drawable.bg_variant_unselected);
            radioAi.setImageResource(R.drawable.ic_radio_selected);
            radioCn.setImageResource(R.drawable.ic_radio_unselected);
        });

        btnNext.setOnClickListener(v -> {
            dialog.dismiss();
            Intent it = new Intent(this, LoginActivity.class);
            it.putExtra("variant", selected[0]);
            startActivity(it);
        });

        dialog.show();
    }

    private void checkinOne(JSONObject account) {
        Toast.makeText(this, "正在签到： " + AccountStore.displayName(account), Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            JSONObject r = Checkin.checkinAccount(this, account);
            runOnUiThread(() -> {
                // Activity 已销毁时不再操作 UI，避免抛异常
                if (isDestroyed() || isFinishing()) return;
                String result = r.optString("result", "error");
                switch (result) {
                    case "success":
                        Toast.makeText(this, R.string.checkin_ok, Toast.LENGTH_SHORT).show();
                        break;
                    case "already":
                        Toast.makeText(this, R.string.checkin_already, Toast.LENGTH_SHORT).show();
                        break;
                    case "skipped":
                        Toast.makeText(this, R.string.checkin_skip_ai, Toast.LENGTH_SHORT).show();
                        break;
                    default:
                        Toast.makeText(this,
                                getString(R.string.checkin_fail, r.optString("error", "")),
                                Toast.LENGTH_LONG).show();
                }
                reloadAccounts();
                refreshStatusesInBackground();
            });
        });
    }

    private void checkinAll() {
        Toast.makeText(this, R.string.checkin_all_running, Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            JSONArray rows = Checkin.checkinAll(this);
            int ok = 0, already = 0, fail = 0;
            StringBuilder firstError = new StringBuilder();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String result = row.optString("result", "error");
                if ("success".equals(result)) ok++;
                else if ("already".equals(result)) already++;
                else if (!"skipped".equals(result)) {
                    fail++;
                    if (firstError.length() == 0) firstError.append(row.optString("error", ""));
                }
            }
            final int fOk = ok, fAlready = already, fFail = fail;
            final String fErr = firstError.toString();
            runOnUiThread(() -> {
                // Activity 已销毁时不再操作 UI，避免抛异常
                if (isDestroyed() || isFinishing()) return;
                String msg = getString(R.string.checkin_all_summary, fOk, fAlready, fFail);
                if (fFail > 0) msg += "\n" + getString(R.string.checkin_fail, fErr);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                reloadAccounts();
                refreshStatusesInBackground();
            });
        });
    }

    /** 每天首次打开 APP 检查一次积分到期情况（有要过期的 → 通知 + 首页提示条）。 */
    private void maybeExpireCheck() {
        if (pool.isShutdown() || isDestroyed() || isFinishing()) return;
        if (!Prefs.expireReminderEnabled(this)) return;
        String today = Checkin.dateStr(System.currentTimeMillis());
        if (today.equals(Prefs.expireCheckDate(this))) return;
        Prefs.setExpireCheckDate(this, today);
        pool.execute(() -> {
            boolean usable = ExpireCheck.refresh(getApplicationContext(), true);
            // 全部账号查询失败（如断网）时清除当日标记，下次打开自动重试
            if (!usable) Prefs.setExpireCheckDate(getApplicationContext(), "");
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                renderExpireBanner();
            });
        });
    }

    /** 用缓存的到期数据渲染首页提示条（积分用掉后缓存清空，提示条自动消失）。 */
    private void renderExpireBanner() {
        if (tvExpireBanner == null || expireBanner == null) return;
        List<JSONObject> items = Prefs.expireReminderEnabled(this)
                ? ExpireCheck.loadCacheItems(this) : new ArrayList<>();
        if (items.isEmpty()) {
            expireBanner.setVisibility(View.GONE);
            return;
        }
        if (items.size() == 1) {
            JSONObject it = items.get(0);
            String date = it.optString("date", "");
            String text = date.isEmpty()
                    ? getString(R.string.expire_notify_line_undated,
                            it.optString("name", "账号"), it.optString("amount", ""))
                    : getString(R.string.expire_notify_line,
                            it.optString("name", "账号"), it.optString("amount", ""), date);
            tvExpireBanner.setText(text + getString(R.string.expire_banner_hint));
        } else {
            long nearest = 0;
            for (JSONObject it : items) {
                long d = it.optLong("dateMs", 0);
                if (d > 0 && (nearest == 0 || d < nearest)) nearest = d;
            }
            String near = nearest > 0
                    ? new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(nearest))
                    : "7 天内";
            tvExpireBanner.setText(getString(R.string.expire_banner_multi,
                    items.size(), near));
        }
        expireBanner.setVisibility(View.VISIBLE);
    }

    private void confirmDelete(JSONObject account) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.confirm_delete, AccountStore.displayName(account)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    AccountStore.deleteAccount(getApplicationContext(), account.optString("id", ""));
                    reloadAccounts();
                })
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    /** 首次启动弹出使用说明 + 隐私协议入口；点"我已阅读并同意"后不再出现。 */
    private void maybeShowOnboarding() {
        if (Prefs.isOnboardingDone(this)) return;
        onboardingDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.onboarding_title)
                .setMessage(R.string.onboarding_message)
                .setCancelable(false)
                .setPositiveButton(R.string.onboarding_agree, (d, w) ->
                        Prefs.setOnboardingDone(this, true))
                .setNegativeButton(R.string.onboarding_read_help, null)
                .setNeutralButton(R.string.onboarding_read_privacy, null)
                .create();
        onboardingDialog.show();
        // 覆盖按钮点击：打开文档后弹窗保持，回来后仍需点"我已阅读并同意"确认
        onboardingDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v ->
                startActivity(new Intent(this, DocsActivity.class)
                        .putExtra(DocsActivity.EXTRA_DOC, DocsActivity.DOC_HELP)));
        onboardingDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                startActivity(new Intent(this, DocsActivity.class)
                        .putExtra(DocsActivity.EXTRA_DOC, DocsActivity.DOC_PRIVACY)));
    }

    // ------------------------------------------------------------------ adapter

    private class AccountAdapter extends RecyclerView.Adapter<AccountVH> {

        private List<JSONObject> accounts = AccountStore.loadAccounts(MainActivity.this);
        private final SimpleDateFormat fmt =
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        final java.util.Map<String, JSONObject> statusMap = new java.util.HashMap<>();

        void setData(List<JSONObject> data) {
            accounts = data;
            // 清理 statusMap 中已不存在的账号，避免残留
            java.util.Set<String> validIds = new java.util.HashSet<>();
            for (JSONObject acc : data) {
                validIds.add(acc.optString("id", ""));
            }
            statusMap.keySet().retainAll(validIds);
            notifyDataSetChanged();
        }

        void updateStatus(String accountId, JSONObject status) {
            // 状态挂在内存视图模型上，不落盘
            statusMap.put(accountId, status);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public AccountVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_account, parent, false);
            return new AccountVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull AccountVH h, int pos) {
            JSONObject acc = accounts.get(pos);
            String variant = acc.optString("variant", "cn");
            boolean isAi = Api.isAi(variant);
            boolean supportsCheckin = Api.supportsCheckin(variant);

            // 账号名
            String displayName = AccountStore.displayName(acc);
            h.tvName.setText(displayName);
            // 超长名字跑马灯滚动（setSelected 激活 marquee）
            h.tvName.setSelected(true);

            // 头像首字
            if (displayName != null && !displayName.isEmpty()) {
                h.tvAvatar.setText(String.valueOf(displayName.charAt(0)).toUpperCase(Locale.getDefault()));
            } else {
                h.tvAvatar.setText("?");
            }

            // 版本标签
            h.tvVariant.setText(isAi ? R.string.variant_ai : R.string.variant_cn);
            if (isAi) {
                h.tvVariant.setBackgroundResource(R.drawable.bg_tag_gray);
                h.tvVariant.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.ink_2));
            } else {
                h.tvVariant.setBackgroundResource(R.drawable.bg_tag_brand);
                h.tvVariant.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.brand_deep));
            }

            boolean relogin = acc.optBoolean("needs_relogin", false);
            JSONObject status = statusMap.get(acc.optString("id", ""));

            // 副行文字
            Long exp = Api.normTs(acc.opt("expiresAt"));
            String subText;
            int subColor;

            if (relogin) {
                subText = getString(R.string.token_need_relogin,
                        acc.optString("needs_relogin_reason", ""));
                subColor = ContextCompat.getColor(MainActivity.this, R.color.warn_deep);
            } else if (!supportsCheckin) {
                subText = getString(R.string.status_unsupported);
                subColor = ContextCompat.getColor(MainActivity.this, R.color.ink_2);
            } else if (exp != null) {
                subText = getString(R.string.token_expiry_fmt, fmt.format(new Date(exp)));
                subColor = ContextCompat.getColor(MainActivity.this, R.color.ink_2);
            } else {
                subText = "";
                subColor = ContextCompat.getColor(MainActivity.this, R.color.ink_2);
            }
            h.tvSub.setText(subText);
            h.tvSub.setTextColor(subColor);

            // 状态徽章 + 卡片高亮态 + 头像背景
            if (relogin) {
                // 需重新登录
                h.layoutBadge.setBackgroundResource(R.drawable.bg_badge_warn);
                h.tvBadge.setText(R.string.badge_relogin);
                h.tvBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.warn_deep));
                h.ivBadgeDot.setImageResource(R.drawable.dot_brand);
                h.ivBadgeDot.setVisibility(View.VISIBLE);
                h.layoutAvatar.setBackgroundResource(R.drawable.bg_avatar_brand);
                // 卡片不高亮
                h.card.setCardBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.surface));
                h.card.setStrokeColor(ContextCompat.getColor(MainActivity.this, R.color.line));
            } else if (!supportsCheckin) {
                // 跳过（不支持签到）
                h.layoutBadge.setBackgroundResource(R.drawable.bg_badge_gray);
                h.tvBadge.setText(R.string.badge_skip);
                h.tvBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.ink_2));
                h.ivBadgeDot.setVisibility(View.GONE);
                h.layoutAvatar.setBackgroundResource(R.drawable.bg_avatar_gray);
                // 卡片不高亮
                h.card.setCardBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.surface));
                h.card.setStrokeColor(ContextCompat.getColor(MainActivity.this, R.color.line));
            } else if (status == null) {
                // 查询中 - 显示去签到态
                h.layoutBadge.setBackgroundResource(R.drawable.bg_badge_brand);
                h.tvBadge.setText(R.string.badge_tocheck);
                h.tvBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.brand_deep));
                h.ivBadgeDot.setImageResource(R.drawable.dot_brand);
                h.ivBadgeDot.setVisibility(View.VISIBLE);
                h.layoutAvatar.setBackgroundResource(R.drawable.bg_avatar_brand);
                // 卡片高亮
                h.card.setCardBackgroundColor(0xFFF7FAFF);
                h.card.setStrokeColor(ContextCompat.getColor(MainActivity.this, R.color.brand_soft));
            } else if (status.optBoolean("ok", false) && status.optBoolean("todayCheckedIn", false)) {
                // 已签到
                h.layoutBadge.setBackgroundResource(R.drawable.bg_badge_ok);
                h.tvBadge.setText(R.string.badge_checked);
                h.tvBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.ok_deep));
                h.ivBadgeDot.setImageResource(R.drawable.dot_ok);
                h.ivBadgeDot.setVisibility(View.VISIBLE);
                h.layoutAvatar.setBackgroundResource(R.drawable.bg_avatar_brand);
                // 卡片不高亮
                h.card.setCardBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.surface));
                h.card.setStrokeColor(ContextCompat.getColor(MainActivity.this, R.color.line));
            } else if (status.optBoolean("ok", false)) {
                // 今日未签 - 去签到态
                h.layoutBadge.setBackgroundResource(R.drawable.bg_badge_brand);
                h.tvBadge.setText(R.string.badge_tocheck);
                h.tvBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.brand_deep));
                h.ivBadgeDot.setImageResource(R.drawable.dot_brand);
                h.ivBadgeDot.setVisibility(View.VISIBLE);
                h.layoutAvatar.setBackgroundResource(R.drawable.bg_avatar_brand);
                // 卡片高亮
                h.card.setCardBackgroundColor(0xFFF7FAFF);
                h.card.setStrokeColor(ContextCompat.getColor(MainActivity.this, R.color.brand_soft));
            } else {
                // 状态查询失败 - 显示去签到态
                h.layoutBadge.setBackgroundResource(R.drawable.bg_badge_brand);
                h.tvBadge.setText(R.string.badge_tocheck);
                h.tvBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.brand_deep));
                h.ivBadgeDot.setImageResource(R.drawable.dot_brand);
                h.ivBadgeDot.setVisibility(View.VISIBLE);
                h.layoutAvatar.setBackgroundResource(R.drawable.bg_avatar_brand);
                // 卡片高亮
                h.card.setCardBackgroundColor(0xFFF7FAFF);
                h.card.setStrokeColor(ContextCompat.getColor(MainActivity.this, R.color.brand_soft));
            }

            // 点击签到
            h.itemView.setOnClickListener(v -> {
                if (supportsCheckin && !relogin) {
                    checkinOne(acc);
                }
            });

            // 长按删除
            h.itemView.setOnLongClickListener(v -> {
                confirmDelete(acc);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return accounts.size();
        }
    }

    private static class AccountVH extends RecyclerView.ViewHolder {
        final com.google.android.material.card.MaterialCardView card;
        final FrameLayout layoutAvatar;
        final TextView tvAvatar;
        final TextView tvName, tvVariant, tvSub;
        final LinearLayout layoutBadge;
        final ImageView ivBadgeDot;
        final TextView tvBadge;

        AccountVH(@NonNull View itemView) {
            super(itemView);
            card = (com.google.android.material.card.MaterialCardView) itemView;
            layoutAvatar = itemView.findViewById(R.id.layout_avatar);
            tvAvatar = itemView.findViewById(R.id.tv_avatar);
            tvName = itemView.findViewById(R.id.tv_name);
            tvVariant = itemView.findViewById(R.id.tv_variant);
            tvSub = itemView.findViewById(R.id.tv_sub);
            layoutBadge = itemView.findViewById(R.id.layout_badge);
            ivBadgeDot = itemView.findViewById(R.id.iv_badge_dot);
            tvBadge = itemView.findViewById(R.id.tv_badge);
        }
    }
}
