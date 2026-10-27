#!/usr/bin/env bash
# Builds the AutoVybz brand intro (app/src/main/res/raw/avz_intro.mp4) from the design source
# (branding/intro/Intro1.mp4: 360x640, 24 fps, 4.0 s, 978 kb/s).
#
# Why this pipeline (each step fixes something the phone showed):
#
#   1. hqdn3d                - the source is a 728 kb/s 360p encode; its block noise would be amplified
#                              3x by the upscale.
#   2. minterpolate fps=120  - real motion-compensated frames instead of a 24 fps judder. The clip is
#                              slowed to 0.67x *before* the upscale (cheap + no upscaled artefacts).
#   3. setpts + fps=60       - 0.67x slow motion -> 6.0 s instead of 4.0 s, 60 fps output.
#   4. scale 1080x1920       - 3x lanczos (accurate rounding, full chroma interpolation): the clip is
#                              shown full-screen on a 1080x2400 phone, so 360x640 was upscaled by the
#                              GPU anyway - just with a cheaper filter and visible softness.
#   5. cas 0.45              - contrast-adaptive sharpening restores the edge definition lanczos
#                              smoothed away (the neon rings are the whole design).
#   6. deband                - the dark slate gradients band badly once upscaled.
#   7. tpad (clone)          - 2.2 s of the final frame, so the finished logo can be looked at before
#                              the overlay hands over (the intro used to end mid-motion).
#   8. fade in/out           - 0.35 s from / 0.7 s to the brand slate (#1E262D = AutoVybzColors
#                              Background), so the clip does not start or end with a hard cut.
#   9. -an                   - no audio track at all: the app must not open an audio stream on start
#                              (music may be playing).
#
# Runtime on a laptop: ~1 min. Requires ffmpeg with libx264, cas, deband and minterpolate.
#
# Usage: tools/intro/make_intro.sh [ffmpeg-binary]
set -euo pipefail

cd "$(dirname "$0")/../.."
FF="${1:-${FFMPEG:-ffmpeg}}"
SOURCE="branding/intro/Intro1.mp4"
OUT="app/src/main/res/raw/avz_intro.mp4"

# Keep in sync with IntroOverlay.kt (aspect 9:16) and IntroGate.MAX_DURATION_MS.
WIDTH=1080
HEIGHT=1920
SLOWDOWN=1.5       # 4.01 s -> 6.02 s
TAIL=2.2           # hold the finished logo
FADE_IN=0.35
FADE_OUT=0.7
FADE_OUT_START=7.5 # 4.01 * 1.5 + 2.2 - 0.7
SLATE=0x1E262D     # AutoVybzColors.Background

"$FF" -hide_banner -y -i "$SOURCE" -an \
  -vf "hqdn3d=1.5:1.5:6:6,\
minterpolate=fps=120:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1,\
setpts=${SLOWDOWN}*PTS,fps=60,\
scale=${WIDTH}:${HEIGHT}:flags=lanczos+accurate_rnd+full_chroma_int,\
cas=0.45,\
deband=1thr=0.02:2thr=0.02:3thr=0.02:range=24,\
tpad=stop_mode=clone:stop_duration=${TAIL},\
fade=t=in:st=0:d=${FADE_IN}:color=${SLATE},\
fade=t=out:st=${FADE_OUT_START}:d=${FADE_OUT}:color=${SLATE},\
format=yuv420p" \
  -c:v libx264 -preset slow -crf 20 -profile:v high -level 4.2 \
  -g 60 -keyint_min 30 -bf 2 -movflags +faststart \
  "$OUT"

echo "wrote $OUT"
"$FF" -hide_banner -i "$OUT" 2>&1 | grep -E "Duration|Stream #" || true
ls -l "$OUT"
