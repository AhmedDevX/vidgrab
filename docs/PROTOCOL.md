# VidGrab share protocol (v1)

The file-sharing feature is deliberately simple: the sender runs a tiny HTTP
server on the local network and any browser can receive. Both apps implement
the same scheme.

## Starting a session

1. The user explicitly selects files in the app.
2. The app generates:
   - a random **session token** (16 random bytes, URL-safe base64)
   - a random **file id** per file (8 random bytes each)
   - an expiry time (10 minutes)
3. It serves on a random local port and displays:
   `http://<sender-lan-ip>:<port>/<token>/` as text and QR code.

## Requests

| Request | Response |
|---|---|
| `GET /<token>/` | HTML page listing only the selected files (name + size) |
| `GET /<token>/<file-id>/<name>` | the file bytes, `Content-Disposition: attachment` |
| anything else | 404 |

- Request paths are never used as filesystem paths; the server only looks up
  ids in the in-memory map built when the files were picked. Directory
  traversal is therefore not possible.
- After expiry or when the user stops sharing, the port is closed.
- No authentication beyond possessing the token, and **no encryption** (plain
  HTTP on the LAN). Anyone on the same network with the full link can fetch
  the files while the session is active.

## Not in v1

Device discovery (mDNS/Nearby), native receivers, resume, transfer over the
internet, TLS. See the roadmap in the README.
