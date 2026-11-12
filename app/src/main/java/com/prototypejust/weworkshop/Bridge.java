package com.prototypejust.weworkshop;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.prototypejust.weworkshop.steam.SteamService;
import com.prototypejust.weworkshop.steam.WorkshopDownloader;

public class Bridge {
    private static final String PREFS = "weworkshop";
    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public Bridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /** 初始化 Steam 会话与监听（主线程调用，WebView 就绪后）。 */
    public void initSteam() {
        SteamService.setStateListener(this::pushSteamState);
        SteamService.setGuardListener(this::pushSteamGuard);
        WorkshopDownloader.setProgressListener(this::pushDownloadProgress);
        DownloadStore.init(activity);
        SteamService.init(activity);
    }

    private boolean resumeInterruptedTriggered = false;

    private void pushSteamState() {
        final String js = "window.__steamState && window.__steamState(" + SteamService.stateJson() + ")";
        main.post(() -> webView.evaluateJavascript(js, null));
        // 登录恢复完成后，把进程被杀时中断的下载重新拉起来（只触发一次）
        if (!resumeInterruptedTriggered && SteamService.INSTANCE.isLoggedIn()) {
            resumeInterruptedTriggered = true;
            pool.execute(() -> {
                try {
                    WorkshopDownloader.resumeInterrupted(activity);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private void pushSteamGuard() {
        final String js = "window.__steamGuard && window.__steamGuard("
                + JSONObject.quote(SteamService.INSTANCE.getGuardType()) + ","
                + JSONObject.quote(SteamService.INSTANCE.getGuardEmail()) + ")";
        main.post(() -> webView.evaluateJavascript(js, null));
    }

    private void pushDownloadProgress(long itemId, long received, long total,
                                      String phase, boolean done, String error) {
        String err = error == null ? "null" : JSONObject.quote(error);
        final String js = "window.__downloadProgress && window.__downloadProgress({itemId:" + itemId
                + ",received:" + received + ",total:" + total
                + ",phase:" + JSONObject.quote(phase)
                + ",done:" + done + ",error:" + err + "})";
        main.post(() -> webView.evaluateJavascript(js, null));
    }

    /** 读取已保存的代理（host:port，空串=直连）并应用到 HttpClient。 */
    public static void applySavedProxy(Activity activity) {
        String v = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString("proxy", "");
        HttpClient.setProxyHostPort(v);
    }

    @JavascriptInterface
    public String getProxy() {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString("proxy", "");
    }

    /** 状态栏高度（px），网页用于避开状态栏。 */
    @JavascriptInterface
    public int getSafeTop() {
        return Math.round(MainActivity.safeTopPx / activity.getResources().getDisplayMetrics().density);
    }

    /** 底部导航栏/手势条高度（px）。 */
    @JavascriptInterface
    public int getSafeBottom() {
        return Math.round(MainActivity.safeBottomPx / activity.getResources().getDisplayMetrics().density);
    }

    /** 同步保存代理设置，返回 "ok" 或错误文本。 */
    @JavascriptInterface
    public String setProxy(String hostPort) {
        String v = hostPort == null ? "" : hostPort.trim();
        if (!v.isEmpty() && v.lastIndexOf(':') <= 0) {
            return "err:格式应为 host:port";
        }
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().putString("proxy", v).apply();
        HttpClient.setProxyHostPort(v);
        return "ok";
    }

    private void reply(String cb, String json) {
        final String js = "window.__bridgeResult(" + JSONObject.quote(cb) + "," + json + ")";
        main.post(() -> webView.evaluateJavascript(js, null));
    }

    @JavascriptInterface
    public void browse(String query, String cb) {
        pool.execute(() -> {
            try {
                String html = HttpClient.get("https://steamcommunity.com/workshop/browse/?" + query);
                reply(cb, WorkshopParser.parseBrowse(html));
            } catch (Exception e) {
                reply(cb, error(e));
            }
        });
    }

    /** 登录后通过 Steam 客户端统一消息查询工坊（结果与 WE 客户端一致）。 */
    @JavascriptInterface
    public void browseOnline(String queryJson, String cb) {
        pool.execute(() -> {
            try {
                reply(cb, SteamService.queryPublishedFiles(queryJson));
            } catch (Exception e) {
                reply(cb, error(e));
            }
        });
    }

    @JavascriptInterface
    public void details(String idsCsv, String cb) {
        pool.execute(() -> {
            try {
                reply(cb, WorkshopParser.fetchDetails(idsCsv));
            } catch (Exception e) {
                reply(cb, error(e));
            }
        });
    }

    @JavascriptInterface
    public String steamState() {
        return SteamService.stateJson();
    }

    @JavascriptInterface
    public void steamLogin(String username, String password) {
        SteamService.loginAsync(username == null ? "" : username,
                password == null ? "" : password);
    }

    @JavascriptInterface
    public void steamGuard(String code) {
        SteamService.submitGuardCode(code == null ? "" : code);
    }

    @JavascriptInterface
    public void steamLogout() {
        SteamService.logout();
    }

    @JavascriptInterface
    public void download(String itemId, String manifestId, String title, String img) {
        try {
            WorkshopDownloader.downloadAsync(activity,
                    Long.parseLong(itemId), Long.parseLong(manifestId), title, img);
        } catch (NumberFormatException e) {
            toast("参数错误");
        }
    }

    @JavascriptInterface
    public void cancelDownload() {
        WorkshopDownloader.cancel();
    }

    /** 查询某个条目当前下载进度（JSON），界面重建/重进详情页时用来恢复进度条。 */
    @JavascriptInterface
    public String downloadState(String itemId) {
        try {
            return WorkshopDownloader.progressJson(Long.parseLong(itemId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 某个条目是否还有下载任务在跑。 */
    /** 所有活跃下载的最新进度（JSON map），用于 WebView 暂停/重建后同步 */
    @JavascriptInterface
    public String activeProgress() {
        return WorkshopDownloader.activeProgressJson();
    }

    @JavascriptInterface
    public boolean isDownloading(String itemId) {
        try {
            return WorkshopDownloader.isActive(Long.parseLong(itemId));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 取消指定条目的下载（下载列表里的「取消」按钮）。 */
    @JavascriptInterface
    public void steamDiagnostics(String cb) {
        String json;
        try {
            json = SteamService.diagnosticsJson();
        } catch (Throwable t) {
            json = "{\"error\":\"诊断失败\"}";
        }
        reply(cb, json);
    }

    @JavascriptInterface
    public void cacheSize(String cb) {
        pool.execute(() -> {
            long bytes = 0L;
            try {
                bytes = WorkshopDownloader.cacheBytes(activity.getApplicationContext());
            } catch (Throwable ignored) {
            }
            reply(cb, "{\"ok\":true,\"bytes\":" + bytes + "}");
        });
    }

    @JavascriptInterface
    public void clearCache(String cb) {
        pool.execute(() -> {
            long freed = 0L;
            try {
                freed = WorkshopDownloader.clearCache(activity.getApplicationContext());
            } catch (Throwable ignored) {
            }
            final long f = freed;
            main.post(() -> {
                try {
                    webView.clearCache(true);
                } catch (Throwable ignored) {
                }
                reply(cb, "{\"ok\":true,\"bytes\":" + f + "}");
            });
        });
    }

    @JavascriptInterface
    public void cancelAllDownloads() {
        try {
            WorkshopDownloader.cancel();
        } catch (Throwable ignored) {
        }
    }

    @JavascriptInterface
    public void cancelDownloadById(String itemId) {
        try {
            WorkshopDownloader.cancel(Long.parseLong(itemId));
        } catch (NumberFormatException ignored) {
        }
    }

    /** 重新下载失败/已取消的条目（下载列表里的「重试」按钮）。
     *  旧记录里的 manifest 曾以 JSON number 保存，超过 2^53 会被 JS 截断，
     *  因此这里优先用详情接口重新取精确的 hcontent_file。 */
    @JavascriptInterface
    public void retryDownload(String itemId, String manifestId, String title, String img) {
        pool.execute(() -> {
            long item;
            try {
                item = Long.parseLong(itemId);
            } catch (NumberFormatException e) {
                toast("参数错误");
                return;
            }
            long mid = 0;
            try {
                mid = Long.parseLong(manifestId);
            } catch (NumberFormatException ignored) {
            }
            if (mid <= 0) {
                try {
                    JSONObject root = new JSONObject(WorkshopParser.fetchDetails(itemId));
                    Object raw = root.optJSONObject("items") == null ? null
                            : root.optJSONObject("items").opt(itemId);
                    if (raw instanceof JSONObject) {
                        String hc = ((JSONObject) raw).optString("hcontent_file", "");
                        if (!hc.isEmpty()) mid = Long.parseLong(hc);
                    }
                } catch (Exception ignored) {
                }
            }
            if (mid <= 0) {
                toast("无法获取源文件信息，请从详情页重新下载");
                return;
            }
            WorkshopDownloader.downloadAsync(activity, item, mid,
                    title == null ? "" : title, img == null ? "" : img);
        });
    }

    /** 下载列表 JSON 数组。 */
    @JavascriptInterface
    public String getDownloads() {
        return DownloadStore.list().toString();
    }

    @JavascriptInterface
    public void removeDownload(String itemId) {
        try {
            DownloadStore.remove(Long.parseLong(itemId));
        } catch (NumberFormatException ignored) {
        }
    }

    @JavascriptInterface
    public void clearDownloads() {
        DownloadStore.clear();
    }

    @JavascriptInterface
    public void clearFinishedDownloads() {
        DownloadStore.clearFinished();
    }

    /** 打开 Download/Wallpaper WE/<id>/ 所在目录（系统文件管理器）。 */
    @JavascriptInterface
    public void openDownloadFolder(String itemId) {
        activity.runOnUiThread(() -> {
            String rel = "primary:Download/Wallpaper WE";
            try {
                android.net.Uri uri = android.provider.DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", rel);
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                i.setDataAndType(uri, "vnd.android.document/directory");
                i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
            // 退化：打开 Download 根目录
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                i.setDataAndType(android.net.Uri.parse(
                                "content://com.android.externalstorage.documents/document/primary%3ADownload"),
                        "vnd.android.document/directory");
                activity.startActivity(i);
            } catch (Exception e) {
                toast("无法打开文件管理器");
            }
        });
    }

    @JavascriptInterface
    public void openUrl(String url) {
        activity.runOnUiThread(() -> {
            try {
                android.content.Intent i = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                activity.startActivity(i);
            } catch (Exception ignored) {
            }
        });
    }

    @JavascriptInterface
    public void toast(String msg) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void log(String msg) {
        android.util.Log.i("WEWorkshop", msg);
    }

    private String error(Exception e) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return o.toString();
        } catch (Exception ex) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }
}
