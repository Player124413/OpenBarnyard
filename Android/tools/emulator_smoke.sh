#!/bin/sh
# Emulator smoke test (run by CI inside reactivecircus/android-emulator-runner):
# installs the universal APK, launches the launcher and the game screen,
# pokes the touch controls, and fails on crashes or a dead process.
# Usage: sh Android/tools/emulator_smoke.sh <apk> [outdir]
set -u
APK="$1"
OUT="${2:-emulator-out}"
PKG="org.opentoshi.openbarnyard"
mkdir -p "$OUT"

echo "[smoke] installing $APK"
adb install -r "$APK" || exit 1

echo "[smoke] launching launcher"
adb shell am start -W -n "$PKG/.LauncherActivity" || exit 1
sleep 5
adb shell screencap -p /sdcard/launcher.png
adb pull /sdcard/launcher.png "$OUT/launcher.png" >/dev/null 2>&1 || true

PID=$(adb shell pidof "$PKG" | tr -d '\r')
if [ -z "$PID" ]; then
  echo "[smoke] FAIL: launcher process not running"
  adb logcat -d > "$OUT/logcat.txt" || true
  exit 1
fi
echo "[smoke] launcher running, pid=$PID"

echo "[smoke] launching game screen"
adb shell am start -W -n "$PKG/.GameActivity" || exit 1
sleep 8

# Poke the virtual controls: hold the stick area, drag the camera, tap jump.
adb shell input swipe 250 750 420 750 900 || true
sleep 1
adb shell input swipe 1200 500 800 500 400 || true
adb shell input tap 1650 800 || true
sleep 2

adb shell screencap -p /sdcard/game.png
adb pull /sdcard/game.png "$OUT/game.png" >/dev/null 2>&1 || true

PID=$(adb shell pidof "$PKG" | tr -d '\r')
adb logcat -d > "$OUT/logcat.txt" || true
if [ -z "$PID" ]; then
  echo "[smoke] FAIL: process died on the game screen"
  tail -n 120 "$OUT/logcat.txt" || true
  exit 1
fi
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "[smoke] FAIL: FATAL EXCEPTION in logcat"
  grep -A 20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -n 60
  exit 1
fi
if grep -q "OpenBarnyard" "$OUT/logcat.txt"; then
  echo "[smoke] native log lines:"
  grep "OpenBarnyard" "$OUT/logcat.txt" | head -n 20
fi
echo "[smoke] PASS: game screen alive after input poking, pid=$PID"
