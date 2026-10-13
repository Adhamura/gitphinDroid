# Security

Please report security problems privately through GitHub: **Security → Report a
vulnerability** on this repository (or open an issue that only says you have something to
report, without details).

## What Gitphin Droid exposes

- **Loopback (`127.0.0.1`) needs no password.** That is the app's own WebView and the PC over
  the USB cable (`gitphin-usb` uses `adb forward`, which needs USB debugging and your OK on the
  phone). Any other app on the phone can also connect to `127.0.0.1:8080` and read and change
  the repos. Install apps you trust, or stop the server when you do not use it.
- **USB tethering needs no password.** Only the PC on the cable is on that network.
- **Wi-Fi is off by default.** When you turn it on, clients need HTTP Basic sign-in (user name
  and password), unless you turn sign-in off. Traffic is **plain HTTP**: anyone on the same
  network can read the password and the code. Use Wi-Fi access only on networks you trust.
  With Wi-Fi access off, Wi-Fi clients get 403.
- **Files.** The server reads and writes only below its root (the app's `files/ext`) and the
  repos folder you choose. Git and document paths are checked against `..`, empty segments and
  leading dots.
- **Network use.** The app sends nothing to the internet except the update check (one request
  to `api.github.com`, which you can turn off in Settings > About), the APK download when you
  tap "Download and install", and the webhooks you set.

## Data safety

- Every write goes to a temp file and is renamed into place; refs change by compare-and-swap.
  A crash or a killed app does not corrupt a repository.
- By default the repos are in the app's private folder, which Android deletes on uninstall.
  Choose a shared repos folder or back up with `git clone --mirror`.

## Known limits (planned work)

- **Other web sites in your browser.** No CORS headers are sent, so other sites cannot read the
  answers. But the server does not check the `Origin` or `Host` header yet, so a malicious page
  open in a browser that can reach the server (on the PC over the cable, or on the phone) could
  send blind write requests, and a DNS-rebinding page could read it. Recent Chrome versions
  block many such requests to local addresses; other browsers may not. Stop the server when you
  do not use it, and keep backups of your repos.

## Release downloads

Release files are built by GitHub Actions from the tagged commit and listed with their SHA-256 in
`SHA256SUMS`. Check them with `sha256sum -c SHA256SUMS --ignore-missing`. The APK is signed with
the same key for every release; Android installs an update only when the key matches.
