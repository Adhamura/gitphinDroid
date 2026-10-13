#!/usr/bin/env bash
# Test of "Download as ZIP" (port/features/zip/) with real git, unzip and python zipfile.
# Usage: tests/run_zip_test.sh   (KEEP=1 keeps the scratch dir)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(dirname "$HERE")"
PORT="${PORT:-8202}"
W="$(mktemp -d "${TMPDIR:-/tmp}/gitphind-zip.XXXXXX")"
SRV_PID=""
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export ASAN_OPTIONS=detect_leaks=0
cleanup() {
    [[ -n "$SRV_PID" ]] && kill "$SRV_PID" 2>/dev/null && wait "$SRV_PID" 2>/dev/null
    [[ -n "${KEEP:-}" ]] && echo "kept $W" || rm -rf "$W"
}
trap cleanup EXIT

build() { # build <dir> [extra cmake args]
    local d="$1"; shift
    cmake -S "$TOP" -B "$d" -DGITPHIN_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug "$@" >/dev/null || exit 1
    cmake --build "$d" -j8 >"$W/build.log" 2>&1 || { tail -30 "$W/build.log"; exit 1; }
}
build "$TOP/build/zip"
BIN="$TOP/build/zip/gitphind"

PASSED=(); FAILED=()
check() { local n="$1"; shift
    if "$@" >"$W/last.log" 2>&1; then PASSED+=("$n"); else FAILED+=("$n"); echo "--- FAIL: $n"; tail -n 15 "$W/last.log"; fi; }

start() { # start <bin> <root> [env...]
    local bin="$1" root="$2"; shift 2
    mkdir -p "$root/apps_assets/gitphin"
    cp "$TOP/core/files/index.html" "$TOP/core/files/index.html.gz" "$root/apps_assets/gitphin/" 2>/dev/null
    env "$@" "$bin" --root "$root" --port "$PORT" >>"$W/server.log" 2>&1 &
    SRV_PID=$!
    for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$PORT/api/info" && return 0; sleep 0.1; done
    echo "server did not start"; cat "$W/server.log"; exit 1
}
stop() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=""; }
URL="http://127.0.0.1:$PORT"

# ---- the test repo ----
R="$W/work"; mkdir -p "$R"; cd "$R"
git init -q -b main .
mkdir -p src/deep/er docs "dir with space"
echo "hello" > README.md
printf '#!/bin/sh\necho hi\n' > run.sh; chmod +x run.sh
echo "nested" > src/deep/er/file.txt
echo "int main(){}" > src/main.c
ln -s README.md link-to-readme
ln -s ../src/main.c docs/link2
head -c 3000 /dev/urandom > blob.bin
: > empty.txt
echo "utf8" > "日本語-ファイル é.txt"
echo "sp" > "dir with space/a b.txt"
head -c 20000000 /dev/urandom > big.bin
head -c 300000 /dev/urandom > stored.png
seq 1 200000 > text.txt
git add -A && git commit -q -m one
git tag v1
echo "more" >> README.md
echo "second" > src/second.txt
git add -A && git commit -q -m two
SHA1="$(git rev-parse v1)"
SHA2="$(git rev-parse HEAD)"

start "$BIN" "$W/ext"
git push -q "$URL/git/z.git" main 2>"$W/push.log" || { cat "$W/push.log"; echo "push failed"; exit 1; }
git push -q "$URL/git/z.git" v1 2>>"$W/push.log" || true

H="$W/hdr"
# compare_zip <label> <url> <git-ref> <expected top> [pathspec]
compare_zip() {
    local label="$1" url="$2" ref="$3" top="$4" spec="${5:-}"
    local z="$W/$label.zip" ex="$W/$label.x" ref_x="$W/$label.r"
    rm -rf "$ex" "$ref_x"; mkdir -p "$ex" "$ref_x"
    curl -sS -D "$H.$label" -o "$z" "$url" || return 1
    grep -qi '^HTTP/1.1 200' "$H.$label" || { cat "$H.$label"; return 1; }
    grep -qi '^Content-Type: application/zip' "$H.$label" || return 1
    grep -qi '^Content-Disposition: attachment; filename="z-.*\.zip"' "$H.$label" || return 1
    grep -qi '^Transfer-Encoding: chunked' "$H.$label" || return 1
    unzip -tq "$z" >/dev/null || { unzip -t "$z" | tail; return 1; }
    python3 -I -m zipfile -t "$z" >/dev/null || return 1
    unzip -q "$z" -d "$ex" || return 1
    (cd "$R" && git archive --format=tar "$ref" $spec) | tar -x -C "$ref_x" || return 1
    [[ -d "$ex/$top" ]] || { ls "$ex"; return 1; }
    diff -r "$ex/$top" "$ref_x${spec:+/$spec}" || return 1
    # exec bit and symlinks match
    ref_x="$ref_x${spec:+/$spec}"
    (cd "$ref_x" && find . -type f -perm -u+x | sort) >"$W/x1"
    (cd "$ex/$top" && find . -type f -perm -u+x | sort) >"$W/x2"
    diff "$W/x1" "$W/x2" || return 1
    (cd "$ref_x" && find . -type l -printf '%p -> %l\n' | sort) >"$W/l1"
    (cd "$ex/$top" && find . -type l -printf '%p -> %l\n' | sort) >"$W/l2"
    diff "$W/l1" "$W/l2" || return 1
}
check "zip main (route /git/..)"       compare_zip main "$URL/git/z.git/archive/main.zip" main z-main
check "zip tag v1"                      compare_zip tag "$URL/git/z.git/archive/v1.zip" v1 z-v1
check "zip sha"                         compare_zip sha "$URL/git/z.git/archive/$SHA1.zip" "$SHA1" "z-$SHA1"
check "zip api main"                    compare_zip api "$URL/api/git/archive?name=z&ref=main" main z-main
check "zip api path=src"                compare_zip sub "$URL/api/git/archive?name=z&ref=main&path=src" main z-main/src src
check "zip api path=src/deep/er/"       compare_zip sub2 "$URL/api/git/archive?name=z&ref=v1&path=src/deep/er/" v1 z-v1/src/deep/er src/deep/er
check "zip refs/heads/main encoded"     compare_zip enc "$URL/git/z.git/archive/refs%2Fheads%2Fmain.zip" main z-refs-heads-main
check "stored file is method 0"         bash -c "python3 -I -c \"
import zipfile,sys
z=zipfile.ZipFile('$W/main.zip')
i={x.filename:x for x in z.infolist()}
t='z-main/'
assert i[t+'stored.png'].compress_type==0, 'png stored'
assert i[t+'big.bin'].compress_type==8, 'bin deflated'
assert i[t+'text.txt'].compress_type==8 and i[t+'text.txt'].compress_size < i[t+'text.txt'].file_size*8//10, 'text compressed'
assert i[t+'empty.txt'].file_size==0
assert i[t+'src/'].is_dir() and i[t].is_dir()
assert all(x.flag_bits & 0x808 for x in z.infolist() if not x.is_dir())
assert any(n.startswith(t+'日本語') for n in i), 'utf8 name'
assert (i[t+'run.sh'].external_attr>>16)==0o100755
assert (i[t+'link-to-readme'].external_attr>>16)==0o120777
assert z.read(t+'link-to-readme')==b'README.md'
assert i[t+'README.md'].date_time[0]>=2020
\""
check "headers: Content-Disposition"    bash -c "grep -i '^Content-Disposition: attachment; filename=\"z-refs-heads-main.zip\"' $H.enc && grep -i 'filename=\"z-main.zip\"' $H.main"
bad() { # bad <url> <code>
    local c; c="$(curl -s -o "$W/err.json" -w '%{http_code}' "$1")"; [[ "$c" == "$2" ]] && grep -q '"ok":false' "$W/err.json"
}
check "404 bad repo"                    bad "$URL/git/nope.git/archive/main.zip" 404
check "404 bad ref"                     bad "$URL/git/z.git/archive/nothere.zip" 404
check "404 bad path"                    bad "$URL/api/git/archive?name=z&ref=main&path=nothing/here" 404
check "404 path is a file"              bad "$URL/api/git/archive?name=z&ref=main&path=README.md" 404
check "404 path dotdot"                 bad "$URL/api/git/archive?name=z&ref=main&path=../x" 404
check "404 api bad repo"                bad "$URL/api/git/archive?name=nope&ref=main" 404
check "no spool file left"              bash -c "! ls $W/ext/apps_data/gitphin/git/z.git/hub/tmp/zipcd 2>/dev/null"
check "page has the Download ZIP button"  bash -c "curl -sf $URL/ | grep -q 'id=\"rv-zip\"' && curl -sf $URL/ | grep -q '/pc/zip?'"
check "/pc/zip = /api/git/archive"      bash -c "curl -sf '$URL/pc/zip?name=z&ref=main' -o $W/pc.zip && curl -sf '$URL/api/git/archive?name=z&ref=main' -o $W/api.zip && cmp $W/pc.zip $W/api.zip"
check "/pc/zip with path"               bash -c "curl -sf '$URL/pc/zip?name=z&ref=main&path=src' -o $W/pcp.zip && curl -sf '$URL/api/git/archive?name=z&ref=main&path=src' -o $W/apip.zip && cmp $W/pcp.zip $W/apip.zip"
check "/pc/zip header"                  bash -c "curl -s -D - -o /dev/null '$URL/pc/zip?name=z&ref=main' | grep -qi '^Content-Disposition: attachment; filename=\"z-main.zip\"'"
check "/pc/zip 404 bad repo"            bad "$URL/pc/zip?name=nope&ref=main" 404
check "/pc/zip 400 no ref"              bash -c "[[ \$(curl -s -o /dev/null -w '%{http_code}' '$URL/pc/zip?name=z') == 400 ]]"
check "/pc/hub still answered"          bash -c "curl -sf $URL/pc/hub | grep -q connected"
check "unknown /pc/ path -> 404"        bad "$URL/pc/nothing" 404
check "/port/extra.js served"           bash -c "curl -sf $URL/port/extra.js | grep -q 'window.GPX'"
if command -v node >/dev/null; then
    check "extra.js valid JS"           bash -c "curl -sf $URL/port/extra.js >$W/extra.js && node --check $W/extra.js"
fi
check "page loads extra.js"             bash -c "curl -sf $URL/ | grep -q '/port/extra.js'"
stop

# ---- ZIP64 (forced by a low limit in a test build) ----
build "$TOP/build/zip64" -DCMAKE_C_FLAGS=-DPORT_ZIP64_TEST
start "$TOP/build/zip64/gitphind" "$W/ext" GITPHIN_ZIP64_TEST=1000000
compare64() {
    compare_zip z64 "$URL/git/z.git/archive/main.zip" main z-main || return 1
    python3 -I - "$W/z64.zip" <<'PY'
import sys, struct
d = open(sys.argv[1], 'rb').read()
assert b'PK\x06\x06' in d and b'PK\x06\x07' in d, 'no zip64 eocd'
import zipfile
z = zipfile.ZipFile(sys.argv[1])
assert any(i.header_offset >= 1000000 for i in z.infolist()), 'no big offsets'
PY
}
check "zip64 forced"                    compare64
stop

if (( ${#FAILED[@]} )); then echo "FAILED: ${FAILED[*]}"; exit 1; fi
if grep -E "AddressSanitizer|runtime error|UndefinedBehavior" "$W/server.log" >"$W/san.log"; then echo "sanitizer output:"; cat "$W/san.log"; exit 1; fi
echo "passed: ${#PASSED[@]}"
rm -rf "$TOP/build/zip64"
echo "ALL TESTS PASSED"
