import SwiftUI

/// 设置页：更新检查开关、当前版本、手动检查入口。
/// 从主页右上角「设置」进入。
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var app: AppState
    var theme: Theme

    private var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    }

    var body: some View {
        ZStack {
            PaperBackground(theme: theme)

            VStack(spacing: 0) {
                header
                Hairline(color: theme.dlgLine)
                    .padding(.horizontal, 24)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 26) {
                        updateSection
                        aboutSection
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 22)
                    .padding(.bottom, 34)
                }
            }
        }
        // 手动检测到新版本时收起设置页，让主页的小对话框露出来
        .onChange(of: app.updateNotice?.latest) { latest in
            if latest != nil { dismiss() }
        }
    }

    private var header: some View {
        HStack {
            Text("设置")
                .font(.system(size: 20, weight: .bold, design: .serif))
                .foregroundColor(theme.title)
                .tracking(1)
            Spacer()
            Button("完成") { dismiss() }
                .font(.system(size: 14, design: .serif))
                .foregroundColor(theme.sub)
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 14)
    }

    // MARK: - 更新

    private var updateSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            sectionTitle("更新")

            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("更新检查")
                        .font(.system(size: 15))
                        .foregroundColor(theme.title)
                    Text("启动应用时自动检测新版本")
                        .font(.system(size: 11))
                        .foregroundColor(theme.foot)
                }
                Spacer(minLength: 0)
                Toggle("", isOn: $app.autoUpdate)
                    .labelsHidden()
                    .tint(theme.accent)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(cardBackground)
            .overlay(cardBorder)

            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("当前版本")
                        .font(.system(size: 15))
                        .foregroundColor(theme.title)
                    Text("v\(UpdateSpec.currentVersion)（\(buildNumber)）")
                        .font(.system(size: 11))
                        .foregroundColor(theme.foot)
                }
                Spacer(minLength: 0)
                Button {
                    app.checkForUpdate(manual: true)
                } label: {
                    Text(app.updateChecking ? "检查中…" : "检查更新")
                        .font(.system(size: 13, weight: .medium, design: .serif))
                        .foregroundColor(theme.btnText)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: theme.btnRadius)
                                .fill(theme.isHara ? Color.clear : theme.btnBg)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: theme.btnRadius)
                                .stroke(theme.btnStroke, lineWidth: 0.8)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(app.updateChecking)
                .opacity(app.updateChecking ? 0.5 : 1)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(cardBackground)
            .overlay(cardBorder)

            if let status = app.updateStatusText {
                Text(status)
                    .font(.system(size: 12))
                    .foregroundColor(theme.sub)
                    .padding(.horizontal, 2)
            }

            Text("更新包由小飞机网盘分发。点击「下载更新」会先取带签名的安装包直链，再交给系统浏览器下载；取直链失败时回退到分享页，不在应用内安装。")
                .font(.system(size: 11))
                .foregroundColor(theme.foot)
                .lineSpacing(3)
                .padding(.horizontal, 2)
        }
    }

    // MARK: - 关于

    private var aboutSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("关于")
            Text("清弈 · 围棋 / 象棋 / 五子棋 / 暗棋")
                .font(.system(size: 13))
                .foregroundColor(theme.sub)
            Text("引擎全部进程内运行，对局无需联网；更新检测仅访问分享目录。")
                .font(.system(size: 11))
                .foregroundColor(theme.foot)
                .lineSpacing(3)
        }
    }

    // MARK: - 样式

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12))
            .tracking(2)
            .foregroundColor(theme.sub)
    }

    private var cardRadius: CGFloat { max(theme.entryRadius, 2) }

    private var cardBackground: some View {
        RoundedRectangle(cornerRadius: cardRadius)
            .fill(theme.isHara ? Color.clear : theme.btnBg.opacity(0.7))
    }

    private var cardBorder: some View {
        RoundedRectangle(cornerRadius: cardRadius)
            .stroke(theme.btnStroke, lineWidth: 0.8)
    }
}
