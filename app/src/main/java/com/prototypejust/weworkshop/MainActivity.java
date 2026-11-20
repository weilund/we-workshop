package com.prototypejust.weworkshop;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.prototypejust.weworkshop.steam.WorkshopDownloader;

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

    public static volatile boolean isForeground = false;
    private static volatile boolean batteryPromptShown = false;

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

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                WorkshopDownloader.logCrash(getApplicationContext(), throwable);
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
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
        requestNotificationPermissionIfNeeded();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 首次发起下载时若未加入系统省电白名单，拉起系统一键无限制运行授权弹窗（每次运行最多弹一次）。 */
    public static void promptBatteryExemptionOnce(Activity activity) {
        if (activity == null || batteryPromptShown || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) activity.getSystemService(POWER_SERVICE);
            String pkg = activity.getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                batteryPromptShown = true;
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + pkg));
                activity.startActivity(intent);
            }
        } catch (Throwable ignored) {
        }
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
    protected void onStart() {
        super.onStart();
        isForeground = true;
    }

    @Override
    protected void onStop() {
        isForeground = false;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // App 回到前台时，WebView 在后台可能收不到进度回调，主动同步一次
        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript(
                    "window.__syncDownloads && window.__syncDownloads();", null));
            // WebView 从后台恢复可能要几百毫秒，再补一次
            webView.postDelayed(() -> webView.evaluateJavascript(
                    "window.__syncDownloads && window.__syncDownloads();", null), 600);
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
