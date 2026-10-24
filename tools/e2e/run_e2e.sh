#!/usr/bin/env bash
# AutoVybz on-device test runner (emulator or USB device with adb).
#
#   tools/e2e/run_e2e.sh functional <outdir>   permission pass, all instrumented suites, monkey,
#                                              crash scan, cold-start timing
#   tools/e2e/run_e2e.sh shots <outdir> [cfg]  screenshot matrix (all configs or one), API 33+
#
# Expects app-debug.apk + app-debug-androidTest.apk (./gradlew assembleDebug assembleDebugAndroidTest).
set -u
MODE=${1:?mode}; OUT=${2:?outdir}; ONLY=${3:-}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
PKG=com.andrexthedev.autovybz
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
E2E=com.andrexthedev.autovybz.e2e
APK=$ROOT/app/build/outputs/apk/debug/app-debug.apk
TAPK=$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
SUMMARY=$OUT/summary.md
FAIL=0

log() { echo "[$(date +%H:%M:%S)] $*"; }
note() { echo "$*" >> "$SUMMARY"; }

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
log "device API $API"
note "## Device: API $API ($(adb shell getprop ro.product.model | tr -d '\r'))"
adb shell input keyevent 82 >/dev/null 2>&1 || true
# root (google_apis images) lets us read ANR traces from /data/anr; harmless if unavailable
adb root >/dev/null 2>&1 && sleep 3 && adb wait-for-device || true
for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
adb shell settings put system screen_off_timeout 1800000
adb uninstall $PKG >/dev/null 2>&1; adb uninstall $PKG.test >/dev/null 2>&1
adb install -r "$APK" >/dev/null && adb install -r "$TAPK" >/dev/null || { echo "install failed"; exit 2; }

# run <name> <am instrument args...>
run() {
  local name=$1; shift
  log "instrument: $name"
  adb logcat -c
  adb shell am instrument -w -r "$@" $RUNNER < /dev/null > "$OUT/$name.instrument.txt" 2>&1
  adb logcat -d -v threadtime > "$OUT/$name.logcat.txt" 2>&1
  python3 "$HERE/parse_instrument.py" "$OUT/$name.instrument.txt" --suite "$name" --api "$API" \
    --json "$OUT/$name.json" >> "$SUMMARY" || FAIL=1
}

pull_shots() {
  local cfg=$1 dst=$OUT/shots/$1
  mkdir -p "$dst"
  for f in $(adb shell run-as $PKG ls files/shots/$cfg 2>/dev/null | tr -d '\r'); do
    adb exec-out run-as $PKG cat "files/shots/$cfg/$f" > "$dst/$f"
  done
  log "pulled $(ls "$dst" | wc -l) screenshots for $cfg"
}

if [ "$MODE" = functional ]; then
  # 1) Microphone denial: must run before the permission was ever granted.
  adb shell pm revoke $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  run permission -e class $E2E.PermissionDeniedE2ETest
  # 2) Everything else (incl. the inherited Equalizer314 instrumented tests).
  run functional -e notClass $E2E.PermissionDeniedE2ETest,$E2E.ScreenshotTourE2ETest
  # 3) Screen-state smoke (all states once, no screenshots).
  run tour_smoke -e class $E2E.ScreenshotTourE2ETest

  # 4) Monkey: 4000 random events restricted to the app, fixed seed (reproducible).
  log "monkey"
  adb shell pm grant $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  adb logcat -c
  adb shell monkey -p $PKG -s 4711 --throttle 40 --pct-syskeys 0 --pct-appswitch 5 --ignore-security-exceptions -v 4000 > "$OUT/monkey.txt" 2>&1
  adb logcat -d -v threadtime > "$OUT/monkey.logcat.txt" 2>&1
  if grep -q "// CRASH\|// NOT RESPONDING" "$OUT/monkey.txt"; then
    WHAT=$(grep -m1 -A12 "// CRASH\|// NOT RESPONDING" "$OUT/monkey.txt" | grep -E "CRASH|NOT RESPONDING|Short Msg|Long Msg|ANR in|Reason|at com\.(andrexthedev|bearinmind)" | head -6 | tr '\n' ' ' | cut -c1-600)
    # Only a crash / ANR of AutoVybz fails the gate. Monkey can pull down the notification shade despite -p;
    # a system-process ANR on an overloaded emulator is recorded as an environment finding.
    WHO=$(grep -m1 -oE "// (CRASH|NOT RESPONDING): [^ ]+" "$OUT/monkey.txt" | awk '{print $NF}')
    if [ "$WHO" = "$PKG" ]; then
      note "- ❌ **Monkey**: $WHAT"; echo "::error title=Monkey API $API::$WHAT"; FAIL=1
    else
      note "- ⚠️ **Monkey**: 4000 events, no AutoVybz crash / ANR; environment: $WHO – $WHAT"
      echo "::warning title=Monkey API $API (system process)::$WHAT"
    fi
    grep -B2 -A40 "// CRASH\|// NOT RESPONDING" "$OUT/monkey.txt" | head -120 > "$OUT/monkey_problem.txt"
    # Main-thread stack of the ANR (what the UI thread was blocked on)
    T=$(adb shell 'ls -t /data/anr/ 2>/dev/null | head -1' | tr -d '\r')
    if [ -n "$T" ]; then
      adb shell "cat /data/anr/$T" > "$OUT/anr_trace.txt" 2>/dev/null
      awk '/^Cmd line: '"$PKG"'$/{p=1} p&&/^"main"/{m=1} m{print} m&&/^$/{exit}' "$OUT/anr_trace.txt" > "$OUT/anr_main.txt"
      # Java frames tell which call blocked; native frames only show "binder transact".
      STACK=$( { grep -E "^\s+(- waiting|- locked|- sleeping)" "$OUT/anr_main.txt"; grep -E "^\s+at " "$OUT/anr_main.txt" | head -12; } | sed 's/^\s*//' | tr '\n' ' ' | cut -c1-1200)
      if [ "$WHO" = "$PKG" ]; then
        note "  - ANR main thread: $STACK"; echo "::error title=ANR main thread API $API::$STACK"
      else
        note "  - AutoVybz main thread at that moment: $STACK"; echo "::notice title=AutoVybz main thread during $WHO ANR (API $API)::$STACK"
      fi
    fi
  else
    note "- ✅ **Monkey**: 4000 events, seed 4711, no crash / ANR"
  fi

  # 5) Crash scan over every logcat captured above.
  CRASHES=$(grep -h -A3 "FATAL EXCEPTION" "$OUT"/*.logcat.txt 2>/dev/null | grep -c "Process: $PKG" || true)
  if [ "${CRASHES:-0}" -gt 0 ]; then
    grep -h -A12 "FATAL EXCEPTION" "$OUT"/*.logcat.txt > "$OUT/crashes.txt"
    FIRST=$(grep -A4 "Process: $PKG" "$OUT/crashes.txt" | grep -vE "^--$|Process:|PID:" | head -3 | sed 's/^.*AndroidRuntime: //' | tr '\n' ' ' | cut -c1-500)
    note "- ❌ **Crash scan**: $CRASHES fatal exception(s) in $PKG – first: $FIRST"
    echo "::error title=Crash scan API $API::$CRASHES fatal exceptions. First: $FIRST"; FAIL=1
  else
    note "- ✅ **Crash scan**: no FATAL EXCEPTION from $PKG in any logcat"
  fi
  STRICT=$(grep -h "StrictMode" "$OUT"/*.logcat.txt 2>/dev/null | grep -c "$PKG" || true)
  note "- ℹ️ StrictMode lines mentioning the app: ${STRICT:-0}"

  # 6) Cold start (3 runs).
  TIMES=""
  for i in 1 2 3; do
    adb shell am force-stop $PKG; sleep 1
    T=$(adb shell am start -W -n $PKG/com.andrexthedev.autovybz.ui.AutoVybzActivity | tr -d '\r' | awk -F': ' '/TotalTime/{print $2}')
    TIMES="$TIMES ${T:-?}"
  done
  note "- ⏱️ **Cold start** (TotalTime ms, 3 runs):$TIMES"
  echo "$TIMES" > "$OUT/coldstart.txt"
  adb shell dumpsys meminfo $PKG > "$OUT/meminfo.txt" 2>&1
  PSS=$(grep -m1 -oE "TOTAL PSS: +[0-9]+" "$OUT/meminfo.txt" | grep -oE "[0-9]+$")
  [ -n "$PSS" ] || PSS=$(grep -m1 -E "^ +TOTAL +[0-9]+" "$OUT/meminfo.txt" | awk '{print $2}')
  note "- 🧠 Memory after start: TOTAL PSS ${PSS:-?} kB"
fi

if [ "$MODE" = shots ]; then
  [ "$API" -ge 33 ] || { echo "screenshot matrix needs API 33+ (per-app language)"; exit 2; }
  adb shell pm grant $PKG android.permission.RECORD_AUDIO
  # name | locale | wm size | density | font scale | rotation
  CONFIGS="en-phone|en-US|1080x2400|420|1.0|0
de-phone|de-DE|1080x2400|420|1.0|0
es-phone|es-ES|1080x2400|420|1.0|0
de-phone-font130|de-DE|1080x2400|420|1.3|0
es-small-font130|es-ES|720x1280|320|1.3|0
en-tablet|en-US|1600x2560|320|1.0|0
de-landscape|de-DE|1080x2400|420|1.0|1"
  # for-loop (no pipe): adb shell would swallow a while-read loop's stdin, and FAIL must survive.
  for LINE in $CONFIGS; do
    IFS='|' read -r NAME LOC SIZE DENS FONT ROT <<< "$LINE"
    [ -n "$ONLY" ] && [ "$ONLY" != "$NAME" ] && continue
    log "config $NAME"
    adb shell cmd locale set-app-locales $PKG --locales "$LOC"
    adb shell wm size "$SIZE"; adb shell wm density "$DENS"
    adb shell settings put system font_scale "$FONT"
    adb shell settings put system accelerometer_rotation 0
    adb shell settings put system user_rotation "$ROT"
    adb shell cmd window user-rotation lock "$ROT" >/dev/null 2>&1 || true   # API 31+, more reliable than the setting
    sleep 2
    run "shots_$NAME" -e class $E2E.ScreenshotTourE2ETest -e avz_shots true -e avz_config "$NAME"
    # The accessibility / clipped-text audit in the same configuration.
    run "a11y_$NAME" -e class $E2E.AccessibilityE2ETest -e avz_config "$NAME"   # same rotation freeze
    pull_shots "$NAME"
  done
  adb shell wm size reset; adb shell wm density reset
  adb shell settings put system font_scale 1.0; adb shell settings put system user_rotation 0
  adb shell cmd window user-rotation free >/dev/null 2>&1 || true
  adb shell cmd locale set-app-locales $PKG --locales ""
fi

log "done (fail=$FAIL)"
exit $FAIL
