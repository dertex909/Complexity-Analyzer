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
import os
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
PORT = 8080
VIEWER_DIR = Path(__file__).parent.resolve()

def free_port(port: int):
    """Forcefully terminates any process occupying the given port and waits until free."""
    current_pid = os.getpid()
    pids = set()

    if os.name == "nt":
        try:
            import ctypes
            from ctypes import wintypes
            iphlpapi = ctypes.windll.iphlpapi
            kernel32 = ctypes.windll.kernel32

            size = wintypes.DWORD(0)
            iphlpapi.GetExtendedTcpTable(None, ctypes.byref(size), False, 2, 5, 0)
            if size.value > 0:
                buf = ctypes.create_string_buffer(size.value)
                if iphlpapi.GetExtendedTcpTable(buf, ctypes.byref(size), False, 2, 5, 0) == 0:
                    num_entries = struct.unpack_from("<I", buf.raw, 0)[0]
                    offset = 4
                    for _ in range(num_entries):
                        _, _, l_port, _, _, pid = struct.unpack_from("<IIIIII", buf.raw, offset)
                        offset += 24
                        entry_port = ((l_port & 0xFF) << 8) | ((l_port >> 8) & 0xFF)
                        if entry_port == port and pid != 0 and pid != current_pid:
                            pids.add(pid)

            size_v6 = wintypes.DWORD(0)
            iphlpapi.GetExtendedTcpTable(None, ctypes.byref(size_v6), False, 23, 5, 0)
            if size_v6.value > 0:
                buf_v6 = ctypes.create_string_buffer(size_v6.value)
                if iphlpapi.GetExtendedTcpTable(buf_v6, ctypes.byref(size_v6), False, 23, 5, 0) == 0:
                    num_entries = struct.unpack_from("<I", buf_v6.raw, 0)[0]
                    offset = 4
                    for _ in range(num_entries):
                        l_port = struct.unpack_from(">H", buf_v6.raw, offset + 20)[0]
                        pid = struct.unpack_from("<I", buf_v6.raw, offset + 52)[0]
                        offset += 56
                        if l_port == port and pid != 0 and pid != current_pid:
                            pids.add(pid)

            for pid in pids:
                print(f"→ Killing previous process PID {pid} on port {port}...")
                handle = kernel32.OpenProcess(0x0001, False, pid)
                if handle:
                    kernel32.TerminateProcess(handle, 1)
                    kernel32.CloseHandle(handle)
        except Exception:
            pass

    else:
        try:
            subprocess.run(["fuser", "-k", "-9", f"{port}/tcp"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass
        try:
            out = subprocess.check_output(["lsof", "-ti", f":{port}"], text=True, stderr=subprocess.DEVNULL)
            for pid_str in out.strip().split():
                if pid_str.isdigit() and int(pid_str) != current_pid:
                    pids.add(int(pid_str))
        except Exception:
            pass
        for pid in pids:
            print(f"→ Killing process PID {pid} on port {port}...")
            try:
                os.kill(pid, 9)
            except OSError:
                pass

    for _ in range(30):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                s.bind(("", port))
                return
            except OSError:
                time.sleep(0.1)

def get_project_root() -> Path:
    """Find the root directory of the Gradle project by looking for build.gradle."""
    cur = VIEWER_DIR
    for p in [cur] + list(cur.parents):
        if (p / "build.gradle").exists() or (p / "settings.gradle").exists():
            return p
    return VIEWER_DIR.parents[4] if len(VIEWER_DIR.parents) >= 5 else Path.cwd()

PROJECT_ROOT = get_project_root()
SAVES_DIR = Path(os.environ.get("COMPLEXITY_SAVES_DIR", PROJECT_ROOT / "run" / "saves"))

def find_cabin_path():
    """Locate the newest latest.cabin across all world saves."""
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

class DevServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    free_port(PORT)
    cabin = find_cabin_path()
    with DevServer(("", PORT), Handler) as httpd:
        print(f"→ Project root: {PROJECT_ROOT}")
        print(f"→ Saves directory: {SAVES_DIR}")
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