#!/usr/bin/env bash
# Test of repo avatars (port/features/avatar/avatar.c) with real git and curl, ASan/UBSan build.
# Usage: tests/run_avatar_test.sh   (KEEP=1 keeps the scratch dir)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(dirname "$HERE")"
PORT="${PORT:-8204}"
W="$(mktemp -d "${TMPDIR:-/tmp}/gitphind-avatar.XXXXXX")"
SRV_PID=""
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export ASAN_OPTIONS=detect_leaks=0
cleanup() {
    [[ -n "$SRV_PID" ]] && kill "$SRV_PID" 2>/dev/null && wait "$SRV_PID" 2>/dev/null
    [[ -n "${KEEP:-}" ]] && echo "kept $W" || rm -rf "$W"
}
trap cleanup EXIT

cmake -S "$TOP" -B "$TOP/build/avatar" -DGITPHIN_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug >/dev/null || exit 1
cmake --build "$TOP/build/avatar" -j8 >"$W/build.log" 2>&1 || { tail -30 "$W/build.log"; exit 1; }
BIN="$TOP/build/avatar/gitphind"

PASSED=(); FAILED=()
check() { local n="$1"; shift
    if "$@" >"$W/last.log" 2>&1; then PASSED+=("$n"); else FAILED+=("$n"); echo "--- FAIL: $n"; tail -n 15 "$W/last.log"; fi; }

ROOT="$W/ext"
mkdir -p "$ROOT/apps_assets/gitphin"
cp "$TOP/core/files/index.html" "$TOP/core/files/index.html.gz" "$ROOT/apps_assets/gitphin/" 2>/dev/null
"$BIN" --root "$ROOT" --port "$PORT" >"$W/server.log" 2>&1 &
SRV_PID=$!
URL="http://127.0.0.1:$PORT"
for _ in $(seq 50); do curl -s -o /dev/null "$URL/api/info" && break; sleep 0.1; done
GR="$ROOT/apps_data/gitphin/git"

R="$W/work"; mkdir -p "$R"; cd "$R"
git init -q -b main .
echo hello > README.md; seq 1 500 > n.txt
git add -A && git commit -q -m one
git push -q "$URL/git/a.git" main 2>"$W/push.log" || { cat "$W/push.log"; echo "push failed"; exit 1; }

# test pictures
python3 -I - "$W" <<'PY'
import sys, zlib, struct
w = sys.argv[1]
def chunk(t, d):
    c = struct.pack(">I", len(d)) + t + d
    return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
raw = b"".join(b"\x00" + bytes([x * 16 % 256, 80, 200]) * 16 for x in range(16))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
open(w + "/a.png", "wb").write(png)
open(w + "/a.jpg", "wb").write(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00" + bytes(range(200)) + b"\xff\xd9")
open(w + "/a.gif", "wb").write(b"GIF89a" + bytes(40))
open(w + "/a.webp", "wb").write(b"RIFF\x20\x00\x00\x00WEBPVP8 " + bytes(30))
open(w + "/t.txt", "wb").write(b"just text, not a picture\n")
open(w + "/big.png", "wb").write(png + bytes(3 * 1024 * 1024))
open(w + "/ok2m.png", "wb").write(png + bytes(2 * 1024 * 1024 - len(png)))
PY

H="$W/hdr"
code() { curl -s -o "$W/out" -w '%{http_code}' "$@"; }
is() { [[ "$(code "${@:2}")" == "$1" ]]; }
A="$URL/api/git/avatar?name=a"

check "GET none -> 404 json"       bash -c "[[ \$(curl -s -o $W/o -w '%{http_code}' '$A') == 404 ]] && grep -q '\"ok\":false' $W/o"
check "POST png ok"                bash -c "curl -s --data-binary @$W/a.png -H 'Content-Type: image/png' '$A' | grep -q '\"ok\":true,\"type\":\"image/png\",\"size\":'"
check "GET same bytes + image/png" bash -c "curl -s -D $H -o $W/g.png '$A' && cmp $W/a.png $W/g.png && grep -qi '^Content-Type: image/png' $H && grep -qi '^Cache-Control: no-cache' $H && grep -qi '^ETag:' $H"
ET="$(curl -s -D - -o /dev/null "$A" | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: //p')"
check "If-None-Match -> 304"       bash -c "[[ \$(curl -s -o /dev/null -w '%{http_code}' -H 'If-None-Match: $ET' '$A') == 304 ]]"
check "other ETag -> 200"          bash -c "[[ \$(curl -s -o /dev/null -w '%{http_code}' -H 'If-None-Match: \"zzz\"' '$A') == 200 ]]"
check "POST jpeg replaces"         bash -c "curl -s --data-binary @$W/a.jpg '$A' | grep -q image/jpeg && curl -s -D $H -o $W/g.jpg '$A' && cmp $W/a.jpg $W/g.jpg && grep -qi '^Content-Type: image/jpeg' $H"
check "ETag changed"               bash -c "[[ \"\$(curl -s -D - -o /dev/null '$A' | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: //p')\" != '$ET' ]]"
check "POST gif"                   bash -c "curl -s --data-binary @$W/a.gif '$A' | grep -q image/gif"
check "POST webp"                  bash -c "curl -s --data-binary @$W/a.webp '$A' | grep -q image/webp && curl -s -D $H -o /dev/null '$A' && grep -qi '^Content-Type: image/webp' $H"
check "POST text -> 415"           is 415 --data-binary @"$W/t.txt" "$A"
check "415 kept old picture"       bash -c "curl -s -D $H -o /dev/null '$A' && grep -qi 'image/webp' $H"
check "POST 3 MB -> 413"           is 413 --data-binary @"$W/big.png" "$A"
check "POST exactly 2 MB ok"       is 200 --data-binary @"$W/ok2m.png" "$A"
check "POST empty -> 4xx"          bash -c "c=\$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Length: 0' '$A'); [[ \$c == 411 || \$c == 400 ]]"
check "no tmp file left"           bash -c "! ls $GR/a.git/hub/avatar.png.tmp 2>/dev/null"
check "bad name -> 404"            is 404 "$URL/api/git/avatar?name=nope"
check "bad name chars -> 404"      is 404 "$URL/api/git/avatar?name=../a"
check "missing name -> 404"        is 404 "$URL/api/git/avatar"
check "POST bad repo -> 404"       is 404 --data-binary @"$W/a.png" "$URL/api/git/avatar?name=nope"
check "PUT -> 405"                 is 405 -X PUT --data-binary @"$W/a.png" "$A"
check "stored as hub/avatar.png"   bash -c "curl -s --data-binary @$W/a.png '$A' >/dev/null && cmp $W/a.png $GR/a.git/hub/avatar.png"
check "file API serves it"         bash -c "curl -sf '$URL/api/file?path=/ext/apps_data/gitphin/git/a.git/hub/avatar.png' -o $W/f.png && cmp $W/a.png $W/f.png"
check "file API PUT -> /api/git/avatar" bash -c "curl -sf -X PUT --data-binary @$W/a.jpg '$URL/api/file?path=/ext/apps_data/gitphin/git/a.git/hub/avatar.png' >/dev/null && curl -sf '$A' -o $W/g3.jpg && cmp $W/a.jpg $W/g3.jpg"
check "file API delete -> 404"     bash -c "curl -sf -X POST '$URL/api/delete?path=/ext/apps_data/gitphin/git/a.git/hub/avatar.png' >/dev/null; [[ \$(curl -s -o /dev/null -w '%{http_code}' '$A') == 404 ]]"
check "git clone works"            bash -c "git clone -q $URL/git/a.git $W/clone && cmp $W/clone/README.md $R/README.md"
check "git fsck clean"             bash -c "cd $W/clone && git fsck --strict 2>&1 && ! git fsck 2>&1 | grep -qi 'error\|missing\|corrupt'"
check "server fsck of repo clean"  bash -c "git --git-dir=$GR/a.git fsck 2>&1 | grep -vi '^Checking\|dangling' | { ! grep -qi 'error\|missing\|corrupt'; }"

curl -s --data-binary @"$W/a.png" "$A" >/dev/null
check "rename keeps avatar"        bash -c "curl -s -X POST '$URL/api/git/rename?name=a&to=b' | grep -q '\"ok\":true' && curl -s -o $W/g2.png '$URL/api/git/avatar?name=b' && cmp $W/a.png $W/g2.png && [[ \$(curl -s -o /dev/null -w '%{http_code}' '$A') == 404 ]]"
check "delete route"               bash -c "curl -s -X POST '$URL/api/git/avatar-delete?name=b' | grep -q '\"ok\":true' && [[ \$(curl -s -o /dev/null -w '%{http_code}' '$URL/api/git/avatar?name=b') == 404 ]]"
check "delete twice ok"            bash -c "curl -s -X POST '$URL/api/git/avatar-delete?name=b' | grep -q '\"ok\":true'"
check "delete bad repo -> 404"     bash -c "[[ \$(curl -s -o /dev/null -w '%{http_code}' -X POST '$URL/api/git/avatar-delete?name=nope') == 404 ]]"
check "delete GET -> 405"          is 405 "$URL/api/git/avatar-delete?name=b"
curl -s --data-binary @"$W/a.png" "$URL/api/git/avatar?name=b" >/dev/null
check "repo delete removes avatar" bash -c "curl -s -X POST '$URL/api/git/delete?name=b' | grep -q '\"ok\":true' && [[ \$(curl -s -o /dev/null -w '%{http_code}' '$URL/api/git/avatar?name=b') == 404 ]] && ! ls -d $GR/b.git 2>/dev/null"

check "page has the avatar code"   bash -c "curl -sf $URL/ | grep -q 'hub/avatar.png'"
check "/port/extra.js has no avatar add-on" bash -c "! curl -sf $URL/port/extra.js | grep -q 'api/git/avatar'"
if command -v node >/dev/null; then
    check "extra.js valid JS"      bash -c "curl -sf $URL/port/extra.js >$W/extra.js && node --check $W/extra.js"
fi
check "no sanitizer report"        bash -c "! grep -q 'ERROR: AddressSanitizer\|runtime error' $W/server.log"

echo
echo "passed: ${#PASSED[@]}  failed: ${#FAILED[@]}"
if ((${#FAILED[@]})); then printf 'FAILED: %s\n' "${FAILED[@]}"; exit 1; fi
echo "ALL TESTS PASSED"
