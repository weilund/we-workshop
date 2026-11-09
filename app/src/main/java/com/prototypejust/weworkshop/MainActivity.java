package com.prototypejust.weworkshop;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 单 Activity + WebView。
 *
 * targetSdk 35+ 在 Android 15/16 上强制 edge-to-edge，无法用旧的
 * setFitsSystemWindows 留白，这里通过监听 DecorView 的 WindowInsets，
 * 把状态栏/导航栏高度注入网页（CSS 变量 --safe-top / --safe-bottom），
 * 由网页自己避开状态栏与底部手势条。
 */
public class MainActivity extends Activity {
    private WebView webView;

    static int safeTopPx = 0;
    static int safeBottomPx = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        // targetSdk 35 在部分定制 ROM 上不会自动 edge-to-edge，显式关闭由系统代管的 insets，
        // 否则会出现「系统留白 + 网页 CSS 留白」双重留白。
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        // 资源里的初始值，insets 到达后会被真实值覆盖
        safeTopPx = statusBarHeightPx();
        safeBottomPx = navBarHeightPx();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0E0F13"));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                applySafeArea();
            }
        });
        Bridge bridge = new Bridge(this, webView);
        webView.addJavascriptInterface(bridge, "Bridge");
        Bridge.applySavedProxy(this);
        bridge.initSteam();

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                if (bars.top > 0) safeTopPx = bars.top;
                if (bars.bottom > 0) safeBottomPx = bars.bottom;
                applySafeArea();
                return insets;
            });
        }

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    /** 把安全区高度发给网页；网页用 CSS 变量避开状态栏和手势条。
     *  注意：WindowInsets 给的是物理像素，网页的 CSS px 是逻辑像素，必须除以 density，
     *  否则状态栏 110px 会被当成 110 CSS px（≈385 物理像素）导致留白过大。 */
    private void applySafeArea() {
        if (webView == null) return;
        float density = getResources().getDisplayMetrics().density;
        int topCss = Math.round(safeTopPx / density);
        int bottomCss = Math.round(safeBottomPx / density);
        String js = "window.__applySafeTop && window.__applySafeTop("
                + topCss + "," + bottomCss + ");";
        webView.evaluateJavascript(js, null);
    }

    private int statusBarHeightPx() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            return getResources().getDimensionPixelSize(resId);
        }
        return Math.round(24 * getResources().getDisplayMetrics().density);
    }

    private int navBarHeightPx() {
        int resId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resId > 0) {
            return getResources().getDimensionPixelSize(resId);
        }
        return Math.round(48 * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // App 回到前台时，WebView 在后台可能收不到进度回调，主动同步一次
        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript(
                    "window.__syncDownloads && window.__syncDownloads();", null));
        }
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("(window.__onBack && window.__onBack()) ? 1 : 0", value -> {
            if (!"1".equals(value)) {
                finish();
            }
        });
    }
}
