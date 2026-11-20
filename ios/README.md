# 清弈 iOS

清弈（QingYi）iOS 版：SwiftUI 界面，KataGo / Pachi / Pikafish 以静态库形式链接进 App（iOS 不允许 fork 子进程）。

本目录是双端仓库 `udokw/qingyi` 的 iOS 部分，安卓代码在同仓库 `android/`。
CI 只监听 `ios/**`：本目录有改动才会构建 IPA，本地构建前先 `cd ios`。

## 构建

需要 macOS + Xcode 16 + XcodeGen（工程文件 `QingYi.xcodeproj` 由 `project.yml` 生成，不入库）：

```
brew install xcodegen
cd ios
xcodegen generate
xcodebuild -project QingYi.xcodeproj -scheme QingYi -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' build
```

CI（`.github/workflows/build-ipa.yml`）先跑五子棋/暗棋 AI 自检，再出未签名 IPA。
打 `vX.Y.Z` tag 时 IPA 会附到同名 Release，与安卓 APK 同一个 Release。

## 版本号

`project.yml` 的 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` 必须与安卓
`app/build.gradle` 的 `versionName` / `versionCode` 保持一致，双端同步递增。
