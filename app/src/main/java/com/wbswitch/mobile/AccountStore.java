package com.wbswitch.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 账号库 / 签到日志 / 设置的本地存储（对应桌面版 ~/.wb-switch 下的 json 文件）。 */
public final class AccountStore {

    private AccountStore() {}

    private static File accountsFile(Context ctx) {
        return new File(ctx.getFilesDir(), "accounts.json");
    }

    private static File logsFile(Context ctx) {
        return new File(ctx.getFilesDir(), "checkin_logs.json");
    }

    public static synchronized List<JSONObject> loadAccounts(Context ctx) {
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(readFile(accountsFile(ctx)));
            for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return list;
    }

    public static synchronized void saveAccounts(Context ctx, List<JSONObject> accounts) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject o : accounts) arr.put(o);
        writeFile(accountsFile(ctx), arr.toString(2));
    }

    public static synchronized void upsertAccount(Context ctx, JSONObject account) {
        try {
            List<JSONObject> accounts = loadAccounts(ctx);
            String id = account.optString("id", "");
            for (int i = 0; i < accounts.size(); i++) {
                if (id.equals(accounts.get(i).optString("id", ""))) {
                    accounts.set(i, account);
                    saveAccounts(ctx, accounts);
                    return;
                }
            }
            accounts.add(account);
            saveAccounts(ctx, accounts);
        } catch (Exception e) {
            throw new RuntimeException("保存账号失败: " + e.getMessage(), e);
        }
    }

    public static synchronized void deleteAccount(Context ctx, String id) {
        try {
            List<JSONObject> accounts = loadAccounts(ctx);
            List<JSONObject> keep = new ArrayList<>();
            for (JSONObject o : accounts) {
                if (!id.equals(o.optString("id", ""))) keep.add(o);
            }
            saveAccounts(ctx, keep);
        } catch (Exception e) {
            throw new RuntimeException("删除账号失败: " + e.getMessage(), e);
        }
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String displayName(JSONObject account) {
        String name = firstNonEmpty(account.optString("nickname", ""), account.optString("email", ""));
        if (name.isEmpty()) name = account.optString("uid", "账号");
        return name;
    }

    /**
     * 导出账号记录。
     * @param ids 要导出的账号 id 列表；为空时导出全部。
     * @return 完整账号 JSON 数组（含 token），与桌面版 accounts.json 格式一致。
     */
    public static synchronized JSONArray exportAccounts(Context ctx, List<String> ids) {
        List<JSONObject> accounts = loadAccounts(ctx);
        JSONArray out = new JSONArray();
        boolean all = ids == null || ids.isEmpty();
        Set<String> idSet = all ? null : new HashSet<>(ids);
        for (JSONObject acc : accounts) {
            if (all || (idSet != null && idSet.contains(acc.optString("id", "")))) {
                out.put(acc);
            }
        }
        return out;
    }

    /** 按 uid 合并导入记录；桌面版 export_import.rs 的简化移植。 */
    public static synchronized ImportResult importAccounts(Context ctx, JSONArray items) {
        List<JSONObject> accounts = loadAccounts(ctx);
        int imported = 0, overwritten = 0, skipped = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) { skipped++; continue; }
            Object token = item.opt("access_token");
            String at = token instanceof String ? (String) token : "";
            if (at.trim().isEmpty() && !(token instanceof JSONObject && ((JSONObject) token).has("envelope"))) {
                skipped++;
                continue;
            }
            String uid = item.optString("uid", "");
            boolean merged = false;
            if (!uid.isEmpty()) {
                for (int j = 0; j < accounts.size(); j++) {
                    if (uid.equals(accounts.get(j).optString("uid", ""))) {
                        JSONObject replaced = mergeImportRecord(accounts.get(j), item);
                        accounts.set(j, replaced);
                        imported++;
                        overwritten++;
                        merged = true;
                        break;
                    }
                }
            }
            if (!merged) {
                if (item.optString("id", "").isEmpty()) {
                    try { item.put("id", newId()); } catch (Exception ignored) {}
                }
                accounts.add(item);
                imported++;
            }
        }
        try {
            saveAccounts(ctx, accounts);
        } catch (Exception e) {
            throw new RuntimeException("保存账号库失败: " + e.getMessage(), e);
        }
        return new ImportResult(imported, overwritten, skipped);
    }

    private static JSONObject mergeImportRecord(JSONObject local, JSONObject imported) {
        JSONObject copy = new JSONObject();
        try {
            // 保留本地展示字段
            for (String key : new String[]{"note", "displayField"}) {
                if (!imported.has(key) && local.has(key)) copy.put(key, local.opt(key));
            }
            // 以导入为准覆盖其余字段
            for (java.util.Iterator<String> it = imported.keys(); it.hasNext(); ) {
                String key = it.next();
                copy.put(key, imported.opt(key));
            }
            if (copy.optString("id", "").isEmpty()) {
                copy.put("id", local.optString("id", newId()));
            }
        } catch (Exception ignored) {}
        return copy;
    }

    public static class ImportResult {
        public final int imported;
        public final int overwritten;
        public final int skipped;
        public ImportResult(int imported, int overwritten, int skipped) {
            this.imported = imported;
            this.overwritten = overwritten;
            this.skipped = skipped;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    // ------------------------------------------------------------------- logs

    /** 追加一条签到日志，超出上限截断最旧的。 */
    public static synchronized void addCheckinLog(Context ctx, JSONObject entry) {
        try {
            List<JSONObject> logs = loadLogs(ctx);
            logs.add(entry);
            while (logs.size() > 500) logs.remove(0);
            saveLogs(ctx, logs);
        } catch (Exception e) {
            throw new RuntimeException("保存签到日志失败: " + e.getMessage(), e);
        }
    }

    public static synchronized List<JSONObject> loadLogs(Context ctx) {
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(readFile(logsFile(ctx)));
            for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return list;
    }

    private static void saveLogs(Context ctx, List<JSONObject> logs) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject o : logs) arr.put(o);
        writeFile(logsFile(ctx), arr.toString(2));
    }

    // ------------------------------------------------------------------ files

    private static String readFile(File f) throws Exception {
        if (!f.exists()) return "[]";
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 先写临时文件再原子替换：直接覆盖写时进程被杀会留下半个 JSON，账号库就全丢了。 */
    private static void writeFile(File f, String content) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }
        if (tmp.renameTo(f)) return;
        // 个别实现不允许覆盖已存在目标，退化为先删后改
        if (!f.delete() || !tmp.renameTo(f)) {
            tmp.delete();
            throw new Exception("无法保存 " + f.getName());
        }
    }
}
