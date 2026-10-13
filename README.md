<div align="center">

<img src="docs/gitphin_logo_orange.svg" width="120" alt="Gitphin logo">

# Gitphin Droid

### Git hosting in your pocket: a Git server with a web UI that runs on your Android phone.

Gitphin Droid keeps your Git repositories on the phone and serves them to plain `git` over a
USB cable or Wi-Fi. Open the app, `git push`, and browse code, commits, diffs, issues, pull
requests and a wiki in the app or on the PC at <http://127.0.0.1:8080>. No server, no cloud,
no account.

[![CI](https://github.com/Adhamura/gitphinDroid/actions/workflows/ci.yml/badge.svg)](https://github.com/Adhamura/gitphinDroid/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Adhamura/gitphinDroid?include_prereleases&sort=semver)](https://github.com/Adhamura/gitphinDroid/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-7.0%2B-3ddc84)

[**Download**](https://github.com/Adhamura/gitphinDroid/releases/latest) ·
[Screenshots](#screenshots) · [Features](#features) · [Install](#install) · [FAQ](#faq)

</div>

> [!NOTE]
> This is the Android port of [Gitphin](https://github.com/Adhamura/Gitphin), the Git server for
> the Flipper Zero. It uses the same C core and the same web UI. It is an independent
> open-source project. Git is a trademark of the Software Freedom Conservancy.

## Screenshots

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/main.png" alt="Main screen"><br><b>Main screen</b> - the web UI in the app, with the addresses in the top bar</td>
    <td width="50%"><img src="docs/screenshots/repo.png" alt="Repository"><br><b>Repositories</b> - every repo on the phone</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/code.png" alt="Code browser"><br><b>Code</b> - files, commits and diffs</td>
    <td><img src="docs/screenshots/settings.png" alt="Settings"><br><b>Settings</b> - port, Wi-Fi access, repos folder, webhooks</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/activity.png" alt="Recent activity"><br><b>Recent activity</b> - pushes, clones and uploads</td>
    <td><img src="docs/screenshots/guide.png" alt="First-start guide"><br><b>Guide</b> - how to connect, step by step</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/share.png" alt="Share sheet"><br><b>Share</b> - send files from other apps to a repo</td>
    <td><img src="docs/screenshots/widget.png" alt="Widget"><br><b>Widget</b> - start and stop the server from the home screen</td>
  </tr>
</table>

## Features

- **A Git server on the phone.** `git clone`, `pull` and `push` work with the `git` you
  already have (smart HTTP and WebDAV). A repo is made on the first push.
- **A full web UI.** Code browser with Markdown READMEs, commits, diffs, branches, tags,
  search, file edits and uploads, issues, pull requests with merge, and a wiki. It runs in the
  app's own WebView and in any browser.
- **Three ways to connect a PC.** USB cable with `gitphin-usb` (no password), USB tethering
  (no PC software, no password), or Wi-Fi (opt-in, user name and password, also as
  `gitphin.local`).
- **Gitphin 1.2 page features**: syntax highlighting, system-folder protection in the Files
  tab, repo avatars, and a Download ZIP button (the phone answers `/pc/zip`). Also a QR code
  for the Wi-Fi address and a Collector tab with data about the PC or the phone.
- **Webhooks** after each push, signed with HMAC-SHA256 when you set a secret.
- **Share sheet**: send files from other apps to a repo.
- **Android extras**: home-screen widget, quick-settings tile, start with the phone, recent
  activity list, a choice of the repos folder, and a pixel-style UI with a size setting.
- **Update check** against GitHub releases, with download and install in the app.
- **Plain Git repos.** Each repo is an ordinary bare repository. Copy it to any PC and use it
  there.
- **Small and open.** Plain C core, Java with no AndroidX and no third-party libraries, MIT
  licensed.

## Install

Android 7.0 (API 24) or newer.

### 1. The app

1. Download `gitphinDroid-<version>.apk` from
   [**Releases**](https://github.com/Adhamura/gitphinDroid/releases/latest).
2. Open the file on the phone. Android asks you to allow installs from this source (the
   browser or file manager you used). Turn it on ("Allow from this source"). This is the
   normal setting for apps outside Google Play.
3. Open Gitphin and follow the guide.

Check the download with `sha256sum -c SHA256SUMS --ignore-missing`. Every release is signed
with the same key, so updates install over the old version.

### 2. The PC helper (optional, USB cable)

On the phone, turn on Developer options > USB debugging. On the PC (Linux, Python 3, `git`,
`adb`) download `gitphin-usb-<version>.tar.gz` from the same release, then:

```bash
tar xzf gitphin-usb-*.tar.gz && cd gitphin-usb-*
./install.sh            # systemd user service, no sudo
./install.sh --uninstall
```

When you plug in the phone, `gitphin-usb` links `http://127.0.0.1:8080` on the PC to the phone
(`adb forward`) and starts the app if needed. If 8080 is busy (for example, the Flipper
bridge), it uses 8081 and up. Without the helper, use USB tethering or Wi-Fi.

## Using it

- **On the phone:** the app shows the web UI, served from `127.0.0.1:8080`.
- **PC over USB cable (first choice):** with `gitphin-usb` running, use
  `http://127.0.0.1:8080` on the PC. No password.
- **PC over USB tethering (second choice):** turn on USB tethering. The top bar shows the
  address. No password.
- **PC over Wi-Fi:** turn on Wi-Fi access in Settings. Wi-Fi clients need the password (HTTP
  Basic, any user name unless you set one). When Wi-Fi access is off, Wi-Fi clients get 403.
  The phone also answers to `http://gitphin.local:8080` on most PCs (Windows 10+, macOS,
  Linux with Avahi). If that fails, use the IP address.

```bash
# put an existing repo on the phone (the repo is made on the first push)
git remote add phone http://127.0.0.1:8080/git/<name>.git
git push phone main --tags
```

```bash
git clone http://127.0.0.1:8080/git/<name>.git
```

```bash
git clone http://gitphin:<password>@<phone-ip>:8080/git/<name>.git
```

The top bar of the app and the notification show the addresses in this order.

## Updating

The app checks GitHub for a newer release at most once a day when you open it, and in
Settings > About with "Check for updates". When there is a new version, a dialog shows the
release notes. Tap "Download and install": the app downloads the APK from the release, then
Android asks you to confirm the install. Android only accepts the update when it is signed with
the same key. You can skip a version, or turn off the automatic check in Settings > About.
The check sends one request to `api.github.com` and nothing else.

## Building

You need JDK 17, the Android SDK, NDK 30.0.16248370 and CMake 3.31.6. Put the SDK folder in
`android/local.properties` (`sdk.dir=...`) or set `ANDROID_HOME`.

```bash
cd android && ./gradlew assembleDebug
```

The APK is at `android/app/build/outputs/apk/debug/gitphinDroid-<version>-debug.apk`.

The version is in `version.properties` (`versionName=MAJOR.MINOR.PATCH`). The version code is
`major*10000 + minor*100 + patch`.

A signed release build reads the key from the environment (`GITPHIN_KEYSTORE_FILE`,
`GITPHIN_KEYSTORE_PASSWORD`, `GITPHIN_KEY_ALIAS`, `GITPHIN_KEY_PASSWORD`) or from a
`keystore.properties` file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`):

```bash
cd android && ./gradlew assembleRelease -PgitphinKeystoreProps=/path/to/keystore.properties
```

Without a key, `assembleRelease` makes an unsigned APK and prints a warning. Never commit the
keystore or its password.

Tests (Linux; they need `cmake`, `gcc`, `zlib`, `git`, `curl`, `python3`, `unzip` and optionally
`node`, and free ports 8197 and 8201-8205):

```bash
tests/run_host_test.sh
tests/run_smart_test.sh
tests/run_zip_test.sh
tests/run_webhook_test.sh
tests/run_avatar_test.sh
tests/run_web_test.sh
```

To update the C core from Gitphin: `./sync_core.sh ../gitphin`.

**Releases are automatic.** Raise `versionName` in `version.properties`, add a `## <version>`
section to `CHANGELOG.md`, and merge to `main`. GitHub Actions runs the tests, builds the signed
APK and publishes the release `v<version>` with the changelog section as notes. The workflow
needs the repository secrets `KEYSTORE_BASE64` (the keystore file, base64), `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`.

## How it works

The Git server and the web UI are the C core of [Gitphin](https://github.com/Adhamura/Gitphin),
copied unchanged into `core/`. On the Flipper, the core uses furi, the Storage API and a USB
link. Here, the **port layer** in `port/` replaces those parts with POSIX code and a TCP
transport, and the Java app starts it as a foreground service over JNI. The WebView and any
browser talk to the same server.

## Collector tab

The Collector tab shows the computer that runs the browser, as with the Flipper bridge:
- In a PC browser over USB, `gitphin-usb` answers `/pc/hub`, `/pc/info` and `/pc/wifi` with `pc/collector.py`
  (the same file as in Gitphin). It also answers `/pc/hub`, the link state for the status dot.
- In the app (or over USB tethering or Wi-Fi), the phone answers with its own data: device,
  Android, memory, IPs, networks and Wi-Fi. The Wi-Fi name needs the location permission
  (Settings > Collector). Android does not let apps read saved Wi-Fi passwords.

On the phone, a separate accept thread answers the `/pc/` routes, so the status dot stays right
while a long Git request runs.

A repo is made on the first push, the same as on the Flipper.

## Layout

| Path | What it is |
|---|---|
| `core/` | The Gitphin C core, copied from `gitphin/flipper_app/src/*/` (flat) by `sync_core.sh`, with a few marked patches, plus the web UI (`core/files/`). CMake compiles only the unit files, which `#include` their parts. |
| `port/` | The port layer. It replaces the Flipper firmware parts (see below). |
| `port/include/` | The furi, Storage and toolbox shim headers, and `port.h` (the public port API). |
| `port/platform/` | `furi.c`, `storage.c`, `plugin.c`: furi, Storage and the plugin table on POSIX. |
| `port/net/` | The TCP transport: `transport.c` (worker side), `acceptor.c`, `auth.c`, `peer.c`, `pc_routes.c`, `net_int.h`. |
| `port/server/` | `server.c` (worker), `jni.c`, `host_main.c`, `hooks.c` + `port_hooks.h` (the hooks `sync_core.sh` adds to `hub_http.c`). |
| `port/features/` | What the port adds: `smart/` (smart HTTP), `zip/` (also `GET /pc/zip`), `webhook/`, `avatar/` (stored as `hub/avatar.png`, the file the page uses). |
| `port/web/` | `core.js` and `phone_texts.js`, served as `/port/extra.js`: only the phone wording (Flipper words become phone words). ZIP, avatars and highlighting come from the Gitphin 1.2 page. |
| `CMakeLists.txt` | Builds `libgitphin.so` (Android, `-DGITPHIN_ANDROID=ON`) or `gitphind` (Linux test server). |
| `android/` | The app: Java, no AndroidX, no third-party libraries. |
| `pc/` | The PC helper `gitphin-usb` (USB link), `collector.py`, `install.sh`. |
| `tests/` | End-to-end tests with real `git` against `gitphind`, the Linux build of the server (ASan and UBSan): `run_host_test.sh`, `run_smart_test.sh`, `run_zip_test.sh`, `run_webhook_test.sh`, `run_avatar_test.sh`, `run_web_test.sh`. |

## What the port replaces

| Flipper | Android |
|---|---|
| `furi.h`, `furi_hal.h`, Storage API | `port/include/` + `port/platform/furi.c`, `storage.c` (POSIX; `/ext` = the app's `files/ext`) |
| USB CDC frames + PC bridge (`hub_transport.c`, `bridge.py`) | `port/net/`: TCP sockets, spare-connection handling, Basic auth for non-loopback clients |
| `.fal` plugins loaded on demand (`hub_plugin.c`) | `port/platform/plugin.c`: all plugins linked in |
| App, screen and worker (`gitphin.c`) | `port/server/server.c` (worker thread) + `port/server/jni.c` + the Android service |
| Guarded allocator (`hub_mem.c`) | Off (`HUB_MEM_RAW`); the phone has no 256 KB heap |

## Notes

- The server serves one request at a time, like on the Flipper. It is much faster here.

## Webhooks

After a push changes branches or tags, the app sends a JSON message with `POST` to the URLs in Settings > Webhooks (http or https, also on the LAN). It works for all three ways to push: dumb WebDAV push, smart HTTP and the web UI API. The API routes `create` and `delete` send `repo_created` and `repo_deleted`.

```json
{"event":"push","repo":"wh","refs":[{"ref":"refs/heads/main","before":"0000000000000000000000000000000000000000","after":"95575243fe82e071ab528250b121c7933c399677"}],"head_commit":{"id":"95575243fe82e071ab528250b121c7933c399677","message":"first commit","author":"t","time":1791899085},"via":"phone","server":"gitphin","time":1791899085}
```

The C side (`port/features/webhook/webhook.c`) compares the refs before and after each request and calls `NativeServer.onEvent`. `WebhookSender` merges the events of one repo that come within 2 s, then posts them with `Content-Type: application/json`, `User-Agent: gitphin-droid`, `X-Gitphin-Event`, `X-Gitphin-Delivery` and, when a secret is set, `X-Gitphin-Signature-256: sha256=<HMAC-SHA256 of the body>`. Timeout 10 s; on failure it retries after 2, 8 and 30 s. Test on the PC: `tests/run_webhook_test.sh` (`gitphind --webhook-log FILE` writes each event as one line).

## FAQ

**Why would I want a Git server on my phone?**
It is one more remote. You always carry the phone, it works offline, and `git push` to it takes
a second. It does not replace GitHub or your own server.

**Is the phone a single point of failure?**
No. Git is distributed: every clone is a full copy of the repository with its whole history.
Use the phone as an extra remote next to your normal one. If the phone is lost, you lose
nothing that is also in a clone.

**Do I need a PC at all?**
No. The app shows the web UI on the phone, and you can edit and upload files there. A PC is
needed only for `git` on the command line.

**What happens to my repos when I uninstall the app?**
By default the repos are stored in the app's private folder, and Android deletes this folder
when you uninstall the app. Choose a shared folder in Settings > Repos folder, or back up with
`git clone --mirror` first.

**Is it safe on Wi-Fi?**
Wi-Fi access is off by default. When you turn it on, clients need a password (you can turn
this off for a trusted network). Traffic is plain HTTP, so use it only on networks you trust.

**Why does the web UI say "Flipper" or "SD card" in some places?**
`core/` is the upstream Gitphin code, unchanged.

**The phone stops the server in the background.**
Allow background running in Settings > Start. On Xiaomi/HyperOS, also set Battery saver to "No
restrictions" and turn on Autostart.

## License

[MIT](LICENSE). The C core in `core/` is from Gitphin, also MIT (`core/LICENSE`).
