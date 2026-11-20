# 清弈（QingYi）

围棋 / 象棋 / 五子棋 / 暗棋双端应用。安卓与 iOS 放在同一个仓库，按目录区分：

```
android/   安卓：Kotlin + Java, Gradle 构建, 内置 GNU Go / Pachi / KataGo / Fairy-Stockfish / Pikafish 原生引擎
ios/       iOS：SwiftUI + 静态链接 KataGo / Pachi / Pikafish, XcodeGen + xcodebuild 出未签名 IPA
.github/   两套 CI, 按目录触发
```

## 分支与提交约定

- 安卓改动只提交到 `android/`，iOS 改动只提交到 `ios/`。
- 一次功能如果双端都要做，分成两个提交（`android: ...` / `ios: ...`），便于 review 和回滚。

## CI 与产物

| Workflow | 触发条件 | 产物 |
| --- | --- | --- |
| `android-release.yml` | 推 `main` 且改动命中 `android/**`；或打 `vX.Y.Z` tag；或手动触发 | `清弈-vX.Y.Z.apk`（统一发布证书签名） |
| `build-ipa.yml` | 推 `main` 且改动命中 `ios/**`；或打 `vX.Y.Z` tag；或手动触发 | `QingYi-unsigned.ipa`（未签名，需自签） |

每次运行都会上传 Actions artifact；打 tag 时两个 workflow 会把 APK 与 IPA 附到**同一个** `vX.Y.Z` Release。

## 版本号

两端版本号必须一致：`android/app/build.gradle` 的 `versionName`/`versionCode` 与 workflow 里的
`VERSION_NAME`/`VERSION_CODE`、`ios/project.yml` 的 `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`
必须同步递增。

## 本地构建

安卓（需 JDK 17 + Android SDK 34）：

```
cd android
./gradlew assembleRelease
```

iOS（需 macOS + Xcode 16 + XcodeGen）：

```
cd ios
xcodegen generate
xcodebuild -project QingYi.xcodeproj -scheme QingYi -configuration Release -sdk iphoneos build
```

## 历史仓库

拆分时代的两个仓库保留为只读备份，不再更新：

- https://github.com/udokw/qingyi-android
- https://github.com/udokw/qingyi-ios

旧的 `vX.Y.Z` Release 与下载链接仍然有效。
