package wm.nativepatch;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * WaterMedia Native Patch v3 — Android (bionic) FFmpeg fix.
 * <p>
 * v3 is the result of on-device diagnosis: the device runs the Android bionic linker
 * (namespace errors like {@code ... in namespace clns-N}), so WaterMedia's bundled
 * <b>glibc</b> FFmpeg natives (ffmpeg-linux-arm64.zip) can never load — they need
 * {@code libc.so.6}/{@code libm.so.6}/{@code libstdc++.so.6} which bionic does not
 * provide. The correct fix is to feed WaterMedia the <b>android-arm64 (bionic)</b>
 * build of the same FFmpeg version instead:
 * <ul>
 *   <li><b>Deploy</b> the bionic FFmpeg 8.0.1-gpl natives (from JavaCPP's
 *       {@code ffmpeg-8.0.1-1.5.13-android-arm64-gpl} artifact) into the directory
 *       WaterMedia configures for JavaCPP ({@code <java.io.tmpdir>/watermedia/ffmpeg}),
 *       overwriting the glibc copies. Versioned aliases ({@code libavcodec.so.62} …)
 *       are created as symlinks so JavaCPP's name-based preloads resolve too.</li>
 *   <li><b>Preload</b> the seven core libraries into the namespace in dependency order,
 *       so the linker satisfies every DT_NEEDED regardless of loader search paths.</li>
 *   <li>A watcher re-deploys if WaterMedia re-extracts its glibc build over ours
 *       (fresh installs) — it runs long before JavaCPP's first load.</li>
 * </ul>
 * The GNU C++ runtime auto-load (libstdc++.so.6/libgcc_s.so.1) is kept for glibc
 * environments only; on bionic those glibc builds can never load and are skipped.
 * The user folder loader ({@code <gameDir>/nativepatch/}) is unchanged.
 */
public final class NativePatchLoader implements PreLaunchEntrypoint, ModInitializer {

    private static final Logger LOG = LogManager.getLogger("NativePatch");
    private static final String TAG = "NativePatch";
    private static final String PROP_DIR = "nativepatch.dir";
    private static final String USER_DIR_NAME = "nativepatch";
    private static final String RUNTIME_DIR_NAME = "nativepatch";
    private static final String BUNDLED_SUBDIR = "bundled";
    private static final String BUNDLED_ROOT = "native/";
    private static final String BUNDLED_FFMPEG = "native/ffmpeg/";
    private static final String FFMPEG_REL = "watermedia/ffmpeg";
    private static final String FFMPEG_MARKER = ".nativepatch-ffmpeg";
    private static final String FFMPEG_MARKER_VALUE = "android-arm64-gpl-8.0.1-v3";

    /** bionic builds are versioned differently; aliases map the glibc names to our files. */
    private static final String[][] FFMPEG_ALIASES = {
            {"libavcodec.so.62", "libavcodec.so"},
            {"libavdevice.so.62", "libavdevice.so"},
            {"libavfilter.so.11", "libavfilter.so"},
            {"libavformat.so.62", "libavformat.so"},
            {"libavutil.so.60", "libavutil.so"},
            {"libswresample.so.6", "libswresample.so"},
            {"libswscale.so.9", "libswscale.so"},
    };

    /** Dependency order for preloading the core libs (retried once at the end). */
    private static final String[] FFMPEG_CORES = {
            "libavutil.so", "libswresample.so", "libswscale.so", "libavcodec.so",
            "libavformat.so", "libavfilter.so", "libavdevice.so",
    };

    /** Matches lib names like {@code libfoo.so}, {@code libfoo.so.6}, {@code libfoo.so.1.2}. */
    private static final Pattern SO_NAME = Pattern.compile("(?i).*\\.so(\\.\\d+)*$");

    private static final Set<String> LOADED = new HashSet<>();
    private static final boolean AARCH64 = isAArch64();
    private static final boolean BIONIC = isBionic();
    private static volatile boolean booted;
    private static volatile boolean ffmpegReady;

    @Override
    public void onPreLaunch() {
        bootstrap();
    }

    @Override
    public void onInitialize() {
        bootstrap();
    }

    private static void bootstrap() {
        if (booted) {
            log("Bootstrap already ran, skipping");
            return;
        }
        booted = true;
        final long t0 = System.currentTimeMillis();
        try {
            final Path tmp = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
            final Path runtimeDir = tmp.resolve("watermedia").resolve(RUNTIME_DIR_NAME);
            final Path userDir = userFolder();
            Files.createDirectories(runtimeDir);
            Files.createDirectories(userDir);
            writeReadme(userDir);

            process(runtimeDir, userDir);

            patchLibraryPath(runtimeDir);
            deployFfmpegAndPreload(tmp);
            startFfmpegWatcher(tmp);
            log("Bootstrap done in " + (System.currentTimeMillis() - t0) + " ms (arch=" + System.getProperty("os.arch", "?")
                + ", bionic=" + BIONIC + ")");
        } catch (final Throwable t) {
            err("Bootstrap failed: " + t);
        }
    }

    /** User-folder staging + GNU runtime auto-load (glibc envs only). Package-private for tests. */
    static int process(final Path runtimeDir, final Path userDir) throws IOException, URISyntaxException {
        Files.createDirectories(runtimeDir);
        final List<Path> candidates = new ArrayList<>();
        for (final Path lib : findLibraries(userDir)) {
            candidates.add(stage(userDir, lib, runtimeDir));
        }
        if (AARCH64 && !BIONIC) {
            candidates.addAll(extractBundled(runtimeDir.resolve(BUNDLED_SUBDIR), ""));
        } else {
            log("Skipping bundled glibc GNU runtime libraries (bionic=" + BIONIC + ", aarch64=" + AARCH64 + ")");
        }
        candidates.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        int n = 0;
        for (final Path lib : candidates) {
            if (load(lib)) n++;
        }
        return n;
    }

    // ================================================================
    // FFmpeg replacement — the actual Android fix
    // ================================================================

    /** Deploys the bundled bionic FFmpeg into WaterMedia's extraction dir and preloads the cores. */
    static void deployFfmpegAndPreload(final Path tmp) throws IOException, URISyntaxException {
        if (!AARCH64) {
            log("Skipping FFmpeg replacement (not aarch64)");
            return;
        }
        final Path ffmpegDir = tmp.resolve(FFMPEG_REL);
        Files.createDirectories(ffmpegDir);
        final boolean deployed = deployFfmpeg(ffmpegDir);
        preloadFfmpegCores(ffmpegDir);
        if (deployed || ffmpegReady) {
            log("FFmpeg bionic natives active in " + ffmpegDir);
        } else {
            err("FFmpeg replacement incomplete in " + ffmpegDir + " — check the errors above");
        }
    }

    /**
     * Writes the 14 bundled bionic libs + versioned symlink aliases + marker into the dir.
     * Returns true when the directory is verified ready afterwards.
     */
    static boolean deployFfmpeg(final Path ffmpegDir) throws IOException, URISyntaxException {
        if (!AARCH64) return false;
        final List<Path> placed = extractBundled(ffmpegDir, "ffmpeg");
        if (placed.isEmpty()) {
            err("No bundled FFmpeg natives found in this jar (native/ffmpeg/)");
            return false;
        }
        for (final String[] alias : FFMPEG_ALIASES) {
            final Path link = ffmpegDir.resolve(alias[0]);
            final Path target = ffmpegDir.resolve(alias[1]);
            try {
                Files.deleteIfExists(link);
                Files.createSymbolicLink(link, target.getFileName());
            } catch (final IOException | UnsupportedOperationException e) {
                log("Symlink alias " + alias[0] + " skipped: " + e.getMessage());
            }
        }
        final Path marker = ffmpegDir.resolve(FFMPEG_MARKER);
        Files.writeString(marker, FFMPEG_MARKER_VALUE);
        return ffmpegReady(ffmpegDir);
    }

    /** True when the dir holds OUR bionic libjniavutil + cores (marker + size sentinels). */
    static boolean ffmpegReady(final Path ffmpegDir) throws IOException {
        try {
            final Path marker = ffmpegDir.resolve(FFMPEG_MARKER);
            if (!Files.isRegularFile(marker)) return false;
            if (!FFMPEG_MARKER_VALUE.equals(Files.readString(marker).trim())) return false;
            final long jniSize = Files.size(ffmpegDir.resolve("libjniavutil.so"));
            final long avcodecSize = Files.size(ffmpegDir.resolve("libavcodec.so"));
            // Sentinel sizes of the bionic builds (differ from the glibc ones).
            if (jniSize != 1_567_632L || avcodecSize != 34_305_904L) return false;
        } catch (final IOException e) {
            return false;
        }
        return true;
    }

    /** Loads the 7 core libs by absolute path, deps first; a retry pass covers stragglers. */
    static void preloadFfmpegCores(final Path ffmpegDir) {
        if (!AARCH64) return;
        for (final String name : FFMPEG_CORES) {
            load(ffmpegDir.resolve(name));
        }
        for (final String name : FFMPEG_CORES) {
            load(ffmpegDir.resolve(name)); // retry: deps that failed first pass now load
        }
        ffmpegReady = true;
    }

    /** Watches the FFmpeg dir: if WaterMedia re-extracts its glibc build, re-deploy + re-preload. */
    private static void startFfmpegWatcher(final Path tmp) {
        final Path ffmpegDir = tmp.resolve(FFMPEG_REL);
        final Thread t = new Thread(() -> {
            final long deadline = System.currentTimeMillis() + 60_000L;
            long interval = 25L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (!ffmpegReady(ffmpegDir)) {
                        log("FFmpeg dir needs (re)deploy — WaterMedia may have re-extracted");
                        final boolean ok = deployFfmpeg(ffmpegDir);
                        preloadFfmpegCores(ffmpegDir);
                        if (ok) {
                            log("FFmpeg bionic natives re-deployed and preloaded");
                            return;
                        }
                    } else {
                        return; // already ours and stable
                    }
                } catch (final Throwable ignored) {
                    // dir may be mid-extraction
                }
                try {
                    Thread.sleep(interval);
                    if (System.currentTimeMillis() > deadline - 55_000L) interval = 100L;
                } catch (final InterruptedException e) {
                    return;
                }
            }
            log("FFmpeg watcher timed out");
        }, TAG + "-FFmpegWatcher");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================
    // Folder & load logic
    // ================================================================

    static List<Path> findLibraries(final Path root) throws IOException {
        final List<Path> out = new ArrayList<>();
        try (final Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(NativePatchLoader::notHidden)
                .filter(p -> SO_NAME.matcher(p.getFileName().toString()).matches())
                .sorted()
                .forEach(out::add);
        }
        return out;
    }

    private static Path stage(final Path sourceRoot, final Path lib, final Path runtimeDir) {
        final Path dest = runtimeDir.resolve(lib.getFileName().toString());
        try {
            if (!Files.exists(dest)) {
                Files.copy(lib, dest);
            } else if (!Files.isSameFile(dest, lib)) {
                log("Keeping existing staged copy of " + lib.getFileName() + " (user folder: " + sourceRoot + ")");
            }
        } catch (final IOException e) {
            err("Could not stage " + lib + " -> " + dest + " (" + e.getMessage() + ")");
        }
        return dest;
    }

    static boolean load(final Path lib) {
        final String key = lib.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!LOADED.add(key)) {
            log("Already loaded, skipping: " + lib.getFileName());
            return false;
        }
        try {
            System.load(lib.toAbsolutePath().toString());
            log("Loaded: " + lib);
            return true;
        } catch (final UnsatisfiedLinkError | RuntimeException e) {
            LOADED.remove(key); // a later candidate with the same name may succeed
            err("FAILED to load " + lib + " -> " + e.getMessage());
            return false;
        }
    }

    /** Extracts {@code native/<subdir>/*.so} from this mod jar (or classes dir in dev). */
    static List<Path> extractBundled(final Path targetDir, final String subdir) throws IOException, URISyntaxException {
        final List<Path> out = new ArrayList<>();
        Files.createDirectories(targetDir);
        final String prefix = BUNDLED_ROOT + (subdir.isEmpty() ? "" : subdir + "/");
        final URL location = NativePatchLoader.class.getProtectionDomain().getCodeSource().getLocation();
        if (location == null) return out;

        if ("file".equals(location.getProtocol())) {
            final Path p = Path.of(location.toURI());
            if (Files.isDirectory(p)) {
                final Path root = p.resolve(prefix);
                if (!Files.isDirectory(root)) return out;
                try (final Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                        .filter(l -> root.equals(l.getParent())) // direct children only
                        .filter(l -> SO_NAME.matcher(l.getFileName().toString()).matches())
                        .forEach(src -> copyOut(src, targetDir, out));
                }
                return out;
            }
            if (p.getFileName().toString().endsWith(".jar")) {
                extractFromJar(p, prefix, targetDir, out);
                return out;
            }
            return out;
        }

        if ("jar".equals(location.getProtocol())) {
            final String spec = location.toString(); // jar:file:/abs/mod.jar!/
            extractFromJar(Path.of(URI.create(spec.substring(4, spec.indexOf('!')))), prefix, targetDir, out);
            return out;
        }
        return out;
    }

    private static void extractFromJar(final Path jarPath, final String prefix, final Path targetDir, final List<Path> out) {
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                final String name = entry.getName();
                if (!name.startsWith(prefix)) continue;
                final String rest = name.substring(prefix.length());
                if (rest.isEmpty() || rest.contains("/")) continue; // direct children only
                if (!SO_NAME.matcher(rest).matches()) continue;
                final Path dest = targetDir.resolve(Path.of(rest).getFileName().toString());
                try (final InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                out.add(dest);
            }
        } catch (final IOException e) {
            err("Could not open bundled jar " + jarPath + " -> " + e);
        }
    }

    private static void copyOut(final Path src, final Path targetDir, final List<Path> out) {
        final Path dest = targetDir.resolve(src.getFileName().toString());
        try {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            out.add(dest);
        } catch (final IOException e) {
            err("Extract failed " + src + " -> " + e);
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static Path userFolder() {
        final String override = System.getProperty(PROP_DIR);
        if (override != null && !override.isBlank()) {
            final Path p = Path.of(override);
            log("Using -D" + PROP_DIR + "=" + p);
            return p;
        }
        return gameDir().resolve(USER_DIR_NAME);
    }

    private static Path gameDir() {
        try {
            final Path dir = FabricLoader.getInstance().getGameDir();
            if (dir != null) {
                log("Game dir: " + dir);
                return dir;
            }
        } catch (final Throwable ignored) {
            // Loader API unavailable (tests, unusual hosts) — fall back below.
        }
        final Path dir = Path.of(System.getProperty("user.dir", "."));
        log("Game dir (fallback user.dir): " + dir);
        return dir;
    }

    private static boolean notHidden(final Path p) {
        for (final Path segment : p) {
            if (segment.toString().startsWith(".")) return false;
        }
        return true;
    }

    private static boolean isAArch64() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    /** Android (bionic) detection: the JVM's native search path includes Android system dirs. */
    static boolean isBionic() {
        final String libPath = System.getProperty("java.library.path", "");
        if (libPath.contains("/system/lib64") || libPath.contains("/system/lib")) return true;
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("android");
    }

    private static void patchLibraryPath(final Path dir) {
        final String add = dir.toAbsolutePath().toString();
        final String oldProp = System.getProperty("java.library.path");
        if (oldProp == null || oldProp.isEmpty()) {
            System.setProperty("java.library.path", add);
        } else if (!oldProp.contains(add)) {
            System.setProperty("java.library.path", add + File.pathSeparator + oldProp);
        }
        try {
            final Field usr = ClassLoader.class.getDeclaredField("usr_paths");
            usr.setAccessible(true);
            final String[] old = (String[]) usr.get(null);
            final String[] merged = new String[1 + (old == null ? 0 : old.length)];
            merged[0] = add;
            if (old != null) System.arraycopy(old, 0, merged, 1, old.length);
            usr.set(null, merged);

            final Field sys = ClassLoader.class.getDeclaredField("sys_paths");
            sys.setAccessible(true);
            sys.set(null, null);
            log("java.library.path patched (+" + add + ")");
        } catch (final Throwable t) {
            log("java.library.path patch skipped (best-effort): " + t.getClass().getSimpleName());
        }
    }

    private static void writeReadme(final Path dir) {
        final Path f = dir.resolve("README.txt");
        if (Files.exists(f)) return;
        final String content = String.join("\n",
            "NativePatch — put native libraries here",
            "========================================",
            "Every *.so / *.so.<ver> file in this folder (recursively) is staged into the",
            "exec-able app cache and loaded at game startup, BEFORE WaterMedia boots its",
            "FFmpeg natives.",
            "",
            "This mod ALSO replaces WaterMedia's glibc FFmpeg natives with the android-arm64",
            "(bionic) build, so they load on Android at all — that is the actual fix for",
            "'dlopen failed ... not found ... in namespace clns-N'.",
            "",
            "Bundled & deployed automatically:",
            "  - FFmpeg 8.0.1-gpl android-arm64 natives (14 libs, incl. libjniavutil.so)",
            "  - libstdc++.so.6 + libgcc_s.so.1 (only on non-bionic systems)",
            "",
            "If something is still missing, drop that .so into this folder and restart.",
            "JVM arg override: -Dnativepatch.dir=/path/to/libs",
            "");
        try {
            Files.writeString(f, content);
        } catch (final IOException ignored) {
            // Never fail the boot over a README.
        }
    }

    // ================================================================
    // Test hooks (package-private)
    // ================================================================

    static java.util.regex.Pattern soName() {
        return SO_NAME;
    }

    static boolean isLoadedName(final String lowercasedFileName) {
        return LOADED.contains(lowercasedFileName);
    }

    private static void log(final String msg) {
        LOG.info(msg);
        System.out.println("[" + TAG + "] " + msg);
    }

    private static void err(final String msg) {
        LOG.error(msg);
        System.err.println("[" + TAG + "] " + msg);
    }
}
