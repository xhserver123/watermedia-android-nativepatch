# WaterMedia Android Native Patch

[English](README.md)

一个 Fabric 模组，专门为 Android arm64 平台修复 WaterMedia 的 FFmpeg 音视频播放功能。  
在 Android 上，由于 bionic 链接器（linker）的限制，WaterMedia 自带的 glibc 原生库无法加载，本模组解决了此问题。

作者：[XHServer](https://xhserver.qzz.io)

## 功能说明

- 将 WaterMedia 使用的 glibc FFmpeg 原生库替换为 **android-arm64 (bionic)** 构建版本（来自 JavaCPP，FFmpeg 8.0.1-gpl），使解码在 Android 上正常工作。
- 在 WaterMedia 启动之前，预先将 FFmpeg 核心库加载到原生命名空间（native namespace）中。
- 可选地加载你放入指定文件夹中的**任何额外 `.so` 文件**，以补全系统缺失的其他共享库。

## 运行要求

| 依赖项 | 版本 |
|---|---|
| Minecraft | 1.18.2+（已测试 26.2） |
| Fabric Loader | ≥ 0.14.0 |
| Java | ≥ 17 |
| WaterMedia | 3.x（可选——没有它模组也不会报错） |

## 安装方法

1. 从 [Releases](https://github.com/xhserver000/watermedia-android-nativepatch/releases) 页面下载最新 jar 文件。
2. 将 `watermedia-android-nativepatch-1.0.0.jar` 放入 `mods/` 文件夹。
3. 启动游戏。
4. 如果 WaterMedia 仍然提示缺少某个原生库，请将对应的 `.so` 文件放入游戏目录旁的 `nativepatch/` 文件夹中，然后重启游戏。

## 使用方法

### 原生库文件夹

所有放置在 **`<游戏目录>/nativepatch/`** 下的 `*.so` / `*.so.<版本号>` 文件（递归扫描）都会被复制到应用缓存中，并在 WaterMedia 加载其 FFmpeg 原生库之前被加载。

你可以通过 JVM 参数指定其他文件夹：

```

-Dnativepatch.dir=/path/to/libs

```

### 模组内置的库文件

| 文件 | 说明 |
|---|---|
| `lib/ffmpeg/*.so` | Android (bionic) FFmpeg 8.0.1-gpl 原生库（共 14 个库） |
| `lib/libstdc++.so.6`, `lib/libgcc_s.so.1` | GNU C++ 运行时库（仅在非 bionic 系统上加载） |

## 从源码构建

不需要 Gradle 或 Loom —— 只需要 JDK 和 shell 即可：

```bash
./build.sh
# 输出文件：release/watermedia-android-nativepatch-1.0.0.jar
```

编译时依赖（fabric-loader、log4j-api）会在首次构建时从 Maven 自动下载。
项目包含一个手动的 Build & Release GitHub Actions 工作流，详见 .github/workflows/build-release.yml。

许可证

· 模组代码：MIT（参见 LICENSE）。
· 内置的原生库：
  · FFmpeg 8.0.1-gpl —— GPL-3.0，由 JavaCPP（org.bytedeco:ffmpeg）构建并发布。
  · libstdc++.so.6 / libgcc_s.so.1 —— GCC 运行时，GPL-3.0 附带 GCC Runtime Library Exception 3.1。