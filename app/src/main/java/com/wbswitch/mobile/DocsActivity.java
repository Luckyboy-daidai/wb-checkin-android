package com.wbswitch.mobile;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

/**
 * 文档阅读页：用 WebView 加载 assets 内置的使用说明书 / 隐私协议。
 * 通过 EXTRA_DOC 指定要打开的文档文件名。
 */
public class DocsActivity extends AppCompatActivity {

    public static final String EXTRA_DOC = "doc";
    public static final String DOC_HELP = "help.html";
    public static final String DOC_PRIVACY = "privacy.html";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_docs);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        String doc = getIntent().getStringExtra(EXTRA_DOC);
        boolean privacy = DOC_PRIVACY.equals(doc);
        setTitle(privacy ? R.string.settings_privacy_title : R.string.settings_help_title);

        webView = findViewById(R.id.web_view);
        webView.getSettings().setJavaScriptEnabled(false);
        webView.setBackgroundColor(0xFFF4F6FA);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/" + (privacy ? DOC_PRIVACY : DOC_HELP));

        // 用 OnBackPressedDispatcher 处理返回（onBackPressed 已废弃，
        // Android 13+ 预测性返回手势下旧写法行为可能异常）
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }
}
