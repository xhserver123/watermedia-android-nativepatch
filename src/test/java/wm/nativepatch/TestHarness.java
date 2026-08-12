package wm.nativepatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Standalone verification (no Minecraft/Fabric runtime): user-folder staging, GNU runtime
 * auto-load, dedupe and failure tolerance on a real aarch64 host.
 */
public final class TestHarness {

    public static void main(final String... args) throws Exception {
        final Path root = Files.createTempDirectory("nativepatch-test");
        final Path userDir = root.resolve("user");
        final Path runtimeDir = root.resolve("cache");
        Files.createDirectories(userDir);

        copy("lib/libstdc++.so.6", userDir);
        copy("lib/libgcc_s.so.1", userDir);
        Files.writeString(userDir.resolve("libbroken.so"), "this is not an ELF");

        System.out.println("== SO_NAME matching ==");
        assertTrue(NativePatchLoaderTest.matches("libstdc++.so.6"), "libstdc++.so.6");
        assertTrue(NativePatchLoaderTest.matches("libfoo.so.1.2.3"), "libfoo.so.1.2.3");
        assertTrue(!NativePatchLoaderTest.matches("libfoo.jar"), "libfoo.jar");

        System.out.println("== bionic detection ==");
        System.out.println("  isBionic = " + NativePatchLoader.isBionic());

        System.out.println("== pipeline (stage -> load) ==");
        final int n = NativePatchLoader.process(runtimeDir, userDir);
        System.out.println("pipeline loaded " + n + " lib(s)");
        assertTrue(Files.exists(runtimeDir.resolve("libstdc++.so.6")), "libstdc++ staged into runtime dir");
        assertTrue(NativePatchLoaderTest.isLoaded("libstdc++.so.6"), "libstdc++ actually loaded (non-bionic host)");

        final int n2 = NativePatchLoader.process(runtimeDir, userDir);
        assertTrue(n2 == 0, "second pass adds nothing (dedupe)");

        cleanup(root);
        System.out.println("== ALL TESTS PASSED ==");
    }

    private static void copy(final String rel, final Path dest) throws Exception {
        Files.copy(Path.of(System.getProperty("user.dir")).resolve(rel), dest.resolve(Path.of(rel).getFileName()));
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
