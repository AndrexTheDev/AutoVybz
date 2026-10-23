#!/usr/bin/env bash
# Host-side checks for the AutoVybz native engine (no NDK needed):
#  1. builds + runs the FFT against a naive DFT
#  2. syntax-checks the Oboe engine + JNI bridge against the real Oboe headers
# Requires g++ and a JDK (for jni.h). Usage: tools/native/host_check.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC=$ROOT/app/src/main/cpp/autovybz
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/android"
printf '#pragma once\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\ninline int __android_log_print(int, const char*, const char*, ...) { return 0; }\n' > "$WORK/android/log.h"
g++ -std=c++17 -O2 -Wall -Wextra -I"$SRC" "$ROOT/tools/native/fft_host_test.cpp" "$SRC/avz_fft.cpp" -o "$WORK/fft_test"
"$WORK/fft_test"
git clone -q --depth 1 --branch 1.9.0 https://github.com/google/oboe.git "$WORK/oboe" 2>/dev/null
JNI_INC=${JNI_INC:-$(dirname "$(find "${JAVA_HOME:-/usr/lib/jvm}" -name jni.h | head -1)")}
for f in measurement_engine avz_jni; do
  g++ -std=c++17 -fsyntax-only -Wall -Wextra -I"$WORK" -I"$WORK/oboe/include" -I"$SRC" -I"$JNI_INC" -I"$JNI_INC/linux" "$SRC/$f.cpp"
  echo "$f.cpp: syntax OK"
done
