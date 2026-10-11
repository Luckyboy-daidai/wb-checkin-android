package com.wbswitch.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 账号导入导出页：通过 Storage Access Framework 读写 JSON 文件，
 * 与桌面版 accounts.json 格式互通。
 */
public class ExportImportActivity extends AppCompatActivity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private TextView tvResult;

    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    doExport(result.getData().getData());
                }
            });

    private final ActivityResultLauncher<Intent> importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    doImport(result.getData().getData());
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export_import);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvResult = findViewById(R.id.tv_result);

        findViewById(R.id.btn_export).setOnClickListener(v -> openExportPicker());
        findViewById(R.id.btn_import).setOnClickListener(v -> openImportPicker());
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private void openExportPicker() {
        String fileName = "wb_accounts_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        exportLauncher.launch(intent);
    }

    private void openImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"application/json", "text/plain"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        importLauncher.launch(intent);
    }

    private void doExport(Uri uri) {
        Toast.makeText(this, R.string.export_import_exporting, Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            String msg;
            boolean ok = false;
            int count = 0;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new RuntimeException("无法打开目标文件");
                JSONArray data = AccountStore.exportAccounts(getApplicationContext(), null);
                os.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
                count = data.length();
                ok = true;
                msg = getString(R.string.export_import_export_summary, count);
            } catch (Exception e) {
                msg = getString(R.string.export_import_export_fail, e.getMessage());
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, fMsg,
                        fOk ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                tvResult.setText(fMsg);
            });
        });
    }

    private void doImport(Uri uri) {
        Toast.makeText(this, R.string.export_import_importing, Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            String msg;
            boolean ok = false;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new RuntimeException("无法打开源文件");
                String text = readAll(is);
                JSONArray items = new JSONArray(text);
                AccountStore.ImportResult r = AccountStore.importAccounts(getApplicationContext(), items);
                ok = true;
                msg = getString(R.string.export_import_import_summary,
                        r.imported, r.overwritten, r.skipped);
            } catch (Exception e) {
                msg = getString(R.string.export_import_import_fail, e.getMessage());
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, fMsg,
                        fOk ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                tvResult.setText(fMsg);
            });
        });
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
