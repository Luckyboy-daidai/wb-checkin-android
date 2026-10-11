package com.wbswitch.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CreditsActivity extends AppCompatActivity {

    private ExecutorService pool;
    private RecyclerView recycler;
    private CreditsAdapter adapter;
    private ProgressBar progress;
    private View heroCard;
    private TextView tvHeroAmount;
    private TextView tvHeroSub;
    private View listHeader;
    private View emptyView;
    private TextView tvEmptyTitle;
    private TextView tvEmptyDesc;
    private View btnAddAccount;

    /** 免费包代码（用于展示分类，与 Api 中一致） */
    private static final String[] FREE_PACKAGE_CODES = {
            "TCACA_code_008_cfWoLwvjU4", "TCACA_code_007_nzdH5h4Nl0",
            "TCACA_code_028_NtpWi0jzXs", "TCACA_code_029_6wCGEWquYy",
            "TCACA_code_030_BjSt89qTvr", "TCACA_code_001_PqouKr6QWV",
            "TCACA_code_006_DbXS0lrypC", "TCACA_code_035_ArVxJcGDsm",
            "TCACA_code_037_WxOD3MpI2o", "TCACA_code_039_KRcQj7wUat",
            "TCACA_code_040_mi9rCYg46x",
    };

    /** 付费包代码（用于展示分类，与 Api 中一致） */
    private static final String[] PAID_PACKAGE_CODES = {
            "TCACA_code_002_AkiJS3ZHF5", "TCACA_code_023_4xbGhMrE6q",
            "TCACA_code_026_BaESVICNoi", "TCACA_code_027_0FCGVA6vSa",
            "TCACA_code_009_0XmEQc2xOf", "TCACA_code_038_OhvqZtiPKr",
            "TCACA_code_003_FAnt7lcmRT", "TCACA_code_036_lupO5WgNdG",
    };

    private static final double LOW_THRESHOLD = 0.30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credits);

        pool = Executors.newSingleThreadExecutor();
        recycler = findViewById(R.id.recycler_credits);
        progress = findViewById(R.id.progress_credits);
        heroCard = findViewById(R.id.hero_card);
        tvHeroAmount = findViewById(R.id.tv_hero_amount);
        tvHeroSub = findViewById(R.id.tv_hero_sub);
        listHeader = findViewById(R.id.list_header);
        emptyView = findViewById(R.id.empty_view);
        tvEmptyTitle = findViewById(R.id.tv_empty_title);
        tvEmptyDesc = findViewById(R.id.tv_empty_desc);
        btnAddAccount = findViewById(R.id.btn_add_account);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CreditsAdapter();
        recycler.setAdapter(adapter);

        btnAddAccount.setOnClickListener(v -> pickVariantAndLogin());

        setupBottomNav();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到本页都重新拉取：从登录页加完账号返回、或隔了一段时间回来，都能拿到最新积分
        loadAll();
    }

    private void setupBottomNav() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setSelectedItemId(R.id.nav_credits);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_checkin) {
                // CLEAR_TOP 复用栈底的 MainActivity：standard 启动模式不带 flags 会不断堆新实例
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                return true;
            } else if (id == R.id.nav_logs) {
                startActivity(new Intent(this, LogsActivity.class));
                finish();
                return true;
            } else if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                finish();
                return true;
            }
            return false;
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

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private void loadAll() {
        List<JSONObject> accounts = AccountStore.loadAccounts(this);
        if (accounts.isEmpty()) {
            showEmptyState();
            return;
        }
        progress.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        heroCard.setVisibility(View.GONE);
        listHeader.setVisibility(View.GONE);
        pool.execute(() -> {
            List<JSONObject> results = new ArrayList<>();
            for (JSONObject account : accounts) {
                results.add(Api.fetchCredits(getApplicationContext(), account));
            }
            // 用刚查到的结果刷新到期缓存：积分用掉后首页提示条/通知及时消失
            ExpireCheck.updateCacheFromResults(getApplicationContext(), results);
            runOnUiThread(() -> {
                // Activity 已销毁时不再操作 UI，避免抛异常
                if (isDestroyed() || isFinishing()) return;
                progress.setVisibility(View.GONE);
                adapter.setData(results);
                if (results.isEmpty()) {
                    showEmptyState();
                } else {
                    updateHeroCard(results, accounts);
                    heroCard.setVisibility(View.VISIBLE);
                    listHeader.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void showEmptyState() {
        emptyView.setVisibility(View.VISIBLE);
        heroCard.setVisibility(View.GONE);
        listHeader.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
    }

    private void updateHeroCard(List<JSONObject> results, List<JSONObject> accounts) {
        double totalRemaining = 0;
        int cnCount = 0;
        Long soonestExpire = null;
        double expiringSoonRemaining = 0;

        for (int i = 0; i < results.size() && i < accounts.size(); i++) {
            JSONObject r = results.get(i);
            JSONObject acc = accounts.get(i);
            if (!r.optBoolean("ok", false)) continue;
            expiringSoonRemaining += Math.round(r.optDouble("expiringSoonRemaining", 0) * 100.0) / 100.0;
            // 汇总按分（两位小数）取整后再相加，与单账号卡片展示口径一致
            totalRemaining += Math.round(r.optDouble("totalRemaining", 0) * 100.0) / 100.0;
            String variant = acc.optString("variant", "cn");
            if (!Api.isAi(variant)) cnCount++;
            Long se = r.isNull("soonestExpireAt") ? null : r.optLong("soonestExpireAt", 0);
            if (se != null && se > 0) {
                if (soonestExpire == null || se < soonestExpire) soonestExpire = se;
            }
        }

        tvHeroAmount.setText(formatInt(totalRemaining));

        StringBuilder sub = new StringBuilder();
        sub.append(getString(R.string.credits_accounts_count, cnCount));
        if (soonestExpire != null && soonestExpire > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("M月d日", Locale.getDefault());
            sub.append(" · ").append(getString(R.string.credits_soonest_expire,
                    sdf.format(new Date(soonestExpire))));
        }
        if (expiringSoonRemaining > 0) {
            sub.append(" · ").append(getString(R.string.credits_expiring_soon,
                    formatInt(expiringSoonRemaining)));
        }
        tvHeroSub.setText(sub.toString());
    }

    // ---- 辅助方法 ----

    private static String formatInt(double value) {
        // 积分常带小数（官网显示 2,827.97 这类），有零头时保留两位小数
        if (Math.abs(value - Math.rint(value)) < 0.005) {
            return String.format(Locale.getDefault(), "%,d", Math.round(value));
        }
        String s = String.format(Locale.US, "%,.2f", value);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static int calcPercent(double remaining, double total) {
        if (total <= 0) return 0;
        double p = remaining / total * 100;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        return (int) Math.round(p);
    }

    private static boolean isLow(double remaining, double total) {
        if (total <= 0) return true;
        return remaining / total < LOW_THRESHOLD;
    }

    private static boolean isFreePackage(String packageCode) {
        if (packageCode == null) return false;
        for (String c : FREE_PACKAGE_CODES) {
            if (c.equals(packageCode)) return true;
        }
        return false;
    }

    private static boolean isPaidPackage(String packageCode) {
        if (packageCode == null) return false;
        for (String c : PAID_PACKAGE_CODES) {
            if (c.equals(packageCode)) return true;
        }
        return false;
    }

    private static String firstChar(String name) {
        if (name == null || name.isEmpty()) return "?";
        return String.valueOf(name.charAt(0)).toUpperCase(Locale.getDefault());
    }

    // ----------------------------------------------------------------- adapter

    private static class CreditsAdapter extends RecyclerView.Adapter<CreditsVH> {
        private final List<JSONObject> items = new ArrayList<>();

        void setData(List<JSONObject> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public CreditsVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_credit, parent, false);
            return new CreditsVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull CreditsVH h, int position) {
            h.bind(items.get(position));
        }

        @Override
        public int getItemCount() { return items.size(); }
    }

    private static class CreditsVH extends RecyclerView.ViewHolder {
        private final TextView tvAvatar, tvName, tvBadge;
        private final View layoutContent;
        private final TextView tvRemaining, tvTotal, tvPercent;
        private final ProgressBar progressBar;
        private final TextView tvPackages, tvExpire;
        private final TextView tvError;

        CreditsVH(View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_avatar);
            tvName = itemView.findViewById(R.id.tv_credit_name);
            tvBadge = itemView.findViewById(R.id.tv_badge);
            layoutContent = itemView.findViewById(R.id.layout_content);
            tvRemaining = itemView.findViewById(R.id.tv_remaining);
            tvTotal = itemView.findViewById(R.id.tv_total);
            tvPercent = itemView.findViewById(R.id.tv_percent);
            progressBar = itemView.findViewById(R.id.progress_bar);
            tvPackages = itemView.findViewById(R.id.tv_packages);
            tvExpire = itemView.findViewById(R.id.tv_expire);
            tvError = itemView.findViewById(R.id.tv_error);
        }

        void bind(JSONObject item) {
            String name = item.optString("accountName", "账号");
            tvName.setText(name);
            tvName.setSelected(true); // 超长名字跑马灯滚动
            tvAvatar.setText(firstChar(name));

            if (!item.optBoolean("ok", false)) {
                layoutContent.setVisibility(View.GONE);
                tvError.setVisibility(View.VISIBLE);
                tvError.setText(item.optString("error", "查询失败"));
                tvBadge.setVisibility(View.GONE);
                return;
            }

            layoutContent.setVisibility(View.VISIBLE);
            tvError.setVisibility(View.GONE);
            tvBadge.setVisibility(View.VISIBLE);

            double total = item.optDouble("totalCapacity", 0);
            double remaining = item.optDouble("totalRemaining", 0);
            int percent = calcPercent(remaining, total);
            boolean low = isLow(remaining, total);

            tvRemaining.setText(formatInt(remaining));
            tvTotal.setText(itemView.getContext().getString(R.string.credits_total)
                    + " " + formatInt(total));
            tvPercent.setText(percent + "%");

            // 徽章
            if (low) {
                tvBadge.setText(R.string.credits_low);
                tvBadge.setBackgroundResource(R.drawable.bg_badge_warn);
                tvBadge.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.warn_deep));
            } else {
                tvBadge.setText(R.string.credits_sufficient);
                tvBadge.setBackgroundResource(R.drawable.bg_badge_ok);
                tvBadge.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.ok_deep));
            }

            // 进度条：先换 drawable 再设进度。setProgressDrawable 不继承旧的 level，
            // 顺序反了会显示为空，RecyclerView 复用时还会残留别的账号的进度
            progressBar.setProgressDrawable(ContextCompat.getDrawable(itemView.getContext(),
                    low ? R.drawable.bg_progress_warn : R.drawable.bg_progress_brand));
            progressBar.setProgress(percent);

            // 套餐明细
            JSONArray resources = item.optJSONArray("resources");
            double freeRemaining = 0, paidRemaining = 0;
            boolean hasFree = false, hasPaid = false;
            if (resources != null && resources.length() > 0) {
                for (int i = 0; i < resources.length(); i++) {
                    JSONObject r = resources.optJSONObject(i);
                    if (r == null) continue;
                    if (r.optBoolean("expired", false)) continue; // 已过期包不计入分类剩余
                    String pc = r.optString("packageCode", "");
                    double rem = r.optDouble("remaining", 0);
                    if (isFreePackage(pc)) {
                        freeRemaining += rem;
                        hasFree = true;
                    } else if (isPaidPackage(pc)) {
                        paidRemaining += rem;
                        hasPaid = true;
                    } else {
                        // 未知类型计入免费包展示兜底
                        freeRemaining += rem;
                        hasFree = true;
                    }
                }
            }

            StringBuilder pkgLine = new StringBuilder();
            if (hasFree) {
                pkgLine.append(itemView.getContext().getString(R.string.credits_free_pkg))
                        .append(" ").append(formatInt(freeRemaining));
            }
            if (hasPaid) {
                if (pkgLine.length() > 0) pkgLine.append(" · ");
                pkgLine.append(itemView.getContext().getString(R.string.credits_paid_pkg))
                        .append(" ").append(formatInt(paidRemaining));
            }
            double expiredRem = item.optDouble("expiredRemaining", 0);
            if (expiredRem > 0) {
                if (pkgLine.length() > 0) pkgLine.append(" · ");
                pkgLine.append(itemView.getContext()
                        .getString(R.string.credits_expired_pkg, formatInt(expiredRem)));
            }
            tvPackages.setText(pkgLine.toString());
            tvPackages.setVisibility(pkgLine.length() > 0 ? View.VISIBLE : View.GONE);

            // 有效期
            Long soonest = item.isNull("soonestExpireAt") ? null : item.optLong("soonestExpireAt", 0);
            if (soonest != null && soonest > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault());
                tvExpire.setText(itemView.getContext().getString(R.string.credits_valid_until)
                        + " " + sdf.format(new Date(soonest)));
                tvExpire.setVisibility(View.VISIBLE);
            } else {
                tvExpire.setVisibility(View.GONE);
            }
        }
    }
}
