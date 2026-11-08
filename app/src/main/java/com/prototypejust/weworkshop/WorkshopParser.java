package com.prototypejust.weworkshop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WorkshopParser {
    /** 卡片图片链接：<a href=".../sharedfiles/filedetails/?id=123" ...><img src="..." alt="标题"> */
    private static final Pattern CARD = Pattern.compile(
            "sharedfiles/filedetails/\\?id=(\\d+)\"[^>]*>\\s*<img[^>]*?src=\"([^\"]+)\"[^>]*?alt=\"([^\"]*)\"",
            Pattern.DOTALL);
    /** 作者：<a href=".../profiles/<id>/myworkshopfiles/?appid=431960" ...>By 名字</a> */
    private static final Pattern AUTHOR = Pattern.compile(
            "myworkshopfiles/\\?appid=431960\"[^>]*>\\s*(?:By\\s+)?([^<]{1,160})<");
    private static final Pattern TOTAL = Pattern.compile("\"total\"\\s*:\\s*(\\d+)");
    private static final String DETAILS_URL =
            "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/";

    private WorkshopParser() {
    }

    public static String parseBrowse(String html) throws Exception {
        List<String[]> raw = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        Matcher m = CARD.matcher(html);
        while (m.find()) {
            raw.add(new String[]{m.group(1), m.group(2), m.group(3)});
            spans.add(new int[]{m.start(), m.end()});
        }

        JSONArray items = new JSONArray();
        for (int i = 0; i < raw.size(); i++) {
            int end = spans.get(i)[1];
            int nextStart = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : html.length();
            String segment = html.substring(end, nextStart);
            Matcher am = AUTHOR.matcher(segment);
            String author = am.find() ? am.group(1).trim() : "";

            JSONObject o = new JSONObject();
            o.put("id", raw.get(i)[0]);
            o.put("img", raw.get(i)[1]);
            o.put("title", unescapeHtml(raw.get(i)[2]));
            o.put("author", unescapeHtml(author));
            items.put(o);
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("items", items);
        out.put("total", extractTotal(html));
        return out.toString();
    }

    /** 反转义 HTML 实体（&amp; &lt; &gt; &quot; &#x27; 等）。 */
    private static String unescapeHtml(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi);
                    String rep = null;
                    if (ent.equals("amp")) {
                        rep = "&";
                    } else if (ent.equals("lt")) {
                        rep = "<";
                    } else if (ent.equals("gt")) {
                        rep = ">";
                    } else if (ent.equals("quot")) {
                        rep = "\"";
                    } else if (ent.equals("apos")) {
                        rep = "'";
                    } else if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        try {
                            rep = new String(Character.toChars(Integer.parseInt(ent.substring(2), 16)));
                        } catch (Exception ignored) {
                        }
                    } else if (ent.startsWith("#")) {
                        try {
                            rep = String.valueOf((char) Integer.parseInt(ent.substring(1)));
                        } catch (Exception ignored) {
                        }
                    }
                    if (rep != null) {
                        out.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static long extractTotal(String html) {
        int idx = html.indexOf("workshopNumbers");
        if (idx < 0) {
            return 0;
        }
        // loaderData 里内嵌 JSON 是转义字符串（\"total\":123），先反转义再匹配
        String seg = html.substring(idx, Math.min(html.length(), idx + 400))
                .replace("\\\"", "\"");
        Matcher m = TOTAL.matcher(seg);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    public static String fetchDetails(String idsCsv) throws Exception {
        String[] ids = idsCsv.split(",");
        StringBuilder body = new StringBuilder("itemcount=").append(ids.length);
        for (int i = 0; i < ids.length; i++) {
            body.append("&publishedfileids[").append(i).append("]=").append(ids[i].trim());
        }
        String resp = HttpClient.post(DETAILS_URL, body.toString());
        JSONObject root = new JSONObject(resp);
        JSONObject response = root.optJSONObject("response");
        JSONObject out = new JSONObject();
        out.put("ok", response != null);
        if (response != null) {
            JSONObject items = new JSONObject();
            JSONArray arr = response.optJSONArray("publishedfiledetails");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.optJSONObject(i);
                    if (d == null) continue;
                    String id = d.optString("publishedfileid", "");
                    if (id.isEmpty()) continue;
                    items.put(id, slim(d));
                }
            }
            out.put("items", items);
        }
        return out.toString();
    }

    /** 精简详情字段，减小 JS 侧解析压力 */
    private static JSONObject slim(JSONObject d) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", d.optString("publishedfileid", ""));
            o.put("title", d.optString("title", ""));
            o.put("description", d.optString("description", ""));
            o.put("preview_url", d.optString("preview_url", ""));
            o.put("creator", d.optString("creator", ""));
            o.put("subscriptions", d.optLong("subscriptions", 0));
            o.put("favorited", d.optLong("favorited", 0));
            o.put("views", d.optLong("views", 0));
            o.put("time_created", d.optLong("time_created", 0));
            o.put("file_size", d.optLong("file_size", 0));
            o.put("star_rating", d.optDouble("star_rating", 0));
            o.put("vote_data", d.optJSONObject("vote_data"));
            JSONArray tags = new JSONArray();
            JSONArray rawTags = d.optJSONArray("tags");
            if (rawTags != null) {
                for (int i = 0; i < rawTags.length(); i++) {
                    JSONObject t = rawTags.optJSONObject(i);
                    if (t != null) {
                        JSONObject tag = new JSONObject();
                        tag.put("name", t.optString("tag", ""));
                        tag.put("display", t.optString("display_name", t.optString("tag", "")));
                        tags.put(tag);
                    }
                }
            }
            o.put("tags", tags);
            o.put("hcontent_file", d.optString("hcontent_file", ""));
            o.put("filename", d.optString("filename", ""));
            o.put("result", d.optInt("result", 0));
        } catch (Exception ignored) {
        }
        return o;
    }
}
