#!/bin/sh
# Builds and runs the platform-free native core tests with any C++17 host
# compiler (also used by CI). Usage: sh Android/tools/run_host_tests.sh
set -e
cd "$(dirname "$0")/../.."
CXX="${CXX:-c++}"
OUT="$(mktemp -d)/core_tests"
echo "[test] compiler: $CXX"
"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror \
  -I Android/app/src/main/cpp \
  Android/app/src/main/cpp/core/Config.cpp \
  Android/app/src/main/cpp/core/CpuFeatures.cpp \
  Android/app/src/main/cpp/core/FramePacer.cpp \
  Android/tests/cpp/core_tests.cpp \
  -o "$OUT" -pthread
"$OUT"
python3 Android/tools/check_sync.py
