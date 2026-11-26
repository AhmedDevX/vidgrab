# VidGrab transfer protocols

VidGrab has two transfer paths. The **native device-to-device transfer**
(v2) is the primary path. The **browser fallback** (v1) is a separate,
optional flow for receivers that cannot run the app.

---

## v2: native device-to-device transfer (TLS)

Two devices running VidGrab find each other over mDNS and move files
directly, with the transport authenticated and encrypted by TLS.

### Roles and trust model

- The **receiver** runs an HTTPS server with a persistent self-signed
  ECDSA P-256 identity certificate, generated on first run. The private key
  stays in the app's private storage (0600 files / app-private dir) and
  never leaves the device.
- TLS authenticates the **receiver to the sender**. The sender is an
  unverified LAN peer; every transfer therefore also requires the
  recipient's explicit accept on the receiver's screen.
- **mDNS is reachability only.** Advertised name, host, port and even the
  advertised fingerprint are untrusted; trust comes only from the TLS
  fingerprint comparison below.

### First contact: fingerprint verification

1. The sender discovers the receiver (mDNS `_vidgrab._tcp.local.`, TXT:
   `v`, `id`, `platform`, `fp`).
2. The sender opens a TLS connection (TLS 1.2 floor, 1.3 where offered) and
   computes the SHA-256 fingerprint (64 hex chars) of the presented
   certificate. CA validation is off; the fingerprint IS the
   authentication.
3. **No HTTP request of any kind leaves the sender before the fingerprint
   is trusted.** First trust requires the user to compare the full
   fingerprint with the receiver's own screen (shown as grouped hex and as
   a QR carrying `vidgrab://connect?h=<host>&p=<port>&fp=<fingerprint>&n=<name>`
   - public connection identity, not a secret, and not by itself a receive
   authorization).
4. Confirmed fingerprints are pinned per device id. A changed fingerprint
   later **fails closed** (no silent re-pin). All requests of a session
   reuse the single verified connection.

### Offer / accept / upload

| Request | Response |
|---|---|
| `GET /v1/info` | `{v, device_id, fingerprint}` - public identity, authorizes nothing |
| `POST /v1/offer` `{sender_name, filename, size}` | `202 {offer_id}`; the receiver's user is asked to accept |
| `GET /v1/offer/{offer_id}` | `{status}`; when accepted also `{upload_token}` (one-time) |
| `POST /v1/upload/{token}` body = exactly `size` bytes | `200 {saved_as}` |

Bounds (both implementations):

- one pending offer and one active upload per receiver; offers expire after
  60 s; upload token is one-time and scoped to the accepted offer
- per-file size cap (32 GiB default) and disk-space preflight; requests
  failing these are rejected before any byte is accepted
- uploads stream to a temp file with a 30 s inactivity abort; aborted
  uploads delete the temp file; the file is renamed into the receive
  folder only after the full body arrived intact
- filenames are reduced to a safe basename; Windows reserved names
  (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`) and trailing
  dots/spaces are handled; collisions auto-rename to `name (2).ext`

### Known limits (v2)

- Sender-side file reads are buffered in memory (receive side streams).
- The fingerprint comparison is only as strong as the user actually
  comparing it; the QR path carries the same full fingerprint.
- No internet traversal: same-LAN only, by design.
- This protocol and implementation have not been independently audited.

---

## v1: browser fallback (unchanged, off unless explicitly started)

For receivers without the app: the sender runs a tiny HTTP server and any
browser can fetch. Off by default; the user starts it explicitly.

1. The user explicitly selects files for browser sharing (a separate
   selection from native transfer).
2. The app generates a random session token (16 bytes), a random file id
   per file, and a 10-minute expiry, then displays
   `http://<lan-ip>:<port>/<token>/` as text and QR.

| Request | Response |
|---|---|
| `GET /<token>/` | HTML page listing only the selected files |
| `GET /<token>/<file-id>/<name>` | the file bytes |
| anything else | 404 |

- Request paths are never used as filesystem paths; traversal is not
  possible. After expiry or stop, the port is closed.
- **No encryption and no authentication beyond possessing the token link.**
  Anyone on the same network with the full link can fetch the files while
  the session is active. The UI says this plainly next to the link; the
  native path above is the encrypted default.
