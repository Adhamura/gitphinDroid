# Changelog

## 1.0.0

First public release of Gitphin Droid: Gitphin (Git hosting in your pocket) as an Android app.
The Git server and the web UI run on the phone itself. No Flipper, no PC bridge, no Termux.

- Git server on the phone: `git clone`, `pull` and `push` over smart HTTP and WebDAV, a repo is made on the first push.
- Web UI in the app's own WebView: repos, code, commits, diffs, issues, pull requests, wiki, uploads and edits.
- PC over USB cable: `gitphin-usb` (systemd user service) links `http://127.0.0.1:8080` on the PC to the phone, no password.
- PC over USB tethering without PC software, and over Wi-Fi (opt-in, user name and password, `gitphin.local` by name).
- Gitphin 1.2 page: syntax highlighting, protected system folders, repo avatars (`hub/avatar.png`) and a Download ZIP button (`/pc/zip`, also `/api/git/archive`).
- QR code for the Wi-Fi address, Collector tab with data about the PC or the phone.
- Webhooks after each push, signed with HMAC-SHA256 when a secret is set.
- Share sheet: send files from other apps to a repo.
- Recent activity list, home-screen widget, quick-settings tile, start with the phone.
- Choice of the repos folder, first-start guide, pixel-style UI with a size setting.
- In-app update check against GitHub releases, with download and install.
