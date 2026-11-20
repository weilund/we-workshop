package com.prototypejust.weworkshop;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 下载记录（下载列表）持久化：SharedPreferences 里存一个 JSON 数组。
 * 每条记录：id / title / img / size / received / status / time / path / manifest
 * status: downloading | paused | done | failed | canceled
 */
public final class DownloadStore {
    private static final String PREFS = "we_downloads";
    private static final String KEY = "items";

    private static SharedPreferences prefs;

    private DownloadStore() {
    }

    public static synchronized void init(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    public static synchronized JSONArray list() {
        if (prefs == null) return new JSONArray();
        String raw = prefs.getString(KEY, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** 查询单条下载记录（副本）；不存在返回 null。供下载器读取已下载字节数/manifest。 */
    public static synchronized JSONObject get(long id) {
        JSONArray arr = list();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("id") == id) return o;
        }
        return null;
    }

    /** 开始/更新一条下载记录。 */
    public static synchronized void upsert(long id, String title, String img, long size,
                                           long received, String status) {
        upsert(id, title, img, size, received, status, 0L);
    }

    /** 开始/更新一条下载记录（带源文件 manifest id，供失败重试用）。 */
    public static synchronized void upsert(long id, String title, String img, long size,
                                           long received, String status, long manifestId) {
        if (prefs == null) return;
        JSONArray arr = list();
        JSONObject found = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("id") == id) {
                found = o;
                break;
            }
        }
        if (found == null) {
            found = new JSONObject();
            try {
                found.put("id", id);
                found.put("title", title == null ? "" : title);
                found.put("img", img == null ? "" : img);
                found.put("size", size);
                found.put("received", received);
                found.put("status", status);
                if (manifestId > 0) found.put("manifest", String.valueOf(manifestId));
                found.put("time", System.currentTimeMillis());
                found.put("path", "Download/Wallpaper WE/" + id);
                if (manifestId > 0) found.put("manifest", String.valueOf(manifestId));
                arr.put(found);
            } catch (JSONException ignored) {
                return;
            }
        } else {
            try {
                if (title != null && !title.isEmpty()) found.put("title", title);
                if (img != null && !img.isEmpty()) found.put("img", img);
                if (size > 0) found.put("size", size);
                // 关键：进度绝不回退。下载器的"排队中/获取密钥/下载 manifest"等准备阶段
                // 会以 received = 0 上报（此时真实已下载字节数还没算出来）。若这里无条件写入，
                // 刚恢复的断点进度会被清零，界面进度条跳回 0%，进程再被杀一次就真的丢了进度。
                // 规则：只有调用方明确给出 received > 0、或状态为 canceled（取消要清零）、
                // 或 received < 0（显式重置）时才覆盖；否则保留原记录里更大的值。
                long prevReceived = found.optLong("received", 0L);
                if (received < 0L) {
                    // 显式重置（调用方确认无可用缓存分块时使用）
                    found.put("received", 0L);
                } else if ("canceled".equals(status)) {
                    // 取消：进度归零，不允许残留旧值
                    found.put("received", 0L);
                } else if (received > 0L) {
                    // 有真实进度：直接覆盖（重新下载从 0 起算的场景由调用方传 -1 或 canceled 走上面两支）
                    found.put("received", received);
                } else if (prevReceived <= 0L) {
                    // 原记录也是 0：保持 0
                    found.put("received", 0L);
                }
                // 其余情况（received == 0 且原记录 received > 0）：保持原值不动，进度不回退
                found.put("status", status);
                if (manifestId > 0) found.put("manifest", String.valueOf(manifestId));
            } catch (JSONException ignored) {
                return;
            }
        }
        if (!"downloading".equals(status) || found.optLong("time") == 0) {
            try {
                found.put("time", System.currentTimeMillis());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    public static synchronized void remove(long id) {
        if (prefs == null) return;
        JSONArray arr = list();
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("id") != id) out.put(o);
        }
        prefs.edit().putString(KEY, out.toString()).apply();
    }

    public static synchronized void clear() {
        if (prefs == null) return;
        prefs.edit().putString(KEY, "[]").apply();
    }

    /** 只清除已完成的记录，保留下载中/失败/已取消的记录。 */
    public static synchronized void clearFinished() {
        if (prefs == null) return;
        JSONArray arr = list();
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !"done".equals(o.optString("status"))) out.put(o);
        }
        prefs.edit().putString(KEY, out.toString()).apply();
    }
}
