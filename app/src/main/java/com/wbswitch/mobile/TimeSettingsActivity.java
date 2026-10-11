package com.wbswitch.mobile;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 签到时间配置：支持多时间点与排除指定账号。
 */
public class TimeSettingsActivity extends AppCompatActivity {

    private final List<Prefs.TimePoint> times = new ArrayList<>();
    private final List<String> excludedIds = new ArrayList<>();
    private TimeAdapter adapter;
    private TextView tvExcludeSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_time_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvExcludeSummary = findViewById(R.id.tv_exclude_summary);

        times.addAll(Prefs.times(this));
        excludedIds.addAll(Prefs.excludedAccounts(this));

        RecyclerView recycler = findViewById(R.id.recycler_times);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TimeAdapter();
        recycler.setAdapter(adapter);

        findViewById(R.id.btn_add_time).setOnClickListener(v -> pickTime());
        findViewById(R.id.row_exclude).setOnClickListener(v -> pickExcludedAccounts());

        updateExcludeSummary();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        Prefs.setTimes(this, times);
        Prefs.setExcludedAccounts(this, excludedIds);
        if (Prefs.autoEnabled(this)) {
            AutoScheduler.rescheduleNow(this);
        }
    }

    private void pickTime() {
        new TimePickerDialog(this, (picker, h, m) -> {
            Prefs.TimePoint t = new Prefs.TimePoint(h, m);
            if (!times.contains(t)) {
                times.add(t);
                Collections.sort(times);
                adapter.notifyDataSetChanged();
            }
        }, 9, 0, true).show();
    }

    private void pickExcludedAccounts() {
        List<JSONObject> accounts = AccountStore.loadAccounts(this);
        if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.travel_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[accounts.size()];
        boolean[] checked = new boolean[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            JSONObject acc = accounts.get(i);
            names[i] = AccountStore.displayName(acc);
            checked[i] = excludedIds.contains(acc.optString("id", ""));
        }
        // 用临时列表保存对话框内的选择，确定后才提交到 excludedIds
        final List<String> tempExcluded = new ArrayList<>(excludedIds);
        new AlertDialog.Builder(this)
                .setTitle(R.string.time_settings_exclude_title)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> {
                    String id = accounts.get(which).optString("id", "");
                    if (isChecked) {
                        if (!tempExcluded.contains(id)) tempExcluded.add(id);
                    } else {
                        tempExcluded.remove(id);
                    }
                })
                .setPositiveButton(R.string.ok, (d, w) -> {
                    excludedIds.clear();
                    excludedIds.addAll(tempExcluded);
                    updateExcludeSummary();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateExcludeSummary() {
        tvExcludeSummary.setText(excludedIds.isEmpty()
                ? getString(R.string.time_settings_exclude_none)
                : getString(R.string.time_settings_exclude_summary, excludedIds.size()));
    }

    private void removeTime(int position) {
        if (position < 0) return; // 快速连点时 getAdapterPosition 可能返回 NO_POSITION
        if (times.size() <= 1) {
            Toast.makeText(this, R.string.time_settings_last_time, Toast.LENGTH_SHORT).show();
            return;
        }
        times.remove(position);
        adapter.notifyDataSetChanged();
    }

    private class TimeAdapter extends RecyclerView.Adapter<TimeVH> {
        @NonNull
        @Override
        public TimeVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_time_point, parent, false);
            return new TimeVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull TimeVH h, int pos) {
            Prefs.TimePoint t = times.get(pos);
            h.tvTime.setText(String.format(Locale.US, "%02d:%02d", t.hour, t.minute));

            // 只剩一个时间点时删除按钮置灰
            boolean canDelete = times.size() > 1;
            h.btnDelete.setEnabled(canDelete);
            h.btnDelete.setClickable(canDelete);
            if (canDelete) {
                h.btnDelete.setBackgroundResource(R.drawable.bg_trash_btn);
                h.ivTrash.setColorFilter(ContextCompat.getColor(TimeSettingsActivity.this, R.color.err));
            } else {
                h.btnDelete.setBackgroundResource(R.drawable.bg_trash_btn_disabled);
                h.ivTrash.setColorFilter(ContextCompat.getColor(TimeSettingsActivity.this, R.color.ink_3));
            }

            h.btnDelete.setOnClickListener(v -> removeTime(h.getAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return times.size();
        }
    }

    private static class TimeVH extends RecyclerView.ViewHolder {
        final TextView tvTime;
        final View btnDelete;
        final ImageView ivTrash;

        TimeVH(@NonNull View itemView) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tv_time);
            btnDelete = itemView.findViewById(R.id.btn_delete);
            ivTrash = btnDelete.findViewById(R.id.iv_trash);
        }
    }
}
