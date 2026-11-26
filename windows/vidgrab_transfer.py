"""VidGrab direct device-to-device transfer (v2, TLS).

Two nearby devices running VidGrab find each other over mDNS and move files
directly, with the transport authenticated and encrypted by TLS.

Trust model (docs/PROTOCOL.md has the full spec):
- Transport: TLS 1.2+ (1.3 where the platform offers it). The receiver runs
  the HTTPS server with a persistent self-signed ECDSA P-256 identity
  certificate generated on first run; the private key never leaves the
  app's private storage directory (0600/0700 permissions).
- Authentication: the sender verifies the receiver's certificate by its
  FULL SHA-256 fingerprint (64 hex chars), compared against the fingerprint
  shown on the receiver's own screen, or imported from the QR on that
  screen (public connection identity, not a secret). No HTTP request of
  any kind leaves the sender until the fingerprint is verified; the
  verified connection is then reused for the whole session. mDNS supplies
  reachability only and is never trusted. Pins are remembered per device
  id; a changed fingerprint fails closed.
- TLS authenticates the RECEIVER only. The sender is an unverified LAN
  peer, so every transfer still requires the recipient's explicit accept
  on the receiver's screen, and each upload is authorized by a one-time
  token scoped to one accepted offer with an exact size.
- Bounded: one pending offer per receiver (60s TTL), one active upload,
  configurable size cap, disk-space preflight, read timeouts, aborted
  uploads delete their temp file. Files are written to a temp name and
  renamed into place only after the full body arrived intact.
- No traversal: request paths never become filesystem paths; filenames are
  sanitized (basename, Windows reserved names and trailing dots/spaces
  handled, collisions auto-renamed).

Interop (Android <-> desktop): stock TLS on both sides, X.509 ECDSA P-256
self-signed certs, SHA-256 fingerprints, HTTP/1.1 with Content-Length.
Android uses javax.net.ssl + Bouncy Castle (cert generation) + NanoHTTPD
(HTTPS server); desktop uses Python ssl + cryptography.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import os
import re
import secrets
import socket
import ssl
import stat
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, Dict, List, Optional

PROTOCOL_VERSION = 2
OFFER_TTL_SECONDS = 60
PAIR_TTL_SECONDS = 120            # fingerprint-compare dialog timeout
DEFAULT_MAX_BYTES = 1024**4  # 1 TiB per-file cap (configurable)
READ_CHUNK = 1024 * 1024
IO_TIMEOUT = 30.0
SEND_CHUNK_BYTES = 1024 * 1024  # upload streaming chunk; bounds sender RAM
SERVICE_TYPE = "_vidgrab._tcp.local."

WINDOWS_RESERVED = {
    "con", "prn", "aux", "nul",
    *(f"com{i}" for i in range(1, 10)),
    *(f"lpt{i}" for i in range(1, 10)),
}


class TransferError(Exception):
    pass


class PinChangedError(TransferError):
    """A previously pinned device presented a different fingerprint."""


# ------------------------------------------------------------------ identity

class Identity:
    """Persistent device identity: ECDSA P-256 key + self-signed X.509 cert."""

    def __init__(self, store_dir: str, key_file: str, cert_file: str,
                 device_id: str, fingerprint: str):
        self.store_dir = store_dir
        self.key_file = key_file
        self.cert_file = cert_file
        self.device_id = device_id
        self.fingerprint = fingerprint  # lowercase hex, 64 chars

    @classmethod
    def load_or_create(cls, store_dir: str) -> "Identity":
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import NameOID

        os.makedirs(store_dir, exist_ok=True)
        try:
            os.chmod(store_dir, stat.S_IRWXU)
        except OSError:
            pass
        key_file = os.path.join(store_dir, "identity.key")
        cert_file = os.path.join(store_dir, "identity.crt")

        if os.path.exists(key_file) and os.path.exists(cert_file):
            with open(key_file, "rb") as fh:
                key = serialization.load_pem_private_key(fh.read(), password=None)
            with open(cert_file, "rb") as fh:
                cert = x509.load_pem_x509_certificate(fh.read())
        else:
            key = ec.generate_private_key(ec.SECP256R1())
            device_seed = secrets.token_hex(4)
            name = x509.Name([
                x509.NameAttribute(NameOID.COMMON_NAME, f"VidGrab-{device_seed}"),
                x509.NameAttribute(NameOID.ORGANIZATION_NAME, "VidGrab"),
            ])
            now = datetime.datetime.now(datetime.timezone.utc)
            cert = (
                x509.CertificateBuilder()
                .subject_name(name).issuer_name(name)
                .public_key(key.public_key())
                .serial_number(x509.random_serial_number())
                .not_valid_before(now)
                .not_valid_after(now + datetime.timedelta(days=3650))
                .sign(key, hashes.SHA256())
            )
            key_pem = key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
            cert_pem = cert.public_bytes(serialization.Encoding.PEM)
            for path, data in ((key_file, key_pem), (cert_file, cert_pem)):
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "wb") as fh:
                    fh.write(data)

        der = cert.public_bytes(serialization.Encoding.DER)
        fingerprint = hashlib.sha256(der).hexdigest()
        device_id = fingerprint[:8]
        return cls(store_dir, key_file, cert_file, device_id, fingerprint)


# -------------------------------------------------------------------- pins

class PinStore:
    """Remembered device-id -> fingerprint pins. Pin changes fail closed."""

    def __init__(self, path: str):
        self.path = path
        self._pins: Dict[str, str] = {}
        try:
            with open(path, "r", encoding="utf-8") as fh:
                self._pins = json.load(fh)
        except (OSError, ValueError):
            self._pins = {}

    def get(self, device_id: str) -> Optional[str]:
        return self._pins.get(device_id)

    def set(self, device_id: str, fingerprint: str) -> None:
        self._pins[device_id] = fingerprint
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(self._pins, fh)


# --------------------------------------------------------------- filenames

def sanitize_filename(name: str) -> str:
    """Reduce an offered filename to a safe basename for Windows/Linux."""
    base = name.replace("\\", "/").split("/")[-1]
    base = re.sub(r'[<>:"|?*\x00-\x1f]', "_", base)
    base = base.strip().rstrip(". ")  # Windows rejects trailing dots/spaces
    stem = base.split(".")[0].lower()
    if stem in WINDOWS_RESERVED:
        base = f"_{base}"
    return base or "received-file"


# ------------------------------------------------------------------ receiver

class OfferState:
    def __init__(self, sender_name: str, filename: str, size: int):
        self.offer_id = secrets.token_urlsafe(12)
        self.upload_token = secrets.token_urlsafe(16)
        self.sender_name = sender_name
        self.filename = filename
        self.size = size
        self.status = "pending"  # pending -> accepted|declined|expired -> uploading -> done|failed
        self.created_at = time.time()


class TransferUI:
    """Callback surface the app implements (called from the HTTP thread;
    the app must proxy to its UI thread)."""

    def decide_offer(self, sender_name: str, filename: str, size: int,
                     respond: Callable[[bool], None]) -> None: ...
    def progress(self, filename: str, received: int, total: int) -> None: ...
    def done(self, filename: str, saved_path: str) -> None: ...
    def failed(self, filename: str, reason: str) -> None: ...


class _QuietHTTPServer(ThreadingHTTPServer):
    """Drops expected network-abort noise (peer disconnects mid-transfer)."""

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, OSError):  # SSL EOF, broken pipe, connection reset
            return
        super().handle_error(request, client_address)


class ReceiveServer:
    """HTTPS receive side. TLS authenticates this device to the sender;
    inbound senders are unverified peers, so acceptance gates everything."""

    def __init__(self, identity: Identity, download_dir: str, ui: TransferUI,
                 port: int = 0, host: Optional[str] = None,
                 max_bytes: int = DEFAULT_MAX_BYTES):
        self.identity = identity
        self.download_dir = download_dir
        self.ui = ui
        self.max_bytes = max_bytes
        os.makedirs(download_dir, exist_ok=True)
        self._offer: Optional[OfferState] = None
        self._upload_active = False
        self._lock = threading.Lock()
        self._server = _QuietHTTPServer(("0.0.0.0", port), self._make_handler())
        self._server.daemon_threads = True
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(identity.cert_file, identity.key_file)
        self._server.socket = ctx.wrap_socket(self._server.socket, server_side=True)
        self._thread: Optional[threading.Thread] = None
        self._host = host or lan_ip()

    @property
    def port(self) -> int:
        return self._server.server_address[1]

    @property
    def host(self) -> str:
        return self._host

    @property
    def fingerprint(self) -> str:
        return self.identity.fingerprint

    def start(self) -> None:
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def _make_handler(self):
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def _json(self, code: int, obj: dict):
                body = json.dumps(obj).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _body(self, limit: int = 4096) -> Optional[dict]:
                try:
                    length = int(self.headers.get("Content-Length") or 0)
                except ValueError:
                    return None
                if length <= 0 or length > limit:
                    return None
                try:
                    return json.loads(self.rfile.read(length))
                except (ValueError, OSError):
                    return None

            def do_GET(self):  # noqa: N802
                parts = [p for p in self.path.split("/") if p]
                if parts[:2] == ["v1", "offer"] and len(parts) == 3:
                    return self._offer_status(parts[2])
                if parts == ["v1", "info"]:
                    # Public connection identity only; authorizes nothing.
                    return self._json(200, {
                        "v": PROTOCOL_VERSION,
                        "device_id": server.identity.device_id,
                        "fingerprint": server.identity.fingerprint,
                    })
                return self._json(404, {"error": "not found"})

            def do_POST(self):  # noqa: N802
                parts = [p for p in self.path.split("/") if p]
                if parts == ["v1", "offer"]:
                    return self._offer_begin()
                if parts[:2] == ["v1", "upload"] and len(parts) == 3:
                    return self._upload(parts[2])
                return self._json(404, {"error": "not found"})

            def _offer_begin(self):
                data = self._body()
                if data is None:
                    return self._json(400, {"error": "bad request"})
                with server._lock:
                    if (server._offer is not None
                            and server._offer.status in ("pending", "uploading")):
                        return self._json(409, {"error": "another offer is pending"})
                    try:
                        size = int(data.get("size"))
                    except (TypeError, ValueError):
                        return self._json(400, {"error": "bad size"})
                    if size <= 0 or size > server.max_bytes:
                        return self._json(400, {"error": "size out of bounds"})
                    try:
                        free = _free_bytes(server.download_dir)
                        if free is not None and size > free:
                            return self._json(507, {"error": "not enough disk space"})
                    except OSError:
                        pass
                    filename = sanitize_filename(str(data.get("filename", "")))
                    sender_name = str(data.get("sender_name", "unknown sender"))[:60]
                    offer = OfferState(sender_name, filename, size)
                    server._offer = offer

                def respond(accepted: bool) -> None:
                    with server._lock:
                        if offer.status == "pending":
                            offer.status = "accepted" if accepted else "declined"

                server.ui.decide_offer(sender_name, filename, size, respond)
                return self._json(202, {"offer_id": offer.offer_id})

            def _offer_status(self, offer_id: str):
                offer = server._offer
                if offer is None or offer.offer_id != offer_id:
                    return self._json(404, {"error": "no such offer"})
                if (offer.status == "pending"
                        and time.time() - offer.created_at > OFFER_TTL_SECONDS):
                    offer.status = "expired"
                out = {"status": offer.status}
                if offer.status == "accepted":
                    out["upload_token"] = offer.upload_token
                return self._json(200, out)

            def _upload(self, token: str):
                with server._lock:
                    offer = server._offer
                    if (offer is None or offer.status != "accepted"
                            or token != offer.upload_token
                            or server._upload_active):
                        return self._json(404, {"error": "no such upload"})
                    server._upload_active = True
                    offer.status = "uploading"
                try:
                    self._receive_body(offer)
                finally:
                    with server._lock:
                        server._upload_active = False
                        if offer.status == "uploading":
                            # Handler exited without a result (e.g. connection
                            # died outside the guarded read loop): never leave
                            # an offer stuck mid-flight.
                            offer.status = "failed"
                            try:
                                server.ui.failed(offer.filename, "connection lost")
                            except Exception:
                                pass

            def _receive_body(self, offer: OfferState):
                """Stream to a temp file; publish (rename) only on full receipt."""
                tmp_path = None
                try:
                    length = int(self.headers.get("Content-Length") or -1)
                except ValueError:
                    length = -1
                if length != offer.size:
                    offer.status = "failed"
                    return self._json(400, {"error": "size mismatch"})

                tmp_path = os.path.join(
                    server.download_dir, f".vidgrab-part-{secrets.token_hex(6)}")
                received = 0
                self.connection.settimeout(IO_TIMEOUT)
                try:
                    with open(tmp_path, "wb") as out:
                        remaining = length
                        while remaining > 0:
                            chunk = self.rfile.read(min(READ_CHUNK, remaining))
                            if not chunk:
                                raise TransferError("connection dropped")
                            out.write(chunk)
                            received += len(chunk)
                            remaining -= len(chunk)
                            server.ui.progress(offer.filename, received, length)
                        out.flush()
                        os.fsync(out.fileno())
                except (OSError, TransferError) as exc:
                    _silent_remove(tmp_path)
                    offer.status = "failed"
                    server.ui.failed(offer.filename, str(exc)[:120])
                    try:
                        return self._json(400, {"error": "upload aborted"})
                    except OSError:
                        return None  # peer already gone; nothing to answer

                # Full body arrived intact over the authenticated TLS channel;
                # only now does the file become visible under its real name.
                saved = _unique_path(server.download_dir, offer.filename)
                try:
                    os.replace(tmp_path, saved)
                except OSError as exc:
                    _silent_remove(tmp_path)
                    offer.status = "failed"
                    server.ui.failed(offer.filename, str(exc)[:120])
                    return self._json(500, {"error": "could not save"})
                offer.status = "done"
                server.ui.done(offer.filename, saved)
                return self._json(200, {"saved_as": os.path.basename(saved)})

        return Handler


def _free_bytes(path: str) -> Optional[int]:
    try:
        import shutil
        return shutil.disk_usage(path).free
    except Exception:
        return None


def _unique_path(directory: str, filename: str) -> str:
    target = os.path.join(directory, filename)
    stem, dot, ext = filename.rpartition(".")
    n = 1
    while os.path.exists(target):
        n += 1
        target = os.path.join(
            directory, f"{stem} ({n}){dot}{ext}" if dot else f"{filename} ({n})")
    return target


def _silent_remove(path: Optional[str]) -> None:
    if path:
        try:
            os.remove(path)
        except OSError:
            pass


# ---------------------------------------------------------------------- sender

class SendClient:
    """Send side: verify the receiver by fingerprint, then offer + upload.

    connect() performs ONLY the TLS handshake and fingerprint check; no
    HTTP request leaves this client until the fingerprint is trusted
    (pinned match or explicit user confirmation via confirm_trust()).
    All requests then reuse that single verified connection.
    """

    def __init__(self, host: str, port: int, sender_name: str,
                 pin_store: PinStore, device_id: str,
                 connect_timeout: float = 10.0):
        self.host = host
        self.port = port
        self.sender_name = sender_name
        self.pins = pin_store
        self.device_id = device_id
        self.connect_timeout = connect_timeout
        self._conn: Optional[ssl.SSLSocket] = None
        self._rfile = None
        self.presented_fingerprint: Optional[str] = None
        self.trusted = False

    # -- trust --

    def connect(self) -> str:
        raw = socket.create_connection((self.host, self.port), timeout=self.connect_timeout)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # no CA; the fingerprint IS the auth
        self._conn = ctx.wrap_socket(raw, server_hostname=self.host)
        self._conn.settimeout(IO_TIMEOUT)
        self._rfile = self._conn.makefile("rb")
        der = self._conn.getpeercert(binary_form=True)
        self.presented_fingerprint = hashlib.sha256(der).hexdigest()

        pinned = self.pins.get(self.device_id)
        if pinned is None:
            self.trusted = False  # first contact: user must compare
        elif pinned != self.presented_fingerprint:
            self.close()
            raise PinChangedError(
                f"device {self.device_id} fingerprint changed; refusing to connect")
        else:
            self.trusted = True
        return self.presented_fingerprint

    def confirm_trust(self) -> None:
        """Pin the presented fingerprint after the user compared it."""
        if self.presented_fingerprint is None:
            raise TransferError("connect first")
        self.pins.set(self.device_id, self.presented_fingerprint)
        self.trusted = True

    # -- minimal HTTP/1.1 over the single verified connection --

    def _send_head(self, method: str, path: str,
                   content_length: Optional[int] = None) -> None:
        if not self.trusted or self._conn is None or self._rfile is None:
            raise TransferError("unverified connection")
        headers = [f"{method} {path} HTTP/1.1", f"Host: {self.host}",
                   "Connection: keep-alive"]
        if content_length is not None:
            headers += ["Content-Type: application/octet-stream",
                        f"Content-Length: {content_length}"]
        head = "\r\n".join(headers) + "\r\n\r\n"
        self._conn.sendall(head.encode("ascii"))

    def _read_response(self) -> dict:
        assert self._rfile is not None
        status_line = self._rfile.readline(8192).decode("latin-1").strip()
        try:
            code = int(status_line.split()[1])
        except (IndexError, ValueError):
            raise TransferError(f"bad response: {status_line!r}")
        resp_headers = {}
        while True:
            line = self._rfile.readline(8192).decode("latin-1")
            if line in ("\r\n", "", None):
                break
            k, _, v = line.partition(":")
            resp_headers[k.strip().lower()] = v.strip()
        try:
            length = int(resp_headers.get("content-length", "0"))
        except ValueError:
            length = 0
        payload = self._rfile.read(length) if length > 0 else b""
        if code == 404:
            detail = "not found"
        else:
            detail = ""
        try:
            obj = json.loads(payload) if payload else {}
        except ValueError:
            obj = {}
        if code >= 400:
            raise TransferError(obj.get("error") or detail or f"HTTP {code}")
        return obj

    def _request(self, method: str, path: str,
                 body: Optional[bytes] = None) -> dict:
        self._send_head(method, path, len(body) if body is not None else None)
        if body:
            self._conn.sendall(body)
        return self._read_response()

    def _request_stream(self, method: str, path: str, fh,
                        size: int, num_cb: Optional[Callable] = None) -> dict:
        """Stream a request body from an open file in bounded chunks.

        Peak memory is one chunk regardless of file size; the socket's
        IO_TIMEOUT bounds every send. If the file changed size mid-send the
        connection is desynced, so we fail and close rather than continue."""
        self._send_head(method, path, size)
        assert self._conn is not None
        sent = 0
        while True:
            chunk = fh.read(SEND_CHUNK_BYTES)
            if not chunk:
                break
            self._conn.sendall(chunk)
            sent += len(chunk)
            if num_cb is not None:
                num_cb(sent, size)
        if sent != size:
            self.close()
            raise TransferError(
                f"file changed during send ({sent} != {size}); connection closed")
        return self._read_response()

    # -- protocol --

    def send(self, path: str,
             progress_cb: Optional[Callable[[str], None]] = None,
             num_cb: Optional[Callable] = None) -> str:
        """Offer then upload one file; returns the name it was saved as.

        num_cb, when given, is called with (bytes_sent, total_bytes) after
        each streamed chunk, for progress/ETA display."""
        size = os.path.getsize(path)
        name = os.path.basename(path)
        if progress_cb:
            progress_cb("waiting for recipient...")
        resp = self._request("POST", "/v1/offer", json.dumps({
            "sender_name": self.sender_name, "filename": name, "size": size,
        }).encode("utf-8"))
        offer_id = resp["offer_id"]

        deadline = time.time() + OFFER_TTL_SECONDS + 30
        token = None
        while time.time() < deadline:
            out = self._request("GET", f"/v1/offer/{offer_id}")
            status = out.get("status")
            if status == "accepted":
                token = out["upload_token"]
                break
            if status in ("declined", "expired"):
                raise TransferError(f"recipient {status}")
            time.sleep(1)
        if token is None:
            raise TransferError("no answer from recipient")

        if progress_cb:
            progress_cb("sending...")
        with open(path, "rb") as fh:
            resp = self._request_stream("POST", f"/v1/upload/{token}", fh, size,
                                        num_cb=num_cb)
        return str(resp.get("saved_as", name))

    def close(self) -> None:
        try:
            if self._rfile is not None:
                self._rfile.close()
            if self._conn is not None:
                self._conn.close()
        except OSError:
            pass
        self._conn = None
        self._rfile = None
        self.trusted = False


# ---------------------------------------------------------------------- mDNS

class DeviceInfo:
    def __init__(self, name: str, host: str, port: int, device_id: str,
                 platform: str, fingerprint: str):
        self.name = name
        self.host = host
        self.port = port
        self.device_id = device_id
        self.platform = platform
        self.fingerprint = fingerprint  # public identity; verify over TLS anyway

    def __repr__(self) -> str:
        return f"DeviceInfo({self.name} {self.host}:{self.port})"


class Discovery:
    """Advertise this device's receiver and browse for others (zeroconf).

    Discovery data (name, host, port, even the advertised fingerprint) is
    UNTRUSTED reachability information: any LAN peer can publish it. Trust
    comes only from the TLS fingerprint comparison in SendClient.
    """

    def __init__(self) -> None:
        from zeroconf import Zeroconf

        self._zc = Zeroconf()
        self._service = None
        self._found: Dict[str, DeviceInfo] = {}

    def advertise(self, name: str, port: int, device_id: str, platform: str,
                  fingerprint: str) -> None:
        from zeroconf import ServiceInfo

        self.unadvertise()
        info = ServiceInfo(
            SERVICE_TYPE,
            f"{name}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(lan_ip())],
            port=port,
            properties={
                "v": str(PROTOCOL_VERSION),
                "id": device_id,
                "platform": platform,
                "fp": fingerprint,
            },
            server=f"{socket.gethostname()}.local.",
        )
        self._service = info
        self._zc.register_service(info)

    def unadvertise(self) -> None:
        if self._service is not None:
            try:
                self._zc.unregister_service(self._service)
            except Exception:
                pass
            self._service = None

    def devices(self) -> List[DeviceInfo]:
        return list(self._found.values())

    def browse(self) -> None:
        from zeroconf import ServiceBrowser, ServiceStateChange

        def on_change(zeroconf, service_type, name, state_change):
            if state_change in (ServiceStateChange.Added, ServiceStateChange.Updated):
                info = zeroconf.get_service_info(service_type, name, timeout=3000)
                if info and info.addresses:
                    props = {
                        (k.decode() if isinstance(k, bytes) else k): (
                            v.decode() if isinstance(v, bytes) else str(v)
                        )
                        for k, v in (info.properties or {}).items()
                    }
                    self._found[name] = DeviceInfo(
                        name=name[: -len(SERVICE_TYPE)].rstrip("."),
                        host=socket.inet_ntoa(info.addresses[0]),
                        port=info.port,
                        device_id=props.get("id", ""),
                        platform=props.get("platform", ""),
                        fingerprint=props.get("fp", ""),
                    )
            elif state_change == ServiceStateChange.Removed:
                self._found.pop(name, None)

        ServiceBrowser(self._zc, SERVICE_TYPE, handlers=[on_change])

    def close(self) -> None:
        self.unadvertise()
        try:
            self._zc.close()
        except Exception:
            pass


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
