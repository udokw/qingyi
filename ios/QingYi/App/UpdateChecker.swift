import Foundation
import UIKit
import WebKit

/// 更新检测的固定配置与纯函数（不依赖主线程，UI 可直接读取）
enum UpdateSpec {
    /// 分享目录：根 → 清弈（旧结构可能没有）→ ios / 安卓；iOS 安装包在 ios 里。
    static let sharePageURL = URL(string: "https://share.feijipan.com/s/PRzlQ1Hk")!

    /// 安装包命名约定：清弈-v1.3.0.ipa（兼容「清弈 1.3.0.ipa」、下划线、全角破折号与 (1) 后缀）
    static let fileNamePattern = "^(?:清弈|qingyi)[-_－— ]*[vV]?([0-9]+(?:\\.[0-9]+){1,3})(?:[(（][0-9]+[)）])?\\.ipa$"
    static let appFolderName = "清弈"
    static let iosFolderName = "ios"

    /// 当前安装版本，来自 Info.plist（随 project.yml 的 MARKETING_VERSION 自动同步）
    static var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    /// 逐段比较版本号，a 比 b 新返回 true
    static func isNewer(_ a: String, than b: String) -> Bool {
        let pa = a.split(separator: ".").map { Int($0) ?? 0 }
        let pb = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(pa.count, pb.count) {
            let va = i < pa.count ? pa[i] : 0
            let vb = i < pb.count ? pb[i] : 0
            if va != vb { return va > vb }
        }
        return false
    }

    /// 从文件名解析版本号；不符合命名约定返回 nil
    static func version(from fileName: String) -> String? {
        let name = fileName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let re = try? NSRegularExpression(pattern: fileNamePattern, options: [.caseInsensitive]) else { return nil }
        let range = NSRange(name.startIndex..<name.endIndex, in: name)
        guard let match = re.firstMatch(in: name, range: range),
              let versionRange = Range(match.range(at: 1), in: name) else { return nil }
        return String(name[versionRange])
    }
}

/// 更新检测（小飞机网盘分享目录），按 feijipan-update 技能的统一模板实现。
///
/// 为什么用 WKWebView 而不是 URLSession：
/// 技能文档的 iOS 首选正是「隐藏 WKWebView 读结构化 DOM + 放行 api 导航截 CDN」，
/// 不手搓 UA / Referer / Cookie / 跳转与会话；页面的请求由分享页自己发出，风控看到的就是正常点击。
///
/// 稳定性处理：
/// - 每次检测都新建 WKWebView，加载前先 `about:blank` 卸载旧文档；
/// - 根目录要等「表头 + 文件夹行」都渲染（连续两次读到同一组文件名）才点；
/// - 目录结构是 根 → 清弈（旧结构可能没有）→ ios，逐层按名字找，不写死 folderId；
/// - 进入目标目录后，出现安装包行立即返回；没有则文本稳定约 1.8s 按空目录返回，不干等超时；
/// - 载入超时/JS 执行失败属于「加载失败」，最多自动重试 2 次（共 3 次）。
///
/// 下载直链：
/// 分享页点文件只会打开详情面板，点其中的下载按钮才通过 window.open 打开
/// `api.feijipan.com/ws/file/redirect?...`，该地址再 302 到带签名的 CDN 直链
/// （`dl-e.feejii.com/...&download_name=xxx.ipa`）。这里复用检测时保留的隐藏页面，
/// 放行接口导航让 WKWebView 自己跟完 302，只在 URL 变成 CDN 时取消并交给系统浏览器；
/// 绝不把接口地址交给 Safari（否则只会下到 redirect.json「参数有误」）。
@MainActor
final class UpdateChecker: NSObject, WKNavigationDelegate {
    /// 桌面版 UA + 大尺寸：移动版布局不会把文件名放进文本节点。
    private static let desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /// 单次检测最多尝试次数（首次 + 2 次重试）
    private static let maxAttempts = 3
    private static let rootTimeout: TimeInterval = 12
    private static let folderRowTimeout: TimeInterval = 8
    private static let folderContentTimeout: TimeInterval = 10
    /// 目录里一直没有安装包时，文本稳定这么久就按空目录返回
    private static let emptyStable: TimeInterval = 1.8
    /// 点文件行后等详情面板（下载按钮）出现
    private static let detailTimeout: TimeInterval = 8
    /// 点下载按钮后等接口地址/302 直链
    private static let redirectTimeout: TimeInterval = 12

    enum Outcome {
        case upToDate
        case update(latest: String, fileName: String)
        /// 目录可达但没有可用的安装包（空目录 / 命名不符 / 确实缺 ios 文件夹）
        case unavailable(String)
        /// 加载失败、超时等可重试问题
        case failure(String)
    }

    /// 取直链结果：失败时带可读原因与实际地址，设置页直接展示，方便真机取证
    enum LinkOutcome {
        case success(URL)
        case failure(String)
    }

    static let shared = UpdateChecker()

    private var webView: WKWebView?
    private var running = false
    /// 当前隐藏页面所在的 ios 文件夹内容；非空表示页面已停在文件夹列表，可直接复用
    private var folderNames: [String] = []
    /// 当前隐藏页面是否停在某个文件的详情面板（下载按钮所在）
    private var detailFileName: String?
    /// 正在取下载直链
    private var resolving = false
    /// 只在取直链阶段拦截导航，避免影响正常检测
    private var captureActive = false
    /// 拦截到的带签名直链
    private var capturedDownloadURL: URL?
    /// 最近一次放行的取链接口地址，只用于失败文案取证
    private var lastAPIURL: URL?

    private struct PageState {
        let names: [String]
        let isEmptyHint: Bool
        let isLoading: Bool
        /// 表头（文件名/大小）是否已渲染；没有表头的旧页面靠多等一轮兜底
        let hasHeader: Bool
    }

    private enum AttemptResult {
        case success(Outcome)
        /// 结构性结论（重试没有意义）
        case structural(String)
        /// 加载/执行失败，可以重试
        case retryable(String)
    }

    private enum FolderRow {
        case found
        case emptyRoot
        case missing(String)
        case timedOut
    }

    private struct Package {
        let version: String
        let fileName: String
    }

    /// 发起一次检测。失败、超时、目录为空都返回可读结果，不会抛错。
    /// 检测成功后保留隐藏页面（停在 ios 文件夹列表），供「下载更新」直接取直链。
    func check() async -> Outcome {
        guard !running, !resolving else { return .failure("正在检查更新，请稍候。") }
        running = true
        captureActive = false
        capturedDownloadURL = nil

        var finalOutcome: Outcome?
        var lastFailure = ""
        var keepPage = false

        attemptLoop: for attempt in 1...Self.maxAttempts {
            switch await runAttempt() {
            case .success(let outcome):
                finalOutcome = outcome
                keepPage = true
                break attemptLoop
            case .structural(let message):
                finalOutcome = .unavailable(message)
                break attemptLoop
            case .retryable(let message):
                lastFailure = message
                teardown()
                if attempt < Self.maxAttempts {
                    // 给网络和页面渲染一点恢复时间再重试
                    try? await Task.sleep(nanoseconds: 900_000_000)
                }
            }
        }

        running = false
        if !keepPage { teardown() }
        return finalOutcome ?? .failure("已重试 \(Self.maxAttempts) 次仍无法读取分享页：\(lastFailure)")
    }

    // MARK: - 单次尝试

    private func runAttempt() async -> AttemptResult {
        // 释放上一次留下的页面，避免读到旧 DOM；检测成功后会重新保留新页面
        teardown()
        guard let host = Self.keyWindow() else { return .retryable("无法获取当前窗口。") }

        // 每次尝试都新建，避免复用上一次的旧页面/DOM
        let webView = makeWebView(in: host)
        self.webView = webView

        // 加载前先卸载旧文档：about:blank 是同步的，等它完成再进分享页
        webView.load(URLRequest(url: URL(string: "about:blank")!))
        try? await Task.sleep(nanoseconds: 150_000_000)

        var request = URLRequest(url: UpdateSpec.sharePageURL)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 15
        webView.load(request)

        // 1) 等根目录「表头 + 文件夹行」都渲染，或页面明确提示「没有分享文件」
        guard let root = await waitForRoot(in: webView) else {
            return .retryable("分享页加载超时。")
        }
        if root.names.isEmpty {
            return .structural("分享目录根目录为空，请确认分享设置。")
        }

        // 兼容没有平台子文件夹的旧结构：根目录直接有更新版 ipa 就用
        if let direct = newestPackage(in: root.names),
           UpdateSpec.isNewer(direct.version, than: UpdateSpec.currentVersion) {
            return .success(.update(latest: direct.version, fileName: direct.fileName))
        }

        // 分享根下可能多一层「清弈」应用文件夹（旧结构没有这一层）
        var appNames = root.names
        if root.names.contains(where: {
            Self.normalizedFolderName($0) == Self.normalizedFolderName(UpdateSpec.appFolderName)
        }) {
            guard let clicked = await clickListName(UpdateSpec.appFolderName, in: webView) else {
                return .retryable("在页面上点击「\(UpdateSpec.appFolderName)」文件夹失败。")
            }
            guard clicked else {
                return .retryable("点击「\(UpdateSpec.appFolderName)」文件夹未命中，页面可能正在重绘。")
            }
            guard let inner = await waitForFolderRows(in: webView, previous: root.names) else {
                return .retryable("读取「\(UpdateSpec.appFolderName)」文件夹超时。")
            }
            if inner.names.isEmpty {
                return .structural("分享目录的「\(UpdateSpec.appFolderName)」文件夹为空。")
            }
            appNames = inner.names
        }

        if let direct = newestPackage(in: appNames),
           UpdateSpec.isNewer(direct.version, than: UpdateSpec.currentVersion) {
            return .success(.update(latest: direct.version, fileName: direct.fileName))
        }

        // 2) 等「ios」文件夹行真正出现；只有整段超时且页面已稳定渲染，才判定结构缺失
        switch await waitForFolderRow(UpdateSpec.iosFolderName, in: webView) {
        case .found:
            break
        case .emptyRoot:
            return .structural("分享目录根目录为空，请确认分享设置。")
        case .missing(let detail):
            return .structural("分享目录里没有「\(UpdateSpec.iosFolderName)」文件夹\(detail)。")
        case .timedOut:
            return .retryable("等待「ios」文件夹出现超时。")
        }

        // 3) 点击进入 ios 文件夹
        guard let clicked = await clickListName(UpdateSpec.iosFolderName, in: webView) else {
            return .retryable("在页面上点击「ios」文件夹失败。")
        }
        guard clicked else {
            return .retryable("点击「ios」文件夹未命中，页面可能正在重绘。")
        }

        // 4) 等子目录内容：出现安装包立即返回；没有则稳定约 1.8s 按空目录返回
        guard let content = await waitForFolderContent(in: webView, previous: appNames) else {
            return .retryable("读取「ios」文件夹内容超时。")
        }
        folderNames = content.names
        if content.names.isEmpty {
            return .success(.unavailable("分享目录的 ios 文件夹里还没有安装包。"))
        }
        guard let best = newestPackage(in: content.names) else {
            return .success(.unavailable("ios 文件夹里的文件不符合「清弈-版本号.ipa」命名。"))
        }
        return .success(UpdateSpec.isNewer(best.version, than: UpdateSpec.currentVersion)
            ? .update(latest: best.version, fileName: best.fileName)
            : .upToDate)
    }

    // MARK: - 页面创建与轮询

    private func makeWebView(in host: UIWindow) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        // 不写入任何浏览数据，检测完即销毁
        config.websiteDataStore = .nonPersistent()
        // 尺寸按桌面宽度给足，避免响应式布局隐藏文件行；视图藏在内容层下面且几乎透明。
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 1280, height: 900), configuration: config)
        webView.customUserAgent = Self.desktopUA
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.alpha = 0.02
        webView.isUserInteractionEnabled = false
        webView.scrollView.isScrollEnabled = false
        webView.navigationDelegate = self
        host.insertSubview(webView, at: 0)
        return webView
    }

    /// 等根目录「表头 + 文件夹行」都渲染：连续两次读到同一组文件名才算稳定；
    /// 没识别到表头的旧页面再额外多等一轮，避免把骨架屏当最终列表。
    private func waitForRoot(in webView: WKWebView) async -> PageState? {
        let deadline = Date().addingTimeInterval(Self.rootTimeout)
        var previous: [String] = []
        var stable = 0
        while Date() < deadline {
            if let state = await readPage(in: webView) {
                if state.names.isEmpty && state.isEmptyHint {
                    return state
                }
                if !state.names.isEmpty {
                    if state.names == previous {
                        stable += 1
                    } else {
                        previous = state.names
                        stable = 0
                    }
                    if stable >= (state.hasHeader ? 1 : 2) {
                        return state
                    }
                }
            }
            try? await Task.sleep(nanoseconds: 400_000_000)
        }
        return nil
    }

    /// 轮询等待指定文件夹行；整段超时后才根据最后一次观测给出结论
    private func waitForFolderRow(_ folder: String, in webView: WKWebView) async -> FolderRow {
        let deadline = Date().addingTimeInterval(Self.folderRowTimeout)
        var lastState: PageState?
        let target = Self.normalizedFolderName(folder)

        while Date() < deadline {
            if let state = await readPage(in: webView) {
                lastState = state
                if state.names.contains(where: { Self.normalizedFolderName($0) == target }) {
                    return .found
                }
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }

        guard let last = lastState else { return .timedOut }
        if last.isEmptyHint && last.names.isEmpty { return .emptyRoot }
        // 最后一眼仍是空列表或仍在加载 → 归为超时，交给重试；不能误报成结构缺失
        guard !last.names.isEmpty, !last.isLoading else { return .timedOut }
        let preview = last.names.prefix(4).joined(separator: "、")
        return .missing(preview.isEmpty ? "" : "（当前目录：\(preview)）")
    }

    /// 等进入下一层目录：出现与上一层不同的文件名行，且连续两次一致
    private func waitForFolderRows(in webView: WKWebView, previous: [String]) async -> PageState? {
        let deadline = Date().addingTimeInterval(Self.folderRowTimeout)
        var candidate: [String] = []
        var stable = 0
        while Date() < deadline {
            if let state = await readPage(in: webView) {
                if state.isEmptyHint && state.names.isEmpty { return state }
                if !state.names.isEmpty && state.names != previous {
                    if state.names == candidate {
                        stable += 1
                    } else {
                        candidate = state.names
                        stable = 0
                    }
                    if stable >= 1 { return state }
                }
            }
            try? await Task.sleep(nanoseconds: 350_000_000)
        }
        return nil
    }

    /// 点击列表里文本匹配的行（文件夹或文件）。nil 表示 JS 执行失败，false 表示没找到
    private func clickListName(_ name: String, in webView: WKWebView) async -> Bool? {
        let raw = await evaluate("""
        (() => {
          const target = \(Self.jsString(Self.normalizedFolderName(name)));
          const el = [...document.querySelectorAll('.list-name')]
            .find(n => n.textContent.replace(/\\s+/g, '').toLowerCase() === target);
          if (!el) return 'missing';
          el.scrollIntoView({ block: 'center' });
          el.click();
          return 'ok';
        })()
        """, in: webView)
        guard let text = raw as? String else { return nil }
        return text == "ok"
    }

    /// 等目标目录内容：
    /// - 出现 ipa 行立即返回；
    /// - 明确提示「没有分享文件」立即返回；
    /// - 没有安装包时，文本稳定约 1.8s 就按空目录返回，不干等 10s 超时。
    private func waitForFolderContent(in webView: WKWebView, previous: [String]) async -> PageState? {
        let deadline = Date().addingTimeInterval(Self.folderContentTimeout)
        var lastSig = ""
        var stableSince = Date()
        while Date() < deadline {
            if let state = await readPage(in: webView) {
                if state.names.contains(where: { $0.lowercased().hasSuffix(".ipa") }) {
                    return state
                }
                if state.isEmptyHint && state.names.isEmpty {
                    return state
                }
                if state.names != previous {
                    let sig = state.names.joined(separator: "|")
                    if state.isLoading {
                        lastSig = sig
                        stableSince = Date()
                    } else if sig != lastSig {
                        lastSig = sig
                        stableSince = Date()
                    } else if Date().timeIntervalSince(stableSince) >= Self.emptyStable {
                        return state
                    }
                }
            }
            try? await Task.sleep(nanoseconds: 350_000_000)
        }
        return nil
    }

    private func readPage(in webView: WKWebView) async -> PageState? {
        let raw = await evaluate("""
        (() => {
          const names = [...document.querySelectorAll('.list-name')]
            .map(n => n.textContent.trim())
            .filter(n => n.length > 0);
          const text = document.body ? document.body.innerText : '';
          const head = document.querySelector('.list-head,.list-header,.file-head,.table-head,.list-title');
          return JSON.stringify({
            names: names,
            empty: text.indexOf('没有分享文件') >= 0,
            loading: text.indexOf('加载中') >= 0 || text.indexOf('正在加载') >= 0,
            header: !!head || text.indexOf('文件名') >= 0 || text.indexOf('大小') >= 0
          });
        })()
        """, in: webView)
        guard let text = raw as? String, let data = text.data(using: .utf8) else { return nil }
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let obj = object as? [String: Any] else { return nil }
        let names = (obj["names"] as? [String]) ?? []
        return PageState(names: names,
                         isEmptyHint: (obj["empty"] as? Bool) ?? false,
                         isLoading: (obj["loading"] as? Bool) ?? false,
                         hasHeader: (obj["header"] as? Bool) ?? false)
    }

    private func evaluate(_ script: String, in webView: WKWebView) async -> Any? {
        await withCheckedContinuation { (continuation: CheckedContinuation<Any?, Never>) in
            webView.evaluateJavaScript(script) { value, _ in
                continuation.resume(returning: value)
            }
        }
    }

    // MARK: - 下载直链

    /// 取某个安装包的带签名直链。失败时返回可读原因（带实际地址），设置页直接展示。
    /// 优先复用检测后保留下来的隐藏页面，避免重新加载。
    func downloadURL(fileName: String) async -> LinkOutcome {
        guard !running, !resolving else { return .failure("正在检查更新，请稍候。") }
        resolving = true
        defer {
            resolving = false
            captureActive = false
            capturedDownloadURL = nil
            // 页面已经跳到接口地址，下次重新从分享页开始
            folderNames = []
            detailFileName = nil
        }
        guard let host = Self.keyWindow() else { return .failure("无法获取当前窗口。") }
        guard let webView = await ensureFolderPage(in: host, fileName: fileName) else {
            return .failure("重新打开分享页失败。\(pageDetail())")
        }
        guard await openDetail(of: fileName, in: webView) else {
            return .failure("没有在分享页找到「\(fileName)」的下载按钮。\(pageDetail())")
        }
        return await tapDownloadAndCapture(in: webView)
    }

    /// 确保隐藏页面停在 ios 文件夹列表；检测成功后保留的页面可以直接复用
    private func ensureFolderPage(in host: UIWindow, fileName: String) async -> WKWebView? {
        if let webView, webView.superview != nil, !folderNames.isEmpty,
           webView.url?.host == UpdateSpec.sharePageURL.host, detailFileName == nil {
            return webView
        }

        teardown()
        let webView = makeWebView(in: host)
        self.webView = webView
        webView.load(URLRequest(url: URL(string: "about:blank")!))
        try? await Task.sleep(nanoseconds: 150_000_000)
        var request = URLRequest(url: UpdateSpec.sharePageURL)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 15
        webView.load(request)

        guard let root = await waitForRoot(in: webView) else { return nil }
        // 兼容根目录直接放 ipa 的旧结构
        if root.names.contains(where: { $0 == fileName }) {
            folderNames = root.names
            return webView
        }

        var appNames = root.names
        if root.names.contains(where: {
            Self.normalizedFolderName($0) == Self.normalizedFolderName(UpdateSpec.appFolderName)
        }) {
            guard let clicked = await clickListName(UpdateSpec.appFolderName, in: webView), clicked,
                  let inner = await waitForFolderRows(in: webView, previous: root.names) else {
                return nil
            }
            appNames = inner.names
            if appNames.contains(fileName) {
                folderNames = appNames
                return webView
            }
        }

        guard let clicked = await clickListName(UpdateSpec.iosFolderName, in: webView), clicked,
              let content = await waitForFolderContent(in: webView, previous: appNames) else {
            return nil
        }
        folderNames = content.names
        return webView
    }

    /// 点文件行打开详情面板，等下载按钮出现
    private func openDetail(of fileName: String, in webView: WKWebView) async -> Bool {
        if detailFileName == fileName {
            if await hasDownloadButton(in: webView) { return true }
        }
        guard let clicked = await clickListName(fileName, in: webView), clicked else { return false }

        let deadline = Date().addingTimeInterval(Self.detailTimeout)
        while Date() < deadline {
            if await hasDownloadButton(in: webView) {
                detailFileName = fileName
                return true
            }
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
        return false
    }

    private func hasDownloadButton(in webView: WKWebView) async -> Bool {
        let raw = await evaluate("document.querySelector('.down-btn') ? '1' : ''", in: webView)
        return (raw as? String) == "1"
    }

    /// 点下载按钮：先挂 window.open 钩子，再点击；接口地址与 302 直链两条路都接住
    private func tapDownloadAndCapture(in webView: WKWebView) async -> LinkOutcome {
        capturedDownloadURL = nil
        lastAPIURL = nil
        captureActive = true
        _ = await evaluate("""
        (() => {
          window.__qyDirect = null;
          window.open = function (u) {
            try { window.__qyDirect = String(u || ''); } catch (e) {}
            return null;
          };
          return 'ok';
        })()
        """, in: webView)
        let raw = await evaluate("""
        (() => {
          const el = document.querySelector('.down-btn');
          if (!el) return 'missing';
          el.scrollIntoView({ block: 'center' });
          el.click();
          return 'ok';
        })()
        """, in: webView)
        guard (raw as? String) == "ok" else {
            return .failure("页面上没有下载按钮。\(pageDetail())")
        }

        // 情况一：页面自行导航到接口（由导航代理接住 302）；情况二：window.open 被钩子拦下
        var navigating = false
        let openDeadline = Date().addingTimeInterval(6)
        while Date() < openDeadline {
            if let url = capturedDownloadURL { return .success(url) }
            if webView.url?.host == "api.feijipan.com" { navigating = true }
            let opened = (await evaluate("window.__qyDirect || ''", in: webView) as? String) ?? ""
            if !opened.isEmpty {
                _ = await evaluate("window.__qyDirect = null; location.href = \(Self.jsString(opened)); 'ok'",
                                   in: webView)
                navigating = true
                break
            }
            if !captureActive && !navigating {
                return .failure("点下载按钮后没有打开取链接口。\(pageDetail())")
            }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        guard navigating || captureActive else {
            return .failure("点下载按钮后没有打开取链接口。\(pageDetail())")
        }

        // 等 302 跳到带签名的 CDN 直链
        let redirectDeadline = Date().addingTimeInterval(Self.redirectTimeout)
        while Date() < redirectDeadline {
            if let url = capturedDownloadURL { return .success(url) }
            if !captureActive { break }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        if let url = capturedDownloadURL { return .success(url) }
        return .failure("接口没有返回安装包直链。\(pageDetail())")
    }

    /// 失败取证：只保留接口与当前页的主机 + 路径，去掉会过期的签名参数
    private func pageDetail() -> String {
        var parts: [String] = []
        if let api = lastAPIURL {
            parts.append("接口 \(Self.shortURL(api))")
        }
        if let current = webView?.url {
            parts.append("当前页 \(Self.shortURL(current))")
        }
        return parts.isEmpty ? "" : "\n" + parts.joined(separator: "\n")
    }

    private static func shortURL(_ url: URL) -> String {
        let host = url.host ?? ""
        let path = url.path.isEmpty ? "/" : url.path
        let hasQuery = url.query != nil ? "?..." : ""
        return host + path + hasQuery
    }

    // MARK: - WKNavigationDelegate（只在取直链阶段生效）

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard captureActive, navigationAction.targetFrame?.isMainFrame == true,
              let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        // 接口地址放行，等它 302 出带签名的直链；
        // iOS 的 WKNavigationAction 不暴露 302 响应，重定向后按 URL 特征识别直链
        if Self.isFeijipanAPI(url) {
            lastAPIURL = url
            decisionHandler(.allow)
            return
        }
        if Self.isSignedFileURL(url) {
            capturedDownloadURL = url
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard captureActive, navigationResponse.isForMainFrame,
              let url = navigationResponse.response.url, !Self.isFeijipanAPI(url) else {
            decisionHandler(.allow)
            return
        }
        capturedDownloadURL = url
        decisionHandler(.cancel)
    }

    /// 兼容接口直接返回 JSON/文本、里面带直链的情况
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard captureActive, let url = webView.url, Self.isFeijipanAPI(url) else { return }
        Task { @MainActor in
            let body = (await self.evaluate("document.body ? document.body.innerText : ''", in: webView) as? String) ?? ""
            if let link = Self.firstExternalURL(in: body) {
                self.capturedDownloadURL = link
            } else {
                // 拿不到链接就尽快结束，别让调用方干等到超时
                self.captureActive = false
            }
        }
    }

    func webView(_ webView: WKWebView,
                 didFailProvisionalNavigation navigation: WKNavigation!,
                 withError error: Error) {
        guard captureActive else { return }
        // 主动取消重定向也会走到这里；真正的加载失败则尽快结束
        if (error as NSError).code != NSURLErrorCancelled {
            captureActive = false
        }
    }

    private static func isFeijipanAPI(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        return host == "api.feijipan.com" || host == "api.feijix.com"
    }

    /// 带签名的下载直链：CDN 域名，或 URL 里带 download_name（文件原名）
    private static func isSignedFileURL(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        if host == "share.feijipan.com" || isFeijipanAPI(url) { return false }
        if url.absoluteString.contains("download_name=") { return true }
        return host.hasSuffix("feejii.com") || host.hasSuffix("feijipan.com")
    }

    private static func firstExternalURL(in text: String) -> URL? {
        guard let re = try? NSRegularExpression(pattern: "https?://[^\\s\"'<>\\\\)]+") else { return nil }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        for match in re.matches(in: text, range: range) {
            guard let r = Range(match.range, in: text) else { continue }
            let raw = String(text[r]).replacingOccurrences(of: "\\u0026", with: "&")
            guard let url = URL(string: raw), isSignedFileURL(url) else { continue }
            return url
        }
        return nil
    }

    // MARK: - 解析

    /// 文件名列表里版本号最高的 ipa（不判断是否比当前版本新）
    private func newestPackage(in names: [String]) -> Package? {
        var best: Package?
        for name in names {
            guard let version = UpdateSpec.version(from: name) else { continue }
            if let existing = best, !UpdateSpec.isNewer(version, than: existing.version) { continue }
            best = Package(version: version, fileName: name)
        }
        return best
    }

    private static func normalizedFolderName(_ name: String) -> String {
        name.replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
            .lowercased()
    }

    private static func jsString(_ value: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [value])
        guard let data, let json = String(data: data, encoding: .utf8) else { return "\"\"" }
        // json 形如 ["ios"]，取中间一段
        return String(json.dropFirst().dropLast())
    }

    // MARK: - 收尾

    private func teardown() {
        captureActive = false
        capturedDownloadURL = nil
        lastAPIURL = nil
        folderNames = []
        detailFileName = nil
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        webView?.removeFromSuperview()
        webView = nil
    }

    private static func keyWindow() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            if let key = scene.windows.first(where: { $0.isKeyWindow }) { return key }
        }
        return scenes.first?.windows.first
    }
}
