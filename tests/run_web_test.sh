#!/usr/bin/env bash
# Test of the page additions (port/web/*.js: phone texts) and of the 1.2 page features the port relies on and the real free RAM (memmgr_get_free_heap).
# Usage: tests/run_web_test.sh   (KEEP=1 keeps the scratch dir and build/web)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(dirname "$HERE")"
PORT="${PORT:-8205}"
W="$(mktemp -d "${TMPDIR:-/tmp}/gitphind-web.XXXXXX")"
SRV_PID=""
export ASAN_OPTIONS=detect_leaks=0
cleanup() {
    [[ -n "$SRV_PID" ]] && kill "$SRV_PID" 2>/dev/null && wait "$SRV_PID" 2>/dev/null
    if [[ -n "${KEEP:-}" ]]; then echo "kept $W"; else rm -rf "$W" "$TOP/build/web"; fi
}
trap cleanup EXIT

cmake -S "$TOP" -B "$TOP/build/web" -DGITPHIN_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug >/dev/null || exit 1
cmake --build "$TOP/build/web" -j8 >"$W/build.log" 2>&1 || { tail -30 "$W/build.log"; exit 1; }
BIN="$TOP/build/web/gitphind"

PASSED=(); FAILED=()
check() { local n="$1"; shift
    if "$@" >"$W/last.log" 2>&1; then PASSED+=("$n"); else FAILED+=("$n"); echo "--- FAIL: $n"; tail -n 15 "$W/last.log"; fi; }

ROOT="$W/root"; mkdir -p "$ROOT/apps_assets/gitphin"
cp "$TOP/core/files/index.html" "$TOP/core/files/index.html.gz" "$ROOT/apps_assets/gitphin/" 2>/dev/null
"$BIN" --root "$ROOT" --port "$PORT" >"$W/server.log" 2>&1 &
SRV_PID=$!
for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$PORT/api/info" && break; sleep 0.1; done
URL="http://127.0.0.1:$PORT"

t_heap() {
    local h; h=$(curl -s "$URL/api/info" | python3 -c 'import json,sys; print(json.load(sys.stdin)["heap_free"])') || return 1
    echo "heap_free=$h"
    [[ "$h" -gt 0 && "$h" != 268435456 ]]
}
check "info heap_free is real" t_heap

curl -s "$URL/port/extra.js" -o "$W/extra.js"
t_served() { [[ -s "$W/extra.js" ]] && grep -q 'window.GPX' "$W/extra.js" && grep -q 'PT-BEGIN' "$W/extra.js"; }
check "extra.js is served" t_served
t_page() { local f="$TOP/core/files/index.html"; grep -q 'id="rv-zip"' "$f" && grep -q '/pc/zip' "$f" && grep -q 'Needed by Gitphin' "$f" && grep -q 'hub/avatar.png' "$f" && grep -q 'hl-' "$f"; }
check "page has ZIP button, avatars, files guard, highlighter" t_page
t_size() { local f; for f in "$TOP"/port/web/*.js; do [[ $(wc -l <"$f") -le 500 ]] || { echo "too long: $f"; return 1; }; done; }
check "each web part has at most 500 lines" t_size

if command -v node >/dev/null; then
    check "extra.js passes node --check" node --check "$W/extra.js"
    cat >"$W/unit.js" <<'JS'
const fs = require("fs"), assert = require("assert");
const src = fs.readFileSync(process.argv[2], "utf8");
const blocks = [];
const re = /\/\*(PT)-BEGIN\*\/([\s\S]*?)\/\*\1-END\*\//g;
let m;
while ((m = re.exec(src))) blocks.push(m[2]);
assert(blocks.length >= 1, "blocks found: " + blocks.length);
const exp = "if(typeof ptMap!=='undefined')GPX.ptMap=ptMap;";
const GPX = {};
for (const b of blocks) new Function("GPX", b + "\n" + exp)(GPX);

let n = 0;
const t = (name, fn) => { fn(); n++; };
// text map
t("pt", () => {
  const p = GPX.ptMap;
  assert.strictEqual(p("Git repositories stored on the Flipper SD card. Git hosting in your pocket."), "Git repositories stored on this phone. Git hosting in your pocket.");
  assert.strictEqual(p("SD free"), "Storage free");
  assert.strictEqual(p("Files on the Flipper"), "Files on the phone");
  assert.strictEqual(p("Delete /ext/x from the Flipper?"), "Delete /ext/x from this phone?");
  assert.strictEqual(p("Flipper idle"), "Phone idle");
  assert.strictEqual(p("Flipper is busy for 12 s"), "The phone is busy for 12 s");
  assert.strictEqual(p("Flipper status unknown"), "Phone status unknown");
  assert.strictEqual(p("The bridge does not answer"), "The server does not answer");
  assert.strictEqual(p("Unnamed repository"), "No description");
  assert.strictEqual(p("git remote add flipper flipper://x"), "git remote add flipper flipper://x");
  assert.strictEqual(p("or run git flipper gc x"), "or run git flipper gc x");
  assert.strictEqual(p("hello"), "hello");
});
console.log("unit checks: " + n);
JS
    check "unit: phone text map" node "$W/unit.js" "$W/extra.js"
else
    echo "node not found: unit tests skipped"
fi

t_asan() { ! grep -qE 'ERROR: AddressSanitizer|runtime error:|LeakSanitizer' "$W/server.log"; }
stop() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=""; }
stop
check "ASan/UBSan log clean" t_asan

echo "passed: ${#PASSED[@]}  failed: ${#FAILED[@]}"
printf '  ok: %s\n' "${PASSED[@]}"
if [[ ${#FAILED[@]} -eq 0 ]]; then echo "ALL TESTS PASSED"; else printf '  FAILED: %s\n' "${FAILED[@]}"; exit 1; fi
