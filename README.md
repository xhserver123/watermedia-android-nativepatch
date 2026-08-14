# WaterMedia Android Native Patch

[中文版](README_CN.md)

A Fabric mod that fixes WaterMedia's FFmpeg playback specifically for Android arm64, where the bundled native libraries fail to load due to the bionic linker.



Made by [XHServer](https://xhserver.qzz.io).

## What it does

- Replaces WaterMedia's glibc FFmpeg natives with the **android-arm64 (bionic)** build of
  the same version (FFmpeg 8.0.1-gpl, from JavaCPP), so decoding works on Android.
- Preloads the FFmpeg core libraries into the native namespace before WaterMedia boots.
- Optionally loads **any extra `.so` files** you drop into a folder, for systems missing
  other shared libraries.

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.18.2+ (26.2 tested) |
| Fabric Loader | ≥ 0.14.0 |
| Java | ≥ 17 |
| WaterMedia | 3.x (optional — the mod is harmless without it) |

## Installation

1. Download the latest jar from the
   [Releases](https://github.com/xhserver000/watermedia-android-nativepatch/releases) page.
2. Put `watermedia-android-nativepatch-1.0.0.jar` into your `mods/` folder.
3. Launch the game.
4. If WaterMedia still reports a missing native library, drop the corresponding
   `.so` file into the `nativepatch/` folder next to your game directory and restart.

## Usage

### Native library folder

Any `*.so` / `*.so.<version>` file placed in **`<game directory>/nativepatch/`**
(recursively) is copied into the app cache and loaded at startup, before WaterMedia
boots its FFmpeg natives.

You can point the loader at a different folder with the JVM argument:

```
-Dnativepatch.dir=/path/to/libs
```

### What is bundled

| File | Purpose |
|---|---|
| `lib/ffmpeg/*.so` | Android (bionic) FFmpeg 8.0.1-gpl natives (14 libraries) |
| `lib/libstdc++.so.6`, `lib/libgcc_s.so.1` | GNU C++ runtime (loaded only on non-bionic systems) |

## Building from source

No Gradle or Loom needed — a JDK and a shell are enough:

```bash
./build.sh
# output: release/watermedia-android-nativepatch-1.0.0.jar
```

Compile-time dependencies (fabric-loader, log4j-api) are downloaded from Maven on the
first build. A manual **Build & Release** GitHub Actions workflow is included —
see `.github/workflows/build-release.yml`.

## License

- Mod code: **MIT** (see [LICENSE](LICENSE)).
- Bundled native libraries:
  - FFmpeg 8.0.1-gpl — **GPL-3.0**, built and published by
    [JavaCPP](https://github.com/bytedeco/javacpp-presets) (`org.bytedeco:ffmpeg`).
  - `libstdc++.so.6` / `libgcc_s.so.1` — GCC runtime, GPL-3.0 with the
    **GCC Runtime Library Exception 3.1**.
<!-- awa -->
<!-- owo -->
