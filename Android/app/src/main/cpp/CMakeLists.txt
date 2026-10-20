cmake_minimum_required(VERSION 3.22)
project(openbarnyard LANGUAGES CXX)

# Native core for the Android port. CI builds via Android/build.py;
# this file mirrors its flags for Android Studio users.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

add_library(openbarnyard SHARED
    core/Config.cpp
    core/CpuFeatures.cpp
    core/FramePacer.cpp
    gl/Renderer.cpp
    jni_bridge.cpp
)

target_include_directories(openbarnyard PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(openbarnyard PRIVATE
    -O3 -DNDEBUG
    -fvisibility=hidden -fvisibility-inlines-hidden
    -ffunction-sections -fdata-sections
    -ffp-contract=fast -fno-math-errno
    -Wall -Wextra
)

# --- per-ABI tuning (keep in sync with Android/build.py ABIS) -------------
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(openbarnyard PRIVATE -march=armv8-a)
    target_link_options(openbarnyard PRIVATE -Wl,-z,max-page-size=16384) # 16 KiB pages (Android 15+)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(openbarnyard PRIVATE
        -march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d32 -mthumb)
    # The dispatch TU gets NEON intrinsics only (no autovectorisation), so
    # baseline non-NEON devices stay safe; see core/CpuFeatures.cpp.
    set_source_files_properties(core/CpuFeatures.cpp PROPERTIES
        COMPILE_OPTIONS "-mfpu=neon-vfpv4;-fno-vectorize;-fno-slp-vectorize")
elseif(ANDROID_ABI STREQUAL "x86_64")
    target_compile_options(openbarnyard PRIVATE -march=x86-64 -msse4.2 -mpopcnt)
    target_link_options(openbarnyard PRIVATE -Wl,-z,max-page-size=16384)
elseif(ANDROID_ABI STREQUAL "x86")
    target_compile_options(openbarnyard PRIVATE -march=i686 -msse3 -mfpmath=sse)
endif()

option(OB_LTO "Thin LTO" ON)
if(OB_LTO)
    target_compile_options(openbarnyard PRIVATE -flto=thin)
    target_link_options(openbarnyard PRIVATE -flto=thin)
endif()

target_link_options(openbarnyard PRIVATE
    -Wl,--gc-sections -Wl,-z,noexecstack -Wl,-z,relro,-z,now -Wl,--exclude-libs,ALL)

# -static-libstdc++ equivalent: AGP passes -DANDROID_STL=c++_static via
# arguments in build.gradle, so no libc++_shared.so needs shipping.
target_link_libraries(openbarnyard PRIVATE EGL GLESv2 android log dl m)
