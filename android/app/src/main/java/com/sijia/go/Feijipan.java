package com.sijia.go;

import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 小飞机网盘（feijipan）分享接口客户端，按 feijipan-update 技能的统一模板实现。
 *
 * <p>分享结构：根 → 清弈（可选）→ ios / 安卓（读取时同时接受 android）→ 安装包。
 * 列目录走 {@code GET /ws/share/list}（与分享页前端同一套参数，含 AES 加密的 timestamp），
 * 取直链走 {@code GET /ws/file/redirect}，只读 302 的 Location，绝不把安装包读进内存。
 *
 * <p>接口不是官方文档，任何一步失败都返回带「实际地址 + HTTP 结果」的可读错误，
 * 由上层回落到隐藏 WebView 或浏览器打开分享页。
 */
public final class Feijipan {

    public static final String TAG = "UpdateFeed";
    public static final String SHARE_ID = "PRzlQ1Hk";
    public static final String SHARE_URL = "https://share.feijipan.com/s/" + SHARE_ID;
    public static final String API_HOST = "api.feijipan.com";
    public static final String API_BASE = "https://api.feijipan.com/ws";
    public static final String SHARE_ORIGIN = "https://share.feijipan.com";
    /** Referer 要指向具体分享页，接口按它校验来源 */
    public static final String SHARE_REFERER = SHARE_URL;
    /** 分享根目录里的应用文件夹（contains 匹配，大小写不敏感；旧结构可能没有） */
    public static final String APP_FOLDER = "清弈";
    /** 安卓平台子目录 */
    public static final String PLATFORM_FOLDER = "安卓";

    public static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    /** 前端 encryptHex 用的固定 key（AES-128-ECB + PKCS7，输出大写 hex） */
    private static final String AES_KEY = "dingHao-disk-app";
    private static final int MAX_BODY = 256 * 1024;

    private static final String UUID = randomHex(32);

    private Feijipan() {
    }

    /** 分享目录里的一个条目。 */
    public static final class Entry {
        public final String name;
        public final boolean folder;
        public final String fileId;
        public final String folderId;

        Entry(String name, boolean folder, String fileId, String folderId) {
            this.name = name;
            this.folder = folder;
            this.fileId = fileId == null ? "" : fileId;
            this.folderId = folderId == null ? "" : folderId;
        }

        /** 列子目录时传的 id：文件夹一般给 folderId，缺省退回 fileId。 */
        public String childId() {
            return folderId.isEmpty() ? fileId : folderId;
        }
    }

    /** 一次不跟随跳转的 HTTP 结果。 */
    public static final class Http {
        public int status;
        public String location;
        public String contentType = "";
        public String body = "";
        public String error;
    }

    public static final class ListResult {
        public final boolean ok;
        public final List<Entry> entries;
        public final String message;

        private ListResult(boolean ok, List<Entry> entries, String message) {
            this.ok = ok;
            this.entries = entries == null ? new ArrayList<>() : entries;
            this.message = message == null ? "" : message;
        }

        static ListResult ok(List<Entry> entries) {
            return new ListResult(true, entries, "");
        }

        static ListResult fail(String message) {
            return new ListResult(false, null, message);
        }
    }

    public static final class LinkResult {
        public final boolean ok;
        public final String url;
        public final String message;

        private LinkResult(boolean ok, String url, String message) {
            this.ok = ok;
            this.url = url;
            this.message = message == null ? "" : message;
        }

        static LinkResult ok(String url) {
            return new LinkResult(true, url, "");
        }

        static LinkResult fail(String message) {
            return new LinkResult(false, null, message);
        }
    }

    // ---------------------------------------------------------------- 地址

    private static String randomHex(int len) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(Character.forDigit(random.nextInt(16), 16));
        }
        return sb.toString();
    }

    static String encryptHex(String text) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            SecretKeySpec key = new SecretKeySpec(
                    AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] out = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format(Locale.US, "%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "encrypt failed: " + e);
            return "";
        }
    }

    public static String listUrl(String folderId) {
        StringBuilder sb = new StringBuilder(API_BASE).append("/share/list?devType=3&devModel=Windows")
                .append("&uuid=").append(UUID)
                .append("&extra=2")
                .append("&timestamp=").append(encryptHex(String.valueOf(System.currentTimeMillis())))
                .append("&shareId=").append(SHARE_ID)
                .append("&offset=1&limit=100");
        if (folderId != null && !folderId.isEmpty()) {
            sb.append("&folderId=").append(folderId);
        }
        return sb.toString();
    }

    public static String redirectUrl(String fileId) {
        String ts = String.valueOf(System.currentTimeMillis());
        return new StringBuilder(API_BASE).append("/file/redirect?downloadId=")
                .append(encryptHex(fileId + "|"))
                .append("&enable=1&devType=3&uuid=").append(UUID)
                .append("&timestamp=").append(encryptHex(ts))
                .append("&auth=").append(encryptHex(fileId + "|" + ts))
                .append("&shareId=").append(SHARE_ID)
                .toString();
    }

    // ---------------------------------------------------------------- HTTP

    /** 发一次 GET：不跟随跳转，带桌面 UA、分享页 Referer/Origin、WebView cookie 与 gzip。 */
    public static Http httpGet(String url) {
        Http result = new Http();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", DESKTOP_UA);
            conn.setRequestProperty("Referer", SHARE_REFERER);
            conn.setRequestProperty("Origin", SHARE_ORIGIN);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Content-Type", "application/json");
            // 接口只有在客户端声明支持压缩时才返回完整目录，手动设了就得自己解 gzip。
            conn.setRequestProperty("Accept-Encoding", "gzip");
            String cookie = cookieFor(url);
            if (!cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
                Log.d(TAG, "cookie len=" + cookie.length() + " url=" + clip(url));
            }

            result.status = conn.getResponseCode();
            result.location = conn.getHeaderField("Location");
            String type = conn.getContentType();
            result.contentType = type == null ? "" : type;
            Log.d(TAG, "http=" + result.status + " type=" + result.contentType
                    + " location=" + clip(result.location) + " url=" + clip(url));

            if (result.status < 300 || result.status >= 400) {
                result.body = readBody(conn);
            }
            Log.d(TAG, "body len=" + result.body.length() + " url=" + clip(url));
        } catch (Exception e) {
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.w(TAG, "http failed: " + result.error + " url=" + clip(url));
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    private static String cookieFor(String url) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            return cookie == null ? "" : cookie;
        } catch (Exception e) {
            return "";
        }
    }

    private static String readBody(HttpURLConnection conn) {
        try {
            InputStream raw = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            InputStream in = encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")
                    ? new GZIPInputStream(raw) : raw;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_BODY) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            try {
                InputStream err = conn.getErrorStream();
                if (err == null) return "";
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = err.read(buf)) > 0) out.write(buf, 0, n);
                err.close();
                return out.toString("UTF-8");
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    // ---------------------------------------------------------------- 列目录

    public static ListResult list(String folderId) {
        String url = listUrl(folderId);
        Http http = httpGet(url);
        if (http.error != null) {
            return ListResult.fail("读取分享目录失败：" + http.error + "\n地址：" + clip(url));
        }
        if (http.status < 200 || http.status >= 300) {
            return ListResult.fail("读取分享目录失败：HTTP " + http.status
                    + "，类型 " + http.contentType + "\n地址：" + clip(url));
        }
        if (http.body.isEmpty()) {
            return ListResult.fail("接口返回空响应，地址：" + clip(url));
        }
        try {
            JSONObject root = new JSONObject(http.body);
            JSONArray list = root.optJSONArray("list");
            int code = root.optInt("code", 200);
            if (list == null) {
                return ListResult.fail("接口返回 code=" + code + " "
                        + root.optString("msg", "") + "，且没有 list\n地址：" + clip(url));
            }
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject obj = list.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("fileName", "");
                if (name.isEmpty()) name = obj.optString("name", "");
                if (name.isEmpty()) name = obj.optString("folderName", "");
                if (name.isEmpty()) continue;
                String fileId = obj.optString("fileId", "");
                String childFolderId = obj.optString("folderId", "");
                boolean folder = "2".equals(obj.optString("fileType", ""))
                        || (!childFolderId.isEmpty() && fileId.isEmpty());
                entries.add(new Entry(name, folder, fileId, childFolderId));
            }
            return ListResult.ok(entries);
        } catch (Exception e) {
            // msg 容错：非 200 但 list 非空时上面已按 list 处理
            String body = http.body == null ? "" : http.body;
            return ListResult.fail("接口返回无法解析：" + body.substring(0, Math.min(120, body.length())));
        }
    }

    /**
     * 逐层进入「清弈（可选）→ 平台文件夹」，返回该目录下的全部条目。
     * 兼容三种结构：根/ios、根/清弈/ios、根直接放安装包。
     */
    public static ListResult readPlatformFolder() {
        ListResult root = list(null);
        if (!root.ok) return root;

        List<Entry> appEntries = root.entries;
        Entry app = findFolder(appEntries, APP_FOLDER, false);
        if (app != null) {
            ListResult inner = list(app.childId());
            if (!inner.ok) return inner;
            appEntries = inner.entries;
        } else if (hasExt(appEntries, "apk")) {
            // 旧结构：安装包直接放在根目录
            return ListResult.ok(appEntries);
        }

        Entry platform = findFolder(appEntries, PLATFORM_FOLDER, true);
        if (platform == null) {
            if (hasExt(appEntries, "apk")) return ListResult.ok(appEntries);
            return ListResult.fail("「" + APP_FOLDER + "」里没找到「" + PLATFORM_FOLDER
                    + "」文件夹（现有：" + names(appEntries) + "）");
        }
        return list(platform.childId());
    }

    private static Entry findFolder(List<Entry> entries, String want, boolean acceptAndroid) {
        String target = want.toLowerCase(Locale.US);
        for (Entry e : entries) {
            if (!e.folder) continue;
            String name = e.name.replaceAll("\\s+", "").toLowerCase(Locale.US);
            if (name.equals(target) || name.contains(target)) return e;
            if (acceptAndroid && (name.equals("android") || name.contains("android"))) return e;
        }
        return null;
    }

    private static boolean hasExt(List<Entry> entries, String ext) {
        for (Entry e : entries) {
            if (!e.folder && e.name.toLowerCase(Locale.US).endsWith("." + ext)) return true;
        }
        return false;
    }

    private static String names(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) sb.append("、");
            sb.append(e.name);
            if (sb.length() > 160) break;
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 取直链

    /**
     * 按 fileId 取安装包直链：接口 302 到 CDN，只读 Location，最多跟 3 跳。
     * 直链带签名会过期，每次现取，绝不缓存。
     */
    public static LinkResult redirect(String fileId) {
        if (fileId == null || fileId.isEmpty()) {
            return LinkResult.fail("安装包缺少 fileId，请重新检测更新。");
        }
        return follow(redirectUrl(fileId), true);
    }

    /**
     * 兜底：WebView 里截到接口地址后，在 App 内带着同样的头与 cookie 自己跟 302。
     * 不把接口地址交给外部浏览器（否则会下载到 redirect.json 并提示「参数有误」）。
     */
    public static LinkResult followCaptured(String capturedUrl) {
        if (capturedUrl == null || capturedUrl.isEmpty()) {
            return LinkResult.fail("没有截获到下载接口地址。");
        }
        if (!isApiHost(capturedUrl)) {
            return LinkResult.ok(capturedUrl);
        }
        return follow(capturedUrl, false);
    }

    private static LinkResult follow(String first, boolean apiChain) {
        String current = first;
        String lastDiagnosis = "";
        for (int hop = 0; hop < 3; hop++) {
            Http http = httpGet(current);
            if (http.error != null) {
                return LinkResult.fail("取下载直链失败：" + http.error + "\n地址：" + clip(current));
            }
            if (http.status >= 300 && http.status < 400) {
                String location = http.location;
                if (location == null || location.isEmpty()) {
                    return LinkResult.fail("接口返回 HTTP " + http.status + " 但没有 Location 头\n地址："
                            + clip(current));
                }
                Log.d(TAG, "location=" + clip(location) + " hop=" + hop);
                // 还在接口域就继续跟一跳，落到 CDN 才收手。
                if (!isApiHost(location)) return LinkResult.ok(location);
                if (location.equals(current)) {
                    return LinkResult.fail("接口反复跳回自己：" + clip(current));
                }
                current = location;
                continue;
            }
            // 200：只有确实是文件类型才算直链，JSON/HTML 一律判失败。
            if (isFileType(http.contentType, current)) {
                return LinkResult.ok(current);
            }
            lastDiagnosis = "接口返回的不是安装包：HTTP " + http.status
                    + "，类型 " + (http.contentType.isEmpty() ? "(空)" : http.contentType)
                    + "，响应 " + snippet(http.body)
                    + "\n接口地址：" + clip(current);
            return LinkResult.fail(lastDiagnosis);
        }
        return LinkResult.fail("接口跳转次数过多，仍未拿到 CDN 直链。"
                + (lastDiagnosis.isEmpty() ? "\n地址：" + clip(current) : "\n" + lastDiagnosis));
    }

    public static boolean isApiHost(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("://api.feijipan.com") || u.contains("://api.feijix.com");
    }

    private static boolean isFileType(String contentType, String url) {
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (t.contains("application/vnd.android.package-archive")
                || t.contains("application/octet-stream")
                || t.contains("application/zip")) {
            return true;
        }
        String u = url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        String path = q < 0 ? u : u.substring(0, q);
        return path.endsWith(".apk");
    }

    private static String snippet(String body) {
        if (body == null || body.isEmpty()) return "(空)";
        String one = body.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }

    /** 日志里只留主机与路径，去掉会过期的签名参数。 */
    public static String clip(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        String head = q < 0 ? url : url.substring(0, q) + "?...";
        return head.length() <= 160 ? head : head.substring(0, 160) + "…";
    }
}
