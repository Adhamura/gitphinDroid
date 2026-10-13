#!/usr/bin/env bash
# Demo repositories for the README screenshots (made-up project, fixed dates).
# Usage: tools/screenshots/make_demo.sh <root>    → <root>/apps_data/gitphin/git/<name>.git
# <root> is the server root (gitphind --root), the phone's files/ext.
set -euo pipefail

ROOT="${1:?usage: make_demo.sh <root>}"
rm -rf "$ROOT"; mkdir -p "$ROOT/apps_data/gitphin/git"
ROOT="$(cd "$ROOT" && pwd)/apps_data/gitphin"
SRC="$(mktemp -d)"; trap 'rm -rf "$SRC"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
export GIT_AUTHOR_NAME="Mika Tanaka" GIT_AUTHOR_EMAIL="mika@example.com"
export GIT_COMMITTER_NAME="Mika Tanaka" GIT_COMMITTER_EMAIL="mika@example.com"
T=1790800000
commit() { # commit <message> [author]
    T=$((T + 5400 + RANDOM % 20000))
    export GIT_AUTHOR_DATE="$T +0200" GIT_COMMITTER_DATE="$T +0200"
    if [[ -n "${2:-}" ]]; then export GIT_AUTHOR_NAME="$2"; else export GIT_AUTHOR_NAME="Mika Tanaka"; fi
    git add -A && git commit -q -m "$1"
}

# ---- weather-station: the main demo repo
W="$SRC/weather-station"; git init -q -b main "$W"; cd "$W"
mkdir -p src docs
cat > README.md <<'MD'
# Weather Station

Firmware for a small solar-powered weather station: temperature, humidity and
pressure every 10 minutes, sent over LoRa to a base station.

## Hardware

| Part | Model |
|---|---|
| MCU | STM32WL55 |
| Sensor | Bosch BME280 |
| Power | 1 W solar panel, 18650 cell |

## Build

```sh
make flash
```

## Status

- [x] Sensor readout
- [x] LoRa uplink
- [ ] Over-the-air updates
MD
printf 'CC=arm-none-eabi-gcc\nCFLAGS=-Os -Wall\n\nall: build/station.elf\n' > Makefile
printf '#include "bme280.h"\n\nint main(void) {\n    sensor_init();\n    for(;;) sample();\n}\n' > src/main.c
commit "Initial firmware skeleton"
printf '#pragma once\n\nvoid sensor_init(void);\nfloat sensor_temperature(void);\n' > src/bme280.h
printf '#include "bme280.h"\n\nvoid sensor_init(void) {\n    /* I2C at 400 kHz */\n}\n\nfloat sensor_temperature(void) {\n    return 21.5f;\n}\n' > src/bme280.c
commit "Add BME280 driver"
printf '#pragma once\n\nvoid lora_send(const void* data, int len);\n' > src/lora.h
printf '#include "lora.h"\n\nvoid lora_send(const void* data, int len) {\n    (void)data; (void)len;\n}\n' > src/lora.c
commit "LoRa uplink every 10 minutes" "Jonas Weber"
printf '# Wiring\n\nBME280 on I2C1 (PB8 / PB9), LoRa antenna on RF1.\n' > docs/wiring.md
commit "Document the wiring"
sed -i 's/return 21.5f;/return bme280_read_t() \/ 100.0f;/' src/bme280.c
commit "Read the real temperature register"
git tag -a v1.0 -m "First field release"
sed -i 's/I2C at 400 kHz/I2C at 400 kHz, forced mode to save power/' src/bme280.c
commit "Sensor: forced mode, 40 % less current" "Jonas Weber"
printf 'void sleep_until_next_sample(void);\n' > src/power.h
commit "Deep sleep between samples"
git tag -a v1.1 -m "Battery life: about 9 months"
git checkout -q -b feature/ota
printf '#pragma once\n\n/* Over-the-air update, chunked over LoRa. */\nvoid ota_begin(unsigned size);\n' > src/ota.h
commit "OTA: receive firmware in chunks" "Aiko Sato"
printf '#include "ota.h"\n\nvoid ota_begin(unsigned size) {\n    (void)size;\n}\n' > src/ota.c
commit "OTA: verify the image before switching" "Aiko Sato"
git checkout -q main
cat > src/bme280.c <<'C'
#include "bme280.h"

/* Oversampling x4 for pressure, x1 for temperature and humidity:
 * less noise for 0.3 mA more during the 8 ms measurement. */
#define OSRS_P 3
#define OSRS_T 1

void sensor_init(void) {
    /* I2C at 400 kHz, forced mode to save power */
    bme280_write(0xF4, (OSRS_T << 5) | (OSRS_P << 2));
}

float sensor_temperature(void) {
    return bme280_read_t() / 100.0f;
}
C
commit "Sensor: 4x pressure oversampling, less noise"
git clone -q --bare "$W" "$ROOT/git/weather-station.git"
git -C "$ROOT/git/weather-station.git" repack -q -a -d
echo "Firmware for a solar-powered LoRa weather station" > "$ROOT/git/weather-station.git/description"

# ---- smaller repos for the list
for spec in "dotfiles|Shell, editor and git config|Mika Tanaka" \
            "garden-app|Android app for the garden sensors|Aiko Sato" \
            "notes|Meeting notes and ideas (Markdown)|Mika Tanaka" \
            "pcb-badge|KiCad project for the conference badge|Jonas Weber"; do
    IFS="|" read -r name desc who <<<"$spec"
    d="$SRC/$name"; git init -q -b main "$d"; cd "$d"
    printf '# %s\n\n%s\n' "$name" "$desc" > README.md
    commit "Initial commit" "$who"
    printf 'More notes.\n' >> README.md; commit "Update README" "$who"
    git clone -q --bare "$d" "$ROOT/git/$name.git"
    git -C "$ROOT/git/$name.git" repack -q -a -d
    echo "$desc" > "$ROOT/git/$name.git/description"
done

# ---- issues, pull requests, releases, wiki of weather-station (doc plugin layout)
H="$ROOT/git/weather-station.git/hub"; mkdir -p "$H/issues" "$H/releases" "$H/wiki"
python3 -I - "$H" <<'PY'
import json, os, sys
H = sys.argv[1]
def w(key, obj):
    with open(os.path.join(H, key), "w") as f:
        f.write(obj if isinstance(obj, str) else json.dumps(obj))
labels = [{"name": "bug", "color": "#d73a4a"}, {"name": "power", "color": "#fbca04"},
          {"name": "enhancement", "color": "#a2eeef"}]
w("meta.json", {"next": 5, "labels": labels,
                "milestones": [{"id": 1, "title": "v1.2", "due": "2026-11-30", "state": "open"}]})
items = [
    dict(n=1, kind="issue", title="Pressure reads 0 after a cold start", state="closed", labels=["bug"],
         milestone=None, author="Jonas Weber", created="2026-10-02T08:10:00Z", updated="2026-10-03T17:40:00Z",
         body="Below 0 °C the first pressure sample is 0 hPa. Probably the sensor needs more time after power-on.",
         comments=[{"id": 1, "author": "Mika Tanaka", "body": "Fixed by waiting 2 ms after the reset, see v1.1.",
                    "created": "2026-10-03T17:40:00Z", "edited": None}]),
    dict(n=2, kind="issue", title="Battery lasts 9 months, goal is 12", state="open", labels=["power"],
         milestone=1, author="Mika Tanaka", created="2026-10-04T09:00:00Z", updated="2026-10-06T12:15:00Z",
         body="Ideas:\n\n- sample every 15 minutes at night\n- skip the uplink when nothing changed",
         comments=[]),
    dict(n=3, kind="pull", title="Over-the-air updates", state="open", labels=["enhancement"],
         milestone=1, author="Aiko Sato", created="2026-10-07T14:30:00Z", updated="2026-10-08T10:05:00Z",
         body="Receives a new image in LoRa chunks, checks it, then switches on the next boot.",
         comments=[{"id": 1, "author": "Mika Tanaka", "body": "Looks good. Can we keep the old image as a fallback?",
                    "created": "2026-10-08T10:05:00Z", "edited": None}],
         pull={"base": "main", "head": "feature/ota", "merged_sha": None, "merged_at": None}),
    dict(n=4, kind="issue", title="Add a humidity alarm", state="open", labels=["enhancement"],
         milestone=None, author="Jonas Weber", created="2026-10-09T16:20:00Z", updated="2026-10-09T16:20:00Z",
         body="Send an extra uplink when humidity goes over 90 %.", comments=[]),
]
for it in items:
    w(f"issues/{it['n']}.json", it)
keys = ("n", "kind", "title", "state", "labels", "milestone", "updated")
w("issues/index.json", {"items": [dict({k: it[k] for k in keys}, comments=len(it["comments"])) for it in items]})
rels = [
    dict(id=1, tag="v1.0", title="v1.0 – first field release", draft=False, prerelease=False,
         created="2026-10-02T12:00:00Z", assets=[],
         body="- Temperature, humidity and pressure every 10 minutes\n- LoRa uplink to the base station"),
    dict(id=2, tag="v1.1", title="v1.1 – battery life", draft=False, prerelease=False,
         created="2026-10-05T12:00:00Z", assets=[],
         body="- Sensor in forced mode: 40 % less current\n- Deep sleep between samples: about 9 months per charge"),
]
for r in rels:
    w(f"releases/{r['id']}.json", r)
w("releases/index.json", {"next": 3, "items": [{k: r[k] for k in ("id", "tag", "title", "draft", "prerelease", "created")} for r in rels]})
w("wiki/Home.md", "# Weather Station wiki\n\nStart here: [[Wiring]], [[Field test log]].\n\n"
  "The station wakes every 10 minutes, samples the BME280 and sends 12 bytes over LoRa.\n")
w("wiki/Wiring.md", "# Wiring\n\nBME280 on I2C1 (PB8 / PB9), LoRa antenna on RF1.\n")
w("wiki/index.json", {"pages": [{"name": "Home", "updated": "2026-10-05T12:00:00Z"},
                                {"name": "Wiring", "updated": "2026-10-06T09:30:00Z"}]})
PY

# ---- repo avatars (hub/avatar.png, as the page uploads them): pixel art, 8x8 cells scaled to 128 px
python3 -I - "$ROOT/git" <<'PY'
import os, struct, sys, zlib
G = sys.argv[1]
def png(path, rows, pal, scale=16):
    raw = b""
    for r in rows:
        line = b"".join(bytes(pal[c]) * scale for c in r)
        raw += (b"\x00" + line) * scale
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    w = len(rows[0]) * scale
    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, len(rows) * scale, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(data)
art = {
    # sun over a cloud
    "weather-station": ("..aa....|.aaaa...|.aaaab..|..abbbb.|.bbbbbbb|bbbbbbbb|.bbbbbb.|........",
                        {".": (32, 104, 176), "a": (255, 196, 40), "b": (240, 244, 250)}),
    # terminal prompt
    "dotfiles": ("........|.a......|..a.....|...a....|..a.....|.a..bbb.|........|........",
                 {".": (40, 44, 52), "a": (120, 220, 120), "b": (200, 200, 200)}),
    # leaf
    "garden-app": ("........|.....aa.|...aaaa.|..aabaa.|..abaa..|..baa...|.b......|........",
                   {".": (232, 245, 225), "a": (60, 160, 70), "b": (30, 90, 40)}),
    # notebook
    "notes": ("........|.aaaaaa.|.abbbba.|.aaaaaa.|.abbba..|.aaaaaa.|.abba...|........",
              {".": (255, 236, 179), "a": (255, 250, 235), "b": (120, 110, 90)}),
    # chip
    "pcb-badge": (".a.a.a..|aaaaaaa.|.abbba..|aabcbaa.|.abbba..|aaaaaaa.|.a.a.a..|........",
                  {".": (20, 90, 60), "a": (210, 170, 60), "b": (30, 30, 30), "c": (90, 200, 255)}),
}
for name, (rows, pal) in art.items():
    png(os.path.join(G, name + ".git", "hub", "avatar.png"), rows.split("|"), pal)
PY
echo "demo repos in $ROOT/git"
