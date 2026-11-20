import SwiftUI
import UIKit

enum GameKind: String {
    case go
    case xiangqi
    case gomoku
    case banqi

    /// 与 Android `GameAdapter` 的 game 字段一致。
    var lanID: String {
        switch self {
        case .go: return LanProto.goGame
        case .xiangqi: return LanProto.xiangqiGame
        case .gomoku: return LanProto.gomokuGame
        case .banqi: return LanProto.banqiGame
        }
    }

    var title: String {
        switch self {
        case .go: return "围棋"
        case .xiangqi: return "象棋"
        case .gomoku: return "五子棋"
        case .banqi: return "暗棋"
        }
    }
}

/// 发现新版本时给主页弹窗用
struct UpdateNotice: Equatable {
    let latest: String
    /// 分享目录里的安装包文件名，下载时用它定位文件行
    let fileName: String
}

final class AppState: ObservableObject {
    @Published var themeID: ThemeID {
        didSet { UserDefaults.standard.set(themeID.rawValue, forKey: "ui.theme") }
    }
    @Published var kind: GameKind {
        didSet { UserDefaults.standard.set(kind.rawValue, forKey: "ui.kind") }
    }
    @Published var goSound: Bool {
        didSet { UserDefaults.standard.set(goSound, forKey: "ui.goSound") }
    }
    @Published var xqHints: Bool {
        didSet { UserDefaults.standard.set(xqHints, forKey: "ui.xqHints") }
    }
    /// 启动时是否自动检测更新（设置页开关，持久化）
    @Published var autoUpdate: Bool {
        didSet { UserDefaults.standard.set(autoUpdate, forKey: "ui.autoUpdate") }
    }
    /// 非空时主页弹「发现新版本」小对话框
    @Published var updateNotice: UpdateNotice?
    @Published private(set) var updateChecking = false
    /// 正在取安装包直链
    @Published private(set) var updateDownloading = false
    /// 设置页里显示的最后一次检测结果
    @Published var updateStatusText: String?

    private var didAutoCheck = false

    var theme: Theme { Theme.of(themeID) }

    init() {
        let defaults = UserDefaults.standard
        let savedTheme = defaults.string(forKey: "ui.theme")
        themeID = ThemeID(rawValue: savedTheme ?? "") ?? .hara
        let savedKind = defaults.string(forKey: "ui.kind")
        kind = GameKind(rawValue: savedKind ?? "") ?? .go
        if defaults.object(forKey: "ui.goSound") == nil {
            goSound = true
        } else {
            goSound = defaults.bool(forKey: "ui.goSound")
        }
        if defaults.object(forKey: "ui.xqHints") == nil {
            xqHints = true
        } else {
            xqHints = defaults.bool(forKey: "ui.xqHints")
        }
        if defaults.object(forKey: "ui.autoUpdate") == nil {
            autoUpdate = true
        } else {
            autoUpdate = defaults.bool(forKey: "ui.autoUpdate")
        }
    }

    // MARK: - 更新检查

    /// manual=true 时把结果写进设置页文案；自动检测只在发现新版时弹窗，失败保持安静。
    func checkForUpdate(manual: Bool) {
        guard !updateChecking, !updateDownloading else { return }
        updateChecking = true
        if manual { updateStatusText = "正在检查更新…" }
        Task { @MainActor in
            let outcome = await UpdateChecker.shared.check()
            self.updateChecking = false
            switch outcome {
            case .update(let latest, let fileName):
                self.updateNotice = UpdateNotice(latest: latest, fileName: fileName)
                self.updateStatusText = "发现新版本 v\(latest)"
            case .upToDate:
                if manual { self.updateStatusText = "已是最新版本 v\(UpdateSpec.currentVersion)" }
            case .unavailable(let message):
                if manual { self.updateStatusText = message }
            case .failure(let message):
                if manual { self.updateStatusText = "检查失败：\(message)" }
            }
        }
    }

    /// 冷启动后自动检测一次（同一进程只跑一次）
    func autoCheckForUpdateIfNeeded() {
        guard autoUpdate, !didAutoCheck else { return }
        didAutoCheck = true
        checkForUpdate(manual: false)
    }

    /// 「下载更新」：复用检测留下的隐藏页面取带签名的直链，交给系统浏览器下载；
    /// 取直链失败时回退为直接打开分享页。
    func beginUpdateDownload(fileName: String) {
        guard !updateDownloading else { return }
        updateDownloading = true
        updateStatusText = "正在获取下载链接…"
        Task { @MainActor in
            let directURL = await UpdateChecker.shared.downloadURL(fileName: fileName)
            self.updateDownloading = false
            self.updateNotice = nil
            if let directURL {
                self.updateStatusText = "正在通过系统浏览器下载…"
                await UIApplication.shared.open(directURL)
            } else {
                self.updateStatusText = "获取直链失败，已打开分享页"
                await UIApplication.shared.open(UpdateSpec.sharePageURL)
            }
        }
    }

    func toggleTheme() {
        themeID = themeID == .hara ? .monument : .hara
    }
}
