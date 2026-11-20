package com.sijia.go;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** 更新提示: 小对话框 + 「下载更新」; 启动静默检测与设置页手动检测共用同一套文案 */
public final class UpdatePrompt {

    public interface Status {
        void set(String text);
    }

    private UpdatePrompt() {
    }

    /**
     * @param showNoUpdate 手动检测时为 true(弹「已是最新」), 启动静默检测时为 false(没有新版本就不打扰)
     */
    public static void showResult(Activity act, UpdateChecker checker, UpdateChecker.Result r,
                                  Status status, boolean showNoUpdate) {
        if (act == null || act.isFinishing()) return;
        UiTheme t = UiTheme.current(act);

        if (!r.ok) {
            if (status != null) status.set(r.error);
            if (checker != null) checker.destroy();     // 结果出来就释放隐藏 WebView
            if (!showNoUpdate) return;
            AlertDialog d = UiTheme.themedBuilder(act, t, "检查更新失败")
                    .setMessage(r.error + "\n\n可改用浏览器打开分享页手动下载。")
                    .setPositiveButton("用浏览器打开", (x, y) -> openSharePage(act))
                    .setNegativeButton("知道了", null)
                    .create();
            d.show();
            UiTheme.styleDialog(d, t, false);
            return;
        }

        if (r.latest == null || r.latest.isEmpty()) {
            if (status != null) {
                status.set(showNoUpdate ? "已是最新版本 v" + r.current : "");
            }
            if (checker != null) checker.destroy();
            if (!showNoUpdate) return;
            AlertDialog d = UiTheme.themedBuilder(act, t, "已是最新版本")
                    .setMessage("当前 v" + r.current + "，分享目录里还没有更新的安装包。")
                    .setPositiveButton("知道了", null)
                    .create();
            d.show();
            UiTheme.styleDialog(d, t, false);
            return;
        }

        if (status != null) status.set("发现新版本 v" + r.latest);
        AlertDialog d = UiTheme.themedBuilder(act, t, "发现新版本 v" + r.latest)
                .setMessage("当前 v" + r.current + "\n更新包 " + r.fileName
                        + "\n\n下载完成后会调起系统安装器，按提示覆盖安装即可。")
                .setPositiveButton("下载更新", (x, y) -> download(act, checker, r, status))
                .setNegativeButton("稍后", (x, y) -> {
                    if (checker != null) checker.destroy();
                })
                .create();
        d.show();
        UiTheme.styleDialog(d, t, false);
    }

    private static void download(Activity act, UpdateChecker checker, UpdateChecker.Result r, Status status) {
        if (checker == null) {
            toast(act, "更新页面已关闭，请重新检查更新。");
            return;
        }
        if (status != null) status.set("正在获取下载地址…");
        checker.resolveUrl((url, error) -> {
            checker.destroy();      // 直链已取出, 隐藏 WebView 可以释放了
            if (url == null) {
                if (status != null) status.set(error);
                else toast(act, error);
                return;
            }
            UpdateInstaller.downloadAndInstall(act, url, r.fileName, msg -> {
                if (status != null) status.set(msg);
                else toast(act, msg);
            });
        });
    }

    public static void openSharePage(Activity act) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.SHARE_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            toast(act, "没有可用浏览器，分享链接：" + UpdateChecker.SHARE_URL);
        }
    }

    private static void toast(Activity act, String msg) {
        if (act == null || act.isFinishing()) return;
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }
}
