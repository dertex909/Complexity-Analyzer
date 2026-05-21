#!/usr/bin/env python3
"""Local dev server for Complexity Analyzer viewer.

Serves the viewer static files and proxies the .cabin snapshot
from the local Minecraft save so you can test without running the game.

Usage:
    python dev-server.py
    # open http://localhost:8080/
"""

import http.server
import json
import struct
from pathlib import Path

PORT = 8080
VIEWER_DIR = Path(__file__).parent.resolve()
CABIN_PATH = Path(
    r"C:\Users\xXx\Desktop\Mods_Sources\ComplexityAnalyzer"
    r"\run\saves\Новый мир (1)\data\complexityanalyzer\cabin\latest.cabin"
)


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
        # suppress default logging; keep output clean
        pass

    def do_GET(self):
        # Match both /api/... and /{token}/api/...
        raw = self.path
        if "api/cabin" in raw:
            self._serve_cabin()
        elif "api/meta" in raw:
            self._serve_meta()
        else:
            super().do_GET()

    def _serve_cabin(self):
        if not CABIN_PATH.exists():
            self.send_error(404, f"Cabin file not found: {CABIN_PATH}")
            return
        data = CABIN_PATH.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_meta(self):
        if not CABIN_PATH.exists():
            body = json.dumps({"hasCabin": False}).encode()
        else:
            st = CABIN_PATH.stat()
            h = read_cabin_file_hash(CABIN_PATH)
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
    import socketserver
    with socketserver.TCPServer(("", PORT), Handler) as httpd:
        print(f"→ http://localhost:{PORT}/")
        print(f"→ Serving cabin: {CABIN_PATH}")
        print("  Press Ctrl+C to stop")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n  Stopped.")
