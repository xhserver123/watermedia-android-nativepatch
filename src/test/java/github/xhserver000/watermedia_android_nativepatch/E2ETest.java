package github.xhserver000.watermedia_android_nativepatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * End-to-end: a C++ library whose DT_NEEDED is exactly
 * [libstdc++.so.6, libgcc_s.so.1, libc.so.6] — the same chain WaterMedia's FFmpeg
 * natives have — must load after the v2 pipeline ran (staged into the runtime dir),
 * resolving libstdc++ against the patch-loaded copy (proved via /proc/self/maps).
 */
public final class E2ETest {

    public static void main(final String... args) throws Exception {
        final Path root = Files.createTempDirectory("nativepatch-e2e");
        final Path userDir = root.resolve("user");
        final Path runtimeDir = root.resolve("cache");
        Files.createDirectories(userDir);

        // the exact libs we ship (user-provided libstdc++, Debian/Ubuntu libgcc_s)
        Files.copy(Path.of("lib/libstdc++.so.6"), userDir.resolve("libstdc++.so.6"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("lib/libgcc_s.so.1"), userDir.resolve("libgcc_s.so.1"), StandardCopyOption.REPLACE_EXISTING);

        // 1) v2 pipeline first
        final int n = WaterMediaAndroidNativePatchMod.process(runtimeDir, userDir);
        System.out.println("pipeline loaded " + n + " lib(s)");

        // 2) now load a real C++ JNI lib that NEEDs libstdc++.so.6
        final Path jni = root.resolve("libtestjni.so");
        Files.copy(Path.of("/tmp/libtestjni.so"), jni, StandardCopyOption.REPLACE_EXISTING);
        try {
            System.load(jni.toAbsolutePath().toString());
            System.out.println("libtestjni.so loaded OK");
        } catch (final UnsatisfiedLinkError e) {
            System.err.println("libtestjni.so FAILED: " + e.getMessage());
            throw e;
        }

        // 3) prove the staged copy is the mapped libstdc++
        final String maps = Files.readString(Path.of("/proc/self/maps"));
        final String expect = runtimeDir.resolve("libstdc++.so.6").toString();
        System.out.println("expect libstdc++ path: " + expect);
        boolean found = false;
        for (final String line : maps.split("\n")) {
            if (line.contains("libstdc++.so.6") && line.contains(expect)) {
                found = true;
            }
        }
        if (!found) {
            System.err.println("OUR staged libstdc++.so.6 is NOT the mapped copy!");
            System.exit(1);
        }
        System.out.println("== E2E PASSED: C++ lib resolved against the patch-staged libstdc++ ==");
    }
}
