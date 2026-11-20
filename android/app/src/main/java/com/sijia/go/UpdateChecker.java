package com.sijia.go;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
import android.webkit.DownloadListener;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 更新检测: 用一个不可见的 WebView 打开小飞机网盘分享页, 让网页自己跑起来再读文本。
 *
 * 为什么不用接口: /ws/share/list 对非浏览器客户端只返回空列表(WAF/会话校验),
 * 同样的分享在浏览器里能读到的文件, 命令行请求 total=0。详见公共技能库 feijipan-update。
 *
 * 分享目录: 根 → 「安卓」 → 清弈-vX.Y.Z.apk(取版本号最大的那个)
 */
public class UpdateChecker {

    public static final String SHARE_URL = "https://share.feijipan.com/s/PRzlQ1Hk";
    private static final String PLATFORM_FOLDER = "安卓";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    /** 清弈-v1.2.7.apk / 清弈-1.2.7.apk / QingYi-1.2.7.apk */
    private static final Pattern APK_NAME = Pattern.compile(
            "(?:清弈|qingyi)[-_－— ]*[vV]?(\\d+(?:\\.\\d+){1,3})(?:\\(\\d+\\))?\\.apk",
            Pattern.CASE_INSENSITIVE);
    /** 兜底: 目录里只放了 1.2.7.apk 这种纯版本号 */
    private static final Pattern APK_BARE = Pattern.compile("(\\d+(?:\\.\\d+){1,3})\\.apk");

    private static final long POLL_MS = 1200L;
    private static final int MAX_POLLS = 18;

    public interface Listener {
        void onResult(UpdateChecker checker, Result r);
    }

    public interface UrlListener {
        void onUrl(String url, String error);
    }

    public static class Result {
        public boolean ok;
        public String current = "";
        public String latest = "";
        public String fileName = "";
        public String error = "";
    }

    private final Activity activity;
    private final String currentVersion;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WebView webView;
    private boolean openedFolder;
    /** 检测已出结论(轮询停止), 但页面仍保留, 供点「下载更新」时取直链 */
    private boolean finished;
    /** 页面已释放, 之后再也没法取直链 */
    private boolean destroyed;
    private int polls;
    private int folderClicks;
    private String prevText = "";
    private String lastFileName = "";

    private volatile String capturedUrl;

    private UpdateChecker(Activity activity, String currentVersion, Listener listener) {
        this.activity = activity;
        this.currentVersion = currentVersion == null ? "" : currentVersion;
        this.listener = listener;
    }

    /**
     * 发起检测。已有检测在跑 / 界面已销毁时返回 null, 调用方按「没有更新」处理即可。
     */
    public static UpdateChecker start(Activity activity, String currentVersion, Listener listener) {
        if (activity == null || activity.isFinishing()) return null;
        if (activity.isDestroyed()) return null;
        UpdateChecker c = new UpdateChecker(activity, currentVersion, listener);
        c.open();
        return c;
    }

    /** 自动检查更新的开关(默认开启), 与 UI 偏好存同一份 SharedPreferences */
    public static boolean autoEnabled(Context ctx) {
        return ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean("update_auto", true);
    }

    public static void setAutoEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .edit().putBoolean("update_auto", on).apply();
    }

    /** 版本号逐段比较: a 比 b 新返回 true */
    public static boolean versionGreater(String a, String b) {
        String[] pa = a == null ? new String[0] : a.split("\\.");
        String[] pb = b == null ? new String[0] : b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseInt(pa[i]) : 0;
            int vb = i < pb.length ? parseInt(pb[i]) : 0;
            if (va != vb) return va > vb;
        }
        return false;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void open() {
        webView = new WebView(activity);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 移动版布局不把文件名写进文本节点, 必须用桌面 UA
        s.setUserAgentString(UA);
        webView.setBackgroundColor(0x00000000);
        webView.setVisibility(View.INVISIBLE);   // 不可见但仍参与布局, 否则响应式布局会把文件行藏掉

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url != null && !url.isEmpty()) capturedUrl = url;
            }
        });
        // 页面点下载走的是 window.open(l,"_self"), 在 WebView 里会变成一次导航;
        // 拦下 api.feijipan.com/ws/downLoad/url 这类地址就拿到了带签名的直链。
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isDownloadUrl(url)) {
                    capturedUrl = url;
                    return true;        // 别让 WebView 自己下载, 交给 DownloadManager
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                ui.postDelayed(UpdateChecker.this::tick, 600);
            }
        });

        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            finish(null, "无法挂载更新检测页面。");
            return;
        }
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadUrl(SHARE_URL);
        // 兜底: 断网或页面结构变化时也要有结论, 不能一直挂着
        ui.postDelayed(() -> {
            if (!finished) finish(null, "检查更新超时，请稍后重试。");
        }, 30_000);
    }

    private void tick() {
        if (finished || webView == null) return;
        if (++polls > MAX_POLLS) {
            finish(null, "检查更新超时，请稍后重试。");
            return;
        }
        webView.evaluateJavascript("(document.body?document.body.innerText:'')", value -> {
            if (finished) return;
            String text = unquote(value);
            if (containsApk(text)) {
                finishFromText(text);
                return;
            }
            if (text.contains("没有分享文件")) {
                // 根目录为空或「安卓」目录为空
                finish(new Result(), null);
                return;
            }
            if (!openedFolder) {
                if (text.contains(PLATFORM_FOLDER)) {
                    openedFolder = true;
                    folderClicks = 1;
                    webView.evaluateJavascript(clickFolderJs(0), null);
                }
            } else if (folderClicks < 4 && text.equals(prevText)) {
                // 上一轮点击没让页面变化, 换一种命中方式再点
                webView.evaluateJavascript(clickFolderJs(folderClicks), null);
                folderClicks++;
            }
            prevText = text;
            ui.postDelayed(this::tick, POLL_MS);
        });
    }

    private void finishFromText(String text) {
        String[] picked = pickBest(text, APK_NAME);
        if (picked == null) picked = pickBest(text, APK_BARE);
        lastFileName = picked == null ? "" : picked[1];
        Result r = new Result();
        if (picked == null || !versionGreater(picked[0], currentVersion)) {
            finish(r, null);
            return;
        }
        r.ok = true;
        r.latest = picked[0];
        r.fileName = picked[1];
        finish(r, null);
    }

    /** @return {版本号, 完整文件名}，取版本号最大的那个 */
    private static String[] pickBest(String text, Pattern p) {
        Matcher m = p.matcher(text);
        String bestVer = "";
        String bestName = "";
        while (m.find()) {
            String ver = m.group(1);
            if (bestVer.isEmpty() || versionGreater(ver, bestVer)) {
                bestVer = ver;
                bestName = m.group();
            }
        }
        return bestVer.isEmpty() ? null : new String[]{bestVer, bestName};
    }

    private static boolean containsApk(String text) {
        return text != null && text.toLowerCase().contains(".apk");
    }

    private void finish(Result r, String error) {
        if (finished) return;
        finished = true;
        ui.removeCallbacksAndMessages(null);
        if (r == null) r = new Result();
        r.current = currentVersion;
        if (error != null) {
            r.ok = false;
            r.error = error;
        } else {
            r.ok = true;
        }
        if (listener != null) listener.onResult(this, r);
    }

    /**
     * 解析安装包真实下载直链(带签名, 会过期, 所以每次现取)。
     * 流程: 点文件行 → 等 1.5s → 点 .down-btn, 由 DownloadListener 拿到跳转后的地址。
     */
    public void resolveUrl(UrlListener l) {
        if (destroyed || webView == null) {
            l.onUrl(null, "更新页面已关闭，请重新检查。");
            return;
        }
        if (capturedUrl != null) {
            l.onUrl(capturedUrl, null);
            return;
        }
        String name = lastFileName;
        if (name.isEmpty()) {
            l.onUrl(null, "安装包信息缺失，请重新检查更新。");
            return;
        }
        // 页面点下载走的是 window.open(url, "_self"): 先挂钩抽走 URL, 再去点,
        // 这样既不依赖类名, 也不会让隐藏 WebView 真的去下载。
        webView.evaluateJavascript(HOOK_JS, v -> webView.evaluateJavascript(clickFileJs(name),
                v2 -> ui.postDelayed(() -> tryClickDownload(l, 0), 1500)));
    }

    /** 文件行点开后页面会渲染下载按钮; 不同版本类名不一致, 多试几轮 */
    private void tryClickDownload(UrlListener l, int attempt) {
        if (capturedUrl != null) {
            l.onUrl(capturedUrl, null);
            return;
        }
        if (destroyed || webView == null) {
            l.onUrl(null, "更新页面已关闭，请重新检查。");
            return;
        }
        if (attempt >= 8) {
            waitUrl(l, 0);
            return;
        }
        webView.evaluateJavascript(clickDownloadBtnJs(), v -> ui.postDelayed(
                () -> tryClickDownload(l, attempt + 1), 1200));
    }

    private void waitUrl(UrlListener l, int attempt) {
        if (capturedUrl != null) {
            l.onUrl(capturedUrl, null);
            return;
        }
        if (destroyed || webView == null) {
            l.onUrl(null, "更新页面已关闭，请重新检查。");
            return;
        }
        if (attempt >= 18) {
            l.onUrl(null, "没有取到下载地址，可改用浏览器打开分享页。");
            return;
        }
        webView.evaluateJavascript("(window.__qyUrl||'')", v -> {
            String u = unquote(v);
            if (u.startsWith("http")) {
                capturedUrl = u;
                Log.i("UpdateChecker", "resolved url=" + redact(u));
                l.onUrl(u, null);
            } else if (attempt % 2 == 1) {
                // 按钮可能还没渲染出来, 半路再点一次
                webView.evaluateJavascript(clickDownloadBtnJs(), null);
                ui.postDelayed(() -> waitUrl(l, attempt + 1), 600);
            } else {
                ui.postDelayed(() -> waitUrl(l, attempt + 1), 600);
            }
        });
    }

    /** 把页面的 window.open 变成可读的全局变量, 只装一次 */
    private static final String HOOK_JS = "(function(){"
            + "window.__qyUrl=window.__qyUrl||'';"
            + "if(window.__qyHooked)return 'ok';"
            + "window.__qyHooked=1;"
            + "var ow=window.open;"
            + "window.open=function(u){try{if(u)window.__qyUrl=String(u);}catch(e){}"
            + "try{return ow.apply(window,arguments);}catch(e){return null;}};"
            + "return 'ok';})()";

    /** 必须在 Activity 销毁或检测结束后调用, 否则 WebView 泄漏 */
    public void destroy() {
        destroyed = true;
        finished = true;
        ui.removeCallbacksAndMessages(null);
        if (webView == null) return;
        try {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
        } catch (Exception ignored) {
        }
        webView = null;
    }

    private static String clickFolderJs(int step) {
        String target = "var n=el.parentElement||el;";
        if (step == 1) target = "var n=el;";
        else if (step == 2) target = "var n=el.closest('[class*=item],[class*=file],[class*=folder],[class*=row]')||el.parentElement||el;";
        else if (step >= 3) target = "var n=el;for(var k=0;k<3&&n.parentElement;k++){n=n.parentElement;}";
        return "(function(){"
                + "var list=document.querySelectorAll('span,div,p,a');"
                + "for(var i=0;i<list.length;i++){"
                + "var el=list[i];"
                + "var t=(el.textContent||'').replace(/^\\s+|\\s+$/g,'');"
                + "if(t!=='" + PLATFORM_FOLDER + "')continue;"
                + "if(el.children.length>0)continue;"
                + target
                + "try{n.click();}catch(e){return 'err';}"
                + "return 'ok';"
                + "}"
                + "return 'nf';})()";
    }

    private static String clickFileJs(String fileName) {
        String want = fileName.replace("\\", "\\\\").replace("'", "\\'");
        return "(function(){"
                + "var want='" + want + "';"
                // 分享页的文件名节点固定带 .list-name(与 iOS 端一致), 优先用它
                + "var names=document.querySelectorAll('.list-name');"
                + "for(var j=0;j<names.length;j++){"
                + "var n0=names[j];"
                + "if((n0.textContent||'').replace(/^\\s+|\\s+$/g,'')!==want)continue;"
                + "var row=n0.closest('[class*=item],[class*=file],[class*=row]')||n0.parentElement||n0;"
                + "try{row.click();}catch(e){try{n0.click();}catch(e2){return 'err';}}"
                + "return 'ok';}"
                + "var list=document.querySelectorAll('span,div');"
                + "for(var i=0;i<list.length;i++){"
                + "var el=list[i];"
                + "if(el.children.length>0)continue;"
                + "var t=(el.textContent||'').replace(/^\\s+|\\s+$/g,'');"
                + "if(t!==want)continue;"
                + "var p=el.parentElement||el;"
                + "try{p.click();}catch(e){return 'err';}"
                + "return 'ok';"
                + "}"
                + "return 'nf';})()";
    }

    private static String clickDownloadBtnJs() {
        return "(function(){"
                + "function hit(el){try{el.click();return true;}catch(e){return false;}}"
                + "var sels=['.down-btn','[class*=down-btn]','[class*=download]','[class*=down]'];"
                + "for(var i=0;i<sels.length;i++){"
                + "var b=document.querySelector(sels[i]);"
                + "if(b&&hit(b))return 'ok';}"
                // 没有稳定类名时按文案找: 下载 / 立即下载 / 下载文件
                + "var list=document.querySelectorAll('button,span,div,a');"
                + "for(var k=0;k<list.length;k++){"
                + "var el=list[k];"
                + "var t=(el.textContent||'').replace(/\\s+/g,'');"
                + "if(t!=='下载'&&t!=='立即下载'&&t!=='下载文件')continue;"
                + "if(el.children.length>1)continue;"
                + "if(hit(el))return 'ok';}"
                + "return 'nf';})()";
    }

    /** 页面里真正的下载跳转地址(带签名, 也可能直接是 CDN 上的 apk) */
    private static boolean isDownloadUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("/ws/download/url") || u.contains("/ws/file/redirect") || u.contains(".apk");
    }

    /** evaluateJavascript 返回的是 JSON 字符串字面量, 需要自己去引号并解转义 */
    private static String unquote(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Exception e) {
                            sb.append("\\u");
                        }
                    }
                    break;
                default:
                    sb.append(n);
            }
        }
        return sb.toString();
    }

    /** 日志里只留主机与路径, 去掉会过期的签名参数 */
    private static String redact(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?...";
    }
}
