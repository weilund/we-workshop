package com.prototypejust.weworkshop;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 下载记录（下载列表）持久化：SharedPreferences 里存一个 JSON 数组。
 * 每条记录：id / title / img / size / received / status / time / path
 * status: downloading | done | failed | canceled
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
                found.put("received", received);
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
}
