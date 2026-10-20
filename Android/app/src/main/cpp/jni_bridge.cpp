// OpenBarnyard Android — JNI bridge between the Java launcher/game activity
// and the native core. Owns the render thread and the ANativeWindow
// lifecycle. All GL work happens on the render thread; the UI thread only
// enqueues window swaps, config changes and input (lock-free via InputState).
#include <jni.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>

#include "core/Config.h"
#include "core/CpuFeatures.h"
#include "core/FramePacer.h"
#include "core/InputState.h"
#include "gl/Renderer.h"

#define LOG_TAG "OpenBarnyard"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using Clock = std::chrono::steady_clock;

int64_t NowMicros() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               Clock::now().time_since_epoch()).count();
}

struct NativeApp {
    std::thread         thread;
    std::mutex          mutex;
    std::condition_variable cv;
    bool                exitRequested = false;
    bool                resumed = false;
    ANativeWindow*      pendingWindow = nullptr; // owned ref; render thread consumes
    bool                windowDirty = false;
    ob::EngineConfig    config;
    bool                configDirty = false;
    bool                started = false;

    ob::InputState          input;
    ob::DynamicResolution   dynRes;
    std::atomic<float>      fps{0.0f};
    std::atomic<float>      currentScale{0.0f};
    std::string             glInfo;      // written by render thread, read under mutex
    std::string             filesDir;
    std::string             gameDataPath;
};

NativeApp g_app;

std::string ToStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void RenderThreadMain() {
    ob::Renderer renderer;
    ANativeWindow* window = nullptr; // owned by this thread once consumed
    ob::EngineConfig cfg;
    {
        std::lock_guard<std::mutex> lock(g_app.mutex);
        cfg = g_app.config;
    }
    auto lastFrame = Clock::now();
    const auto startTime = lastFrame;
    float fpsEma = 0.0f;

    auto dropWindow = [&]() {
        renderer.Shutdown();
        if (window != nullptr) ANativeWindow_release(window);
        window = nullptr;
    };

    while (true) {
        bool recreate = false;
        bool mustWait = false;
        {
            std::lock_guard<std::mutex> lock(g_app.mutex);
            if (g_app.exitRequested) break;

            if (g_app.configDirty) {
                const bool needsRecreate =
                    cfg.msaa != g_app.config.msaa || cfg.vsync != g_app.config.vsync;
                cfg = g_app.config;
                g_app.configDirty = false;
                g_app.dynRes.Configure(cfg.resolutionScale, cfg.minResolutionScale,
                                       cfg.fpsLimit, cfg.dynamicResolution);
                if (needsRecreate && window != nullptr) recreate = true;
            }
            if (g_app.windowDirty) {
                // Take ownership of the pending window (may be null = clear).
                ANativeWindow* next = g_app.pendingWindow;
                g_app.pendingWindow = nullptr;
                g_app.windowDirty = false;
                if (next != window) {
                    dropWindow();
                    window = next;
                    recreate = window != nullptr;
                    lastFrame = Clock::now();
                }
            }
            mustWait = (window == nullptr || !g_app.resumed);
        }

        if (mustWait) {
            // Idle until any state change. Every JNI mutator notifies the CV,
            // and the predicate re-checks the full state, so no wakeup is lost.
            std::unique_lock<std::mutex> lock(g_app.mutex);
            g_app.cv.wait(lock, [&] {
                return g_app.exitRequested || g_app.windowDirty || g_app.configDirty ||
                       (g_app.resumed && (g_app.pendingWindow != nullptr || window != nullptr));
            });
            continue;
        }

        if (recreate || !renderer.IsInitialized()) {
            renderer.Shutdown();
            if (!renderer.Initialize(window, cfg)) {
                LOGE("Renderer::Initialize failed; waiting for next surface");
                dropWindow();
                continue;
            }
            {
                std::lock_guard<std::mutex> lock(g_app.mutex);
                g_app.glInfo = renderer.GlInfo();
            }
            g_app.dynRes.Configure(cfg.resolutionScale, cfg.minResolutionScale,
                                   cfg.fpsLimit, cfg.dynamicResolution);
            g_app.dynRes.Reset();
            lastFrame = Clock::now();
        }

        const int64_t frameStart = NowMicros();
        const auto now = Clock::now();
        float dt = std::chrono::duration<float>(now - lastFrame).count();
        lastFrame = now;
        if (dt > 0.25f) dt = 0.25f; // pause/rotation hitch: don't teleport
        if (dt <= 0.0f) dt = 0.0001f;
        const float elapsed = std::chrono::duration<float>(now - startTime).count();

        const float scale = g_app.dynRes.Update(dt);
        g_app.currentScale.store(scale, std::memory_order_relaxed);

        if (!renderer.DrawFrame(dt, elapsed, g_app.input, cfg, scale)) {
            LOGE("Surface lost during DrawFrame; tearing down");
            dropWindow();
            continue;
        }

        const float instFps = 1.0f / dt;
        fpsEma = fpsEma <= 0.0f ? instFps : fpsEma + (instFps - fpsEma) * 0.05f;
        g_app.fps.store(fpsEma, std::memory_order_relaxed);

        const int64_t sleepUs = ob::ComputeSleepMicros(frameStart, NowMicros(), cfg.fpsLimit);
        if (sleepUs > 0) std::this_thread::sleep_for(std::chrono::microseconds(sleepUs));
        // Loop straight into the next frame; dirty flags are re-checked at
        // the top without blocking while we are actively rendering.
    }

    dropWindow();
}

} // namespace

extern "C" {

// Matches NativeBridge.nativeInit(String filesDir, String gameDataPath, String configJson)
JNIEXPORT void JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeInit(JNIEnv* env, jclass /*clazz*/,
                                                        jstring filesDir, jstring gameDataPath,
                                                        jstring configJson) {
    ob::GetCpuFeatures(); // primes runtime SIMD dispatch
    std::lock_guard<std::mutex> lock(g_app.mutex);
    g_app.filesDir = ToStdString(env, filesDir);
    g_app.gameDataPath = ToStdString(env, gameDataPath);
    const std::string json = ToStdString(env, configJson);
    if (!json.empty()) ob::EngineConfig::Parse(json, &g_app.config);
    g_app.config.Sanitize();
    if (!g_app.started) {
        g_app.started = true;
        g_app.exitRequested = false;
        g_app.thread = std::thread(RenderThreadMain);
        LOGI("Native core initialised (filesDir=%s)", g_app.filesDir.c_str());
    }
    g_app.configDirty = true;
    g_app.cv.notify_all();
}

JNIEXPORT void JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeShutdown(JNIEnv* /*env*/, jclass /*clazz*/) {
    std::thread t;
    {
        std::lock_guard<std::mutex> lock(g_app.mutex);
        if (!g_app.started) return;
        g_app.exitRequested = true;
        g_app.started = false;
        t = std::move(g_app.thread);
        g_app.cv.notify_all();
    }
    if (t.joinable()) t.join();
    std::lock_guard<std::mutex> lock(g_app.mutex);
    if (g_app.pendingWindow != nullptr) {
        ANativeWindow_release(g_app.pendingWindow);
        g_app.pendingWindow = nullptr;
    }
    g_app.input.Reset();
    LOGI("Native core shut down");
}

JNIEXPORT void JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeSetSurface(JNIEnv* env, jclass /*clazz*/, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    std::lock_guard<std::mutex> lock(g_app.mutex);
    if (g_app.pendingWindow != nullptr) ANativeWindow_release(g_app.pendingWindow);
    g_app.pendingWindow = window;
    g_app.windowDirty = true;
    g_app.cv.notify_all();
}

JNIEXPORT void JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeClearSurface(JNIEnv* /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_app.mutex);
    if (g_app.pendingWindow != nullptr) {
        ANativeWindow_release(g_app.pendingWindow);
        g_app.pendingWindow = nullptr;
    }
    g_app.windowDirty = true; // render thread releases its current window too
    g_app.cv.notify_all();
}

JNIEXPORT void JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeSetResumed(JNIEnv* /*env*/, jclass /*clazz*/, jboolean resumed) {
    std::lock_guard<std::mutex> lock(g_app.mutex);
    g_app.resumed = resumed == JNI_TRUE;
    if (!g_app.resumed) g_app.input.Reset(); // no stuck buttons after pause
    g_app.cv.notify_all();
}

JNIEXPORT void JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeApplyConfig(JNIEnv* env, jclass /*clazz*/, jstring configJson) {
    const std::string json = ToStdString(env, configJson);
    std::lock_guard<std::mutex> lock(g_app.mutex);
    if (!json.empty()) ob::EngineConfig::Parse(json, &g_app.config);
    g_app.config.Sanitize();
    g_app.configDirty = true;
    g_app.cv.notify_all();
}

JNIEXPORT void JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeSetButton(JNIEnv* /*env*/, jclass /*clazz*/,
                                                             jint command, jboolean down) {
    g_app.input.SetButton(command, down == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeSetStick(JNIEnv* /*env*/, jclass /*clazz*/, jfloat x, jfloat y) {
    g_app.input.SetStick(x, y);
}

JNIEXPORT void JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeAddCameraDelta(JNIEnv* /*env*/, jclass /*clazz*/, jfloat dx, jfloat dy) {
    g_app.input.AddCameraDelta(dx, dy);
}

JNIEXPORT jfloat JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeGetFps(JNIEnv* /*env*/, jclass /*clazz*/) {
    return g_app.fps.load(std::memory_order_relaxed);
}

JNIEXPORT jfloat JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeGetCurrentScale(JNIEnv* /*env*/, jclass /*clazz*/) {
    return g_app.currentScale.load(std::memory_order_relaxed);
}

JNIEXPORT jstring JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeGetDiagnostics(JNIEnv* env, jclass /*clazz*/) {
    const ob::CpuFeatures& cpu = ob::GetCpuFeatures();
    std::string gl, cfgSummary, dataPath;
    {
        std::lock_guard<std::mutex> lock(g_app.mutex);
        gl = g_app.glInfo;
        dataPath = g_app.gameDataPath;
        char buf[256];
        std::snprintf(buf, sizeof(buf),
                      "preset=%s scale=%.2f (dyn=%s, min=%.2f) fpsLimit=%d msaa=%d texQ=%d shadowQ=%d",
                      g_app.config.preset.c_str(), g_app.config.resolutionScale,
                      g_app.config.dynamicResolution ? "on" : "off",
                      g_app.config.minResolutionScale, g_app.config.fpsLimit,
                      g_app.config.msaa, g_app.config.textureQuality, g_app.config.shadowQuality);
        cfgSummary = buf;
    }
    std::string out;
    out += "CPU: " + ob::DescribeCpu(cpu) + "\n";
    out += ob::BenchmarkMat4() + "\n";
    out += "GL: " + (gl.empty() ? std::string("not initialised yet (open the game screen once)") : gl) + "\n";
    out += "Config: " + cfgSummary + "\n";
    out += "Game data: " + (dataPath.empty() ? std::string("(not set)") : dataPath) + "\n";
    out += "Live: fps=";
    char fpsBuf[64];
    std::snprintf(fpsBuf, sizeof(fpsBuf), "%.1f, renderScale=%.2f",
                  g_app.fps.load(std::memory_order_relaxed),
                  g_app.currentScale.load(std::memory_order_relaxed));
    out += fpsBuf;
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_opentoshi_openbarnyard_NativeBridge_nativeGetCpuSummary(JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(ob::DescribeCpu(ob::GetCpuFeatures()).c_str());
}

} // extern "C"
