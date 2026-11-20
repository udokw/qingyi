# 清弈 Android

清弈（QingYi）安卓版：围棋与象棋双棋种，纯 Java + Canvas 自绘棋盘，内置本地 AI 引擎与局域网联机。

## 功能

- 围棋：19 / 13 / 9 路，GNU Go / Pachi / KataGo 三引擎，等级与让子可调，形势判断、数子、存谱
- 象棋：Fairy-Stockfish / Pikafish，棋力分级，悔棋 / 认输 / 重开
- 联机：同一 Wi-Fi 下房主创建房间（TCP 41234），对手自动发现或输入房间码加入，房主裁决
- 主题：墨白 / 粉彩两套，首页可切换

## 构建

需要 JDK 17 与 Android SDK 34：

```
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。
release 使用统一发布密钥签名，私钥不入库：本地把 `storeFile/storePassword/keyAlias/keyPassword` 写进根目录 `keystore.properties`（已 gitignore），CI 走 `ANDROID_KEYSTORE_*` Secrets。

## 应用内更新

更新包通过小飞机网盘公开分享目录分发（`清弈/安卓`、`清弈/ios`，文件名 `清弈-v<版本>.apk|ipa`）。
安卓端由隐藏 WebView 渲染分享页取版本号与带签名直链，再走原生下载安装：

- 落盘：`DownloadManager.setDestinationInExternalPublicDir(DIRECTORY_DOWNLOADS, "清弈-v<版本>.apk")`，
  必须落在**公共下载目录**，用户能在文件管理器里直接看到；不要再写 `Android/data/...` 私有目录。
- MIME：`application/vnd.android.package-archive`。
- 授权：安装时优先用 `DownloadManager.getUriForDownloadedFile()` 的 `content://`，其次用 `FileProvider`
  暴露 `Download/`，最后才回退应用私有目录的自建 Provider；一律带 `FLAG_GRANT_READ_URI_PERMISSION`。
- 安装前校验：大小 ≥ 1MB、文件头为 `PK`、ZIP 内含 `AndroidManifest.xml`，防止把网盘返回的 JSON/网页交给安装器。
- 日志：`adb logcat -s UpdateChecker UpdateInstaller`。

## 版本号规则

- `versionName` 形如 `1.1.x`，`versionCode` 单调递增，二者同在 `app/build.gradle`
- 发布时打 tag `v<versionName>`（如 `v1.1.7`），并把 APK 作为 Release 资产上传

## 目录

- `app/src/main/java/com/sijia/go/` 业务代码：棋盘绘制、规则、引擎桥接、局域网联机
- `app/src/main/assets/` 引擎资源（KataGo 模型、Pikafish 神经网络、Pachi 定式、内置棋谱）
- `app/src/main/jniLibs/` 预编译 native 引擎（arm64-v8a / armeabi-v7a）
- `docs/lan-multiplayer-design.md` 局域网联机协议设计
- `design-demos/` 界面设计稿

## 说明

第三方引擎源码（KataGo / Pachi / GNU Go / Eigen）体积过大，未纳入仓库。构建 APK 不需要这些源码（`jniLibs` 已含预编译产物）；如需重新交叉编译 native 引擎，按 `build_engine.py`、`build_pachi.py` 中的路径约定自行获取源码。
