package com.sijia.go;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 安卓原生下载与安装: DownloadManager 下载 APK → 交系统安装器。
 * 系统不允许静默安装, 最后一定会弹系统安装页让用户确认。
 *
 * 落盘选择公共下载目录(Download/), 这样用户能在文件管理器里直接看到安装包;
 * 安装时优先用 DownloadManager 给出的 content:// (DownloadProvider), 省得自建 Provider 兜底。
 */
public class UpdateInstaller {
    private static final String TAG = "UpdateInstaller";
    private static final String MIME_APK = "application/vnd.android.package-archive";
    private static final String DIR = "update";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    /** 小于这个尺寸肯定不是安装包, 直接判失败, 免得把错误页交给安装器 */
    private static final long MIN_APK_BYTES = 1_000_000L;

    public interface Listener {
        void onMessage(String message);
    }

    private static BroadcastReceiver receiver;
    private static Listener pendingListener;
    private static String pendingName = "";
    private static long downloadId = -1L;

    /** 应用私有目录(仅作 ApkProvider 的回退路径) */
    public static File downloadDir(Context ctx) {
        File dir = ctx.getExternalFilesDir(DIR);
        if (dir == null) dir = ctx.getFilesDir();
        return dir;
    }

    /** 下载并安装; url 为分享页解析出的直链(带签名, 会过期) */
    public static void downloadAndInstall(Context ctx, String url, String fileName, Listener l) {
        Context app = ctx.getApplicationContext();
        String name = sanitize(fileName);
        if (name.isEmpty()) name = "qingyi-update.apk";
        Log.i(TAG, "downloadAndInstall file=" + name + " url=" + redact(url));

        if (!canInstall(app)) {
            l.onMessage("请允许「安装未知应用」，授权后再次点击下载更新。");
            openInstallPermissionSettings(app);
            return;
        }

        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Log.e(TAG, "DownloadManager 不可用");
            l.onMessage("系统下载服务不可用，请用浏览器打开分享页下载。");
            return;
        }
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(name);
            req.setDescription("清弈更新包");
            req.setMimeType(MIME_APK);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // 分享页的下载接口会校验来源, 补上浏览器 UA 与 Referer
            req.addRequestHeader("User-Agent", UA);
            req.addRequestHeader("Referer", "https://share.feijipan.com/");
            // 关键: 直链来自隐藏 WebView 里的会话, DownloadManager 不共享 WebView 的 Cookie,
            // 不带上就会拿到 {"msg":"...","code":-1} 这类 JSON, 装的时候报 packageinfo is null
            String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                req.addRequestHeader("Cookie", cookie);
                Log.i(TAG, "attach cookie len=" + cookie.length());
            } else {
                Log.w(TAG, "no webview cookie for " + redact(url));
            }
            boolean publicDir = true;
            try {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } catch (Exception e) {
                // 个别 ROM 不允许写公共目录, 退回应用私有目录
                publicDir = false;
                req.setDestinationInExternalFilesDir(app, DIR, name);
                Log.w(TAG, "公共下载目录不可用, 回退私有目录: " + e);
            }
            pendingName = name;
            pendingListener = l;
            downloadId = dm.enqueue(req);
            Log.i(TAG, "enqueue id=" + downloadId + " publicDir=" + publicDir + " path=" + describeTarget(app, name));
            register(app);
            l.onMessage("已开始下载 " + name + "，完成后会自动调起安装。");
        } catch (Exception e) {
            Log.e(TAG, "enqueue failed url=" + redact(url), e);
            l.onMessage("下载启动失败：" + e.getMessage());
        }
    }

    private static String describeTarget(Context app, String name) {
        return "Download/" + name + " 或 " + new File(downloadDir(app), name).getAbsolutePath();
    }

    private static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    private static void openInstallPermissionSettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "open settings failed", e);
        }
    }

    private static void register(Context app) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (downloadId != -1L && id != downloadId) return;
                handleComplete(context.getApplicationContext());
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    private static void unregister(Context app) {
        if (receiver == null) return;
        try {
            app.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiver = null;
    }

    private static void handleComplete(Context app) {
        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        Listener l = pendingListener;
        unregister(app);
        if (dm == null) return;

        DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
        int status = -1, reason = -1;
        long bytes = -1, total = -1;
        String localUri = "";
        String requestUrl = "";
        try (Cursor c = dm.query(q)) {
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                requestUrl = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
            }
        } catch (Exception e) {
            Log.e(TAG, "query failed", e);
        }
        Log.i(TAG, "download complete id=" + downloadId + " status=" + status + " reason=" + reason
                + " bytes=" + bytes + "/" + total + " localUri=" + localUri
                + " request=" + redact(requestUrl));

        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            if (l != null) {
                l.onMessage("下载失败（状态 " + status + "，原因码 " + reason + "），请稍后重试或改用浏览器下载。");
            }
            return;
        }

        // 安装来源优先级: DownloadManager 的 content:// → 公共下载目录(FileProvider) → 私有目录(ApkProvider)
        Uri uri = null;
        try {
            uri = dm.getUriForDownloadedFile(downloadId);
        } catch (Exception e) {
            Log.e(TAG, "getUriForDownloadedFile failed", e);
        }
        long size = uri == null ? -1 : sizeOf(app, uri);
        Log.i(TAG, "downloaded uri=" + uri + " size=" + size);

        if (uri != null) {
            String check = verifyApk(app, uri, size);
            Log.i(TAG, "verify " + uri + " => " + check);
            if (check == null) {
                install(app, uri, l);
                return;
            }
        }

        File pub = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), pendingName);
        long pubSize = pub.isFile() ? pub.length() : -1;
        Log.i(TAG, "public file=" + pub.getAbsolutePath() + " size=" + pubSize
                + " readable=" + pub.canRead());
        if (pubSize > 0 && pub.canRead()) {
            Uri fp = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", pub);
            String check = verifyApk(app, fp, pubSize);
            Log.i(TAG, "verify fileprovider => " + check);
            if (check == null) {
                install(app, fp, l);
                return;
            }
        }

        // 最后兜底: DownloadManager 回退写进私有目录时留下的同名文件
        File file = new File(downloadDir(app), pendingName);
        long fsize = file.isFile() ? file.length() : -1;
        Log.i(TAG, "fallback file=" + file.getAbsolutePath() + " size=" + fsize);
        if (fsize > 0) {
            String check = verifyApk(app, Uri.fromFile(file), fsize);
            Log.i(TAG, "verify private file => " + check);
            if (check == null) {
                install(app, ApkProvider.uriFor(app, file), l);
                return;
            }
        }
        if (l != null) {
            l.onMessage("下载到的文件不是可安装的安装包（大小 " + Math.max(size, Math.max(pubSize, fsize))
                    + " 字节），已记录日志。请稍后重试或改用浏览器下载。");
        }
    }

    private static long sizeOf(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{"_size"}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            Log.w(TAG, "size query failed: " + e);
        }
        return -1;
    }

    /**
     * 校验下载物是不是真安装包: 必须是 ZIP(PK\003\004) 且含 AndroidManifest.xml。
     * @return null 表示通过, 否则返回失败原因
     */
    private static String verifyApk(Context ctx, Uri uri, long size) {
        if (size >= 0 && size < MIN_APK_BYTES) {
            return "文件过小(" + size + " 字节), 多半是网页或接口返回的 JSON";
        }
        ContentResolver cr = ctx.getContentResolver();
        byte[] head = new byte[4];
        int read;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return "无法打开下载文件";
            read = in.read(head);
        } catch (Exception e) {
            return "读取失败: " + e;
        }
        Log.i(TAG, "head bytes=" + hex(head, read));
        if (read < 4 || head[0] != 'P' || head[1] != 'K') {
            return "文件头不是 ZIP(实际 " + hex(head, read) + ")";
        }
        boolean hasManifest = false;
        try (InputStream in = cr.openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            int guard = 0;
            while ((e = zip.getNextEntry()) != null && guard++ < 4000) {
                if ("AndroidManifest.xml".equals(e.getName())) {
                    hasManifest = true;
                    break;
                }
            }
        } catch (Exception e) {
            return "解压失败: " + e;
        }
        return hasManifest ? null : "ZIP 里没有 AndroidManifest.xml";
    }

    private static String hex(byte[] b, int len) {
        if (len <= 0) return "(空)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(String.format("%02X ", b[i]));
        return sb.toString().trim();
    }

    private static void install(Context app, Uri uri, Listener l) {
        if (!canInstall(app)) {
            openInstallPermissionSettings(app);
            if (l != null) l.onMessage("请允许「安装未知应用」，授权后再次点击下载更新。");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, MIME_APK);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            app.startActivity(i);
            Log.i(TAG, "install intent started uri=" + uri);
            if (l != null) l.onMessage("已调起系统安装器，请按提示完成安装。");
        } catch (Exception e) {
            Log.e(TAG, "install failed uri=" + uri, e);
            if (l != null) l.onMessage("无法调起安装器：" + e.getMessage());
        }
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        return name.replace('/', '_').replace('\\', '_').trim();
    }

    /** 日志里只留主机与路径, 去掉会过期的签名参数 */
    private static String redact(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?...";
    }
}
