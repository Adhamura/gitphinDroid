#!/usr/bin/env bash
# Resources for the Robolectric screens: build/res.apk (aapt2), build/gen/R.java, build/assets
# and build/test-config. Run by ../capture.sh and ../../promo/make_promo.sh.
# aapt2 and android.jar come from $ANDROID_HOME, or from Debian/Ubuntu packages
# (apt install aapt android-sdk-platform-23).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(cd "$HERE/../../.." && pwd)"
B="$HERE/build"
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
