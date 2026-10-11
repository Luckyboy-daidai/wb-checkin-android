package com.wbswitch.mobile;

import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TravelActivity extends AppCompatActivity {

    // 账号状态分类
    private static final int STATE_CAN_DISPATCH = 0;   // 可派发
    private static final int STATE_CAN_CLAIM = 1;      // 可领奖
    private static final int STATE_TRAVELING = 2;      // 旅行中
    private static final int STATE_UNSUPPORTED = 3;    // 不适用（国际版）
    private static final int STATE_DISPATCHED = 4;     // 已派发（操作后）
    private static final int STATE_CLAIMED = 5;        // 已领取（操作后）
    private static final int STATE_DAILY_LIMIT = 6;    // 今日已派/达上限
    private static final int STATE_FAILED = 7;         // 失败
    private static final int STATE_QUERYING = 8;       // 查询中

    private ExecutorService pool;
    private TravelAdapter adapter;
    private TextView tvSummaryTitle;
    private TextView tvStatCanNum;
    private TextView tvStatClaimNum;
    private TextView tvStatRunningNum;
    private TextView btnTravelRun;
    private ProgressBar progressBtn;
    private TextView tvListCount;
    private TextView tvEmpty;
    private boolean isRunning = false;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private List<JSONObject> pendingQuery = null; // 可重复查询的账号快照
    private String updatedAtText = null;          // "更新于 HH:mm"

    private static final long REFRESH_INTERVAL_MS = 60_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_travel);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        pool = Executors.newSingleThreadExecutor();

        tvSummaryTitle = findViewById(R.id.tv_summary_title);
        tvStatCanNum = findViewById(R.id.tv_stat_can_num);
        tvStatClaimNum = findViewById(R.id.tv_stat_claim_num);
        tvStatRunningNum = findViewById(R.id.tv_stat_running_num);
        btnTravelRun = findViewById(R.id.btn_travel_run);
        progressBtn = findViewById(R.id.progress_btn);
        tvListCount = findViewById(R.id.tv_list_count);
        tvEmpty = findViewById(R.id.tv_travel_empty);

        RecyclerView recycler = findViewById(R.id.recycler_travel);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TravelAdapter();
        recycler.setAdapter(adapter);

        btnTravelRun.setOnClickListener(v -> runTravel());

        loadAndQueryStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 页面停留期间定时自动刷新：两只猫到达时间不同，页面若只查一次，
        // 顶部汇总会一直停留在打开那一刻的快照，与下方账号状态脱节
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed() || isFinishing()) return;
            if (!isRunning && pendingQuery != null) {
                queryStatuses(pendingQuery);
            }
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onDestroy() {
        refreshHandler.removeCallbacks(refreshRunnable);
        pool.shutdownNow();
        super.onDestroy();
    }

    /**
     * 加载账号并查询初始状态（仅查询，不执行派发/领奖）。
     * 复用 Travel 已有的 fetchStatus / supportsTravel 方法，不改业务逻辑。
     */
    private void loadAndQueryStatus() {
        List<JSONObject> accounts = AccountStore.loadAccounts(this);
        List<JSONObject> initial = new ArrayList<>();
        for (JSONObject acc : accounts) {
            JSONObject o = new JSONObject();
            try {
                o.put("accountId", acc.optString("id", ""));
                o.put("accountName", AccountStore.displayName(acc));
                o.put("ok", true);
                o.put("state", "querying");
                o.put("message", getString(R.string.travel_sub_querying));
                // 把原始 account 暂存，用于后续 status 查询
                o.put("_account", acc);
            } catch (Exception ignored) {}
            initial.add(o);
        }
        adapter.setData(initial);
        updateSummary(initial);
        tvEmpty.setVisibility(initial.isEmpty() ? View.VISIBLE : View.GONE);
        pendingQuery = initial;

        if (initial.isEmpty()) return;
        queryStatuses(initial);
    }

    /** 后台逐账号查询旅行状态并回写（打开页面和每 60 秒自动刷新共用）。 */
    private void queryStatuses(List<JSONObject> initial) {
        pool.execute(() -> {
            List<JSONObject> results = new ArrayList<>();
            for (JSONObject item : initial) {
                JSONObject acc = item.optJSONObject("_account");
                JSONObject row = new JSONObject();
                try {
                    row.put("accountId", item.optString("accountId", ""));
                    row.put("accountName", item.optString("accountName", ""));
                } catch (Exception ignored) {}

                if (acc == null || !Travel.supportsTravel(acc)) {
                    // 不支持（国际版）
                    try {
                        row.put("ok", true);
                        row.put("skip", "unsupported");
                        row.put("message", getString(R.string.travel_sub_na));
                    } catch (Exception ignored) {}
                } else {
                    JSONObject status = Travel.fetchStatus(getApplicationContext(), acc);
                    try {
                        if (status.optBoolean("ok", false)) {
                            row.put("ok", true);
                            row.put("state", status.optString("state", "idle"));
                            row.put("dailyLimitReached", status.optBoolean("dailyLimitReached", false));
                            row.put("locationName", status.optString("locationName", ""));
                            row.put("arriveAt", status.optLong("arriveAt", 0));
                            row.put("recordId", status.optLong("recordId", 0));
                            row.put("rewardCredit", status.opt("rewardCredit"));
                        } else {
                            row.put("ok", false);
                            row.put("message", status.optString("error", "查询失败"));
                        }
                    } catch (Exception ignored) {}
                }
                results.add(row);
            }
            runOnUiThread(() -> {
                // Activity 已销毁时不再操作 UI，避免抛异常
                if (isDestroyed() || isFinishing()) return;
                updatedAtText = getString(R.string.travel_updated_at,
                        new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));
                adapter.setData(results);
                updateSummary(results);
            });
        });
    }

    /**
     * 执行派发/领奖（原 runTravel 逻辑，业务代码一行不改）。
     */
    private void runTravel() {
        if (isRunning) return;
        isRunning = true;
        setButtonLoading(true);
        Toast.makeText(this, R.string.travel_running, Toast.LENGTH_SHORT).show();

        pool.execute(() -> {
            // ===== 以下为原有业务逻辑，完全保留 =====
            JSONArray rows = Travel.runAll(getApplicationContext());
            List<JSONObject> results = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) results.add(rows.optJSONObject(i));
            // ===== 以上为原有业务逻辑，完全保留 =====

            runOnUiThread(() -> {
                isRunning = false;
                // Activity 已销毁时不再操作 UI，避免抛异常
                if (isDestroyed() || isFinishing()) return;
                setButtonLoading(false);
                updatedAtText = getString(R.string.travel_updated_at,
                        new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));
                adapter.setData(results);
                updateSummary(results);

                // 原有统计 Toast 保留
                int ok = 0, already = 0, fail = 0;
                for (JSONObject r : results) {
                    if (!r.optBoolean("ok", false)) fail++;
                    else if (r.optBoolean("already", false)) already++;
                    else ok++;
                }
                Toast.makeText(this, getString(R.string.travel_summary, ok, already, fail),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    /**
     * 汇总统计：计算可派发、可领奖、旅行中的数量。
     */
    private void updateSummary(List<JSONObject> items) {
        int canDispatch = 0;
        int canClaim = 0;
        int traveling = 0;

        for (JSONObject item : items) {
            int state = classifyState(item);
            if (state == STATE_CAN_DISPATCH) canDispatch++;
            else if (state == STATE_CAN_CLAIM) canClaim++;
            else if (state == STATE_TRAVELING || state == STATE_DISPATCHED) traveling++;
        }

        int actionable = canDispatch + canClaim;

        tvSummaryTitle.setText(getString(R.string.travel_summary_title, actionable));
        tvStatCanNum.setText(String.valueOf(canDispatch));
        tvStatClaimNum.setText(String.valueOf(canClaim));
        tvStatRunningNum.setText(String.valueOf(traveling));
        String count = getString(R.string.travel_list_count, items.size());
        if (updatedAtText != null) count += " · " + updatedAtText;
        tvListCount.setText(count);

        // 更新按钮状态
        updateButtonState(canDispatch, canClaim, traveling, items.size());
    }

    /**
     * 根据全局状态更新主按钮文案和样式。
     */
    private void updateButtonState(int canDispatch, int canClaim, int traveling, int total) {
        if (isRunning) return; // 加载中由 setButtonLoading 控制

        if (canDispatch > 0) {
            btnTravelRun.setText(R.string.travel_btn_dispatch);
            btnTravelRun.setBackgroundResource(R.drawable.bg_btn_primary);
            btnTravelRun.setTextColor(ContextCompat.getColor(this, R.color.brand_on_primary));
            btnTravelRun.setEnabled(true);
        } else if (canClaim > 0) {
            btnTravelRun.setText(R.string.travel_btn_claim);
            btnTravelRun.setBackgroundResource(R.drawable.bg_btn_primary);
            btnTravelRun.setTextColor(ContextCompat.getColor(this, R.color.brand_on_primary));
            btnTravelRun.setEnabled(true);
        } else if (traveling > 0 && traveling == countSupported(items())) {
            btnTravelRun.setText(R.string.travel_btn_wait);
            btnTravelRun.setBackgroundResource(R.drawable.bg_btn_disabled);
            btnTravelRun.setTextColor(ContextCompat.getColor(this, R.color.ink_3));
            btnTravelRun.setEnabled(false);
        } else {
            btnTravelRun.setText(R.string.travel_btn_none);
            btnTravelRun.setBackgroundResource(R.drawable.bg_btn_disabled);
            btnTravelRun.setTextColor(ContextCompat.getColor(this, R.color.ink_3));
            btnTravelRun.setEnabled(false);
        }
    }

    private List<JSONObject> items() {
        return adapter.items;
    }

    /**
     * 统计支持旅行的账号数。
     */
    private int countSupported(List<JSONObject> items) {
        int count = 0;
        for (JSONObject item : items) {
            int state = classifyState(item);
            if (state != STATE_UNSUPPORTED) count++;
        }
        return count;
    }

    /**
     * 按钮加载状态：显示进度圈 + "处理中..."
     */
    private void setButtonLoading(boolean loading) {
        if (loading) {
            btnTravelRun.setText(R.string.travel_loading);
            progressBtn.setVisibility(View.VISIBLE);
            btnTravelRun.setEnabled(false);
        } else {
            progressBtn.setVisibility(View.GONE);
            btnTravelRun.setEnabled(true);
        }
    }

    /**
     * 根据 JSONObject 数据判定 UI 状态分类。
     * 适用于初始 status 查询结果和 runAll 操作结果。
     */
    private static int classifyState(JSONObject item) {
        if (!item.optBoolean("ok", true)) {
            return STATE_FAILED;
        }
        String skip = item.optString("skip", "");
        if ("unsupported".equals(skip)) {
            return STATE_UNSUPPORTED;
        }
        if (item.optBoolean("claimed", false)) {
            // 已领取（操作后 or 达上限）
            if ("daily-limit".equals(skip)) {
                return STATE_DAILY_LIMIT;
            }
            return STATE_CLAIMED;
        }
        String state = item.optString("state", "");
        if ("querying".equals(state)) {
            return STATE_QUERYING;
        }
        if ("traveling".equals(state)) {
            // already=true 表示本来就在旅行中（操作前或操作后）
            // already=false 表示刚派发的
            if (item.optBoolean("already", false)) {
                return STATE_TRAVELING;
            }
            // 没有 already 字段或 already=false
            // 对于初始状态查询，没有 already 字段 → 旅行中
            // 对于操作后，!already && traveling → 刚派发
            if (item.has("already") && !item.optBoolean("already", false)) {
                return STATE_DISPATCHED;
            }
            return STATE_TRAVELING;
        }
        if ("arrived".equals(state)) {
            return STATE_CAN_CLAIM;
        }
        if ("idle".equals(state)) {
            if ("daily-limit".equals(skip)) {
                return STATE_DAILY_LIMIT;
            }
            if (item.optBoolean("dailyLimitReached", false)) {
                return STATE_DAILY_LIMIT;
            }
            // idle 且未达上限 → 可派发
            return STATE_CAN_DISPATCH;
        }
        // 默认
        return STATE_CAN_DISPATCH;
    }

    // ==================== Adapter & ViewHolder ====================

    private class TravelAdapter extends RecyclerView.Adapter<TravelVH> {
        final List<JSONObject> items = new ArrayList<>();

        void setData(List<JSONObject> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public TravelVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_travel, parent, false);
            return new TravelVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull TravelVH h, int position) {
            h.bind(items.get(position));
        }

        @Override
        public int getItemCount() { return items.size(); }
    }

    private static class TravelVH extends RecyclerView.ViewHolder {
        private final TextView tvAvatar;
        private final TextView tvName;
        private final TextView tvSub;
        private final LinearLayout badgeContainer;
        private final View badgeDot;
        private final TextView tvBadge;

        TravelVH(View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_avatar);
            tvName = itemView.findViewById(R.id.tv_travel_name);
            tvSub = itemView.findViewById(R.id.tv_travel_sub);
            badgeContainer = itemView.findViewById(R.id.badge_container);
            badgeDot = itemView.findViewById(R.id.badge_dot);
            tvBadge = itemView.findViewById(R.id.tv_badge_text);
        }

        void bind(JSONObject item) {
            String name = item.optString("accountName", "账号");
            tvName.setText(name);

            // 头像首字
            if (!name.isEmpty()) {
                tvAvatar.setText(String.valueOf(name.charAt(0)).toUpperCase());
            } else {
                tvAvatar.setText("?");
            }

            int state = classifyState(item);
            bindBadge(state);
            bindSubtitle(item, state);
        }

        /**
         * 根据状态设置徽章样式（背景、文字颜色、圆点、文案）。
         */
        private void bindBadge(int state) {
            switch (state) {
                case STATE_CAN_DISPATCH:
                case STATE_DISPATCHED:
                    badgeContainer.setBackgroundResource(R.drawable.bg_badge_warn);
                    tvBadge.setTextColor(ContextCompat.getColor(tvBadge.getContext(), R.color.warn_deep));
                    badgeDot.setVisibility(View.VISIBLE);
                    badgeDot.setBackgroundResource(R.drawable.dot_warn);
                    tvBadge.setText(state == STATE_CAN_DISPATCH
                            ? R.string.travel_badge_can
                            : R.string.travel_badge_dispatched);
                    break;
                case STATE_CAN_CLAIM:
                case STATE_CLAIMED:
                    badgeContainer.setBackgroundResource(R.drawable.bg_badge_ok);
                    tvBadge.setTextColor(ContextCompat.getColor(tvBadge.getContext(), R.color.ok_deep));
                    badgeDot.setVisibility(View.VISIBLE);
                    badgeDot.setBackgroundResource(R.drawable.dot_ok);
                    tvBadge.setText(state == STATE_CAN_CLAIM
                            ? R.string.travel_badge_claim
                            : R.string.travel_badge_claimed);
                    break;
                case STATE_TRAVELING:
                    badgeContainer.setBackgroundResource(R.drawable.bg_badge_gray);
                    tvBadge.setTextColor(ContextCompat.getColor(tvBadge.getContext(), R.color.ink_2));
                    badgeDot.setVisibility(View.VISIBLE);
                    badgeDot.setBackgroundResource(R.drawable.dot_gray);
                    tvBadge.setText(R.string.travel_badge_running);
                    break;
                case STATE_DAILY_LIMIT:
                    badgeContainer.setBackgroundResource(R.drawable.bg_badge_gray);
                    tvBadge.setTextColor(ContextCompat.getColor(tvBadge.getContext(), R.color.ink_2));
                    badgeDot.setVisibility(View.VISIBLE);
                    badgeDot.setBackgroundResource(R.drawable.dot_gray);
                    tvBadge.setText(R.string.travel_badge_daily);
                    break;
                case STATE_UNSUPPORTED:
                    badgeContainer.setBackgroundResource(R.drawable.bg_badge_gray);
                    tvBadge.setTextColor(ContextCompat.getColor(tvBadge.getContext(), R.color.ink_2));
                    badgeDot.setVisibility(View.GONE);
                    tvBadge.setText(R.string.travel_badge_na);
                    break;
                case STATE_FAILED:
                    badgeContainer.setBackgroundResource(R.drawable.bg_badge_gray);
                    tvBadge.setTextColor(ContextCompat.getColor(tvBadge.getContext(), R.color.err));
                    badgeDot.setVisibility(View.VISIBLE);
                    badgeDot.setBackgroundResource(R.drawable.dot_gray);
                    tvBadge.setText(R.string.travel_badge_fail);
                    break;
                case STATE_QUERYING:
                default:
                    badgeContainer.setBackgroundResource(R.drawable.bg_badge_gray);
                    tvBadge.setTextColor(ContextCompat.getColor(tvBadge.getContext(), R.color.ink_2));
                    badgeDot.setVisibility(View.GONE);
                    tvBadge.setText(R.string.travel_sub_querying);
                    break;
            }
        }

        /**
         * 设置副行文案。
         */
        private void bindSubtitle(JSONObject item, int state) {
            switch (state) {
                case STATE_CAN_DISPATCH: {
                    String loc = item.optString("locationName", "");
                    if (!loc.isEmpty()) {
                        tvSub.setText(tvSub.getContext().getString(R.string.travel_sub_last, loc));
                    } else {
                        tvSub.setText(R.string.travel_stat_can);
                    }
                    break;
                }
                case STATE_CAN_CLAIM: {
                    String loc = item.optString("locationName", "");
                    if (!loc.isEmpty()) {
                        tvSub.setText(loc);
                    } else {
                        tvSub.setText(R.string.travel_sub_can_claim);
                    }
                    break;
                }
                case STATE_TRAVELING:
                case STATE_DISPATCHED: {
                    StringBuilder sb = new StringBuilder();
                    String loc = item.optString("locationName", "");
                    if (!loc.isEmpty()) {
                        sb.append(loc);
                    } else {
                        sb.append(tvSub.getContext().getString(R.string.travel_sub_traveling));
                    }
                    long arriveAt = item.optLong("arriveAt", 0);
                    String time = Travel.formatArriveAt(arriveAt);
                    if (!time.isEmpty()) {
                        if (sb.length() > 0) sb.append(" · ");
                        sb.append("预计 ").append(time).append(" 到达");
                    }
                    tvSub.setText(sb);
                    break;
                }
                case STATE_UNSUPPORTED:
                    tvSub.setText(R.string.travel_sub_na);
                    break;
                case STATE_DAILY_LIMIT:
                    tvSub.setText(R.string.travel_sub_daily);
                    break;
                case STATE_CLAIMED: {
                    StringBuilder sb = new StringBuilder();
                    sb.append(tvSub.getContext().getString(R.string.travel_sub_claimed));
                    Object rc = item.opt("rewardCredit");
                    if (rc instanceof Number && ((Number) rc).doubleValue() != 0) {
                        sb.append(" · 积分 +").append(rc);
                    }
                    tvSub.setText(sb);
                    break;
                }
                case STATE_FAILED:
                    tvSub.setText(item.optString("message", ""));
                    break;
                case STATE_QUERYING:
                default:
                    tvSub.setText(R.string.travel_sub_querying);
                    break;
            }
        }
    }
}
