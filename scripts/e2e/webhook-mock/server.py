#!/usr/bin/env python3
"""Minimal webhook mock for E2E Phase 5. python scripts/e2e/webhook-mock/server.py"""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json

HOST, PORT = "127.0.0.1", 9090


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"[webhook-mock] {self.path} {args[0] if args else ''}")

    def do_POST(self):
        if self.path.startswith("/status/500"):
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b"error")
            return
        if self.path.startswith("/status/404"):
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"not found")
            return
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""
        print(f"[webhook-mock] body={body[:200]!r}")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"ok": True}).encode())


if __name__ == "__main__":
    print(f"Webhook mock on http://{HOST}:{PORT}")
    HTTPServer((HOST, PORT), Handler).serve_forever()
