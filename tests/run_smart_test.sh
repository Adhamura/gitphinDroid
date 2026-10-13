#!/usr/bin/env bash
# End-to-end test of smart HTTP (git-upload-pack, git-receive-pack) in gitphind with real git,
# built with ASan/UBSan. Usage: tests/run_smart_test.sh   (KEEP=1 keeps the scratch dir,
# TIMING=0 skips the 100 MB smart vs dumb timing, PORT=n changes the port, default 8201)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(dirname "$HERE")"
PORT="${PORT:-8201}"
BUILD="$TOP/build/smart"
W="$(mktemp -d "${TMPDIR:-/tmp}/smart-test.XXXXXX")"
ROOT="$W/ext"
REPOS="$ROOT/apps_data/gitphin/git"
SRV_PID=""
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export ASAN_OPTIONS=detect_leaks=0

cleanup() {
    [[ -n "$SRV_PID" ]] && kill "$SRV_PID" 2>/dev/null && wait "$SRV_PID" 2>/dev/null
    [[ -n "${KEEP:-}" ]] && echo "kept $W" || rm -rf "$W"
}
trap cleanup EXIT

cmake -S "$TOP" -B "$BUILD" -DGITPHIN_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug >/dev/null || exit 1
cmake --build "$BUILD" -j8 >"$W/build.log" 2>&1 || { tail -30 "$W/build.log"; exit 1; }
BIN="$BUILD/gitphind"

start() {
    "$BIN" --root "$ROOT" --port "$PORT" >>"$W/server.log" 2>&1 &
    SRV_PID=$!
    for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$PORT/api/info" && return 0; sleep 0.1; done
    echo "server did not start"; cat "$W/server.log"; exit 1
}

PASSED=(); FAILED=()
check() {
    local name="$1"; shift
    if "$@" >"$W/last.log" 2>&1; then PASSED+=("$name"); else FAILED+=("$name"); echo "--- FAIL: $name"; tail -n 15 "$W/last.log"; fi
}
refuse() {
    local name="$1"; shift
    if "$@" >"$W/last.log" 2>&1; then FAILED+=("$name"); echo "--- FAIL (should be refused): $name"; tail -n 15 "$W/last.log"; else PASSED+=("$name"); fi
}
same() { [ "$(git -C "$1" rev-parse "$2")" = "$(git -C "$3" rev-parse "$4")" ]; }
fsck() { git -C "$1" fsck --strict --no-progress; }
# git used smart HTTP for this command (packet trace shows the service line)
smart_trace() { GIT_TRACE_PACKET="$W/trace.log" "$@" && grep -q "# service=git-" "$W/trace.log"; }
# pkt-line of a string
pkt() { printf '%04x%s' $(( ${#1} + 4 )) "$1"; }
# pkt-line of a command with capabilities after a NUL (bash strings cannot hold NUL)
pktcap() { printf '%04x%s\0%s' $(( ${#1} + ${#2} + 5 )) "$1" "$2"; }
verify_packs() { # every pack of the server repo
    local ok=0
    for i in "$REPOS/$1.git"/objects/pack/pack-*.idx; do git verify-pack -v "$i" >/dev/null || ok=1; done
    return $ok
}

URL="http://127.0.0.1:$PORT"
start

# ---- a source repo: folders, a binary file, branches, annotated and light tags
SRC="$W/src"
git init -q -b main "$SRC"
for i in 1 2 3 4 5; do
    mkdir -p "$SRC/dir$i/sub"
    seq 1 $((i * 700)) >"$SRC/dir$i/numbers.txt"
    seq $i 3 9000 >"$SRC/dir$i/sub/more.txt"
    echo "# Demo $i" >"$SRC/README.md"
    git -C "$SRC" add -A && git -C "$SRC" commit -q -m "commit $i"
done
head -c 300000 /dev/urandom >"$SRC/blob.bin"
git -C "$SRC" add -A && git -C "$SRC" commit -q -m "binary"
git -C "$SRC" tag -a v1 -m "release 1"
git -C "$SRC" tag light
git -C "$SRC" checkout -q -b feature && echo feature >"$SRC/f.txt" && git -C "$SRC" add -A && git -C "$SRC" commit -q -m feature
git -C "$SRC" checkout -q main

# ---- discovery
check "advertisement content type" bash -c "curl -sfD - -o /dev/null '$URL/git/none.git/info/refs?service=git-receive-pack' | grep -qi 'content-type: application/x-git-receive-pack-advertisement'"
check "push to create made the repo" test -f "$REPOS/none.git/HEAD"
check "new repo is like the core's" bash -c "grep -q 'ref: refs/heads/main' '$REPOS/none.git/HEAD' && grep -q 'Unnamed repository' '$REPOS/none.git/description' && test -f '$REPOS/none.git/info/refs' && test -f '$REPOS/none.git/objects/info/packs'"
check "empty repo advertises capabilities^{}" bash -c "curl -sf '$URL/git/none.git/info/refs?service=git-upload-pack' | grep -qa 'capabilities^{}'"
check "upload-pack discovery of a missing repo is 404" bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' '$URL/git/nothere.git/info/refs?service=git-upload-pack')\" = 404 ]"

# ---- push to create, clone
check "push to create (smart)" smart_trace git -C "$SRC" push -q "$URL/git/demo.git" main feature --tags
check "server has a pack, no loose objects" bash -c "ls '$REPOS/demo.git/objects/pack/'*.pack && ! ls -d '$REPOS/demo.git/objects/'[0-9a-f][0-9a-f] 2>/dev/null"
check "advertisement peels the annotated tag" bash -c "curl -sf '$URL/git/demo.git/info/refs?service=git-upload-pack' | grep -qa 'refs/tags/v1^{}'"
check "advertisement has symref HEAD" bash -c "curl -sf '$URL/git/demo.git/info/refs?service=git-upload-pack' | grep -qa 'symref=HEAD:refs/heads/main'"
check "clone v0" smart_trace git -c protocol.version=0 clone -q "$URL/git/demo.git" "$W/c0"
check "clone default protocol" smart_trace git clone -q "$URL/git/demo.git" "$W/c1"
check "clone has main" same "$W/c1" HEAD "$SRC" main
check "clone has feature" same "$W/c1" origin/feature "$SRC" feature
check "clone has tags" bash -c "git -C '$W/c1' rev-parse -q --verify v1 && git -C '$W/c1' rev-parse -q --verify light"
check "clone v0 fsck" fsck "$W/c0"
check "clone fsck" fsck "$W/c1"
check "server fsck" fsck "$REPOS/demo.git"
check "server packs verify-pack" verify_packs demo

# ---- incremental fetch
echo "one more line" >>"$SRC/dir1/numbers.txt"
git -C "$SRC" commit -q -am "small change"
check "push update" git -C "$SRC" push -q "$URL/git/demo.git" main
LOOSE0=$(git -C "$W/c1" count-objects -v | sed -n 's/^count: //p')
check "fetch after a new commit" git -C "$W/c1" fetch -q origin
check "fetch got it" same "$W/c1" origin/main "$SRC" main
check "fetch is incremental (few objects)" bash -c "n=\$(( \$(git -C '$W/c1' count-objects -v | sed -n 's/^count: //p') - $LOOSE0 )); echo new objects=\$n; [ \$n -ge 3 ] && [ \$n -le 6 ]"
check "fetch fsck" fsck "$W/c1"

# ---- push from the clone, force push, delete, tags
git -C "$W/c1" checkout -q main && git -C "$W/c1" merge -q --ff-only origin/main
echo "from clone" >>"$W/c1/README.md" && git -C "$W/c1" commit -q -am "from clone"
check "push from clone" git -C "$W/c1" push -q origin main
check "pull into source" git -C "$SRC" pull -q --ff-only "$URL/git/demo.git" main
refuse "non-fast-forward refused by the client" bash -c "cd '$W/c0' && echo x > x && git add x && git commit -qm x && git push -q origin main"
check "force push" git -C "$W/c0" push -q -f origin main
check "force push landed" bash -c "[ \"\$(git ls-remote '$URL/git/demo.git' refs/heads/main | cut -f1)\" = \"\$(git -C '$W/c0' rev-parse HEAD)\" ]"
check "delete branch" git -C "$W/c1" push -q origin :feature
check "branch is gone" bash -c "! git ls-remote --heads '$URL/git/demo.git' | grep -q feature"
git -C "$W/c0" tag -a v2 -m "release 2" && git -C "$W/c0" tag light2
check "push tags (annotated and light)" git -C "$W/c0" push -q origin v2 light2
check "tags are there" bash -c "git ls-remote --tags '$URL/git/demo.git' | grep -q 'refs/tags/v2^{}' && git ls-remote --tags '$URL/git/demo.git' | grep -q light2"
check "delete tag" git -C "$W/c0" push -q origin :refs/tags/light2
check "fetch tags (include-tag)" bash -c "git -C '$W/c1' fetch -q origin && git -C '$W/c1' rev-parse -q --verify v2"
check "server fsck after updates" fsck "$REPOS/demo.git"
check "server packs verify-pack after updates" verify_packs demo

# ---- refused pushes (crafted requests)
MAIN="$(git ls-remote "$URL/git/demo.git" refs/heads/main | cut -f1)"
WRONG="$(git -C "$SRC" rev-parse main~1)"
ZERO=0000000000000000000000000000000000000000
{ pktcap "$WRONG $ZERO refs/heads/main" report-status; printf 0000; } >"$W/stale.req"
check "stale old value refused" bash -c "curl -sf --data-binary @'$W/stale.req' -H 'Content-Type: application/x-git-receive-pack-request' '$URL/git/demo.git/git-receive-pack' | grep -qa 'ng refs/heads/main failed to lock'"
check "ref kept after the refused push" bash -c "[ \"\$(git ls-remote '$URL/git/demo.git' refs/heads/main | cut -f1)\" = '$MAIN' ]"
{ pktcap "$ZERO $MAIN refs/notes/x" report-status; printf 0000; } >"$W/funny.req"
check "ref outside heads/tags refused" bash -c "curl -sf --data-binary @'$W/funny.req' '$URL/git/demo.git/git-receive-pack' | grep -qa 'ng refs/notes/x'"

# ---- a thin pack (REF_DELTA bases in the repo) is completed on the server
git -C "$W/c0" checkout -q -b thin && seq 1 9000 | sed 's/5/five/' >"$W/c0/dir5/sub/more.txt" && git -C "$W/c0" commit -q -am thin
OLD="$(git -C "$W/c0" rev-parse thin~1)"; NEW="$(git -C "$W/c0" rev-parse thin)"
{ pktcap "$ZERO $NEW refs/heads/thin" report-status; printf 0000; printf '%s\n^%s\n' "$NEW" "$OLD" | git -C "$W/c0" pack-objects --thin --stdout --revs -q; } >"$W/thin.req"
check "thin pack push" bash -c "curl -sf -H 'Transfer-Encoding: chunked' --data-binary @'$W/thin.req' '$URL/git/demo.git/git-receive-pack' | grep -qa 'ok refs/heads/thin'"
check "thin pack: server fsck" fsck "$REPOS/demo.git"
check "thin pack: verify-pack" verify_packs demo
check "thin pack: clone has the branch" bash -c "git clone -q -b thin '$URL/git/demo.git' '$W/cthin' && git -C '$W/cthin' fsck --strict"

# ---- request codings: gzip and chunked upload-pack requests
{ pkt "want $MAIN multi_ack_detailed side-band-64k ofs-delta"; printf 0000; pkt "done"; } >"$W/up.req"
gzip -c "$W/up.req" >"$W/up.req.gz"
check "gzip upload-pack request" bash -c "curl -sf -H 'Content-Encoding: gzip' --data-binary @'$W/up.req.gz' '$URL/git/demo.git/git-upload-pack' | grep -qa PACK"
check "chunked upload-pack request" bash -c "curl -sf -H 'Transfer-Encoding: chunked' --data-binary @'$W/up.req' '$URL/git/demo.git/git-upload-pack' | grep -qa PACK"

# ---- shallow
check "clone --depth 1" git clone -q --depth 1 "$URL/git/demo.git" "$W/sh"
check "depth 1 has one commit" bash -c "[ \"\$(git -C '$W/sh' rev-list --count HEAD)\" = 1 ] && test -f '$W/sh/.git/shallow'"
check "shallow fsck" fsck "$W/sh"
check "fetch --deepen 2" git -C "$W/sh" fetch -q --deepen 2
check "deepen gave 3 commits" bash -c "[ \"\$(git -C '$W/sh' rev-list --count HEAD)\" = 3 ]"
check "deepened fsck" fsck "$W/sh"
check "fetch --unshallow" git -C "$W/sh" fetch -q --unshallow
check "unshallow is complete" bash -c "[ \"\$(git -C '$W/sh' rev-list --count HEAD)\" = \"\$(git -C '$W/c0' rev-list --count main)\" ] && ! test -f '$W/sh/.git/shallow'"
check "unshallow fsck" fsck "$W/sh"
check "clone --depth 2 (protocol v0)" bash -c "git -c protocol.version=0 clone -q --depth 2 '$URL/git/demo.git' '$W/sh2' && [ \"\$(git -C '$W/sh2' rev-list --count HEAD)\" = 2 ] && git -C '$W/sh2' fsck --strict"
check "clone --shallow-exclude (deepen-not)" bash -c "git clone -q --shallow-exclude=v1 '$URL/git/demo.git' '$W/sh3' && n=\$(git -C '$W/sh3' rev-list --count HEAD) && [ \$n -ge 1 ] && [ \$n -lt \$(git -C '$W/c0' rev-list --count main) ] && git -C '$W/sh3' fsck --strict"
check "clone --shallow-since (deepen-since)" bash -c "git clone -q --shallow-since=2000-01-01 '$URL/git/demo.git' '$W/sh4' && git -C '$W/sh4' fsck --strict"

# ---- many objects and binary files, with deltas
BIG="$W/many"
git init -q -b main "$BIG"
for round in 1 2 3; do
    for i in $(seq 1 300); do
        d="$BIG/d$((i % 17))"; mkdir -p "$d"
        if (( i % 10 == 0 )); then head -c 20000 /dev/urandom >"$d/bin$i.dat"; else seq $i $((i + 400 + round)) >"$d/t$i.txt"; fi
    done
    git -C "$BIG" add -A && git -C "$BIG" commit -q -m "round $round"
done
check "push many objects" git -C "$BIG" push -q "$URL/git/many.git" main
check "clone many objects" git clone -q "$URL/git/many.git" "$W/many2"
check "many: same tree" same "$W/many2" HEAD^{tree} "$BIG" HEAD^{tree}
check "many: clone fsck" fsck "$W/many2"
check "many: server fsck" fsck "$REPOS/many.git"
check "many: verify-pack" verify_packs many

# ---- dumb protocol still works next to smart
check "dumb clone (GIT_SMART_HTTP=0)" env GIT_SMART_HTTP=0 git clone -q "$URL/git/demo.git" "$W/dumb"
check "dumb clone matches" same "$W/dumb" HEAD "$W/c0" main
check "dumb clone fsck" fsck "$W/dumb"
check "dumb push (WebDAV) still works" bash -c "cd '$W/dumb' && echo d >> README.md && git commit -qam dumb && GIT_SMART_HTTP=0 git push -q origin HEAD:refs/heads/dumbpush"
check "smart clone after dumb push" bash -c "git clone -q -b dumbpush '$URL/git/demo.git' '$W/afterdumb' && git -C '$W/afterdumb' fsck --strict"

# ---- the web API on smart-pushed repos
check "api repos" bash -c "curl -sf '$URL/api/git/repos' | grep -q '\"many\"'"
check "api log" bash -c "curl -sf '$URL/api/git/log?name=many&ref=main' | grep -q 'round 3'"
check "api log demo" bash -c "curl -sf '$URL/api/git/log?name=demo&ref=main' | grep -q 'commit 5'"
check "api tree" bash -c "curl -sf '$URL/api/git/tree?name=many&ref=main' | grep -q d1"
check "api diff" bash -c "curl -sf '$URL/api/git/diff?name=many&sha=$(git -C "$BIG" rev-parse HEAD)' | python3 -c 'import json,sys; json.load(sys.stdin)'"
check "api search" bash -c "curl -sf '$URL/api/git/search?name=demo&ref=main&q=Demo' | grep -q README"
check "api pack (idle packer) and fsck" bash -c "curl -sf -X POST '$URL/api/git/pack?name=demo' | grep -q '\"ok\":true' && git -C '$REPOS/demo.git' fsck --strict && git clone -q '$URL/git/demo.git' '$W/afterpack' && git -C '$W/afterpack' fsck --strict"

# ---- timing: ~100 MB, ~2,000 objects, smart vs dumb
if [[ "${TIMING:-1}" != 0 ]]; then
    T="$W/timing"
    git init -q -b main "$T"
    for i in $(seq 1 1960); do mkdir -p "$T/d$((i % 20))"; head -c 51000 /dev/urandom >"$T/d$((i % 20))/f$i.bin"; done
    git -C "$T" add -A && git -C "$T" commit -q -m big
    objs=$(git -C "$T" count-objects -v | sed -n 's/^count: //p'); size=$(du -sm "$T/.git/objects" | cut -f1)
    t() { local s=$EPOCHREALTIME; "$@" >>"$W/timing.log" 2>&1; local rc=$?; python3 -c "print(round($EPOCHREALTIME - $s, 2))"; return $rc; }
    ps=$(t git -C "$T" push -q "$URL/git/tsmart.git" main) || FAILED+=("timing smart push")
    cs=$(t git clone -q "$URL/git/tsmart.git" "$W/tc1") || FAILED+=("timing smart clone")
    curl -sf -X POST "$URL/api/git/create?name=tdumb" >/dev/null # dumb push cannot create a repo
    pd=$(GIT_SMART_HTTP=0 t git -C "$T" push -q "$URL/git/tdumb.git" main) || FAILED+=("timing dumb push")
    cd_=$(GIT_SMART_HTTP=0 t git clone -q "$URL/git/tdumb.git" "$W/tc2") || FAILED+=("timing dumb clone")
    check "timing clones fsck" bash -c "git -C '$W/tc1' fsck --strict && git -C '$W/tc2' fsck --strict"
    echo "TIMING ($objs objects, ${size} MB): smart push ${ps}s, smart clone ${cs}s, dumb push ${pd}s, dumb clone ${cd_}s"
fi

kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=""
if grep -qE 'ERROR: AddressSanitizer|runtime error' "$W/server.log"; then
    FAILED+=("sanitizers"); grep -E -A20 'AddressSanitizer|runtime error' "$W/server.log" | head -40
else
    PASSED+=("sanitizers")
fi

echo "passed: ${#PASSED[@]}  failures: ${#FAILED[@]}"
[[ ${#FAILED[@]} -eq 0 ]] && echo "ALL TESTS PASSED" || { printf ' - %s\n' "${FAILED[@]}"; exit 1; }
