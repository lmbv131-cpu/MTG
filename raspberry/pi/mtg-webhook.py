#!/usr/bin/env python3
"""GitHub webhook receiver for the Pi (user mtg, 127.0.0.1:9000; cloudflared publishes it as /github-hook).
On a signed push to the game's branch it leaves estado/buscar-ya, and mtg-actualizar picks it up within a minute.
Without a public address it is never called and the 10-minute check does the same job."""
import hashlib
import hmac
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

M = Path("/opt/mtglocal")
SECRET_FILE = M / "webhook-secreto.txt"
CONF = dict(l.split("=", 1) for l in (M / "config.env").read_text().splitlines() if "=" in l and not l.startswith("#"))
BRANCH = "refs/heads/" + CONF.get("RAMA", "main").strip('"')


class Hook(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(min(int(self.headers.get("Content-Length", 0)), 5_000_000))
        secret = SECRET_FILE.read_bytes().strip() if SECRET_FILE.exists() else b""
        want = "sha256=" + hmac.new(secret, body, hashlib.sha256).hexdigest()
        if not secret or not hmac.compare_digest(want, self.headers.get("X-Hub-Signature-256", "")):
            return self.answer(403, "firma incorrecta")
        event = self.headers.get("X-GitHub-Event", "")
        if event == "ping":
            return self.answer(200, "pong")
        if event == "push" and json.loads(body or b"{}").get("ref") == BRANCH:
            (M / "estado" / "buscar-ya").touch()
            return self.answer(200, "actualización anotada")
        self.answer(200, "ignorado")

    def answer(self, code, text):
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(text.encode())

    def log_message(self, fmt, *args):
        pass


HTTPServer(("127.0.0.1", 9000), Hook).serve_forever()
