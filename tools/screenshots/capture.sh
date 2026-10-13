#!/usr/bin/env bash
# Makes the README screenshots (docs/screenshots/*.png) with made-up demo repos:
#  1. the web UI, served by gitphind (the real server, Linux build) and captured with Chromium;
#  2. the app's own screens (main screen with the addresses, settings, activity, guide, share,
#     QR, widget), drawn by Robolectric from the real Activities, with the web UI of step 1 in
#     the WebView area;
#  3. docs/screenshots/hero.png from both.
# Usage: tools/screenshots/capture.sh        (WEB_ONLY=1 skips step 2; ROBO_JARS=DIR takes the
#        android-all and android-all-instrumented jars of step 2 from DIR instead of Maven)
# Needs cmake, gcc, zlib, git, curl, python3, Node.js and Chromium (CHROMIUM=/path, default:
# Playwright's). Step 2 also needs JDK 17+, Gradle (GRADLE=..., default: gradle or
# android/gradlew), aapt2 and an android.jar: from $ANDROID_HOME, or on Debian/Ubuntu
# `apt install aapt android-sdk-platform-23`. Downloads come from Maven Central and npm only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(cd "$HERE/../.." && pwd)"
OUT="$TOP/docs/screenshots"
SRV_PORT="${SRV_PORT:-8097}" PROXY_PORT="${PROXY_PORT:-8099}"
W="$(mktemp -d "${TMPDIR:-/tmp}/gitphin-shots.XXXXXX")"
trap 'kill $(jobs -p) 2>/dev/null; rm -rf "$W"' EXIT
mkdir -p "$OUT" "$W/web"

# ---- 1. the server with the demo repos, and the web UI
cmake -S "$TOP" -B "$TOP/build/shots" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$TOP/build/shots" -j4 >/dev/null
"$HERE/make_demo.sh" "$W/ext" >/dev/null
mkdir -p "$W/ext/apps_assets/gitphin"
cp "$TOP/core/files/index.html" "$TOP/core/files/index.html.gz" "$W/ext/apps_assets/gitphin/"
"$TOP/build/shots/gitphind" --root "$W/ext" --port "$SRV_PORT" >"$W/server.log" 2>&1 &
python3 -I "$HERE/demo_proxy.py" "$SRV_PORT" "$PROXY_PORT" &
for _ in $(seq 50); do curl -s "http://127.0.0.1:$PROXY_PORT/api/info" >/dev/null && break; sleep 0.1; done

(cd "$W" && npm init -y >/dev/null && npm i -s playwright-core@1.55.0 >/dev/null)
NODE_PATH="$W/node_modules" node "$HERE/capture.cjs" "http://127.0.0.1:$PROXY_PORT" "$W/web"
for f in "$W"/web/*.png; do
    case "$(basename "$f")" in phone-*) ;; *) cp "$f" "$OUT/" ;; esac
done

# ---- 2. the app's screens
if [[ -z "${WEB_ONLY:-}" ]]; then
    A="$HERE/android" B="$HERE/android/build"
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/build-tools" ]]; then
        AAPT2="$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)"
        JAR="$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)"
    else
        AAPT2="$(command -v aapt2)"
        JAR="$(ls /usr/lib/android-sdk/platforms/android-*/android.jar | sort -V | tail -1)"
    fi
    rm -rf "$B/res" "$B/gen" "$B/assets" "$B/test-config" && mkdir -p "$B/gen" "$B/assets/webui" "$B/test-config/com/android/tools"
    cp -r "$TOP/android/app/src/main/res" "$B/res"
    # attributes newer than an old android.jar knows; only the widget picker uses them
    sed -i -E 's/android:(previewLayout|targetCellWidth|targetCellHeight|maxResizeWidth|maxResizeHeight)="[^"]*"//g' \
        "$B/res/xml/gitphin_widget_info.xml"
    cp "$TOP/core/files/index.html" "$B/assets/webui/"   # PixelFont reads the pixel font from it
    cat >"$B/Manifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.adhamura.gitphindroid">
    <application android:theme="@style/AppTheme" android:label="Gitphin" />
</manifest>
XML
    "$AAPT2" compile --dir "$B/res" -o "$B/res.zip"
    "$AAPT2" link -o "$B/res.apk" -I "$JAR" --manifest "$B/Manifest.xml" --java "$B/gen" -A "$B/assets" \
        --auto-add-overlay --min-sdk-version 24 --target-sdk-version 36 "$B/res.zip"
    cat >"$B/test-config/com/android/tools/test_config.properties" <<PROPS
android_resource_apk=$B/res.apk
android_merged_assets=$B/assets
android_merged_manifest=$B/Manifest.xml
android_custom_package=io.github.adhamura.gitphindroid
PROPS
    GRADLE="${GRADLE:-$(command -v gradle || echo "$TOP/android/gradlew")}"
    "$GRADLE" -p "$A" test --console=plain -q -PshotsOut="$OUT" -PshotsWeb="$W/web" -PshotsPort="$SRV_PORT" \
        ${ROBO_JARS:+-PlocalJars="$ROBO_JARS"}
fi

# ---- 3. the picture at the top of the README
NODE_PATH="$W/node_modules" node "$HERE/hero.cjs" "$OUT"
ls -1 "$OUT"
