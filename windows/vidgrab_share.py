"""VidGrab local file sharing (scoped first version).

Sends explicitly selected files to any device on the same network that can
open a browser. No installs needed on the receiving side.

Security model:
- Only the files the user explicitly picked are reachable. Nothing else on
  the disk is exposed; the server never lists directories.
- Every session gets an unpredictable random token, and every file gets its
  own random id. URLs are checked against in-memory maps built when the
  files were picked. Request paths are never treated as filesystem paths,
  so directory traversal is not possible.
- The session expires (default 10 minutes) and can be stopped at any time;
  after that the port is closed.
- Traffic is plain HTTP on the local network: it is not encrypted, so
  anyone on the same network who obtains the full link while the session
  is active could fetch the shared files. Treat the link like a key.
"""

from __future__ import annotations

import html
import mimetypes
import os
import secrets
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, List, Optional

DEFAULT_EXPIRY_SECONDS = 600


def lan_ip() -> str:
    """Best-effort LAN IP of this machine (no traffic is actually sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 80))  # TEST-NET-1, unroutable; only picks the interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class ShareSession:
    """One sharing session: a fixed set of files behind a session token."""

    def __init__(
        self,
        files: List[str],
        port: int = 0,
        expiry_seconds: int = DEFAULT_EXPIRY_SECONDS,
        host: Optional[str] = None,
    ) -> None:
        if not files:
            raise ValueError("no files selected")
        for f in files:
            if not os.path.isfile(f):
                raise ValueError(f"not a file: {f}")
        self.token = secrets.token_urlsafe(16)
        self.expires_at = time.time() + expiry_seconds
        self._files: Dict[str, str] = {}  # random file id -> absolute path
        for f in files:
            self._files[secrets.token_urlsafe(8)] = os.path.abspath(f)
        self._server = ThreadingHTTPServer(("0.0.0.0", port), self._make_handler())
        self._server.daemon_threads = True
        self._thread: Optional[threading.Thread] = None
        self._host = host or lan_ip()
        self._stopped = threading.Event()

    @property
    def port(self) -> int:
        return self._server.server_address[1]

    @property
    def url(self) -> str:
        return f"http://{self._host}:{self.port}/{self.token}/"

    def expired(self) -> bool:
        return time.time() >= self.expires_at

    def start(self) -> str:
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return self.url

    def stop(self) -> None:
        if not self._stopped.is_set():
            self._stopped.set()
            self._server.shutdown()
            self._server.server_close()

    def _make_handler(self):
        session = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # keep console clean
                pass

            def _not_found(self):
                body = b"Not found"
                self.send_response(404)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):  # noqa: N802 (http.server API)
                if session.expired():
                    return self._not_found()
                # Split once; any extra path segments simply will not match.
                parts = self.path.lstrip("/").split("/")
                if not parts or parts[0] != session.token:
                    return self._not_found()

                if len(parts) == 1 or (len(parts) == 2 and parts[1] == ""):
                    return self._listing()
                if len(parts) == 3:
                    file_id = parts[1]
                    path = session._files.get(file_id)
                    if path is None:
                        return self._not_found()
                    return self._send_file(path)
                return self._not_found()

            def _listing(self):
                rows = []
                for file_id, path in session._files.items():
                    name = os.path.basename(path)
                    size_mb = os.path.getsize(path) / (1024 * 1024)
                    link = f"{session.token}/{file_id}/{name}"
                    rows.append(
                        f'<li><a href="{html.escape(link)}">{html.escape(name)}</a>'
                        f" ({size_mb:.1f} MB)</li>"
                    )
                page = (
                    "<!doctype html><meta charset='utf-8'>"
                    "<meta name='viewport' content='width=device-width'>"
                    "<title>VidGrab Share</title>"
                    "<h2>VidGrab shared files</h2><ul>"
                    + "".join(rows)
                    + "</ul><p>This page expires when sharing stops.</p>"
                ).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(page)))
                self.end_headers()
                self.wfile.write(page)

            def _send_file(self, path: str):
                name = os.path.basename(path)
                size = os.path.getsize(path)
                ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(size))
                self.send_header(
                    "Content-Disposition", f"attachment; filename*=UTF-8''{name}"
                )
                self.end_headers()
                with open(path, "rb") as fh:
                    while True:
                        chunk = fh.read(1024 * 1024)
                        if not chunk:
                            break
                        try:
                            self.wfile.write(chunk)
                        except (BrokenPipeError, ConnectionResetError):
                            break

        return Handler
