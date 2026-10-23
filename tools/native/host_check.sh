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
# jni.h: explicit JNI_INC, else the JDK (JAVA_HOME may be a symlink → find -L), else the NDK sysroot
find_jni() {
  local d
  for d in "${JAVA_HOME:-}" /usr/lib/jvm "${ANDROID_NDK_HOME:-}" "${ANDROID_HOME:-/nonexistent}/ndk"; do
    [ -n "$d" ] && [ -d "$d" ] || continue
    local h; h=$(find -L "$d" -name jni.h -print -quit 2>/dev/null || true)
    if [ -n "$h" ]; then dirname "$h"; return; fi
  done
}
JNI_INC=${JNI_INC:-$(find_jni)}
[ -n "$JNI_INC" ] || { echo "jni.h not found (set JNI_INC)"; exit 1; }
echo "using jni.h from $JNI_INC"
for f in measurement_engine avz_jni; do
  g++ -std=c++17 -fsyntax-only -Wall -Wextra -I"$WORK" -I"$WORK/oboe/include" -I"$SRC" -I"$JNI_INC" -I"$JNI_INC/linux" "$SRC/$f.cpp"
  echo "$f.cpp: syntax OK"
done
