#!/usr/bin/env bash
# Webhook events of gitphind: pushes (dumb WebDAV and smart), tags, branch delete, commit-file API,
# repo delete/create API, and no event for clones. Reads the --webhook-log file of the server.
# Usage: tests/run_webhook_test.sh   (KEEP=1 keeps the scratch dir)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(dirname "$HERE")"
PORT="${PORT:-8203}"
W="$(mktemp -d "${TMPDIR:-/tmp}/gitphind-wh.XXXXXX")"
ROOT="$W/ext"
LOG="$W/events.log"
SRV_PID=""
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export ASAN_OPTIONS=detect_leaks=0

cleanup() {
    [[ -n "$SRV_PID" ]] && kill "$SRV_PID" 2>/dev/null && wait "$SRV_PID" 2>/dev/null
    [[ -n "${KEEP:-}" ]] && echo "kept $W" || rm -rf "$W"
}
trap cleanup EXIT

cmake -S "$TOP" -B "$TOP/build/webhook" -DGITPHIN_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug >/dev/null || exit 1
cmake --build "$TOP/build/webhook" -j8 >"$W/build.log" 2>&1 || { tail -30 "$W/build.log"; exit 1; }
BIN="$TOP/build/webhook/gitphind"

mkdir -p "$ROOT/apps_assets/gitphin"
cp "$TOP/core/files/index.html" "$TOP/core/files/index.html.gz" "$ROOT/apps_assets/gitphin/"
: >"$LOG"
"$BIN" --root "$ROOT" --bind 127.0.0.1 --port "$PORT" --webhook-log "$LOG" >"$W/server.log" 2>&1 &
SRV_PID=$!
URL="http://127.0.0.1:$PORT"
for _ in $(seq 50); do curl -s -o /dev/null "$URL/api/info" && break; sleep 0.1; done

PASSED=(); FAILED=()
check() {
    local name="$1"; shift
    if "$@" >"$W/last.log" 2>&1; then PASSED+=("$name"); else FAILED+=("$name"); echo "--- FAIL: $name"; tail -n 15 "$W/last.log"; fi
}

# merged <from-line> <repo>: the events after that line as "ref before after" (the first before and
# the last after of each ref, like the app's debounce); "-" = none. Also prints "EVENTS n" and "HEAD msg".
cat >"$W/merge.py" <<'PY'
import json, sys
log, start, repo = sys.argv[1], int(sys.argv[2]), sys.argv[3]
refs, n, head, kinds = {}, 0, "", []
for line in open(log).read().splitlines()[start:]:
    e = json.loads(line)
    if e.get("server") != "gitphin" or e.get("repo") != repo: continue
    kinds.append(e["event"]); n += 1
    for r in e.get("refs", []):
        if r["ref"] in refs: refs[r["ref"]][1] = r["after"]
        else: refs[r["ref"]] = [r["before"], r["after"]]
    if "head_commit" in e: head = e["head_commit"]["message"] + "|" + e["head_commit"]["id"] + "|" + e["head_commit"]["author"]
    assert e["via"] in ("phone", "usb", "tether", "wifi", ""), e
for k in sorted(refs): print(k, refs[k][0], refs[k][1])
print("EVENTS", n, " ".join(sorted(set(kinds))))
print("HEAD", head)
PY
lines() { wc -l <"$LOG"; }
merged() { python3 -I "$W/merge.py" "$LOG" "$1" "$2"; }
Z=0000000000000000000000000000000000000000
sha() { git -C "$1" rev-parse "$2"; }
# expect <name> <from-line> <repo> <expected ref lines...>  (the lines "EVENTS"/"HEAD" are ignored)
expect() {
    local name="$1" from="$2" repo="$3"; shift 3
    local got want
    got="$(merged "$from" "$repo" | grep -v -e '^EVENTS' -e '^HEAD')"
    want="$(printf '%s\n' "$@")"
    check "$name" bash -c "[ \"\$1\" = \"\$2\" ] || { echo 'got:'; echo \"\$1\"; echo 'want:'; echo \"\$2\"; exit 1; }" _ "$got" "$want"
}

SRC="$W/src"
git init -q -b main "$SRC"
echo one >"$SRC/a.txt"; git -C "$SRC" add -A; git -C "$SRC" commit -q -m "first commit"
C1="$(sha "$SRC" HEAD)"

# 1. first push of a new repo (dumb protocol)
export GIT_SMART_HTTP=0
M=$(lines)
curl -s -o /dev/null "$URL/git/wh.git/info/refs?service=git-receive-pack" # a push-to-create discovery makes the repo
check "dumb push new repo" git -C "$SRC" push -q "$URL/git/wh.git" main
expect "event: new repo, refs created" "$M" wh "refs/heads/main $Z $C1"
check "head_commit of the push" bash -c "python3 -I '$W/merge.py' '$LOG' $M wh | grep -q '^HEAD first commit|$C1|t\$'"
check "event is one JSON line with server/via/time" bash -c "tail -n 1 '$LOG' | python3 -I -c 'import json,sys; e=json.loads(sys.stdin.read()); assert e[\"event\"]==\"push\" and e[\"repo\"]==\"wh\" and e[\"server\"]==\"gitphin\" and e[\"time\"]>1e9 and e[\"via\"]==\"phone\", e'"

# 2. second push
echo two >>"$SRC/a.txt"; git -C "$SRC" commit -q -am "second: with \"quotes\" and ü"
C2="$(sha "$SRC" HEAD)"
M=$(lines)
check "dumb push update" git -C "$SRC" push -q "$URL/git/wh.git" main
expect "event: update before/after" "$M" wh "refs/heads/main $C1 $C2"
check "head_commit message escapes" bash -c "python3 -I '$W/merge.py' '$LOG' $M wh | grep -q '^HEAD second: with \"quotes\" and \\\\u00fc|$C2|t\$' || python3 -I '$W/merge.py' '$LOG' $M wh | grep -q '^HEAD second: with \"quotes\" and ü|$C2|t\$'"

# 3. tag push, a new branch, then deleting it
git -C "$SRC" tag v1
M=$(lines)
check "dumb push tag" git -C "$SRC" push -q "$URL/git/wh.git" v1
expect "event: tag created" "$M" wh "refs/tags/v1 $Z $C2"
git -C "$SRC" branch feature
M=$(lines)
check "dumb push branch" git -C "$SRC" push -q "$URL/git/wh.git" feature
expect "event: branch created" "$M" wh "refs/heads/feature $Z $C2"
M=$(lines)
check "dumb push branch delete" git -C "$SRC" push -q "$URL/git/wh.git" :feature
expect "event: branch deleted" "$M" wh "refs/heads/feature $C2 $Z"
check "branch delete has no head_commit" bash -c "python3 -I '$W/merge.py' '$LOG' $M wh | grep -q '^HEAD \$'"

# 4. no event for reads
M=$(lines)
check "clone" git clone -q "$URL/git/wh.git" "$W/c1"
check "fetch" git -C "$W/c1" fetch -q origin
check "ls-remote" git ls-remote "$URL/git/wh.git"
check "api reads" bash -c "curl -sf '$URL/api/git/refs?name=wh' >/dev/null && curl -sf '$URL/api/git/repos' >/dev/null && curl -sf '$URL/api/git/log?name=wh&ref=main' >/dev/null"
sleep 0.5
check "no event for clone/fetch/reads" bash -c "[ \$(wc -l <'$LOG') -eq $M ]"

# 5. commit-file API
M=$(lines)
OLD="$(curl -sf "$URL/api/git/refs?name=wh" | python3 -I -c 'import json,sys; d=json.load(sys.stdin); print([r for r in d["refs"] if r["name"]=="refs/heads/main"][0]["sha"])')"
check "commit-file" bash -c "curl -sf -X POST --data-binary 'hello' '$URL/api/git/commit-file?name=wh&branch=main&old=$OLD&path=x.txt&message=add+x' | grep -q '\"ok\":true'"
NEW="$(curl -sf "$URL/api/git/refs?name=wh" | python3 -I -c 'import json,sys; d=json.load(sys.stdin); print([r for r in d["refs"] if r["name"]=="refs/heads/main"][0]["sha"])')"
expect "event: commit-file" "$M" wh "refs/heads/main $C2 $NEW"
check "commit-file head_commit" bash -c "python3 -I '$W/merge.py' '$LOG' $M wh | grep -q '^HEAD add x|$NEW|'"

# 6. ref API (create a branch), then merge is not tested; repo delete API
M=$(lines)
check "api ref create" bash -c "curl -sf -X POST '$URL/api/git/ref?name=wh&ref=refs/heads/side&old=$Z&new=$NEW' | grep -q '\"ok\":true'"
expect "event: api ref" "$M" wh "refs/heads/side $Z $NEW"

# 7. repo create and delete API
M=$(lines)
check "api create" bash -c "curl -sf -X POST '$URL/api/git/create?name=other' | grep -q '\"ok\":true'"
check "event: repo_created" bash -c "python3 -I '$W/merge.py' '$LOG' $M other | grep -q '^EVENTS 1 repo_created'"
M=$(lines)
check "api delete" bash -c "curl -sf -X POST '$URL/api/git/delete?name=wh' | grep -q '\"ok\":true'"
check "event: repo_deleted" bash -c "python3 -I '$W/merge.py' '$LOG' $M wh | grep -q '^EVENTS 1 repo_deleted'"
M=$(lines)
curl -s -X POST "$URL/api/git/delete?name=nonexistent" >/dev/null
sleep 0.3
check "no event for deleting a missing repo" bash -c "[ \$(wc -l <'$LOG') -eq $M ]"

# 8. smart HTTP push (only when the server has it)
unset GIT_SMART_HTTP
CT="$(curl -s -D - -o /dev/null "$URL/git/smart.git/info/refs?service=git-receive-pack" | tr -d '\r' | grep -i '^content-type:' || true)"
if echo "$CT" | grep -qi 'application/x-git-receive-pack-advertisement'; then
    M=$(lines)
    check "smart push new repo" git -C "$SRC" push -q "$URL/git/smart.git" main
    expect "event: smart push" "$M" smart "refs/heads/main $Z $C2"
else
    echo "(smart HTTP not advertised: smart push check skipped)"
fi

stop_srv() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=""; }
stop_srv
if grep -qE 'ERROR: AddressSanitizer|runtime error' "$W/server.log"; then
    FAILED+=("sanitizers"); grep -E -A20 'AddressSanitizer|runtime error' "$W/server.log" | head -40
else
    PASSED+=("sanitizers")
fi

echo "passed: ${#PASSED[@]}  failures: ${#FAILED[@]}"
[[ ${#FAILED[@]} -eq 0 ]] && echo "ALL TESTS PASSED" || { printf ' - %s\n' "${FAILED[@]}"; exit 1; }
