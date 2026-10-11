package com.wbswitch.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        // 加载日志（保留原有数据读取逻辑）
        List<JSONObject> logs = AccountStore.loadLogs(this);
        Collections.reverse(logs);

        // 按日期分组
        List<DayGroup> groups = groupByDate(logs);

        recycler.setAdapter(new LogDayAdapter(groups));

        // 空状态
        View emptyView = findViewById(R.id.empty_view);
        emptyView.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);
        if (logs.isEmpty()) {
            TextView btnGoSettings = findViewById(R.id.btn_go_settings);
            btnGoSettings.setOnClickListener(v -> {
                startActivity(new Intent(this, SettingsActivity.class));
                finish();
            });
        }

        // 底部导航
        setupBottomNav();
    }

    private void setupBottomNav() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setSelectedItemId(R.id.nav_logs);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_checkin) {
                // CLEAR_TOP 复用栈底的 MainActivity：standard 启动模式不带 flags 会不断堆新实例
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                return true;
            } else if (id == R.id.nav_credits) {
                startActivity(new Intent(this, CreditsActivity.class));
                finish();
                return true;
            } else if (id == R.id.nav_logs) {
                return true;
            } else if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                finish();
                return true;
            }
            return false;
        });
    }

    // ----------------------------------------------------------------- 分组

    /** 按天分组的数据结构。 */
    private static class DayGroup {
        final String dateKey;   // yyyy-MM-dd
        final String dateLabel; // 显示用，如 "今天 · 10月1日"
        final List<JSONObject> logs = new ArrayList<>();

        DayGroup(String dateKey, String dateLabel) {
            this.dateKey = dateKey;
            this.dateLabel = dateLabel;
        }
    }

    /** 将日志列表按日期分组，最新的日期在最前面。 */
    private List<DayGroup> groupByDate(List<JSONObject> logs) {
        List<DayGroup> groups = new ArrayList<>();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        SimpleDateFormat labelFmt = new SimpleDateFormat("M月d日", Locale.getDefault());

        // 今天/昨天的日期 key
        Calendar cal = Calendar.getInstance();
        String todayKey = dayFmt.format(cal.getTime());
        cal.add(Calendar.DAY_OF_YEAR, -1);
        String yesterdayKey = dayFmt.format(cal.getTime());

        String lastKey = null;
        DayGroup current = null;

        for (JSONObject log : logs) {
            Long ts = Api.normTs(log.opt("ts"));
            Date date = ts == null ? new Date() : new Date(ts);
            String key = dayFmt.format(date);

            if (!key.equals(lastKey)) {
                String label;
                if (key.equals(todayKey)) {
                    label = getString(R.string.logs_today) + " · " + labelFmt.format(date);
                } else if (key.equals(yesterdayKey)) {
                    label = getString(R.string.logs_yesterday) + " · " + labelFmt.format(date);
                } else {
                    label = labelFmt.format(date);
                }
                current = new DayGroup(key, label);
                groups.add(current);
                lastKey = key;
            }
            if (current != null) {
                current.logs.add(log);
            }
        }

        return groups;
    }

    // -------------------------------------------------------------- Adapter

    private class LogDayAdapter extends RecyclerView.Adapter<DayVH> {

        private final List<DayGroup> groups;

        LogDayAdapter(List<DayGroup> groups) {
            this.groups = groups;
        }

        @NonNull
        @Override
        public DayVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new DayVH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_log_day, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull DayVH h, int pos) {
            DayGroup g = groups.get(pos);
            h.tvDate.setText(g.dateLabel);

            // 动态添加日志行
            h.logsContainer.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(h.logsContainer.getContext());
            int size = g.logs.size();
            for (int i = 0; i < size; i++) {
                JSONObject log = g.logs.get(i);
                View row = inflater.inflate(R.layout.item_log, h.logsContainer, false);
                bindLogRow(row, log);

                // 分隔线：除了最后一条，其余底部加 1dp line_2 分隔线
                if (i < size - 1) {
                    View divider = new View(h.logsContainer.getContext());
                    divider.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1));
                    divider.setBackgroundResource(R.color.line_2);
                    h.logsContainer.addView(divider);
                }

                h.logsContainer.addView(row);
            }
        }

        @Override
        public int getItemCount() {
            return groups.size();
        }
    }

    /** 绑定单条日志行的 UI。 */
    private void bindLogRow(View row, JSONObject log) {
        TextView tvName = row.findViewById(R.id.tv_name);
        TextView tvSubtitle = row.findViewById(R.id.tv_subtitle);
        TextView tvTime = row.findViewById(R.id.tv_time);
        View iconBlock = row.findViewById(R.id.icon_block);
        ImageView ivIcon = row.findViewById(R.id.iv_icon);

        // 账号名
        tvName.setText(log.optString("email", ""));

        // 结果判断
        String result = log.optString("result", "error");
        boolean isSuccess = "success".equals(result) || "already".equals(result);
        boolean isSkipped = "skipped".equals(result);
        boolean isError = !isSuccess && !isSkipped;

        // 图标块三态
        if (isSuccess) {
            iconBlock.setBackgroundResource(R.drawable.bg_log_icon_ok);
            ivIcon.setImageResource(R.drawable.ic_check_small);
        } else if (isSkipped) {
            iconBlock.setBackgroundResource(R.drawable.bg_log_icon_gray);
            ivIcon.setImageResource(R.drawable.ic_minus_small);
        } else {
            iconBlock.setBackgroundResource(R.drawable.bg_log_icon_err);
            ivIcon.setImageResource(R.drawable.ic_exclaim_small);
        }

        // 副行文字
        String subtitle;
        if ("success".equals(result)) {
            subtitle = getString(R.string.log_success);
        } else if ("already".equals(result)) {
            subtitle = getString(R.string.checkin_already);
        } else if ("skipped".equals(result)) {
            subtitle = getString(R.string.checkin_skip_ai);
        } else {
            String error = log.optString("error", "");
            if (error.isEmpty()) error = "未知错误";
            subtitle = getString(R.string.log_fail) + " · " + error;
        }
        tvSubtitle.setText(subtitle);

        // 时间
        Long ts = Api.normTs(log.opt("ts"));
        if (ts != null) {
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
            tvTime.setText(timeFmt.format(new Date(ts)));
        } else {
            tvTime.setText("");
        }
    }

    // ----------------------------------------------------------- ViewHolder

    private static class DayVH extends RecyclerView.ViewHolder {
        final TextView tvDate;
        final LinearLayout logsContainer;

        DayVH(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tv_date);
            logsContainer = itemView.findViewById(R.id.logs_container);
        }
    }
}
