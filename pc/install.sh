#!/usr/bin/env bash
# Installs gitphin-usb as a systemd user service (starts at login, no root needed).
# Usage: pc/install.sh            Remove: pc/install.sh --uninstall
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
UNIT="$HOME/.config/systemd/user/gitphin-usb.service"
if [[ "${1:-}" == "--uninstall" ]]; then
    systemctl --user disable --now gitphin-usb.service 2>/dev/null || true
    rm -f "$UNIT"
    systemctl --user daemon-reload
    echo "gitphin-usb removed"
    exit 0
fi
ADB="$(command -v adb || echo /opt/android-sdk/platform-tools/adb)"
[[ -x "$ADB" ]] || { echo "adb not found: install android-tools (or the Android SDK platform-tools)"; exit 1; }
mkdir -p "$(dirname "$UNIT")"
cat >"$UNIT" <<UNIT
[Unit]
Description=Gitphin Droid USB link (http://127.0.0.1:8080 <-> Gitphin on the phone)

[Service]
Type=simple
Environment=ADB=$ADB
ExecStart=/usr/bin/python3 -I $HERE/gitphin-usb
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
UNIT
chmod +x "$HERE/gitphin-usb"
systemctl --user daemon-reload
systemctl --user enable --now gitphin-usb.service
echo "gitphin-usb installed. Plug in the phone (USB debugging on), then open http://127.0.0.1:8080"
