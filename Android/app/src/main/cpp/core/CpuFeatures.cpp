#include "CpuFeatures.h"
#include "Math3d.h"

#include <chrono>
#include <cstdio>
#include <thread>

#if defined(__ANDROID__) || defined(__linux__)
#  include <sys/auxv.h>
#  include <unistd.h>
#endif
#if (defined(__aarch64__) || defined(__arm__)) && (defined(__ANDROID__) || defined(__linux__))
#  if __has_include(<asm/hwcap.h>)
#    include <asm/hwcap.h>
#  endif
   // Fallbacks (values from the kernel UAPI) if the NDK header is absent.
#  if defined(__aarch64__) && !defined(HWCAP_SVE)
#    define HWCAP_SVE (1 << 22)
#  endif
#  if defined(__arm__) && !defined(HWCAP_NEON)
#    define HWCAP_NEON (1 << 12)
#  endif
#endif
#if defined(__x86_64__) || defined(__i386__)
#  include <cpuid.h>
#endif

namespace ob {

Mat4MultiplyFn g_mat4Multiply = &MultiplyScalar;

namespace {

CpuFeatures Detect() {
    CpuFeatures f;
#if defined(__aarch64__)
    f.abi = "arm64-v8a";
    f.is64Bit = true;
    f.hasNeon = true; // Advanced SIMD is mandatory on AArch64 Android
#  if defined(HWCAP_SVE) && (defined(__ANDROID__) || defined(__linux__))
    f.hasSve = (getauxval(AT_HWCAP) & HWCAP_SVE) != 0;
#  endif
#elif defined(__arm__)
    f.abi = "armeabi-v7a";
    f.is64Bit = false;
    // IMPORTANT for 32-bit support: armeabi-v7a does NOT guarantee NEON
    // (ancient Tegra 2 etc.), so it must be probed, never assumed.
#  if defined(HWCAP_NEON) && (defined(__ANDROID__) || defined(__linux__))
    f.hasNeon = (getauxval(AT_HWCAP) & HWCAP_NEON) != 0;
#  elif OB_HAS_NEON_COMPILE
    f.hasNeon = true; // compiled with NEON enabled for this translation unit set
#  endif
#elif defined(__x86_64__)
    f.abi = "x86_64";
    f.is64Bit = true;
#elif defined(__i386__)
    f.abi = "x86";
    f.is64Bit = false;
#endif

#if defined(__x86_64__) || defined(__i386__)
    unsigned int eax, ebx, ecx, edx;
    if (__get_cpuid(1, &eax, &ebx, &ecx, &edx)) {
        f.hasSse42 = (ecx & bit_SSE4_2) != 0;
    }
    if (__get_cpuid_count(7, 0, &eax, &ebx, &ecx, &edx)) {
        f.hasAvx2 = (ebx & bit_AVX2) != 0;
    }
#endif

    const unsigned hc = std::thread::hardware_concurrency();
    f.coreCount = hc > 0 ? static_cast<int>(hc) : 1;

    // Runtime dispatch for hot math.
#if OB_HAS_NEON_COMPILE
    if (f.hasNeon) g_mat4Multiply = &MultiplyNeon;
#endif
    return f;
}

} // namespace

const CpuFeatures& GetCpuFeatures() {
    static const CpuFeatures s_features = Detect();
    return s_features;
}

std::string DescribeCpu(const CpuFeatures& f) {
    char buf[192];
    std::snprintf(buf, sizeof(buf), "%s · %d cores · %s-bit%s%s%s%s",
                  f.abi, f.coreCount, f.is64Bit ? "64" : "32",
                  f.hasNeon ? " · NEON" : "",
                  f.hasSve ? " · SVE" : "",
                  f.hasSse42 ? " · SSE4.2" : "",
                  f.hasAvx2 ? " · AVX2" : "");
    return std::string(buf);
}

std::string BenchmarkMat4() {
    using Clock = std::chrono::steady_clock;
    const Mat4 a = Mat4::Perspective(1.1f, 1.7f, 0.1f, 500.0f);
    Mat4 b = Mat4::LookAt({3, 4, 5}, {0, 1, 0}, {0, 1, 0});
    constexpr int kIters = 200000;
    volatile float sink = 0.0f;

    const auto t0 = Clock::now();
    for (int i = 0; i < kIters; ++i) { b = MultiplyScalar(a, b); }
    const auto t1 = Clock::now();
    sink += b.m[0];

    Mat4 c = Mat4::LookAt({3, 4, 5}, {0, 1, 0}, {0, 1, 0});
    const auto t2 = Clock::now();
    for (int i = 0; i < kIters; ++i) { c = g_mat4Multiply(a, c); }
    const auto t3 = Clock::now();
    sink += c.m[1];

    const double scalarNs = std::chrono::duration<double, std::nano>(t1 - t0).count() / kIters;
    const double simdNs   = std::chrono::duration<double, std::nano>(t3 - t2).count() / kIters;
    char buf[160];
    std::snprintf(buf, sizeof(buf), "Mat4 mul: scalar %.1f ns, dispatched %.1f ns (x%.2f)",
                  scalarNs, simdNs, simdNs > 0.0 ? scalarNs / simdNs : 0.0);
    return std::string(buf);
}

} // namespace ob
