#!/usr/bin/env bash
# Copies the shared C core and the web UI from a Gitphin checkout into core/ (files unchanged except the patches below).
# Usage: ./sync_core.sh [path/to/gitphin]     (default: ../gitphin)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "${1:-$HERE/../gitphin}" && pwd)/flipper_app"
# Flipper-only files that port/ replaces: the app entry and screen, the USB transport,
# the .fal plugin loader and the guarded allocator.
SKIP='^(gitphin\.c|hub_transport\.c|hub_plugin\.c|hub_mem\.c|gitphin_logo_xbm\.h|hub_api_table\.inc)$'
rm -rf "$HERE/core"
mkdir -p "$HERE/core/files"
# Gitphin 1.2 keeps the sources in src/<area>/ (basenames are unique). Big files are unit files that
# #include their parts; CMakeLists.txt compiles only the units. Includes across folders are
# relative ("../obj/x.h"): they become flat names here.
for f in "$SRC"/src/*/*.c "$SRC"/src/*/*.h "$SRC"/src/*/*.inc; do
    b="$(basename "$f")"
    [[ "$b" =~ $SKIP ]] || sed -E 's|^(\s*#\s*include\s+")\.\./[a-z]+/|\1|' "$f" > "$HERE/core/$b"
done
cp "$SRC"/files/index.html "$SRC"/files/index.html.gz "$HERE/core/files/"
cp "$SRC/../LICENSE" "$HERE/core/LICENSE"
cp "$SRC/../pc/gitphin_bridge/collector.py" "$HERE/pc/collector.py" # the Collector tab data, used by pc/gitphin-usb
# The only change to the core: the 64 KB upload limit of commit-file (Flipper RAM) becomes a
# build setting (CMakeLists.txt sets 32 MB). Fails loudly when upstream changes these lines.
F="$HERE/core/hub_gitext_int.h"
grep -q '^#define EXT_BODY_MAX    (64 \* 1024)$' "$F" || { echo "patch: EXT_BODY_MAX line changed upstream"; exit 1; }
sed -i 's|^#define EXT_BODY_MAX    (64 \* 1024)$|#ifndef EXT_BODY_MAX /* gitphinDroid: set by the build */\n#define EXT_BODY_MAX    (64 * 1024)\n#endif|' "$F"
W="$HERE/core/hub_gitext_write.c"
grep -q '"file too large (max 64 KB)"' "$W" || { echo "patch: size message changed upstream"; exit 1; }
sed -i 's|"file too large (max 64 KB)"|"file too large"|' "$W"
# Hooks for the port (port/server/hooks.c): routes before the git routes (smart HTTP, ZIP, page
# extras) and a call after each request (webhooks). Fails loudly when the anchors change upstream.
H="$HERE/core/hub_http.c"
grep -q '^        } else if(hub_git_route(r)) {$' "$H" || { echo "patch: route anchor changed upstream"; exit 1; }
grep -q '^    prog_end(r);$' "$H" || { echo "patch: prog_end anchor changed upstream"; exit 1; }
grep -q '^#include "hub_mem.h"$' "$H" || { echo "patch: include anchor changed upstream"; exit 1; }
sed -i 's|^#include "hub_mem.h"$|#include "port_hooks.h" /* gitphinDroid */\n#include "hub_mem.h"|' "$H"
sed -i 's|^        } else if(hub_git_route(r)) {$|        } else if(port_route(r)) {\n            /* gitphinDroid: port/server/hooks.c */\n        } else if(hub_git_route(r)) {|' "$H"
sed -i 's|^    prog_end(r);$|    prog_end(r);\n    port_after_request(r); /* gitphinDroid */|' "$H"
# The page loads /port/extra.js (port/web/*.js: phone wording).
I="$HERE/core/files/index.html"
grep -q '</body>' "$I" || { echo "patch: </body> missing in index.html"; exit 1; }
sed -i 's|</body>|<script src="/port/extra.js" defer></script></body>|' "$I"
gzip -9 -n -c "$I" > "$I.gz"
git -C "$SRC/.." rev-parse --short HEAD > "$HERE/core/UPSTREAM" 2>/dev/null || true
echo "core/ synced from $SRC ($(ls "$HERE/core"/*.c | wc -l) C files)"
