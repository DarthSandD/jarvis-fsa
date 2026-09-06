#!/usr/bin/env python3
"""Jarvis Termux bridge — localhost-only command runner for the JARVIS APK.

Runs on the S23 inside Termux (or anywhere for protocol tests):
  export JARVIS_BRIDGE_TOKEN=<long-random>
  python termux-bridge.py            # listens on 127.0.0.1:8087

Protocol:
  GET  /health            -> {"ok": true, "name": "jarvis-termux-bridge"}
  POST /exec  {"token":..., "cmd": "...", "timeout": 30}
                          -> {"ok": true, "code": N, "stdout": "...", "stderr": "..."}
                          -> {"ok": false, "error": "..."}  (bad token / timeout / refused)

Safety: binds 127.0.0.1 ONLY (no LAN exposure). Every command runs with
`shell=False`-style shlex splitting, 30s default cap, 60s hard cap.
The APK ALWAYS asks Darren to approve each command before sending it —
this server never auto-runs anything unprompted.
"""
import json
import os
import shlex
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST, PORT = "127.0.0.1", int(os.environ.get("JARVIS_BRIDGE_PORT", "8087"))
TOKEN = os.environ.get("JARVIS_BRIDGE_TOKEN", "")
MAX_OUT = 8000
BODY_LIMIT = 65536


class Handler(BaseHTTPRequestHandler):
    server_version = "JarvisBridge/1.0"

    def log_message(self, *a):
        pass

    def _send(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            return self._send({"ok": True, "name": "jarvis-termux-bridge"})
        return self._send({"ok": False, "error": "not found"}, 404)

    def do_POST(self):
        if self.path != "/exec":
            return self._send({"ok": False, "error": "not found"}, 404)
        try:
            length = int(self.headers.get("Content-Length", 0))
        except ValueError:
            length = 0
        if length <= 0 or length > BODY_LIMIT:
            return self._send({"ok": False, "error": "bad body"}, 400)
        try:
            req = json.loads(self.rfile.read(length).decode())
        except Exception:
            return self._send({"ok": False, "error": "bad json"}, 400)
        if not TOKEN or req.get("token") != TOKEN:
            return self._send({"ok": False, "error": "forbidden"}, 403)
        cmd = str(req.get("cmd", ""))[:2000]
        if not cmd.strip():
            return self._send({"ok": False, "error": "empty cmd"}, 400)
        try:
            timeout = min(int(req.get("timeout", 30)), 60)
        except (TypeError, ValueError):
            timeout = 30
        try:
            p = subprocess.run(
                shlex.split(cmd), capture_output=True, text=True,
                timeout=timeout, cwd=os.path.expanduser("~"),
            )
            return self._send({
                "ok": True, "code": p.returncode,
                "stdout": (p.stdout or "")[-MAX_OUT:],
                "stderr": (p.stderr or "")[-MAX_OUT:],
            })
        except subprocess.TimeoutExpired:
            return self._send({"ok": False, "error": "timeout after %ss" % timeout})
        except FileNotFoundError as e:
            return self._send({"ok": False, "error": "not found: %s" % str(e)[:120]})
        except Exception as e:
            return self._send({"ok": False, "error": str(e)[:200]})


if __name__ == "__main__":
    if not TOKEN:
        print("JARVIS_BRIDGE_TOKEN is not set — refusing to start.", file=sys.stderr)
        sys.exit(2)
    HTTPServer((HOST, PORT), Handler).serve_forever()
