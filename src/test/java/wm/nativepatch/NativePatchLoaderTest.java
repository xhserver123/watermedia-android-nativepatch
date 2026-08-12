package wm.nativepatch;

import java.util.Locale;

/** Test-only accessors into NativePatchLoader package-private state. */
final class NativePatchLoaderTest {

    private NativePatchLoaderTest() {}

    static boolean matches(final String name) {
        return NativePatchLoader.soName().matcher(name).matches();
    }

    static boolean isLoaded(final String fileName) {
        return NativePatchLoader.isLoadedName(fileName.toLowerCase(Locale.ROOT));
    }
}
