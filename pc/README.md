# gitphin-usb: the PC helper for Gitphin Droid

`gitphin-usb` links your PC to the Gitphin Droid app on a phone over a USB cable. It needs no
password. When you plug in the phone, it opens `http://127.0.0.1:8080` on the PC and passes the
requests to the app. It also starts the app if needed. If port 8080 is busy, it uses 8081 and up.

## Needs

- Linux with Python 3, `git` and `adb` (Android platform-tools).
- A systemd user session (for the automatic start; you can also run `./gitphin-usb` by hand).
- On the phone: Developer options > USB debugging on. Accept the PC's key on the phone once.

## Install

```bash
tar xzf gitphin-usb-*.tar.gz && cd gitphin-usb-*
./install.sh            # systemd user service, no sudo
./install.sh --uninstall
```

## Use

Plug in the phone. Then:

```bash
git clone http://127.0.0.1:8080/git/<name>.git
```

Open <http://127.0.0.1:8080> in a browser for the web UI. A repo is made on the first push.

## Files

| File | What it is |
|---|---|
| `gitphin-usb` | The helper (Python 3, standard library only). |
| `collector.py` | Reads data about this PC for the Collector tab. |
| `install.sh` | Installs or removes the systemd user service. |

Environment variables: `GITPHIN_PORT` (PC port, default 8080), `GITPHIN_PHONE_PORT` (port in the
app, default 8080), `ADB` (path of `adb`).
