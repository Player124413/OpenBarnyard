package org.opentoshi.openbarnyard;

import android.util.Log;
import android.view.Surface;

/**
 * JNI bridge to libopenbarnyard.so (cpp/jni_bridge.cpp). Every call is safe
 * to make even if the native library failed to load (e.g. an ABI the APK
 * does not ship): methods become no-ops and {@link #isAvailable()} is false,
 * so the launcher UI still works for configuration/editing.
 */
public final class NativeBridge {
    private static final String TAG = "OpenBarnyard";
    private static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("openbarnyard");
            ok = true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load libopenbarnyard.so", t);
            ok = false;
        }
        AVAILABLE = ok;
    }

    private NativeBridge() {}

    public static boolean isAvailable() { return AVAILABLE; }

    public static void init(String filesDir, String gameDataPath, String configJson) {
        if (AVAILABLE) nativeInit(filesDir, gameDataPath, configJson);
    }

    public static void shutdown() { if (AVAILABLE) nativeShutdown(); }

    public static void setSurface(Surface surface) {
        if (AVAILABLE && surface != null) nativeSetSurface(surface);
    }

    public static void clearSurface() { if (AVAILABLE) nativeClearSurface(); }

    public static void setResumed(boolean resumed) {
        if (AVAILABLE) nativeSetResumed(resumed);
    }

    public static void applyConfig(String configJson) {
        if (AVAILABLE) nativeApplyConfig(configJson);
    }

    public static void setButton(int command, boolean down) {
        if (AVAILABLE) nativeSetButton(command, down);
    }

    public static void setStick(float x, float y) {
        if (AVAILABLE) nativeSetStick(x, y);
    }

    public static void addCameraDelta(float dx, float dy) {
        if (AVAILABLE) nativeAddCameraDelta(dx, dy);
    }

    public static float getFps() { return AVAILABLE ? nativeGetFps() : 0f; }

    public static float getCurrentScale() { return AVAILABLE ? nativeGetCurrentScale() : 0f; }

    public static String getDiagnostics() {
        return AVAILABLE ? nativeGetDiagnostics() : "native library not loaded";
    }

    public static String getCpuSummary() {
        return AVAILABLE ? nativeGetCpuSummary() : "";
    }

    // ------------------------------------------------------------ JNI (cpp/jni_bridge.cpp)
    private static native void nativeInit(String filesDir, String gameDataPath, String configJson);
    private static native void nativeShutdown();
    private static native void nativeSetSurface(Surface surface);
    private static native void nativeClearSurface();
    private static native void nativeSetResumed(boolean resumed);
    private static native void nativeApplyConfig(String configJson);
    private static native void nativeSetButton(int command, boolean down);
    private static native void nativeSetStick(float x, float y);
    private static native void nativeAddCameraDelta(float dx, float dy);
    private static native float nativeGetFps();
    private static native float nativeGetCurrentScale();
    private static native String nativeGetDiagnostics();
    private static native String nativeGetCpuSummary();
}
