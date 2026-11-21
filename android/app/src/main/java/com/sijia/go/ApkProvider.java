package com.sijia.go;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** 把暂存目录里的安装包以 content:// 交给系统安装器(Android 7+ 不允许直接传 file://) */
public class ApkProvider extends ContentProvider {
    private static final String SUFFIX = ".apkfiles";

    public static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + SUFFIX)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("no context");
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) throw new FileNotFoundException("no name");
        // 先找安装前的暂存件（从公共下载目录复制而来），再退回应用专属下载目录
        File[] dirs = {UpdateInstaller.stagingDir(ctx), UpdateInstaller.downloadDir(ctx)};
        for (File dir : dirs) {
            File target = new File(dir, name);
            try {
                if (!target.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                    continue;
                }
            } catch (Exception e) {
                continue;
            }
            if (target.isFile()) {
                return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        }
        throw new FileNotFoundException("apk not in staging/download dir");
    }
}
