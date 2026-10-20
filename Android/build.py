#!/usr/bin/env python3
"""
OpenBarnyard Android build — dependency-free APK builder.

Why not Gradle? This script needs only the Android SDK (aapt2/d8/zipalign/
apksigner), an NDK and a JDK. No Maven/Google downloads, no Gradle daemon,
no version-matrix surprises: the same commands run on a developer machine
and in CI (.github/workflows/Android.yaml). A Gradle project is also shipped
for Android Studio users (Android/build.gradle), but this script is the
reference, CI-tested path.

Outputs (Android/dist/):
  OpenBarnyard-v<ver>-universal.apk     all ABIs in one APK
  OpenBarnyard-v<ver>-arm64-v8a.apk     64-bit ARM phones/tablets
  OpenBarnyard-v<ver>-armeabi-v7a.apk   32-bit ARM phones
  OpenBarnyard-v<ver>-x86_64.apk        64-bit x86 (emulators, ChromeOS)
  OpenBarnyard-v<ver>-x86.apk           32-bit x86
  OpenBarnyard-v<ver>-symbols.zip       unstripped .so per ABI (crash symbolication)
  SHA256SUMS.txt

Usage:
  python3 Android/build.py              build APKs
  python3 Android/build.py --test       run JVM unit tests only
  python3 Android/build.py --abis arm64-v8a,x86_64
Env:
  Android_SDK_ROOT / Android_HOME, Android_NDK_HOME / NDK_HOME, JAVA_HOME
  OB_KEYSTORE, OB_KEYSTORE_PASS, OB_KEY_ALIAS, OB_KEY_PASS  release signing
  OB_NO_LTO=1                           disable thin LTO
"""
import argparse
import hashlib
import os
import platform
import re
import shutil
import subprocess
import sys
import zipfile

VERSION_NAME = "0.1.0"
VERSION_CODE = 1          # per-variant code = VERSION_CODE*10 + abi_code
MIN_SDK = 24              # Android 7.0 — wide 32-bit device coverage
TARGET_SDK = 34
PACKAGE = "org.opentoshi.openbarnyard"

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "app")
SRC_MAIN = os.path.join(APP, "src", "main")
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")

# Per-ABI native build profiles: baseline stays compatible with the oldest
# silicon of that ABI; hot paths dispatch at runtime (core/CpuFeatures.cpp).
ABIS = {
    "arm64-v8a": {
        "triple": "aarch64-linux-Android",
        "flags": ["-march=armv8-a"],
        "page16k": True,
        "code": 4,
    },
    "armeabi-v7a": {
        "triple": "armv7a-linux-androideabi",
        # Baseline VFPv3 (no NEON assumption -> runs even on Tegra-2 era SoCs).
        "flags": ["-march=armv7-a", "-mfloat-abi=softfp", "-mfpu=vfpv3-d32", "-mthumb"],
        # ...but the NEON matrix path lives in CpuFeatures.cpp, compiled with
        # NEON enabled + autovectorisation off (so the only NEON instructions
        # are the intrinsics we explicitly dispatch to after HWCAP probing).
        "per_file": {"core/CpuFeatures.cpp": ["-mfpu=neon-vfpv4", "-fno-vectorize", "-fno-slp-vectorize"]},
        "page16k": False,
        "code": 3,
    },
    "x86_64": {
        "triple": "x86_64-linux-Android",
        "flags": ["-march=x86-64", "-msse4.2", "-mpopcnt"],
        "page16k": True,
        "code": 2,
    },
    "x86": {
        "triple": "i686-linux-Android",
        "flags": ["-march=i686", "-msse3", "-mfpmath=sse"],
        "page16k": False,
        "code": 1,
    },
}

CPP_SOURCES = [
    "core/Config.cpp",
    "core/CpuFeatures.cpp",
    "core/FramePacer.cpp",
    "gl/Renderer.cpp",
    "jni_bridge.cpp",
]


def log(msg):
    print("[build] " + msg, flush=True)


def die(msg):
    print("[build] ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def run(cmd, env=None, cwd=None):
    log("$ " + " ".join(str(c) for c in cmd))
    res = subprocess.run([str(c) for c in cmd], env=env, cwd=cwd)
    if res.returncode != 0:
        die("command failed with exit code %d: %s" % (res.returncode, cmd[0]))


def run_try(cmd, env=None):
    log("$ " + " ".join(str(c) for c in cmd))
    return subprocess.run([str(c) for c in cmd], env=env).returncode == 0


def version_key(name):
    parts = re.findall(r"\d+", name)
    return [int(p) for p in parts] if parts else [0]


def find_sdk():
    for env in ("Android_SDK_ROOT", "Android_HOME"):
        if os.environ.get(env) and os.path.isdir(os.environ[env]):
            return os.environ[env]
    home = os.path.expanduser("~")
    candidates = [
        os.path.join(home, "Android", "Sdk"),
        "/usr/local/lib/Android/sdk",
        os.path.join(home, "Library", "Android", "sdk"),
    ]
    for c in candidates:
        if os.path.isdir(c):
            return c
    die("Android SDK not found. Set Android_SDK_ROOT.")


def find_ndk(sdk):
    for env in ("Android_NDK_HOME", "NDK_HOME", "Android_NDK_ROOT"):
        if os.environ.get(env) and os.path.isdir(os.environ[env]):
            return os.environ[env]
    ndk_dir = os.path.join(sdk, "ndk")
    if os.path.isdir(ndk_dir):
        versions = sorted(os.listdir(ndk_dir), key=version_key)
        if versions:
            # Prefer the CI-tested NDK, then the newest of each known-good
            # major line, then anything. (Newer NDKs keep changing target
            # support; our flags were validated against r27 LTS.)
            for wanted in ("27.2.12479018",):
                if wanted in versions:
                    return os.path.join(ndk_dir, wanted)
            for major in ("27.", "26.", "25.", "24.", "23."):
                hits = [v for v in versions if v.startswith(major)]
                if hits:
                    return os.path.join(ndk_dir, hits[-1])
            return os.path.join(ndk_dir, versions[-1])
    bundle = os.path.join(sdk, "ndk-bundle")
    if os.path.isdir(bundle):
        return bundle
    die("Android NDK not found under SDK. Install with sdkmanager 'ndk;27.2.12479018'.")


def find_build_tools(sdk):
    bt = os.path.join(sdk, "build-tools")
    if not os.path.isdir(bt):
        die("build-tools not found in SDK")
    versions = sorted(os.listdir(bt), key=version_key)
    if not versions:
        die("no build-tools versions installed")
    return os.path.join(bt, versions[-1])


def find_android_jar(sdk):
    platforms = os.path.join(sdk, "platforms")
    candidates = []
    if os.path.isdir(platforms):
        for name in os.listdir(platforms):
            jar = os.path.join(platforms, name, "Android.jar")
            if os.path.isfile(jar):
                candidates.append((version_key(name), jar))
    if not candidates:
        die("no Android platform (Android.jar) found in SDK")
    candidates.sort()
    # Prefer TARGET_SDK exactly, else take the newest available.
    for key, jar in candidates:
        if key and key[0] == TARGET_SDK:
            return jar
    return candidates[-1][1]


def java_tool(name, java_home):
    if java_home:
        exe = os.path.join(java_home, "bin", name)
        if os.path.isfile(exe) or os.path.isfile(exe + ".exe"):
            return exe
    found = shutil.which(name)
    if found:
        return found
    die("Java tool '%s' not found. Install JDK 17." % name)


def find_java_home():
    if os.environ.get("JAVA_HOME") and os.path.isdir(os.environ["JAVA_HOME"]):
        return os.environ["JAVA_HOME"]
    javac = shutil.which("javac")
    if javac:
        return os.path.dirname(os.path.dirname(os.path.realpath(javac)))
    return None


def collect_files(base, suffix):
    out = []
    for dirpath, _dirnames, filenames in os.walk(base):
        for f in filenames:
            if f.endswith(suffix):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def compile_native(ndk, abi, cfg, out_dir):
    host = platform.system().lower()
    host_tag = {"linux": "linux-x86_64", "darwin": "darwin-x86_64", "windows": "windows-x86_64"}.get(host)
    if not host_tag:
        die("unsupported host OS: " + host)
    bin_dir = os.path.join(ndk, "toolchains", "llvm", "prebuilt", host_tag, "bin")
    clangxx = os.path.join(bin_dir, "%s%d-clang++" % (cfg["triple"], MIN_SDK))
    if host == "windows":
        clangxx += ".cmd"
    if not os.path.isfile(clangxx):
        die("NDK compiler not found: " + clangxx)
    strip = os.path.join(bin_dir, "llvm-strip")

    use_lto = not os.environ.get("OB_NO_LTO")
    common = [
        "-std=c++17", "-O3", "-DNDEBUG", "-fPIC",
        "-fvisibility=hidden", "-fvisibility-inlines-hidden",
        "-ffunction-sections", "-fdata-sections",
        "-ffp-contract=fast", "-fno-math-errno",
        "-Wall", "-Wextra",
        "-I", os.path.join(SRC_MAIN, "cpp"),
    ] + cfg["flags"]
    if use_lto:
        common.append("-flto=thin")

    obj_dir = os.path.join(out_dir, "obj")
    os.makedirs(obj_dir, exist_ok=True)
    objects = []
    per_file = cfg.get("per_file", {})
    for rel in CPP_SOURCES:
        src = os.path.join(SRC_MAIN, "cpp", rel)
        obj = os.path.join(obj_dir, rel.replace("/", "_") + ".o")
        flags = list(common)
        if rel in per_file:
            # Strip a conflicting baseline -mfpu so the override wins cleanly.
            flags = [f for f in flags if not f.startswith("-mfpu=")]
            flags += per_file[rel]
        run([clangxx] + flags + ["-c", src, "-o", obj])
        objects.append(obj)

    so_path = os.path.join(out_dir, "libopenbarnyard.so")
    link = [
        clangxx, "-shared", "-static-libstdc++",
        "-Wl,--gc-sections", "-Wl,-z,noexecstack", "-Wl,-z,relro,-z,now",
        "-Wl,--exclude-libs,ALL",
    ]
    if cfg.get("page16k"):
        # Android 15+ 64-bit devices may use 16 KiB pages.
        link.append("-Wl,-z,max-page-size=16384")
    if use_lto:
        link.append("-flto=thin")
    link += objects + ["-lEGL", "-lGLESv2", "-lAndroid", "-llog", "-ldl", "-lm", "-o", so_path]
    run(link)

    symbols_dir = os.path.join(BUILD, "symbols", abi)
    os.makedirs(symbols_dir, exist_ok=True)
    shutil.copy2(so_path, os.path.join(symbols_dir, "libopenbarnyard.so"))
    if os.path.isfile(strip):
        run([strip, so_path])
    log("built %s (%d bytes)" % (so_path, os.path.getsize(so_path)))
    return so_path


def compile_java(javac, Android_jar, sources, out_dir, env):
    os.makedirs(out_dir, exist_ok=True)
    list_file = os.path.join(BUILD, "sources.txt")
    with open(list_file, "w") as f:
        for s in sources:
            f.write('"' + s.replace("\\", "/") + '"\n')
    attempts = [
        [javac, "-encoding", "UTF-8", "--release", "8", "-cp", Android_jar, "-d", out_dir, "@" + list_file],
        [javac, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-bootclasspath", Android_jar, "-cp", Android_jar, "-d", out_dir, "@" + list_file],
        [javac, "-encoding", "UTF-8", "-source", "11", "-target", "11", "-cp", Android_jar, "-d", out_dir, "@" + list_file],
    ]
    for cmd in attempts:
        if run_try(cmd, env=env):
            return
        log("javac strategy failed, trying next…")
        shutil.rmtree(out_dir, ignore_errors=True)
        os.makedirs(out_dir, exist_ok=True)
    die("javac failed with all strategies")


def make_keystore(env, java_home):
    ks = os.environ.get("OB_KEYSTORE")
    if ks:
        return (ks, os.environ.get("OB_KEYSTORE_PASS", ""),
                os.environ.get("OB_KEY_ALIAS", ""), os.environ.get("OB_KEY_PASS", ""))
    ks = os.path.join(BUILD, "debug.keystore")
    if not os.path.isfile(ks):
        keytool = java_tool("keytool", java_home)
        run([keytool, "-genkeypair", "-keystore", ks, "-storepass", "Android",
             "-alias", "Androiddebugkey", "-keypass", "Android", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=Android Debug,O=Android,C=US"], env=env)
    return (ks, "Android", "Androiddebugkey", "Android")


def package_apk(variant, abi_list, native_libs, dex_dir, tools, Android_jar, env, keystore):
    aapt2, d8, zipalign, apksigner = tools
    work = os.path.join(BUILD, "apk-" + variant)
    os.makedirs(work, exist_ok=True)
    unsigned = os.path.join(work, "unsigned.apk")
    abi_code = ABIS[abi_list[0]]["code"] if len(abi_list) == 1 else 0
    vc = VERSION_CODE * 10 + abi_code

    gen_dir = os.path.join(BUILD, "gen")
    cmd = [aapt2, "link", "-o", unsigned, "-I", Android_jar,
           "--manifest", os.path.join(SRC_MAIN, "AndroidManifest.xml"),
           "--java", gen_dir, "--min-sdk-version", str(MIN_SDK),
           "--target-sdk-version", str(TARGET_SDK),
           "--version-code", str(vc), "--version-name", VERSION_NAME,
           os.path.join(BUILD, "res.zip")]
    run(cmd, env=env)

    merged = os.path.join(work, "merged.apk")
    shutil.copy2(unsigned, merged)
    stamp = (1980, 1, 1, 0, 0, 0)  # reproducible zips
    with zipfile.ZipFile(merged, "a", compression=zipfile.ZIP_DEFLATED) as z:
        for dex in collect_files(dex_dir, ".dex"):
            info = zipfile.ZipInfo(os.path.basename(dex), date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, open(dex, "rb").read())
        for abi in abi_list:
            info = zipfile.ZipInfo("lib/%s/libopenbarnyard.so" % abi, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, open(native_libs[abi], "rb").read())

    aligned = os.path.join(work, "aligned.apk")
    run([zipalign, "-f", "-p", "4", merged, aligned], env=env)

    os.makedirs(DIST, exist_ok=True)
    final = os.path.join(DIST, "OpenBarnyard-v%s-%s.apk" % (VERSION_NAME, variant))
    ks, ks_pass, alias, key_pass = keystore
    sign = [apksigner, "sign", "--ks", ks, "--ks-pass", "pass:" + ks_pass,
            "--out", final]
    if alias:
        sign += ["--ks-key-alias", alias]
    if key_pass:
        sign += ["--key-pass", "pass:" + key_pass]
    sign.append(aligned)
    run(sign, env=env)
    run([apksigner, "verify", final], env=env)
    log("APK: %s (%d bytes, versionCode %d)" % (final, os.path.getsize(final), vc))
    return final


def build(abis, clean=True):
    sdk = find_sdk()
    ndk = find_ndk(sdk)
    bt = find_build_tools(sdk)
    Android_jar = find_android_jar(sdk)
    java_home = find_java_home()
    log("SDK: %s\n[build] NDK: %s\n[build] build-tools: %s\n[build] platform: %s\n[build] JDK: %s"
        % (sdk, ndk, bt, Android_jar, java_home))

    env = os.environ.copy()
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")

    suffix = ".bat" if platform.system() == "Windows" else ""
    aapt2 = os.path.join(bt, "aapt2" + (".exe" if suffix else ""))
    d8 = os.path.join(bt, "d8" + suffix)
    zipalign = os.path.join(bt, "zipalign" + (".exe" if suffix else ""))
    apksigner = os.path.join(bt, "apksigner" + suffix)
    for tool in (aapt2, d8, zipalign, apksigner):
        if not os.path.isfile(tool):
            die("build tool not found: " + tool)

    if clean:
        shutil.rmtree(BUILD, ignore_errors=True)
        shutil.rmtree(DIST, ignore_errors=True)
    os.makedirs(BUILD, exist_ok=True)

    # 1) Resources
    run([aapt2, "compile", "--dir", os.path.join(SRC_MAIN, "res"),
         "-o", os.path.join(BUILD, "res.zip")], env=env)

    # 2) Generate R.java via a throwaway link, then compile Java once.
    gen_dir = os.path.join(BUILD, "gen")
    run([aapt2, "link", "-o", os.path.join(BUILD, "rgen.apk"), "-I", Android_jar,
         "--manifest", os.path.join(SRC_MAIN, "AndroidManifest.xml"),
         "--java", gen_dir, "--min-sdk-version", str(MIN_SDK),
         "--target-sdk-version", str(TARGET_SDK),
         "--version-code", str(VERSION_CODE * 10), "--version-name", VERSION_NAME,
         os.path.join(BUILD, "res.zip")], env=env)
    r_java = os.path.join(gen_dir, *PACKAGE.split("."), "R.java")
    if not os.path.isfile(r_java):
        die("R.java was not generated at " + r_java)

    javac = java_tool("javac", java_home)
    classes_dir = os.path.join(BUILD, "classes")
    sources = collect_files(os.path.join(SRC_MAIN, "java"), ".java") + [r_java]
    compile_java(javac, Android_jar, sources, classes_dir, env)

    # 3) Dex
    dex_dir = os.path.join(BUILD, "dex")
    os.makedirs(dex_dir, exist_ok=True)
    class_files = collect_files(classes_dir, ".class")
    if not class_files:
        die("javac produced no .class files")
    run([d8, "--release", "--min-api", str(MIN_SDK), "--lib", Android_jar,
         "--output", dex_dir] + class_files, env=env)

    # 4) Native libraries
    native_libs = {}
    for abi in abis:
        out_dir = os.path.join(BUILD, "native", abi)
        os.makedirs(out_dir, exist_ok=True)
        native_libs[abi] = compile_native(ndk, abi, ABIS[abi], out_dir)

    # 5) Package universal + per-ABI APKs
    keystore = make_keystore(env, java_home)
    tools = (aapt2, d8, zipalign, apksigner)
    outputs = []
    outputs.append(package_apk("universal", abis, native_libs, dex_dir, tools, Android_jar, env, keystore))
    for abi in abis:
        outputs.append(package_apk(abi, [abi], native_libs, dex_dir, tools, Android_jar, env, keystore))

    # 6) Symbols + checksums
    symbols_zip = os.path.join(DIST, "OpenBarnyard-v%s-symbols.zip" % VERSION_NAME)
    with zipfile.ZipFile(symbols_zip, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for abi in abis:
            z.write(os.path.join(BUILD, "symbols", abi, "libopenbarnyard.so"),
                    "lib/%s/libopenbarnyard.so" % abi)
    outputs.append(symbols_zip)

    sums_path = os.path.join(DIST, "SHA256SUMS.txt")
    with open(sums_path, "w") as f:
        for path in outputs:
            digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
            f.write("%s  %s\n" % (digest, os.path.basename(path)))
    log("done. Artifacts in " + DIST)
    for path in outputs:
        log("  %s (%d bytes)" % (os.path.basename(path), os.path.getsize(path)))


def run_jvm_tests():
    java_home = find_java_home()
    javac = java_tool("javac", java_home)
    java = java_tool("java", java_home)
    out = os.path.join(BUILD, "test-classes")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out, exist_ok=True)
    core_src = collect_files(os.path.join(SRC_MAIN, "java", "org", "opentoshi", "openbarnyard", "core"), ".java")
    test_src = collect_files(os.path.join(ROOT, "tests", "java"), ".java")
    run([javac, "-encoding", "UTF-8", "-d", out] + core_src + test_src)
    run([java, "-cp", out, "org.opentoshi.openbarnyard.core.CoreTests"])


def main():
    parser = argparse.ArgumentParser(description="OpenBarnyard Android builder")
    parser.add_argument("--test", action="store_true", help="run JVM unit tests and exit")
    parser.add_argument("--abis", default=",".join(ABIS.keys()),
                        help="comma-separated ABIs to build (default: all)")
    parser.add_argument("--no-clean", action="store_true")
    args = parser.parse_args()

    if args.test:
        run_jvm_tests()
        return
    abis = [a.strip() for a in args.abis.split(",") if a.strip()]
    for abi in abis:
        if abi not in ABIS:
            die("unknown ABI '%s' (known: %s)" % (abi, ", ".join(ABIS)))
    build(abis, clean=not args.no_clean)


if __name__ == "__main__":
    main()
