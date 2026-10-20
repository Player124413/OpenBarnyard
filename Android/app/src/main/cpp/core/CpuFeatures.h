#pragma once
// OpenBarnyard Android — runtime CPU feature detection used for the "mega
// optimisation for every processor" story: the same .so ships a portable
// baseline build per ABI, and hot paths dispatch at runtime to NEON /
// AVX2 implementations when the silicon supports them. Nothing here assumes
// features the ABI baseline does not guarantee.
#include <cstdint>
#include <string>

namespace ob {

struct CpuFeatures {
    bool is64Bit   = false;
    bool hasNeon   = false;  // ARM Advanced SIMD
    bool hasSve    = false;  // ARM SVE (informational)
    bool hasSse42  = false;  // x86
    bool hasAvx2   = false;  // x86
    int  coreCount = 1;
    const char* abi = "unknown";
};

// Detected once (function-local static) and cached.
const CpuFeatures& GetCpuFeatures();

// One-line human readable summary for the launcher diagnostics screen,
// e.g. "arm64-v8a · 8 cores · NEON · 64-bit".
std::string DescribeCpu(const CpuFeatures& f);

// Micro-benchmark: multiplies 4x4 matrices with the scalar and the best
// SIMD path and returns "scalar=Xns simd=Yns speedup=Z" for diagnostics.
// Runs ~a few ms; safe to call from a background thread.
std::string BenchmarkMat4();

} // namespace ob
