#!/usr/bin/env python3
"""
Cross-language contract checks for the Android port. These guard against
the classic multi-person-port failure mode: one side of a contract drifting
away from the other. Run locally or in CI:

  1. AInputCommand ids: OpenBarnyard's AInputMap.h  vs  GameCommand.java
     and cpp/core/InputState.h (subsets must match the engine exactly).
  2. openbarnyard.json keys: EngineConfig.java (writer) vs Config.cpp (parser).
  3. JNI surface: NativeBridge.java `native` methods vs jni_bridge.cpp exports.
  4. TouchProfile JSON fields: Java writer emits what the reader expects.
Exit code 0 = everything in sync.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # repo root (native_window.h: Android/tools/..)
failures = []


def check(cond, msg):
    if not cond:
        failures.append(msg)
        print("FAIL:", msg)


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def parse_engine_commands():
    text = read("OpenBarnyard/Source/Input/AInputMap.h")
    m = re.search(r"enum\s+AInputCommand\s*\{(.*?)\};", text, re.S)
    if not m:
        check(False, "AInputCommand enum not found in AInputMap.h")
        return {}
    out = {}
    for name, value in re.findall(r"(AInputCommand_\w+)\s*=\s*(-?\d+)", m.group(1)):
        out[name.replace("AInputCommand_", "")] = int(value)
    return out


def parse_java_commands():
    text = read("Android/app/src/main/java/org/opentoshi/openbarnyard/core/GameCommand.java")
    out = {}
    # JUMP        (57, "Jump", ...),
    for name, value in re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\s*\(\s*(\d+)\s*,", text, re.M):
        out[name] = int(value)
    return out


def parse_cpp_commands():
    text = read("Android/app/src/main/cpp/core/InputState.h")
    out = {}
    for name, value in re.findall(r"(kCmd\w+)\s*=\s*(\d+)", text):
        out[name[4:]] = int(value)  # kCmdJump -> Jump
    return out


def engine_name_for_java(java_name):
    # Java uses SCREAMING_SNAKE; engine uses CamelCase (mostly mechanical).
    special = {
        "OK": "OK", "ZOOM_IN": "ZoomIn", "ZOOM_OUT": "ZoomOut",
        "CONTEXT1": "Context1", "CONTEXT2": "Context2",
        "CONTEXT3": "Context3", "CONTEXT4": "Context4",
        "SHOOT_ALT": "ShootAlt", "TRICK1": "Trick1", "TRICK2": "Trick2",
        "SIDESTEP": "SideStep", "ZLOCK": "ZLock", "ICONS": "Icons",
    }
    if java_name in special:
        return special[java_name]
    return "".join(w.capitalize() for w in java_name.split("_"))


def engine_name_for_cpp(cpp_name):
    special = {"ZLock": "ZLock", "Ok": "OK"}
    return special.get(cpp_name, cpp_name)


def main():
    engine = parse_engine_commands()
    check(len(engine) >= 40, "engine command table suspiciously small: %d" % len(engine))

    java_cmds = parse_java_commands()
    check(len(java_cmds) >= 30, "Java command table suspiciously small: %d" % len(java_cmds))
    for name, value in sorted(java_cmds.items()):
        en = engine_name_for_java(name)
        if en not in engine:
            check(False, "GameCommand.%s -> '%s' not in AInputMap.h" % (name, en))
        elif engine[en] != value:
            check(False, "GameCommand.%s id %d != engine %s id %d" % (name, value, en, engine[en]))

    cpp_cmds = parse_cpp_commands()
    for name, value in sorted(cpp_cmds.items()):
        if name == "Max" or name == "Empty":
            continue
        en = engine_name_for_cpp(name)
        if en not in engine:
            check(False, "InputState kCmd%s -> '%s' not in AInputMap.h" % (name, en))
        elif engine[en] != value:
            check(False, "InputState kCmd%s id %d != engine %s id %d" % (name, value, en, engine[en]))

    # Config keys: Java writer -> C++ reader (both directions for our schema)
    java_cfg = read("Android/app/src/main/java/org/opentoshi/openbarnyard/core/EngineConfig.java")
    cpp_cfg = read("Android/app/src/main/cpp/core/Config.cpp")
    written = set(re.findall(r'append\("\s*\\"(\w+)\\"', java_cfg))
    parsed = set(re.findall(r'Read(?:Int|Float|Bool|String)\(json, "(\w+)"', cpp_cfg))
    check(written == parsed,
          "config keys mismatch: Java-only %s, C++-only %s"
          % (sorted(written - parsed), sorted(parsed - written)))

    # JNI surface
    java_nb = read("Android/app/src/main/java/org/opentoshi/openbarnyard/NativeBridge.java")
    cpp_jni = read("Android/app/src/main/cpp/jni_bridge.cpp")
    java_natives = set(re.findall(r"native\s+[\w\[\]]+\s+(native\w+)\s*\(", java_nb))
    cpp_exports = set(re.findall(r"NativeBridge_(native\w+)\s*\(", cpp_jni))
    check(java_natives == cpp_exports,
          "JNI mismatch: Java-only %s, C++-only %s"
          % (sorted(java_natives - cpp_exports), sorted(cpp_exports - java_natives)))

    # TouchProfile fields present in both serialiser and parser
    tp = read("Android/app/src/main/java/org/opentoshi/openbarnyard/core/TouchProfile.java")
    for field in ("id", "type", "command", "x", "y", "size", "opacity", "elements", "version"):
        count = tp.count('"' + field + '"') + tp.count('\\"' + field + '\\"')
        check(count >= 2, "TouchProfile field '%s' not in both writer and reader (found %d)" % (field, count))

    if failures:
        print("\n%d sync check(s) FAILED" % len(failures))
        return 1
    print("ALL SYNC CHECKS PASSED (commands=%d engine, config keys=%d, JNI methods=%d)"
          % (len(engine), len(written), len(java_natives)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
