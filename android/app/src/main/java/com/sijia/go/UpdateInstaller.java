package com.sijia.go;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 安卓下载与安装，按 feijipan-update 技能的统一模板实现。
 *
 * <p>下载交给系统 DownloadManager：Android 10+ 落到公共 Download 目录（文件管理器可见），
 * 9 及以下退回应用专属外部目录，文件名保持原名。下载完成后从 DownloadManager 的
 * content:// 复制一份到 cache/updates，用 {@code getPackageArchiveInfo} 校验包名与体积，
 * 通过才用 ApkProvider 的 content:// 调系统安装器；校验不过只提示，不强行安装。
 */
public final class UpdateInstaller {
    private static final String TAG = Feijipan.TAG;
    private static final String MIME_APK = "application/vnd.android.package-archive";
    private static final String DIR = "update";
    /** 小于这个尺寸肯定不是安装包（接口 JSON / 网页），直接判失败 */
    private static final long MIN_APK_BYTES = 1_000_000L;

    public interface Listener {
        void onMessage(String message);
    }

    private static BroadcastReceiver receiver;
    private static Listener pendingListener;
    private static String pendingName = "";
    private static long downloadId = -1L;

    private UpdateInstaller() {
    }

    /** 应用专属下载目录（Android 9 及以下的落地点，也是 ApkProvider 的回退路径） */
    public static File downloadDir(Context ctx) {
        File dir = ctx.getExternalFilesDir(DIR);
        if (dir == null) dir = ctx.getFilesDir();
        return dir;
    }

    /** 安装前的暂存目录：从下载件复制一份，安装器只读这里 */
    public static File stagingDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** 下载并安装；url 必须是 CDN 直链（带签名，会过期，每次现取） */
    public static void downloadAndInstall(Context ctx, String url, String fileName, Listener l) {
        Context app = ctx.getApplicationContext();
        String name = sanitize(fileName);
        if (name.isEmpty()) name = "qingyi-update.apk";
        Log.d(TAG, "downloadAndInstall file=" + name + " url=" + Feijipan.clip(url));

        if (!canInstall(app)) {
            l.onMessage("请允许「安装未知应用」，授权后再次点击「下载更新」继续安装。");
            openInstallPermissionSettings(app);
            return;
        }

        // 上次已下好同版本：直接校验安装，不重复下载
        File staged = new File(stagingDir(app), name);
        if (staged.isFile() && staged.length() > 0 && verifyApk(app, staged) == null) {
            install(app, ApkProvider.uriFor(app, staged), l);
            return;
        }

        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            l.onMessage("系统下载服务不可用，请用浏览器打开分享页下载。");
            return;
        }
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(name);
            req.setDescription("清弈更新包");
            req.setMimeType(MIME_APK);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.addRequestHeader("User-Agent", Feijipan.DESKTOP_UA);
            req.addRequestHeader("Referer", Feijipan.SHARE_REFERER);
            String cookie = cookieFor(url);
            if (!cookie.isEmpty()) req.addRequestHeader("Cookie", cookie);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } else {
                req.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name);
            }

            pendingName = name;
            pendingListener = l;
            downloadId = dm.enqueue(req);
            Log.d(TAG, "enqueue id=" + downloadId + " name=" + name);
            register(app);
            l.onMessage("已开始下载 " + name + "，完成后会自动调起安装。");
        } catch (Exception e) {
            Log.w(TAG, "enqueue failed url=" + Feijipan.clip(url) + " " + e);
            l.onMessage("下载启动失败：" + e.getMessage() + "\n地址：" + Feijipan.clip(url));
        }
    }

    private static String cookieFor(String url) {
        try {
            String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
            return cookie == null ? "" : cookie;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean canInstall(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ctx.getPackageManager().canRequestPackageInstalls();
    }

    private static void openInstallPermissionSettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "open install settings failed: " + e);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

        int status = -1;
        int reason = -1;
        long bytes = -1;
        long total = -1;
        String requestUrl = "";
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                requestUrl = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
            }
        } catch (Exception e) {
            Log.w(TAG, "query failed: " + e);
        }
        Log.d(TAG, "download complete id=" + downloadId + " status=" + status + " reason=" + reason
                + " bytes=" + bytes + "/" + total + " url=" + Feijipan.clip(requestUrl));

        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            if (l != null) {
                l.onMessage("下载失败（状态 " + status + "，原因码 " + reason + "）。\n地址："
                        + Feijipan.clip(requestUrl));
            }
            return;
        }

        File staged;
        try {
            staged = stage(app, dm.openDownloadedFile(downloadId));
        } catch (Exception e) {
            Log.w(TAG, "open downloaded file failed: " + e);
            if (l != null) {
                l.onMessage("下载完成但读取失败：" + e.getMessage() + "\n地址：" + Feijipan.clip(requestUrl));
            }
            return;
        }

        long size = staged.length();
        String problem = verifyApk(app, staged);
        if (problem != null) {
            Log.w(TAG, "verify failed: " + problem + " size=" + size);
            if (l != null) {
                l.onMessage("下载到的不是可安装的安装包（" + problem + "，大小 " + size
                        + " 字节）。\n地址：" + Feijipan.clip(requestUrl));
            }
            return;
        }
        Log.d(TAG, "verify ok size=" + size + " file=" + staged.getName());
        install(app, ApkProvider.uriFor(app, staged), l);
    }

    /** 从 DownloadManager 的 content:// 复制到 cache/updates, 公共下载目录里的原文件保留不动 */
    private static File stage(Context app, ParcelFileDescriptor pfd) throws Exception {
        File target = new File(stagingDir(app), pendingName);
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor());
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
        return target;
    }

    /**
     * 校验安装包：体积足够 + getPackageArchiveInfo 能解析 + 包名与本应用一致。
     * @return null 表示通过，否则返回失败原因
     */
    private static String verifyApk(Context ctx, File file) {
        long size = file.isFile() ? file.length() : -1;
        if (size < MIN_APK_BYTES) {
            return "文件过小 " + size + " 字节，多半是接口返回的 JSON 或网页";
        }
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            info = ctx.getPackageManager().getPackageArchiveInfo(
                    file.getAbsolutePath(), android.content.pm.PackageManager.PackageInfoFlags.of(0));
        } else {
            info = ctx.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        }
        if (info == null) {
            return "getPackageArchiveInfo 解析失败（packageInfo is null）";
        }
        if (info.packageName == null || !info.packageName.equals(ctx.getPackageName())) {
            return "包名不一致（下载包 " + info.packageName + "，本机 " + ctx.getPackageName() + "）";
        }
        return null;
    }

    private static void install(Context app, Uri uri, Listener l) {
        if (!canInstall(app)) {
            openInstallPermissionSettings(app);
            if (l != null) l.onMessage("请允许「安装未知应用」，授权后再次点击「下载更新」继续安装。");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, MIME_APK);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            app.startActivity(i);
            Log.d(TAG, "install intent uri=" + uri);
            if (l != null) l.onMessage("已调起系统安装器，请按提示完成安装。");
        } catch (Exception e) {
            Log.w(TAG, "install failed uri=" + uri + " " + e);
            if (l != null) l.onMessage("无法调起安装器：" + e.getMessage());
        }
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        return name.replace('/', '_').replace('\\', '_').trim();
    }
}
