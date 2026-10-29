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
# Campaigns 12 + 14 (API 35): an "Application Not Responding: com.google.android.apps.nexuslauncher" system dialog
# held window focus and swallowed the back key / clipboard of the app under test. Error dialogs of *other* processes
# are not ours to judge; app ANRs are still caught by monkey and by the logcat ANR scan at the end.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
adb uninstall $PKG >/dev/null 2>&1; adb uninstall $PKG.test >/dev/null 2>&1
# Right after "adb root" restarts adbd the package manager can still be coming up (campaign 11: API 35 job died
# here with "install failed" while the identical APK installed fine in the shots job). Wait for it, retry, and keep
# the real error message instead of a bare exit code.
install_all() {
  adb install -r "$APK" > "$OUT/install.txt" 2>&1 && adb install -r "$TAPK" >> "$OUT/install.txt" 2>&1
}
installed=0
for attempt in 1 2 3; do
  adb wait-for-device
  for _ in $(seq 1 60); do adb shell pm path android 2>/dev/null | grep -q package: && break; sleep 2; done
  if install_all; then installed=1; break; fi
  log "install attempt $attempt failed: $(tail -n 2 "$OUT/install.txt" | tr '\n' ' ')"
  sleep 10
done
[ "$installed" = 1 ] || { note "- ❌ **install** failed 3×: \`$(tail -n 1 "$OUT/install.txt")\`"; echo "install failed"; exit 2; }
[ "$attempt" = 1 ] || note "- ⚠️ install needed $attempt attempts (package manager not ready yet)"

# Campaign 15 (API 35): a stuck system window (launcher ANR dialog) left the app without window focus and
# every following instrumented test failed with "the root of the view hierarchy never had window focus".
# Before each suite: dismiss system dialogs, and if the app still does not own the focus, bring it back.
focus_line() { adb shell dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus" | tr -d '\r' | sed 's/^ *//'; }

ensure_app_focus() {
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  local f; f=$(focus_line)
  case "$f" in
    *$PKG*) return 0 ;;
  esac
  log "focus is not the app ($f) - relaunching"
  echo "::warning title=Focus lost before $1 (API $API)::${f:-unknown}" >&2
  adb shell am start -n $PKG/com.andrexthedev.autovybz.ui.AutoVybzActivity >/dev/null 2>&1 || true
  sleep 3
  f=$(focus_line)
  log "focus after relaunch: $f"
  case "$f" in
    *$PKG*) return 0 ;;
  esac
  echo "::warning title=Focus still not the app before $1 (API $API)::${f:-unknown}" >&2
}

# run <name> <am instrument args...>
run() {
  local name=$1; shift
  log "instrument: $name"
  ensure_app_focus "$name"
  adb logcat -c
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  adb shell am instrument -w -r "$@" $RUNNER < /dev/null > "$OUT/$name.instrument.txt" 2>&1
  adb logcat -d -v threadtime > "$OUT/$name.logcat.txt" 2>&1
  python3 "$HERE/parse_instrument.py" "$OUT/$name.instrument.txt" --suite "$name" --api "$API" \
    --json "$OUT/$name.json" >> "$SUMMARY" || { FAIL=1; focus_diag "$name"; }
}

# Campaign 12 (API 35): whole suites failed with "root ... has-window-focus=false" and no app crash/ANR.
# On failure record which window holds focus so a stuck system window is visible in the summary.
focus_diag() {
  adb shell dumpsys window > "$OUT/$1.window.txt" 2>&1
  local f; f=$(grep -m1 -E "mCurrentFocus=" "$OUT/$1.window.txt" | tr -d '\r' | sed 's/^ *//')
  note "  - focus after $1: \`${f:-unknown}\`"
  # Annotated as well: the summary only lives in the results bundle, the annotation is visible in the check run
  # and therefore tells *which* window (ours or a system dialog) held the focus.
  echo "::warning title=Focus after $1 (API $API)::${f:-unknown}" >&2
  local top; top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "ResumedActivity|topResumedActivity" | tr -d '\r' | sed 's/^ *//')
  [ -n "$top" ] && echo "::notice title=Top activity after $1 (API $API)::$top" >&2
}

# Which kind of stall is this ANR? Reads the app's main-thread stack ($1 = the extracted "main" block)
# and echoes "" when the app itself has a frame there - i.e. when the app is the suspect - or a name for
# the three framework stalls seen so far:
#
#   framework graphics stall   blocked in hwui/EGL teardown or draw (campaign 6, API 30: the emulator's
#                              software composer used 50 % kernel CPU while Dialog.cancel tore down the
#                              hardware renderer)
#   idle main thread           the looper sits in MessageQueue.next while Android reports "Input
#                              dispatching timed out" (campaign of 3e7b61a, API 30: the launcher held the
#                              window focus, so the queued MOVE event went to a window that never drew)
#   framework IME binder stall blocked in the input-method binder call that a window-focus gain triggers
#                              (campaign 15, API 30: com.android.systemui and the app both failed to consume
#                              the *same* MotionEvent, and the app's main thread had no AutoVybz frame - it
#                              was inside InputMethodManager.startInputInner via BinderProxy.transactNative)
#
# Anything else - and any stack with a com.andrexthedev / com.bearinmind frame - stays unclassified, which
# is the caller's signal to treat the ANR as ours.
classify_anr_main() {
  local f=$1
  [ -s "$f" ] || return 0
  grep -qE "at com\.(andrexthedev|bearinmind)\." "$f" && return 0
  if grep -qE "HardwareRenderer\.n|ThreadedRenderer\.|eglSwapBuffers|nSyncAndDrawFrame" "$f"; then
    echo "framework graphics stall"
  elif grep -qE "MessageQueue\.next|nativePollOnce" "$f"; then
    echo "idle main thread"
  elif grep -qE "startInputOrWindowGainedFocus|ImeFocusController|InputMethodManager|IInputMethodManager" "$f"; then
    echo "framework IME binder stall"
  fi
}

# One monkey random walk plus its evaluation. $1 = seed, $2 = file prefix ("monkey", "monkey2").
# Only a crash / ANR of the app itself fails the job; a system-process ANR on an overloaded emulator is
# reported as an environment finding, and a framework-only graphics stall is re-run with the same seed.
monkey_run() {
  local SEED=$1 PFX=$2 WHAT WHO KIND STACK STALL_KIND T
  adb logcat -c
  adb shell monkey -p $PKG -s "$SEED" --throttle 40 --pct-syskeys 0 --pct-appswitch 5 --ignore-security-exceptions -v 4000 > "$OUT/$PFX.txt" 2>&1
  adb logcat -d -v threadtime > "$OUT/$PFX.logcat.txt" 2>&1
  if ! grep -q "// CRASH\|// NOT RESPONDING" "$OUT/$PFX.txt"; then
    note "- ✅ **Monkey**: 4000 events, seed $SEED, no crash / ANR"
    return 0
  fi
  WHAT=$(grep -m1 -A12 "// CRASH\|// NOT RESPONDING" "$OUT/$PFX.txt" | grep -E "CRASH|NOT RESPONDING|Short Msg|Long Msg|ANR in|Reason|at com\.(andrexthedev|bearinmind)" | head -6 | tr '\n' ' ' | cut -c1-600)
  WHO=$(grep -m1 -oE "// (CRASH|NOT RESPONDING): [^ ]+" "$OUT/$PFX.txt" | awk '{print $NF}')
  KIND=$(grep -m1 -oE "// (CRASH|NOT RESPONDING)" "$OUT/$PFX.txt" | awk '{print $2}')
  grep -B2 -A40 "// CRASH\|// NOT RESPONDING" "$OUT/$PFX.txt" | head -120 > "$OUT/${PFX}_problem.txt"
  # Main-thread stack of the ANR (what the UI thread was blocked on)
  STACK=""; STALL_KIND=""
  T=$(adb shell 'ls -t /data/anr/ 2>/dev/null | head -1' < /dev/null | tr -d '\r')
  if [ -n "$T" ]; then
    adb shell "cat /data/anr/$T" < /dev/null > "$OUT/${PFX}_anr_trace.txt" 2>/dev/null
    awk '/^Cmd line: '"$PKG"'$/{p=1} p&&/^"main"/{m=1} m{print} m&&/^$/{exit}' "$OUT/${PFX}_anr_trace.txt" > "$OUT/${PFX}_anr_main.txt"
    # Java frames tell which call blocked; native frames only show "binder transact".
    STACK=$( { grep -E "^\s+(- waiting|- locked|- sleeping)" "$OUT/${PFX}_anr_main.txt"; grep -E "^\s+at " "$OUT/${PFX}_anr_main.txt" | head -12; } | sed 's/^\s*//' | tr '\n' ' ' | cut -c1-1200)
    # A "not responding" the app cannot have caused requires that its *main thread* shows no AutoVybz /
    # Equalizer314 frame at all - see `classify_anr_main` for the three framework signatures and the campaign
    # each one came from. Only the idle-looper case additionally needs the "Input dispatching timed out"
    # reason in the monkey log; the other two are recognisable from the stack alone. Any stall found here is
    # still confirmed by re-running the same seed before it is reported without failing the job.
    if [ "$KIND" = "NOT" ]; then
      STALL_KIND=$(classify_anr_main "$OUT/${PFX}_anr_main.txt")
      if [ "$STALL_KIND" = "idle main thread" ] && ! grep -q "Input dispatching timed out" "$OUT/$PFX.txt"; then
        STALL_KIND=""
      fi
    fi
  fi
  if [ "$WHO" = "$PKG" ] && [ -n "$STALL_KIND" ]; then
    # Confirmation run with the SAME seed: a reproducible ANR fails, a transient emulator stall is a warning.
    log "monkey (seed $SEED): $STALL_KIND - confirmation run"
    adb shell am force-stop $PKG < /dev/null
    adb shell monkey -p $PKG -s "$SEED" --throttle 40 --pct-syskeys 0 --pct-appswitch 5 --ignore-security-exceptions -v 4000 < /dev/null > "$OUT/${PFX}_rerun.txt" 2>&1
    if grep -qE "// (CRASH|NOT RESPONDING): $PKG" "$OUT/${PFX}_rerun.txt"; then
      note "- ❌ **Monkey** (seed $SEED): reproducible with the same seed: $WHAT"; echo "::error title=Monkey API $API (seed $SEED, reproducible)::$WHAT"; FAIL=1
      note "  - ANR main thread: $STACK"; echo "::error title=ANR main thread API $API::$STACK"
    else
      note "- ⚠️ **Monkey** (seed $SEED): 4000 events; one $STALL_KIND (no app frame on the main thread, not reproducible with the same seed) – $WHAT"
      note "  - main thread: $STACK"
      echo "::warning title=Monkey API $API seed $SEED ($STALL_KIND, not reproducible)::$STACK"
    fi
  elif [ "$WHO" = "$PKG" ]; then
    note "- ❌ **Monkey** (seed $SEED): $WHAT"; echo "::error title=Monkey API $API seed $SEED::$WHAT"; FAIL=1
    [ -n "$STACK" ] && { note "  - ANR main thread: $STACK"; echo "::error title=ANR main thread API $API::$STACK"; }
  else
    # Monkey can pull down the notification shade despite -p; a system-process ANR on an overloaded emulator
    # is recorded as an environment finding.
    note "- ⚠️ **Monkey** (seed $SEED): 4000 events, no AutoVybz crash / ANR; environment: $WHO – $WHAT"
    echo "::warning title=Monkey API $API seed $SEED (system process)::$WHAT"
    [ -n "$STACK" ] && { note "  - AutoVybz main thread at that moment: $STACK"; echo "::notice title=AutoVybz main thread during $WHO ANR (API $API)::$STACK"; }
  fi
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

  # 4) Monkey: two independent random walks of 4000 events (fixed seeds -> reproducible). A single seed walks
  # one path through the state space; a second, unrelated seed is the cheapest way to widen that coverage.
  log "monkey"
  adb shell pm grant $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  monkey_run 4711 monkey
  monkey_run 20261028 monkey2

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
    run "shots_$NAME" -e class $E2E.ScreenshotTourE2ETest,$E2E.LegacyScreensE2ETest -e avz_shots true -e avz_config "$NAME"
    # The accessibility / clipped-text audit in the same configuration.
    run "a11y_$NAME" -e class $E2E.AccessibilityE2ETest -e avz_config "$NAME"   # same rotation freeze
    pull_shots "$NAME"
  done
  adb shell wm size reset; adb shell wm density reset
  adb shell settings put system font_scale 1.0; adb shell settings put system user_rotation 0
  adb shell cmd window user-rotation free >/dev/null 2>&1 || true
  adb shell cmd locale set-app-locales $PKG --locales ""
fi

# ANR scan over the instrumented runs (monkey reports its own ANRs above). Needed because error dialogs are hidden.
# A framework stall during an instrumented run is not ours either - but unlike the monkey path there is no
# confirmation run to ask, so it takes two independent pieces of evidence to call it the environment:
# (a) the app's main-thread stack classifies as a framework stall (no AutoVybz frame), and (b) another
# process ANR'd in the same window, i.e. the stall was system-wide (campaign 15, API 30: systemui and the
# app both missed the same MotionEvent). Without both, it fails as before.
ANRS=$(grep -h "ANR in $PKG" $(ls "$OUT"/*.logcat.txt 2>/dev/null | grep -v monkey.logcat) /dev/null 2>/dev/null | wc -l)
if [ "${ANRS:-0}" -gt 0 ]; then
  FIRST=$(grep -h -m1 -A2 "ANR in $PKG" $(ls "$OUT"/*.logcat.txt | grep -v monkey.logcat) | tr '\n' ' ' | cut -c1-400)
  SCAN_KIND=""; SCAN_STACK=""
  T=$(adb shell 'ls -t /data/anr/ 2>/dev/null | head -1' < /dev/null | tr -d '\r')
  if [ -n "$T" ]; then
    adb shell "cat /data/anr/$T" < /dev/null > "$OUT/anr_scan_trace.txt" 2>/dev/null
    awk '/^Cmd line: '"$PKG"'$/{p=1} p&&/^"main"/{m=1} m{print} m&&/^$/{exit}' "$OUT/anr_scan_trace.txt" > "$OUT/anr_scan_main.txt"
    SCAN_KIND=$(classify_anr_main "$OUT/anr_scan_main.txt")
    SCAN_STACK=$(grep -E "^\s+at " "$OUT/anr_scan_main.txt" | head -6 | sed 's/^\s*//' | tr '\n' ' ' | cut -c1-600)
  fi
  OTHER_ANR=$(grep -h "ANR in " $(ls "$OUT"/*.logcat.txt 2>/dev/null) /dev/null 2>/dev/null | grep -v "ANR in $PKG" | sed 's/.*ANR in \([^ ]*\).*/\1/' | sort -u | tr '\n' ' ')
  if [ -n "$SCAN_KIND" ] && [ -n "$OTHER_ANR" ]; then
    note "- ⚠️ **ANR scan**: $ANRS ANR(s) of $PKG during instrumented runs, $SCAN_KIND with no app frame while $OTHER_ANR also ANR'd – $FIRST"
    note "  - main thread: $SCAN_STACK"
    echo "::warning title=ANR scan API $API ($SCAN_KIND, system-wide stall)::$FIRST"
  else
    note "- ❌ **ANR scan**: $ANRS ANR(s) of $PKG during instrumented runs – $FIRST"
    [ -n "$SCAN_STACK" ] && note "  - main thread: $SCAN_STACK"
    echo "::error title=ANR scan API $API::$FIRST"; FAIL=1
  fi
else
  OTHER=$(grep -h "ANR in " $(ls "$OUT"/*.logcat.txt 2>/dev/null | grep -v monkey.logcat) /dev/null 2>/dev/null | grep -v "$PKG" | sed 's/.*ANR in \([^ ]*\).*/\1/' | sort -u | tr '\n' ' ')
  note "- ✅ **ANR scan**: no ANR of $PKG in the instrumented runs${OTHER:+ (system processes: $OTHER)}"
fi

log "done (fail=$FAIL)"
exit $FAIL
