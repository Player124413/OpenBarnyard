# Porting TOSHI 2.0 / OpenBarnyard to Android — status & roadmap

This document is the honest engineering ledger for the Android port: what is
done, what remains, and exactly where each remaining piece lives in the tree.
Numbers below were measured on this repository (October 2026 snapshot).

## The codebase at a glance

| Area | Size | Notes |
|---|---|---|
| `Toshi/Source` (engine) | 262 `.cpp` | Mostly portable C++20 already |
| `OpenBarnyard/Source` (game) | 789 `.cpp` | Game logic; platform code is isolated |
| MSVC-isms (`__forceinline`, `__assume`, `__cdecl`, `__declspec`, …) | 18 sites | Surprisingly few — good news |
| Files including `Windows.h`/D3D8 headers | 16 | Concentrated in the Win platform layer |
| `Toshi/Source/Platform/Windows` | 20 files | Needs an Android/POSIX sibling |
| `OpenBarnyard/Source/Platform` | 23 `.cpp`, **DX8 + Windows only** | No GL game shaders exist upstream yet |

## ✅ Done in this Android port (the `Android/` tree)

Everything an Android game needs *around* the engine:

- **Launcher** (Java, framework-only, zero permissions): device detection
  (SoC/ABI/RAM/GLES/refresh rate), Auto quality preset, game-data folder
  management with a heuristic verifier for real Barnyard installs
  (`.trb`/`.ttl`/`.tkl` census, nested-install detection), diagnostics report.
- **Configuration contract**: launcher writes `openbarnyard.json`; the native
  core parses it (`Android/app/src/main/cpp/core/Config.cpp`). Keys are synced
  by an automated check (`Android/tools/check_sync.py`).
- **Touch controls**: normalised-coordinate profiles (JSON, shareable),
  WYSIWYG editor (drag/resize/opacity/rebind/duplicate, grid snap, floating
  stick, left-handed mirror), live multi-touch overlay with dead-zone and
  haptics. Buttons bind to the engine's own `AInputCommand` ids from
  `OpenBarnyard/Source/Input/AInputMap.h` — verified automatically against
  the header, so editor bindings will drive the real game logic unchanged.
- **Native core** (`libopenbarnyard.so`): EGL (ES3→ES2 fallback), dedicated
  render thread with a clean surface-lifecycle state machine, dynamic
  resolution with hysteresis, FPS pacing, runtime CPU feature detection with
  NEON dispatch for hot math, per-ABI tuned builds (arm64-v8a / armeabi-v7a
  / x86_64 / x86), thin LTO, 16 KiB page alignment. Renders an interactive
  farm tech-demo scene exercising all of it (~12 draw calls/frame).
- **Build & CI**: dependency-free `Android/build.py` (SDK+NDK+JDK only),
  APK structure/ELF verifier, host C++ and JVM unit tests, cross-language
  contract checks, GitHub Actions workflow incl. emulator smoke test with
  screenshots. Gradle/CMake mirrors for Android Studio.

## 🚧 Remaining for full gameplay (in dependency order)

### 1. `TOSHI_SKU_ANDROID` platform layer for the engine — days of work

Mirror `Toshi/Source/Platform/Windows/` with POSIX/Android implementations:

| Windows file | Android approach |
|---|---|
| `Platform_Win.cpp` (paths, UTF) | `std::filesystem`/iconv-free UTF-8 helpers (game data is ANSI/UTF-8) |
| `TThread_Win`, `T2Atomic_Win` | `USE_ATOMIC` path already exists; threads via `std::thread`/pthreads |
| `TMemory_Win`, `TMemoryDL_Win` | `mmap`/`malloc`; `dlmalloc` sources are already in-tree (`Core/dlmalloc.cpp`) with a premake switch |
| `TNativeFile_Win`, `TFileManager_Win` | `FILE*` + `AAssetManager`/SAF file descriptors for the game-data path chosen in the launcher |
| `THPTimer_Win` | `clock_gettime(CLOCK_MONOTONIC)` |
| `TDebug_Win`, `TDebugConsole_Win`, `TUtil_Win` | `__android_log_print` (launcher Diagnostics already surfaces the log) |
| `TSystemDLL_Win` | `dlopen` or stubbed (plugins are optional on Android) |
| CRT section tricks (`.CRT$XI*` memory init in `AppBoot.cpp`) | `__attribute__((constructor))` priorities or explicit init in `JNI_OnLoad` |

### 2. Game GL shaders — the big one — weeks of work

The engine has a GL backend in `Toshi/Source/Platform/GL/` and an SDL window
in `Platform/SDL/`, and premake already knows a `--renderer=GL` mode — but
**the game's material shaders exist only as DX8** under
`OpenBarnyard/Source/Platform/DX8/` (Sys, Skin, World, StaticInstance, Grass,
FX, Line, GUI2Renderer — 23 `.cpp`). Each needs a `Platform/GL/` (GLES2/3)
counterpart, then the premake GL filter (which already references the
not-yet-existing `Source/Platform/GL/**` and `Source/Platform/SDL/**` game
files) starts working. On Android, SDL2 can provide the window/input glue,
or the existing `ANativeWindow` plumbing in this port can be reused.
Barnyard's fixed-function-era materials map well onto small GLSL programs;
the tech-demo renderer in `Android/app/src/main/cpp/gl/Renderer.cpp` is a
working reference for the FBO/dynamic-resolution composition around them.

### 3. Sound — FMOD is proprietary

`Content/` ships `fmod.dll`/`fmod64.dll` for Windows only. Options, in order
of fidelity: (a) FMOD's official Android `.so` (free for indies under their
licence; the game's banks would need version-compat checking), (b) reimplement
the game's `Sound/` layer on OpenAL-soft or Oboe/AAudio with a custom
FSB/ADPCM decoder. The launcher already treats audio as data-driven and the
native core leaves the audio thread story to this step.

### 4. Movies — Bink is proprietary

`OpenBarnyard/Source/Movie/ABINKMoviePlayer` uses `binkw32`. The PC `.bik`
files can't legally ship a decoder keyed to RAD's SDK here; the pragmatic
path is transcoding prompts (Theora/WebM) or linking a user-supplied decoder.
`Dependencies.lua` already names (absent) vendor dirs for libogg/
libvorbis/libtheora/theoraplay, which is the intended direction. Until then,
movies should be skippable (the input layer already delivers Cancel/OK).

### 5. 64-bit cleanliness audit

The decompilation faithfully preserves a 32-bit codebase: headers annotate
32-bit offsets (e.g. `InputEvent` fields at `0x0C`), and asset chunks
(`.trb`) were authored for a 32-bit little-endian runtime. An `arm64-v8a`
engine build needs a careful pass over raw struct serialisation in
`TFile`/TRB loading and any pointer-sized `TINT` assumptions. The 32-bit
`armeabi-v7a` build sidesteps most of this and is the recommended first
target for the full engine — one more reason this port ships a first-class
32-bit build rather than treating it as an afterthought.

### Touch → engine wiring (ready when step 1 lands)

`Android/app/src/main/cpp/core/InputState.h` already models buttons as a
bitmask keyed by `AInputCommand`. The bridge into the engine is a
`TInputDeviceTouch` implementing `TInputDevice` (see
`Toshi/Source/Input/TInputInterface.h`: `IsDown`, `GetAxisFloat`,
`GetButtonFromDoodad`, `ProcessEvents`) whose doodads map 1:1 to those ids,
registered with `TInputInterface::AddDevice`. The JNI surface
(`nativeSetButton/nativeSetStick/nativeAddCameraDelta`) stays as-is.

## What is *not* planned

- Shipping any game assets (legal: bring your own copy, always).
- Touch controls emulating a mouse pointer as the primary scheme — the
  command-based scheme above is better and already built.
- A separate "lite" codebase — everything above feeds back into the same
  tree so PC and Android share game logic forever.
