package com.prototypejust.weworkshop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public final class HttpClient {
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36 WEWorkshop/0.1";

    /** 手动设置的 HTTP 代理（来自 App 设置）；null 表示直连。 */
    private static volatile Proxy httpProxy = null;

    private HttpClient() {
    }

    public static void setProxy(Proxy proxy) {
        httpProxy = proxy;
    }

    public static Proxy getProxy() {
        return httpProxy;
    }

    public static void setProxyHostPort(String hostPort) {
        if (hostPort == null || hostPort.trim().isEmpty()) {
            httpProxy = null;
            return;
        }
        String v = hostPort.trim();
        int idx = v.lastIndexOf(':');
        if (idx <= 0 || idx == v.length() - 1) {
            httpProxy = null;
            return;
        }
        String host = v.substring(0, idx).trim();
        int port;
        try {
            port = Integer.parseInt(v.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            httpProxy = null;
            return;
        }
        httpProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    public static String get(String url) throws IOException {
        return request(url, null);
    }

    public static String post(String url, String body) throws IOException {
        return request(url, body);
    }

    private static String request(String urlStr, String postBody) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            Proxy p = httpProxy;
            conn = (HttpURLConnection) (p != null ? url.openConnection(p) : url.openConnection());
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (postBody != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] bytes = postBody.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                throw new IOException("HTTP " + code + " (empty body)");
            }
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            in.close();
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            return bos.toString("UTF-8");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
