#!/usr/bin/env bash
# End-to-end test of gitphind (the Android server, built for Linux) with real git:
# push a new repo, clone, push again, pull, idle packing, web UI and API, and the password for
# non-loopback clients. Usage: tests/run_host_test.sh   (KEEP=1 keeps the scratch dir)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(dirname "$HERE")"
PORT="${PORT:-8197}"
W="$(mktemp -d "${TMPDIR:-/tmp}/gitphind-test.XXXXXX")"
ROOT="$W/ext"
SRV_PID=""
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export ASAN_OPTIONS=detect_leaks=0

cleanup() {
    [[ -n "$SRV_PID" ]] && kill "$SRV_PID" 2>/dev/null && wait "$SRV_PID" 2>/dev/null
    [[ -n "${KEEP:-}" ]] && echo "kept $W" || rm -rf "$W"
}
trap cleanup EXIT

cmake -S "$TOP" -B "$TOP/build/host" -DGITPHIN_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug >/dev/null || exit 1
cmake --build "$TOP/build/host" -j8 >"$W/build.log" 2>&1 || { tail -30 "$W/build.log"; exit 1; }
BIN="$TOP/build/host/gitphind"

mkdir -p "$ROOT/apps_assets/gitphin"
cp "$TOP/core/files/index.html" "$TOP/core/files/index.html.gz" "$ROOT/apps_assets/gitphin/"

start() { # start <bind> <password> [git-dir] [extra args...]
    local b="$1" p="$2" g="${3:-}"; shift 3 2>/dev/null || shift $#
    "$BIN" --root "$ROOT" --bind "$b" --port "$PORT" --password "$p" ${g:+--git-dir "$g"} "$@" >>"$W/server.log" 2>&1 &
    SRV_PID=$!
    for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$PORT/api/info" && return 0; sleep 0.1; done
    echo "server did not start"; cat "$W/server.log"; exit 1
}
stop() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=""; }

PASSED=(); FAILED=()
check() {
    local name="$1"; shift
    if "$@" >"$W/last.log" 2>&1; then PASSED+=("$name"); else FAILED+=("$name"); echo "--- FAIL: $name"; tail -n 15 "$W/last.log"; fi
}
refuse() {
    local name="$1"; shift
    if "$@" >"$W/last.log" 2>&1; then FAILED+=("$name"); echo "--- FAIL (should be refused): $name"; tail -n 15 "$W/last.log"; else PASSED+=("$name"); fi
}

URL="http://127.0.0.1:$PORT"
start 127.0.0.1 ""

# a source repo with a few commits, a subfolder, a binary file and a tag
SRC="$W/src"
git init -q -b main "$SRC"
for i in 1 2 3; do
    mkdir -p "$SRC/dir$i"
    seq 1 $((i * 500)) >"$SRC/dir$i/numbers.txt"
    echo "# Demo $i" >"$SRC/README.md"
    git -C "$SRC" add -A && git -C "$SRC" commit -q -m "commit $i"
done
head -c 200000 /dev/urandom >"$SRC/blob.bin"
git -C "$SRC" add -A && git -C "$SRC" commit -q -m "binary"
git -C "$SRC" tag v1
git -C "$SRC" checkout -q -b feature && echo feature >"$SRC/f.txt" && git -C "$SRC" add -A && git -C "$SRC" commit -q -m feature
git -C "$SRC" checkout -q main

check "push new repo" git -C "$SRC" push "$URL/git/demo.git" main feature --tags
check "bare repo on disk" test -f "$ROOT/apps_data/gitphin/git/demo.git/HEAD"
check "clone" git clone -q "$URL/git/demo.git" "$W/c1"
check "clone has main" bash -c "[ \"\$(git -C '$W/c1' rev-parse HEAD)\" = \"\$(git -C '$SRC' rev-parse main)\" ]"
check "clone fsck" git -C "$W/c1" fsck --strict
check "clone has tag" git -C "$W/c1" rev-parse -q --verify v1

# push from the clone, pull into the source
echo "change from clone" >>"$W/c1/README.md"
git -C "$W/c1" commit -q -am "from clone"
check "push update" git -C "$W/c1" push -q origin main
check "pull" git -C "$SRC" pull -q --ff-only "$URL/git/demo.git" main
check "pull got commit" bash -c "[ \"\$(git -C '$SRC' rev-parse main)\" = \"\$(git -C '$W/c1' rev-parse HEAD)\" ]"
refuse "non-fast-forward refused" bash -c "cd '$W/c1' && git reset -q --hard HEAD~2 && echo x > x && git add x && git commit -qm x && git push -q origin main"

# web UI and API
check "index.html" bash -c "curl -sf '$URL/' | grep -qi '<html'"
check "index gzip" bash -c "curl -sf -H 'Accept-Encoding: gzip' -D - -o /dev/null '$URL/' | grep -qi 'content-encoding: gzip'"
check "api info" bash -c "curl -sf '$URL/api/info' | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d[\"name\"]==\"Linux host\"'"
check "api repos" bash -c "curl -sf '$URL/api/git/repos' | grep -q demo"
check "api log (query plugin)" bash -c "curl -sf '$URL/api/git/log?name=demo&ref=main' | grep -q 'from clone'"
check "api diff (query plugin)" bash -c "curl -sf '$URL/api/git/diff?name=demo&sha=$(git -C "$SRC" rev-parse main)' | python3 -c 'import json,sys; json.load(sys.stdin)'"
check "api search (query plugin)" bash -c "curl -sf '$URL/api/git/search?name=demo&ref=main&q=Demo' | grep -q README"
check "collector /pc/info" bash -c "curl -sf '$URL/pc/info' | python3 -c 'import json,sys; assert json.load(sys.stdin)[\"os\"]==\"Linux\"'"
check "collector /pc/hub idle" bash -c "curl -sf '$URL/pc/hub' | grep -q '\"busy_s\":null'"
check "collector /pc/wifi" bash -c "curl -sf '$URL/pc/wifi' | grep -q connected"
check "/pc/hub answers while a request is busy" bash -c "exec 3<>/dev/tcp/127.0.0.1/$PORT; printf 'PUT /api/file?path=/ext/x HTTP/1.1\r\nContent-Length: 100\r\n\r\nabc' >&3; sleep 0.5; curl -sf -m 2 '$URL/pc/hub' | grep -q '\"busy_s\":[0-9]'"
check "via phone (loopback)" bash -c "curl -sf -o /dev/null '$URL/api/info' && curl -sf '$URL/pc/hub' | grep -q '\"via\":\"phone\"'"
check "via usb (proxy header)" bash -c "curl -sf -o /dev/null -H 'X-Gitphin-Via: usb' '$URL/api/info' && curl -sf '$URL/pc/hub' | grep -q '\"via\":\"usb\"'"
check "commit-file 1 MB (EXT_BODY_MAX)" bash -c "head -c 1000000 /dev/urandom > '$W/big.bin'; old=\$(git -C '$SRC' rev-parse main); curl -sf -X POST --data-binary @'$W/big.bin' '$URL/api/git/commit-file?name=demo&branch=main&old='\$(curl -sf '$URL/api/git/refs?name=demo' | python3 -c 'import json,sys; d=json.load(sys.stdin); print([r for r in d[\"refs\"] if r[\"name\"]==\"refs/heads/main\"][0][\"sha\"])')'&path=inbox/big.bin&message=share' | grep -q '\"ok\":true'"
check "spare connection does not block" bash -c "exec 3<>/dev/tcp/127.0.0.1/$PORT; timeout 3 curl -sf '$URL/api/info' >/dev/null"

# idle packing: after 10 s without requests the worker packs loose objects
sleep 14
for _ in $(seq 60); do grep -q '"activity":2' <(curl -s "$URL/api/info") || break; sleep 1; done
check "clone after idle pack" git clone -q "$URL/git/demo.git" "$W/c2"
check "clone2 fsck" git -C "$W/c2" fsck --strict
check "repo fsck on server" git -C "$ROOT/apps_data/gitphin/git/demo.git" fsck --strict
stop

# LAN mode: loopback stays free, other addresses need the password
LANIP="$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)"
start 0.0.0.0 "s3cret-pass"
check "loopback without password" curl -sf -o /dev/null "$URL/api/info"
if [[ -n "$LANIP" ]]; then
    LURL="http://$LANIP:$PORT"
    check "LAN without password is 401" bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' '$LURL/api/info')\" = 401 ]"
    check "LAN wrong password is 401" bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -u x:nope '$LURL/api/info')\" = 401 ]"
    check "LAN with password" curl -sf -o /dev/null -u x:s3cret-pass "$LURL/api/info"
    check "LAN clone with password" git clone -q "http://gitphin:s3cret-pass@$LANIP:$PORT/git/demo.git" "$W/c3"
    refuse "LAN clone without password" git clone -q "$LURL/git/demo.git" "$W/c4"
    echo lan >>"$W/c3/README.md"; git -C "$W/c3" commit -q -am lan
    check "LAN push with password" git -C "$W/c3" push -q origin main
    check "via wifi" bash -c "curl -sf '$URL/pc/hub' | grep -q '\"via\":\"wifi\"'"
    check "LAN cannot fake usb" bash -c "curl -sf -o /dev/null -u x:s3cret-pass -H 'X-Gitphin-Via: usb' '$LURL/api/info' && curl -sf '$URL/pc/hub' | grep -q '\"via\":\"wifi\"'"
    stop
    start 0.0.0.0 "s3cret-pass" "" --user kazuki
    check "LAN right user" curl -sf -o /dev/null -u kazuki:s3cret-pass "$LURL/api/info"
    check "LAN wrong user is 401" bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -u other:s3cret-pass '$LURL/api/info')\" = 401 ]"
    check "LAN clone with user" git clone -q "http://kazuki:s3cret-pass@$LANIP:$PORT/git/demo.git" "$W/c6"
    stop
    start 0.0.0.0 "" "" --open
    check "LAN open without password" curl -sf -o /dev/null "$LURL/api/info"
    check "LAN open clone" git clone -q "$LURL/git/demo.git" "$W/c7"
    stop
    start 0.0.0.0 ""
    check "LAN with access off is 403" bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -u x:s3cret-pass '$LURL/api/info')\" = 403 ]"
    check "loopback with access off" curl -sf -o /dev/null "$URL/api/info"
else
    echo "(no LAN address: LAN checks skipped)"
fi
stop

# repos in their own folder (the app's "Repos folder" setting)
start 127.0.0.1 "" "$W/myrepos"
check "git-dir push" git -C "$SRC" push -q "$URL/git/other.git" main
check "git-dir repo is in the folder" test -f "$W/myrepos/other.git/HEAD"
check "git-dir not under root" test ! -e "$ROOT/apps_data/gitphin/git/other.git"
check "git-dir clone" git clone -q "$URL/git/other.git" "$W/c5"
check "git-dir repos list" bash -c "curl -sf '$URL/api/git/repos' | grep -q other"
stop

if grep -qE 'ERROR: AddressSanitizer|runtime error' "$W/server.log"; then
    FAILED+=("sanitizers"); grep -E -A20 'AddressSanitizer|runtime error' "$W/server.log" | head -40
else
    PASSED+=("sanitizers")
fi

echo "passed: ${#PASSED[@]}  failures: ${#FAILED[@]}"
[[ ${#FAILED[@]} -eq 0 ]] && echo "ALL TESTS PASSED" || { printf ' - %s\n' "${FAILED[@]}"; exit 1; }
