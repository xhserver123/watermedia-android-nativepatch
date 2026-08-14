package github.xhserver000.watermedia_android_nativepatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Verifies the Android FFmpeg replacement: deployment (14 bionic libs + symlink aliases +
 * marker), readiness sentinels, re-deploy after a simulated glibc overwrite, and graceful
 * preload failure on non-bionic hosts (real loading needs bionic libc/libm).
 */
public final class FfmpegTest {

    public static void main(final String... args) throws Exception {
        final Path root = Files.createTempDirectory("nativepatch-ffmpeg");
        final Path ffmpegDir = root.resolve("ffmpeg");
        Files.createDirectories(ffmpegDir);

        System.out.println("== initial deploy ==");
        final boolean ready1 = WaterMediaAndroidNativePatchMod.deployFfmpeg(ffmpegDir);
        System.out.println("ready = " + ready1 + ", isBionic = " + WaterMediaAndroidNativePatchMod.isBionic());
        assertTrue(ready1, "deploy makes dir ready (marker + bionic sizes)");
        assertTrue(Files.exists(ffmpegDir.resolve("libjniavutil.so")), "libjniavutil.so deployed");
        assertTrue(Files.exists(ffmpegDir.resolve("libavcodec.so")), "libavcodec.so deployed");
        assertTrue(Files.exists(ffmpegDir.resolve("libavutil.so.60")), "versioned alias libavutil.so.60 exists");
        assertTrue(Files.isSymbolicLink(ffmpegDir.resolve("libavcodec.so.62")), "alias is a symlink");
        assertTrue(Files.exists(ffmpegDir.resolve(".nativepatch-ffmpeg")), "marker written");

        System.out.println("== simulate glibc overwrite (watermedia re-extract) ==");
        Files.writeString(ffmpegDir.resolve("libjniavutil.so"), "glibc build! not the bionic one");
        assertTrue(!WaterMediaAndroidNativePatchMod.ffmpegReady(ffmpegDir), "readiness detects foreign libjniavutil");

        System.out.println("== re-deploy fixes it ==");
        assertTrue(WaterMediaAndroidNativePatchMod.deployFfmpeg(ffmpegDir), "re-deploy restores readiness");

        System.out.println("== preload (graceful on non-bionic host) ==");
        WaterMediaAndroidNativePatchMod.preloadFfmpegCores(ffmpegDir); // must not throw

        cleanup(root);
        System.out.println("== ALL TESTS PASSED ==");
    }

    private static void cleanup(final Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (final Exception ignored) {}
            });
        }
    }

    private static void assertTrue(final boolean cond, final String what) {
        if (!cond) throw new AssertionError("FAILED: " + what);
        System.out.println("  ok: " + what);
    }
}
