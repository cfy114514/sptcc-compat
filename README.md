# 上海交通卡启动兼容模块

用于上海交通卡 App 在特定 Root 环境中启动闪退的 LSPosed 模块。本仓库保存已在实机验证、用户确认可以正常启动的版本，使用 MIT 许可证。

**下载：[Releases](https://github.com/cfy114514/sptcc-compat/releases)**

## 已验证环境

| 项目 | 版本 |
| --- | --- |
| 手机 | 三星 Galaxy S23 / SM-S9110 |
| 系统 | Android 16，arm64-v8a |
| 上海交通卡 | 202610.1 / versionCode 224 |
| 应用包名 | `com.jiuyu.sptcc.cordova` |
| Root | KernelSU 32664，运行时加载模式 |
| Zygisk | Zygisk Next 1.5.2 |
| LSPosed | 2.2.1 / 7912 |
| 模块版本 | 0.1-test |

这是针对上述环境的兼容快照。已验证应用启动；充值、支付、实体卡读写等业务尚未逐项验证。

## 安装

1. 安装 Release 中的 APK。
2. 在 LSPosed 中启用“上海交通卡兼容助手（测试）”。
3. **作用域只勾选上海交通卡**。
4. 结束上海交通卡进程后重新打开。已正常运行的 LSPosed 可以在下次应用启动时加载本模块。

当前预编译 APK 的早期初始化限定应用 UID **10506**，对应实测手机现有的安装。如果在其他手机使用，先执行：

```sh
adb shell cmd package list packages -U com.jiuyu.sptcc.cordova
```

将 `MainHook.java` 中早期初始化的 UID 条件改为目标安装的 UID，再重新构建。不要给系统框架或其他应用添加作用域。卸载后重新安装交通卡也可能改变 UID。

## 实现

启动时的加固子进程在此环境中发生原生段错误，随后主进程被终止。模块在应用初始化前介入 `fork()`，在这一小段启动阶段返回标准的 `EAGAIN` 失败结果，使应用走可继续启动的回退路径；进入目标应用的 `handleLoadPackage` 后，后续 `fork()` 调用恢复委托原函数。

Java 层仅在目标包内处理部分 Root 文件、Root 管理器包名、调试设置和 `su` 探测。原应用 APK、签名及账号数据不做修改。

该快照保留本地诊断代码：日志标签为 `SptccCompat`，原生事件文件位于目标应用自己的 `cache/sptcc-native-crash.txt`。诊断文件不会上传；不要把包含完整内存映射的私人日志直接公开。日志中的 `v0.3` 是诊断迭代标记，APK 元数据为 `0.1-test`。

NFC 和网络服务不在模块作用域内；本项目不包含系统服务修复脚本。

## 源码结构

- `src/`：Java / Xposed 入口、应用范围与探测兼容。
- `native/diagnostic.rs`：Rust `no_std` 原生兼容层和诊断。
- `apk/`：Manifest、推荐作用域与 LSPosed 入口声明。
- `tools/build.py`：从源码编译、打包、对齐原生库并签名。

## 构建

需要 Python 3、JDK 17 或更新版本、Rust，以及 Android SDK/NDK。安装 Rust 的 Android 目标：

```sh
rustup target add aarch64-linux-android
```

准备以下环境变量（Windows PowerShell 用 `$env:变量名 = '路径'`，Linux/macOS 用 `export 变量名=路径`）：

| 变量 | 内容 |
| --- | --- |
| `ANDROID_JAR` | Android SDK 平台的 `android.jar`，实测 API 36 |
| `APKTOOL_JAR` | [Apktool 2.10.0](https://github.com/iBotPeaches/Apktool/releases/tag/v2.10.0) 的 JAR |
| `APKSIGNER_JAR` | Android SDK build-tools 中 `lib/apksigner.jar` |
| `SPTCC_TOOLS` | 放置 `r8.jar` 和 `xposed-api-82.jar` 的目录，默认 `.tools/` |
| `ANDROID_LIBS` | 含 arm64 `libc.so`、`liblog.so` 的链接目录，可使用 Android NDK sysroot 的 API 26+ arm64 库目录 |
| `RUST_LLD` | Rust 工具链中 `lib/rustlib/<主机目标>/bin/rust-lld` 的完整路径，Windows 加 `.exe` |
| `KEYTOOL` | 可选，JDK 的 `keytool` 路径 |
| `SPTCC_KEYSTORE` | 可选，签名密钥文件；默认 `.local-signing/module.p12` |
| `SPTCC_SIGNING_PASSWORD` | 可选，签名密码；未设置时只生成本地开发签名 |

编译依赖：[R8 8.3.37](https://storage.googleapis.com/r8-releases/raw/8.3.37/r8.jar)、[Xposed API 82](https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar)。Xposed API 只参与编译，不打包到 APK。

```sh
python tools/build.py
```

输出为 `build/sptcc-compat.apk`。脚本会运行 APK 签名校验。自己构建的 APK 与 Release 签名可能不同，更新模块时需要使用同一个密钥或先卸载模块。签名密钥和构建依赖不包含在仓库中。

## 停用

在 LSPosed 关闭此模块，然后结束并重新打开上海交通卡即可。也可卸载本模块；无需清除交通卡数据。

## 许可证与参考

本仓库自行实现的代码按 [MIT](LICENSE) 发布。第三方框架和构建工具保留各自许可证。

- [LSPosed Native Hook API](https://github.com/LSPosed/LSPosed/wiki/Native-Hook)
- [Android Bionic signal ABI](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/include/bits/signal_types.h)

本项目与上海公共交通卡官方无关联。
