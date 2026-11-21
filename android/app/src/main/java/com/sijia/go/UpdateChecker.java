package com.sijia.go;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 更新检测与取链，按 feijipan-update 技能的统一模板实现。
 *
 * <p>主路径：直连 {@code GET /ws/share/list} 逐层读「清弈 → 安卓」，拿 fileId 后
 * {@code GET /ws/file/redirect} 只看 302 的 Location。直连失败时回落到隐藏 WebView
 * 渲染分享页（桌面 UA + DomStorage，先 about:blank 卸载旧文档），仍不出结果就交给浏览器。
 *
 * <p>WebView 兜底截到的是接口地址，绝不再交给外部浏览器：在 App 内带着同样的头与
 * cookie 自己跟最多 3 跳，落到 CDN 才收手，避免下载到 redirect.json（解析包失败）。
 */
public class UpdateChecker {

    public static final String SHARE_URL = Feijipan.SHARE_URL;
    private static final String TAG = Feijipan.TAG;

    /** 清弈-v1.3.0.apk / QingYi-1.3.0.apk / 1.3.0.apk */
    private static final Pattern APK_NAME = Pattern.compile(
            "(?:清弈|qingyi)[-_－— ]*[vV]?(\\d+(?:\\.\\d+){1,3})(?:\\(\\d+\\))?\\.apk",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern APK_BARE = Pattern.compile("(\\d+(?:\\.\\d+){1,3})\\.apk");

    private static final long POLL_MS = 350L;
    /** 进入平台目录后，文本稳定这么久仍没有安装包就按空目录返回（不要干等超时） */
    private static final long EMPTY_STABLE_MS = 1800L;
    private static final long FALLBACK_TIMEOUT_MS = 30_000L;

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
        /** 直连接口列目录拿到的文件 id；WebView 兜底时为空 */
        public String fileId = "";
        public String error = "";
    }

    private final Activity activity;
    private final String currentVersion;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private WebView webView;
    private volatile String capturedUrl;
    private boolean destroyed;
    private boolean delivered;

    private String lastFileName = "";
    private String lastFileId = "";

    // WebView 兜底的状态
    private boolean fallbackActive;
    private boolean sharingLoaded;
    private long fallbackStart;
    private List<String> rootNames;
    private int rootStable;
    private boolean enteredFolder;
    private long folderEnteredAt;
    private String folderSig = "";
    private long folderStableSince;
    private boolean folderRetried;
    private String rootSig = "";
    private long rootStableSince;
    private int fallbackPolls;

    private UpdateChecker(Activity activity, String currentVersion, Listener listener) {
        this.activity = activity;
        this.currentVersion = currentVersion == null ? "" : currentVersion;
        this.listener = listener;
    }

    /** 发起一次检测；界面已销毁时返回 null，调用方按「没有更新」处理。 */
    public static UpdateChecker start(Activity activity, String currentVersion, Listener listener) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        UpdateChecker c = new UpdateChecker(activity, currentVersion, listener);
        c.begin();
        return c;
    }

    /** 自动检查更新的开关（默认开启），与 UI 偏好存同一份 SharedPreferences */
    public static boolean autoEnabled(Context ctx) {
        return ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean("update_auto", true);
    }

    public static void setAutoEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .edit().putBoolean("update_auto", on).apply();
    }

    /** 版本号逐段比较：a 比 b 新返回 true */
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

    // ---------------------------------------------------------------- 直连主路径

    private void begin() {
        worker.execute(() -> {
            Feijipan.ListResult result = Feijipan.readPlatformFolder();
            ui.post(() -> {
                if (destroyed || delivered) return;
                if (result.ok && !result.entries.isEmpty()) {
                    deliverFromEntries(result.entries);
                } else if (result.ok) {
                    // 目录为空多半是接口被当成非浏览器客户端（只回了空 list），交给 WebView 复核
                    Log.w(TAG, "direct list empty, fallback to webview");
                    startWebFallback();
                } else {
                    Log.w(TAG, "direct list failed, fallback to webview: " + result.message);
                    startWebFallback();
                }
            });
        });
    }

    private void deliverFromEntries(List<Feijipan.Entry> entries) {
        String bestVer = "";
        String bestName = "";
        String bestId = "";
        for (Feijipan.Entry e : entries) {
            if (e.folder) continue;
            String ver = matchVersion(e.name);
            if (ver.isEmpty()) continue;
            if (bestVer.isEmpty() || versionGreater(ver, bestVer)) {
                bestVer = ver;
                bestName = e.name;
                bestId = e.fileId;
            }
        }
        Result r = new Result();
        lastFileName = bestName;
        lastFileId = bestId;
        if (!bestVer.isEmpty() && versionGreater(bestVer, currentVersion)) {
            r.latest = bestVer;
            r.fileName = bestName;
        }
        Log.d(TAG, "direct found latest=" + r.latest + " file=" + r.fileName + " fileId=" + bestId);
        deliver(r);
    }

    // ---------------------------------------------------------------- WebView 兜底

    private void startWebFallback() {
        if (activity.isFinishing() || activity.isDestroyed()) {
            fail("当前界面已关闭，请重新检查更新。");
            return;
        }
        fallbackActive = true;
        fallbackStart = System.currentTimeMillis();
        webView = new WebView(activity);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 移动版布局不把文件名写进文本节点, 必须用桌面 UA
        s.setUserAgentString(Feijipan.DESKTOP_UA);
        webView.setBackgroundColor(0x00000000);
        webView.setVisibility(View.INVISIBLE);   // 不可见但仍参与布局

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url != null && !url.isEmpty()) {
                    capturedUrl = url;
                    Log.d(TAG, "captured api url(download listener): " + Feijipan.clip(url));
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isCaptureUrl(url)) {
                    capturedUrl = url;
                    Log.d(TAG, "captured api url(nav): " + Feijipan.clip(url));
                    return true;        // 不让 WebView 自己下载，交给 App 内取链
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("share.feijipan.com/s/")) {
                    sharingLoaded = true;
                    ui.postDelayed(UpdateChecker.this::tick, 600);
                }
            }
        });

        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            fail("无法挂载更新检测页面。");
            return;
        }
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 先卸载旧文档，避免读文本/点击落在上一次的 DOM 上
        webView.loadUrl("about:blank");
        ui.postDelayed(() -> {
            if (webView != null && !destroyed) {
                webView.loadUrl(SHARE_URL);
                ui.postDelayed(UpdateChecker.this::tick, 1200);
            }
        }, 250);
    }

    private void tick() {
        if (destroyed || delivered || webView == null) return;
        if (!sharingLoaded && System.currentTimeMillis() - fallbackStart > 8000) {
            fail("分享页加载超时，请稍后重试。");
            return;
        }
        if (System.currentTimeMillis() - fallbackStart > FALLBACK_TIMEOUT_MS) {
            fail("检查更新超时，请稍后重试。");
            return;
        }
        fallbackPolls++;
        if (fallbackPolls > 90) {
            fail("检查更新超时，请稍后重试。");
            return;
        }
        webView.evaluateJavascript(STATE_JS, value -> {
            if (destroyed || delivered) return;
            JSONObject state = parseState(value);
            if (state == null) {
                ui.postDelayed(this::tick, POLL_MS);
                return;
            }
            handleState(state);
        });
    }

    private void handleState(JSONObject state) {
        List<String> names = readNames(state);
        boolean empty = state.optBoolean("empty");
        boolean loading = state.optBoolean("loading");
        boolean header = state.optBoolean("header");
        String sig = state.optString("sig", "");

        if (!enteredFolder) {
            if (names.isEmpty()) {
                if (empty && !loading) {
                    deliver(new Result());
                    return;
                }
                if (sig.equals(rootSig)) {
                    if (System.currentTimeMillis() - rootStableSince >= EMPTY_STABLE_MS) {
                        deliver(new Result());
                        return;
                    }
                } else {
                    rootSig = sig;
                    rootStableSince = System.currentTimeMillis();
                }
                ui.postDelayed(this::tick, POLL_MS);
                return;
            }

            if (hasApk(names)) {
                finishFromNames(names);
                return;
            }
            if (rootNames == null) {
                rootNames = names;
                rootStable = 0;
            } else if (names.equals(rootNames)) {
                rootStable++;
            } else {
                rootNames = names;
                rootStable = 0;
            }
            // 等「表头 + 文件夹行」都渲染：有表头时两次一致即可，没有表头再多等一轮
            boolean ready = header ? rootStable >= 1 : rootStable >= 2;
            if (ready) {
                enteredFolder = true;
                folderEnteredAt = System.currentTimeMillis();
                folderSig = sig;
                folderStableSince = folderEnteredAt;
                webView.evaluateJavascript(clickFolderJs(0), null);
            }
            ui.postDelayed(this::tick, POLL_MS);
            return;
        }

        // 已进入平台目录：出现安装包立刻返回；没有则文本稳定约 1.8s 按空目录返回
        if (hasApk(names)) {
            finishFromNames(names);
            return;
        }
        if (empty && !loading) {
            deliver(new Result());
            return;
        }
        long now = System.currentTimeMillis();
        if (!sig.equals(folderSig)) {
            folderSig = sig;
            folderStableSince = now;
        }
        if (names.isEmpty() && !loading && now - folderStableSince >= EMPTY_STABLE_MS) {
            deliver(new Result());
            return;
        }
        if (!folderRetried && now - folderEnteredAt > 5000) {
            // 点击可能没命中，换一种命中方式再点一次
            folderRetried = true;
            webView.evaluateJavascript(clickFolderJs(1), null);
        }
        ui.postDelayed(this::tick, POLL_MS);
    }

    private void finishFromNames(List<String> names) {
        String[] picked = pickBest(names);
        lastFileName = picked == null ? "" : picked[1];
        lastFileId = "";
        Result r = new Result();
        if (picked != null && versionGreater(picked[0], currentVersion)) {
            r.latest = picked[0];
            r.fileName = picked[1];
        }
        deliver(r);
    }

    /** @return {版本号, 完整文件名}，取版本号最大的那个 */
    private static String[] pickBest(List<String> names) {
        String bestVer = "";
        String bestName = "";
        for (String name : names) {
            String ver = matchVersion(name);
            if (ver.isEmpty()) continue;
            if (bestVer.isEmpty() || versionGreater(ver, bestVer)) {
                bestVer = ver;
                bestName = name;
            }
        }
        return bestVer.isEmpty() ? null : new String[]{bestVer, bestName};
    }

    /** 从「清弈-v1.3.0.apk」这类文件名里取版本号，取不到返回空串 */
    private static String matchVersion(String name) {
        Matcher m = APK_NAME.matcher(name);
        if (m.find()) return m.group(1);
        Matcher bare = APK_BARE.matcher(name);
        return bare.find() ? bare.group(1) : "";
    }

    private static List<String> readNames(JSONObject state) {
        List<String> names = new ArrayList<>();
        JSONArray arr = state.optJSONArray("names");
        if (arr == null) return names;
        for (int i = 0; i < arr.length(); i++) {
            String name = arr.optString(i, "");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static JSONObject parseState(String raw) {
        String text = unquote(raw);
        if (text.isEmpty()) return null;
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasApk(List<String> names) {
        for (String name : names) {
            if (name.toLowerCase().contains(".apk")) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 取直链

    /**
     * 解析安装包真实下载直链（带签名，会过期，每次现取）。
     * 直连结果有 fileId 时走接口 302；WebView 兜底则先点文件行与 .down-btn，再把接口地址在 App 内跟完。
     */
    public void resolveUrl(UrlListener l) {
        if (destroyed) {
            l.onUrl(null, "更新页面已关闭，请重新检查。");
            return;
        }
        if (!lastFileId.isEmpty()) {
            resolveDirect(lastFileId, l);
            return;
        }
        if (capturedUrl != null) {
            resolveCaptured(capturedUrl, l);
            return;
        }
        if (webView == null) {
            l.onUrl(null, "安装包信息缺失，请重新检查更新。");
            return;
        }
        String name = lastFileName;
        if (name.isEmpty()) {
            l.onUrl(null, "安装包信息缺失，请重新检查更新。");
            return;
        }
        webView.evaluateJavascript(HOOK_JS, v -> webView.evaluateJavascript(clickFileJs(name),
                v2 -> ui.postDelayed(() -> tryClickDownload(l, 0), 1500)));
    }

    private void resolveDirect(String fileId, UrlListener l) {
        worker.execute(() -> {
            Feijipan.LinkResult link = Feijipan.redirect(fileId);
            if (link.ok) Log.d(TAG, "download url: " + Feijipan.clip(link.url));
            else Log.w(TAG, "resolve failed: " + link.message);
            ui.post(() -> l.onUrl(link.ok ? link.url : null, link.ok ? null : link.message));
        });
    }

    private void resolveCaptured(String url, UrlListener l) {
        Log.d(TAG, "captured api url: " + Feijipan.clip(url));
        worker.execute(() -> {
            Feijipan.LinkResult link = Feijipan.followCaptured(url);
            if (link.ok) Log.d(TAG, "download url: " + Feijipan.clip(link.url));
            else Log.w(TAG, "resolve failed: " + link.message);
            ui.post(() -> l.onUrl(link.ok ? link.url : null, link.ok ? null : link.message));
        });
    }

    /** 文件行点开后页面会渲染下载按钮；不同版本类名不一致，多试几轮 */
    private void tryClickDownload(UrlListener l, int attempt) {
        if (capturedUrl != null) {
            resolveCaptured(capturedUrl, l);
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
            resolveCaptured(capturedUrl, l);
            return;
        }
        if (destroyed || webView == null) {
            l.onUrl(null, "更新页面已关闭，请重新检查。");
            return;
        }
        if (attempt >= 18) {
            l.onUrl(null, "没有取到下载接口地址，已用浏览器打开分享页。");
            return;
        }
        webView.evaluateJavascript("(window.__qyUrl||'')", v -> {
            String u = unquote(v);
            if (u.startsWith("http")) {
                capturedUrl = u;
                resolveCaptured(u, l);
            } else if (attempt % 2 == 1) {
                webView.evaluateJavascript(clickDownloadBtnJs(), null);
                ui.postDelayed(() -> waitUrl(l, attempt + 1), 600);
            } else {
                ui.postDelayed(() -> waitUrl(l, attempt + 1), 600);
            }
        });
    }

    // ---------------------------------------------------------------- 收尾

    private void deliver(Result r) {
        if (delivered) return;
        delivered = true;
        ui.removeCallbacksAndMessages(null);
        r.ok = true;
        r.current = currentVersion;
        r.fileId = lastFileId;
        if (listener != null) listener.onResult(this, r);
    }

    private void fail(String error) {
        if (delivered) return;
        delivered = true;
        ui.removeCallbacksAndMessages(null);
        Result r = new Result();
        r.ok = false;
        r.current = currentVersion;
        r.error = error;
        Log.w(TAG, "check failed: " + error);
        if (listener != null) listener.onResult(this, r);
    }

    /** 必须在 Activity 销毁或流程结束后调用, 否则 WebView 泄漏 */
    public void destroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        worker.shutdown();
        if (webView == null) return;
        try {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
        } catch (Exception ignored) {
        }
        webView = null;
    }

    // ---------------------------------------------------------------- JS

    /** 结构化 DOM + 文本：文件名列表、空目录提示、加载态、表头是否渲染。 */
    private static final String STATE_JS = "(function(){"
            + "var out=[];var els=document.querySelectorAll('.list-name');"
            + "for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').replace(/^\\s+|\\s+$/g,'');if(t)out.push(t);}"
            + "var text=document.body?document.body.innerText:'';"
            + "var head=document.querySelector('.list-head,.list-header,.file-head,.table-head,.list-title');"
            + "var hdr=!!head||text.indexOf('文件名')>=0||text.indexOf('大小')>=0;"
            + "return JSON.stringify({names:out,empty:text.indexOf('没有分享文件')>=0,"
            + "loading:text.indexOf('加载中')>=0||text.indexOf('正在加载')>=0,header:hdr,"
            + "sig:out.join('|')+'#'+text.length});})()";

    /** 把页面的 window.open 变成可读的全局变量, 只装一次 */
    private static final String HOOK_JS = "(function(){"
            + "window.__qyUrl=window.__qyUrl||'';"
            + "if(window.__qyHooked)return 'ok';"
            + "window.__qyHooked=1;"
            + "var ow=window.open;"
            + "window.open=function(u){try{if(u)window.__qyUrl=String(u);}catch(e){}"
            + "try{return ow.apply(window,arguments);}catch(e){return null;}};"
            + "return 'ok';})()";

    private static String clickFolderJs(int step) {
        String target = "var n=el.parentElement||el;";
        if (step == 1) target = "var n=el;";
        else if (step == 2) {
            target = "var n=el.closest('[class*=item],[class*=file],[class*=folder],[class*=row]')"
                    + "||el.parentElement||el;";
        } else if (step >= 3) {
            target = "var n=el;for(var k=0;k<3&&n.parentElement;k++){n=n.parentElement;}";
        }
        return "(function(){"
                + "var list=document.querySelectorAll('span,div,p,a');"
                + "for(var i=0;i<list.length;i++){"
                + "var el=list[i];"
                + "var t=(el.textContent||'').replace(/^\\s+|\\s+$/g,'');"
                + "var low=t.toLowerCase();"
                + "if(t!=='安卓'&&low!=='android')continue;"
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
                + "var list=document.querySelectorAll('button,span,div,a');"
                + "for(var k=0;k<list.length;k++){"
                + "var el=list[k];"
                + "var t=(el.textContent||'').replace(/\\s+/g,'');"
                + "if(t!=='下载'&&t!=='立即下载'&&t!=='下载文件')continue;"
                + "if(el.children.length>1)continue;"
                + "if(hit(el))return 'ok';}"
                + "return 'nf';})()";
    }

    /** 页面里真正的下载接口/直链地址 */
    private static boolean isCaptureUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("/ws/download/url") || u.contains("/ws/file/redirect")
                || u.contains(".apk") || u.contains("download_name=");
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
}
