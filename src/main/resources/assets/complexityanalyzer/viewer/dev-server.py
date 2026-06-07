#!/usr/bin/env python3
"""Local dev server for Complexity Analyzer viewer.

Serves the viewer static files and proxies the .cabin snapshot
from the local Minecraft save so you can test without running the game.

Usage:
    python dev-server.py
    # open http://localhost:8080/
"""

import base64
import hashlib
import http.server
import json
import struct
import time
from pathlib import Path

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

PORT = 8080
VIEWER_DIR = Path(__file__).parent.resolve()

SAVES_DIR = Path(
    r"C:\Users\xXx\Desktop\Mods_Sources\ComplexityAnalyzer\run\saves"
)


def find_cabin_path():
    """Locate the newest latest.cabin across all world saves.

    Auto-discovery means the dev server keeps working when the world is renamed
    or a new world is created, without editing this file.
    """
    if not SAVES_DIR.is_dir():
        return None
    candidates = list(SAVES_DIR.glob("*/data/complexityanalyzer/cabin/latest.cabin"))
    if not candidates:
        return None
    return max(candidates, key=lambda p: p.stat().st_mtime)


def read_cabin_file_hash(path):
    """Read the u64 fileHash stored at offset 24 in the .cabin header (little-endian)."""
    with open(path, "rb") as f:
        f.seek(24)
        raw = f.read(8)
        if len(raw) < 8:
            return "0"
        val = struct.unpack("<Q", raw)[0]
        return format(val, "x")


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(VIEWER_DIR), **kwargs)

    def log_message(self, fmt, *args):
        pass

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()

    def send_head(self):
        self.headers.replace_header("If-Modified-Since", "") if "If-Modified-Since" in self.headers else None
        if "If-None-Match" in self.headers:
            del self.headers["If-None-Match"]
        return super().send_head()

    def do_GET(self):
        if self.headers.get("Upgrade", "").lower() == "websocket":
            self._serve_ws()
            return
        raw = self.path
        if "api/cabin" in raw:
            self._serve_cabin()
        elif "api/meta" in raw:
            self._serve_meta()
        else:
            super().do_GET()

    def _serve_ws(self):
        """Minimal WebSocket endpoint: handshake, then push the cabin file hash on connect and on change.

        The server watches the file itself (server-side), so the browser uses a pure WebSocket with no
        polling — same contract as the in-game Netty server.
        """
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            self.send_error(400, "Missing Sec-WebSocket-Key")
            return
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.wfile.write((
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
        ).encode())
        self.wfile.flush()

        last = None
        try:
            while True:
                path = find_cabin_path()
                cur = read_cabin_file_hash(path) if (path and path.exists()) else None
                if cur and cur != last:
                    self._ws_send_text(cur)
                    last = cur
                time.sleep(1)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def _ws_send_text(self, msg):
        data = msg.encode("utf-8")
        header = bytearray([0x81])
        n = len(data)
        if n < 126:
            header.append(n)
        elif n < 65536:
            header.append(126)
            header += n.to_bytes(2, "big")
        else:
            header.append(127)
            header += n.to_bytes(8, "big")
        self.wfile.write(bytes(header) + data)
        self.wfile.flush()

    def _serve_cabin(self):
        cabin_path = find_cabin_path()
        if cabin_path is None or not cabin_path.exists():
            self.send_error(404, f"No latest.cabin found under {SAVES_DIR}")
            return
        data = cabin_path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_meta(self):
        cabin_path = find_cabin_path()
        if cabin_path is None or not cabin_path.exists():
            body = json.dumps({"hasCabin": False}).encode()
        else:
            st = cabin_path.stat()
            h = read_cabin_file_hash(cabin_path)
            body = json.dumps({
                "hasCabin": True,
                "size": st.st_size,
                "hash": h,
                "generatedAtMs": int(st.st_mtime * 1000),
                "itemCount": 0,
                "mobCount": 0,
                "recipeCount": 0,
                "serverName": "Dev Server",
                "viewerVersion": "1.2"
            }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    import sys
    from http.server import ThreadingHTTPServer
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    cabin = find_cabin_path()
    with ThreadingHTTPServer(("", PORT), Handler) as httpd:
        print(f"→ http://localhost:{PORT}/")
        if cabin is not None:
            print(f"→ Serving cabin: {cabin}")
        else:
            print(f"→ No latest.cabin found yet under {SAVES_DIR} (run the game / scan first)")
        print("  Press Ctrl+C to stop")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n  Stopped.")