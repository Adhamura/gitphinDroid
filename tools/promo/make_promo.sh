#!/usr/bin/env bash
# Makes the promo video and GIF: docs/promo/gitphin-droid.mp4 (1280x720, ~50 s) and
# docs/promo/gitphin-droid.gif (the first part, smaller). Run tools/screenshots/capture.sh first:
# the video uses docs/screenshots/*.png.
#  1. gitphind with the demo repos, but without weather-station; a real `git push` of it,
#     whose output the terminal in the video shows;
#  2. the app's main screen before, during and after the push (Robolectric, as capture.sh);
#  3. promo.cjs draws the frames, ffmpeg makes the MP4 and the GIF.
# Usage: tools/promo/make_promo.sh   (ROBO_JARS=DIR as in capture.sh)
# Needs what capture.sh needs, plus ffmpeg with libx264.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(cd "$HERE/../.." && pwd)"
SHOTS="$TOP/tools/screenshots"
OUT="$TOP/docs/promo"
SRV_PORT="${SRV_PORT:-8097}" PROXY_PORT="${PROXY_PORT:-8099}"
W="$(mktemp -d "${TMPDIR:-/tmp}/gitphin-promo.XXXXXX")"
trap 'kill $(jobs -p) 2>/dev/null; rm -rf "$W"' EXIT
mkdir -p "$OUT" "$W/web" "$W/frames" "$W/video"
[[ -f "$TOP/docs/screenshots/main.png" ]] || { echo "run tools/screenshots/capture.sh first" >&2; exit 1; }

# ---- 1. the server without weather-station, then a real push of it
cmake -S "$TOP" -B "$TOP/build/shots" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$TOP/build/shots" -j4 >/dev/null
"$SHOTS/make_demo.sh" "$W/ext" >/dev/null
G="$W/ext/apps_data/gitphin/git"
mv "$G/weather-station.git" "$W/ws.git"
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
git clone -q "$W/ws.git" "$W/weather-station"
mkdir -p "$W/ext/apps_assets/gitphin"
cp "$TOP/core/files/index.html" "$TOP/core/files/index.html.gz" "$W/ext/apps_assets/gitphin/"
"$TOP/build/shots/gitphind" --root "$W/ext" --port "$SRV_PORT" >"$W/server.log" 2>&1 &
python3 -I "$SHOTS/demo_proxy.py" "$SRV_PORT" "$PROXY_PORT" &
for _ in $(seq 50); do curl -s "http://127.0.0.1:$PROXY_PORT/api/info" >/dev/null && break; sleep 0.1; done
(cd "$W" && npm init -y >/dev/null && npm i -s playwright-core@1.55.0 >/dev/null)
NODE_PATH="$W/node_modules" node "$SHOTS/capture.cjs" "http://127.0.0.1:$PROXY_PORT" "$W/web" "promo-before=#repos"

cd "$W/weather-station"
git remote add phone "http://127.0.0.1:$SRV_PORT/git/weather-station.git"
# the output as git prints it on a terminal: keep the last state of each \r-updated line;
# the video shows the app's default port
git push --progress phone main 2>&1 | tr '\r' '\n' | awk '
    /: +[0-9]+% / { key = $1 " " $2; last[key] = $0; if (!(key in seen)) { seen[key] = 1; order[++n] = key; line[n] = "" } next }
    { order[++n] = ""; line[n] = $0 }
    END { for (i = 1; i <= n; i++) print (order[i] != "" ? last[order[i]] : line[i]) }' |
    sed "s/:$SRV_PORT/:8080/g" >"$W/push.txt"
git push -q phone --all && git push -q phone --tags
cp -r "$W/ws.git/hub" "$G/weather-station.git/" && cp "$W/ws.git/description" "$G/weather-station.git/"
cd "$TOP"
NODE_PATH="$W/node_modules" node "$SHOTS/capture.cjs" "http://127.0.0.1:$PROXY_PORT" "$W/web" "promo-after=#repos"
cat "$W/push.txt"

# ---- 2. the app's main screen, before / during / after
"$SHOTS/android/prepare.sh"
GRADLE="${GRADLE:-$(command -v gradle || echo "$TOP/android/gradlew")}"
"$GRADLE" -p "$SHOTS/android" test --tests 'shots.AppScreens.promo' --console=plain -q \
    -PshotsPromo="$W/frames" -PshotsWeb="$W/web" ${ROBO_JARS:+-PlocalJars="$ROBO_JARS"}

# ---- 3. frames, video, GIF
NODE_PATH="$W/node_modules" node "$HERE/promo.cjs" "$W/frames" "$TOP/docs/screenshots" "$W/push.txt" "$W/video"
ffmpeg -loglevel error -y -framerate 30 -i "$W/video/%05d.png" -c:v libx264 -preset slow -crf 20 \
    -pix_fmt yuv420p -movflags +faststart "$OUT/gitphin-droid.mp4"
# the GIF: the push and the browser part (seconds 3.5 to 22), 12 fps, 720 px wide
ffmpeg -loglevel error -y -ss 3.5 -t 18.5 -i "$OUT/gitphin-droid.mp4" \
    -vf "fps=12,scale=720:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
    "$OUT/gitphin-droid.gif"
ls -la "$OUT"
