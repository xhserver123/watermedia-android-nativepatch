package github.xhserver000.watermedia_android_nativepatch;

import java.util.Locale;

/** Test-only accessors into WaterMediaAndroidNativePatchMod package-private state. */
final class WaterMediaAndroidNativePatchModTest {

    private WaterMediaAndroidNativePatchModTest() {}

    static boolean matches(final String name) {
        return WaterMediaAndroidNativePatchMod.soName().matcher(name).matches();
    }

    static boolean isLoaded(final String fileName) {
        return WaterMediaAndroidNativePatchMod.isLoadedName(fileName.toLowerCase(Locale.ROOT));
    }
}
