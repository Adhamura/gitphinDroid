#!/usr/bin/env python3
"""Proxy for the README screenshots: gitphind, plus the Collector data of a made-up phone.

Usage: demo_proxy.py <gitphind port> [port]

/pc/info and /pc/wifi answer fixed demo values in the shape of NativeServer.collect() (the
app's own collector; gitphind only has a stub). /api/info gets the phone values the app sets
with nativeSetDevice() and a phone-sized storage. Everything else, /pc/hub included, goes
unchanged to gitphind, the real server. Read-only: writes answer 403.
"""

import http.client
import http.server
import json
import sys

INFO = {
    "hostname": "Pixel 8",
    "os": "Android 16 (API 36)",
    "kernel": "6.1.99-android14-11",
    "arch": "arm64-v8a",
    "cpu_count": 9,
    "mem_total": 8 * 1024 ** 3,
    "mem_available": 3_400_000_000,
    "uptime_s": 186_420,
    "user": "",
    "ips": [{"iface": "wlan0", "cidr": "192.168.1.42/24"}, {"iface": "rndis0", "cidr": "10.42.0.129/24"}],
    "connections": [
        {"name": "Home Wi-Fi", "type": "802-11-wireless", "device": "wlan0"},
        {"name": "USB tethering", "type": "usb", "device": "rndis0"},
        {"name": "Mobile data", "type": "mobile", "device": "rmnet_data1"},
    ],
}
WIFI = {
    "connected": True,
    "ssid": "Home Wi-Fi",
    "signal": "84",
    "rate": "866 Mb/s",
    "password": None,
    "password_source": None,
    "note": "Android does not let apps read saved Wi-Fi passwords. "
            "To share it, use Settings > Wi-Fi > Share (QR code).",
}
DEVICE = {"name": "Google Pixel 8", "firmware": "Android 16", "battery": 78, "charging": True,
          "sd_total_kib": 119_000_000, "sd_free_kib": 61_500_000}
HOP = {"connection", "keep-alive", "transfer-encoding", "content-length"}


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt, *args):
        pass

    def send(self, status, body, headers):
        self.send_response(status)
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/pc/info", "/pc/wifi"):
            body = json.dumps(INFO if path == "/pc/info" else WIFI).encode()
            return self.send(200, body, [("Content-Type", "application/json")])
        c = http.client.HTTPConnection("127.0.0.1", UPSTREAM, timeout=30)
        hdrs = {k: v for k, v in self.headers.items() if k.lower() not in HOP and k.lower() != "host"}
        c.request(self.command, self.path, headers=hdrs)
        r = c.getresponse()
        body = r.read()
        c.close()
        if path == "/api/info" and r.status == 200:
            body = json.dumps(dict(json.loads(body), **DEVICE)).encode()
        self.send(r.status, body, [(k, v) for k, v in r.getheaders() if k.lower() not in HOP])

    do_HEAD = do_GET

    def do_PUT(self):
        self.send(403, b'{"ok":false,"error":"read-only demo"}', [("Content-Type", "application/json")])

    do_POST = do_DELETE = do_PUT


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    UPSTREAM = int(sys.argv[1])
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8099
    http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
