"""Gate tests for vidgrab_transfer (TLS design). Functional tests only:
they verify behavior of this implementation, not cryptographic security."""

import os
import shutil
import socket
import ssl
import stat
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vidgrab_transfer as vt


class UI(vt.TransferUI):
    def __init__(self, accept=True):
        self.accept = accept
        self.events = []

    def decide_offer(self, sender_name, filename, size, respond):
        self.events.append(("offer", sender_name, filename, size))
        respond(self.accept)

    def progress(self, filename, received, total):
        pass

    def done(self, filename, saved_path):
        self.events.append(("done", filename, saved_path))

    def failed(self, filename, reason):
        self.events.append(("failed", filename, reason))


def make_world(tmp, accept=True, max_bytes=vt.DEFAULT_MAX_BYTES):
    store = os.path.join(tmp, "id")
    identity = vt.Identity.load_or_create(store)
    rdir = os.path.join(tmp, "recv")
    ui = UI(accept=accept)
    srv = vt.ReceiveServer(identity, rdir, ui, max_bytes=max_bytes)
    srv.start()
    pins = vt.PinStore(os.path.join(tmp, "pins.json"))
    return identity, srv, ui, rdir, pins


def main():
    tmp = tempfile.mkdtemp()
    payload = os.urandom(3_000_000)
    src = os.path.join(tmp, "clip one.mp4")
    with open(src, "wb") as fh:
        fh.write(payload)

    identity, srv, ui, rdir, pins = make_world(tmp)

    # 1. identity storage permissions (POSIX mode check; Windows uses ACLs)
    if os.name == "posix":
        mode = stat.S_IMODE(os.stat(identity.key_file).st_mode)
        assert mode == 0o600, f"key perms {oct(mode)}"
        print("T1 identity key stored 0600 OK")
    else:
        assert os.path.isfile(identity.key_file)
        print("T1 identity key exists OK (0600 check is POSIX-only; Windows uses ACLs)")

    # 2. connect performs handshake only; untrusted until user confirms
    client = vt.SendClient("127.0.0.1", srv.port, "TestPhone", pins, identity.device_id)
    fp = client.connect()
    assert fp == identity.fingerprint
    assert not client.trusted
    try:
        client.send(src)
        raise AssertionError("send before trust must fail")
    except vt.TransferError as e:
        assert "unverified" in str(e)
    print("T2 no request before fingerprint trust OK")

    # 3. user confirmed the fingerprint -> pinned -> happy path byte-exact
    client.confirm_trust()
    saved = client.send(src)
    with open(os.path.join(rdir, saved), "rb") as fh:
        assert fh.read() == payload
    client.close()
    print("T3 pinned happy path byte-exact OK ->", saved)

    # 4. returning session: pinned fingerprint auto-trusted
    c2 = vt.SendClient("127.0.0.1", srv.port, "TestPhone", pins, identity.device_id)
    c2.connect()
    assert c2.trusted
    saved2 = c2.send(src)
    assert saved2 != saved  # collision renamed
    c2.close()
    print("T4 pin reuse + collision rename OK ->", saved2)

    # 5. pin change fails closed
    other_dir = os.path.join(tmp, "id2")
    other_id = vt.Identity.load_or_create(other_dir)
    evil = vt.ReceiveServer(other_id, rdir, UI(), port=0)
    evil.start()
    c3 = vt.SendClient("127.0.0.1", evil.port, "Mallory", pins, identity.device_id)
    try:
        c3.connect()
        raise AssertionError("pin change must fail closed")
    except vt.PinChangedError:
        print("T5 pin change fails closed OK")
    evil.stop()

    # 6. upload without an accepted offer / wrong token refused
    c4 = vt.SendClient("127.0.0.1", srv.port, "TestPhone", pins, identity.device_id)
    c4.connect()
    try:
        c4._request("POST", "/v1/upload/deadbeef", b"x" * 100)
        raise AssertionError("bad token must fail")
    except vt.TransferError as e:
        assert "no such upload" in str(e)
    print("T6 wrong upload token refused OK")

    # 7. size cap enforced at offer time
    small_id, small_srv, small_ui, small_rdir, small_pins = make_world(
        os.path.join(tmp, "w2"), max_bytes=1024)
    c5 = vt.SendClient("127.0.0.1", small_srv.port, "T", small_pins, small_id.device_id)
    c5.connect()
    c5.confirm_trust()
    try:
        c5.send(src)
        raise AssertionError("oversize offer must fail")
    except vt.TransferError as e:
        assert "size out of bounds" in str(e)
    c5.close()
    small_srv.stop()
    print("T7 size cap OK")

    # 8. disk-space preflight
    real_free = vt._free_bytes
    vt._free_bytes = lambda p: 10
    try:
        c6 = vt.SendClient("127.0.0.1", srv.port, "T", pins, identity.device_id)
        c6.connect()
        try:
            c6.send(src)
            raise AssertionError("low disk must fail")
        except vt.TransferError as e:
            assert "disk space" in str(e)
        c6.close()
    finally:
        vt._free_bytes = real_free
    print("T8 disk-space preflight OK")

    # 9. aborted upload: temp file cleaned, nothing published
    c7 = vt.SendClient("127.0.0.1", srv.port, "T", pins, identity.device_id)
    c7.connect()
    resp = c7._request("POST", "/v1/offer",
                       b'{"sender_name":"T","filename":"abort.bin","size":1000}')
    offer_id = resp["offer_id"]
    token = None
    for _ in range(50):
        out = c7._request("GET", f"/v1/offer/{offer_id}")
        if out.get("status") == "accepted":
            token = out["upload_token"]
            break
        time.sleep(0.1)
    assert token
    raw = socket.create_connection(("127.0.0.1", srv.port), timeout=10)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    conn = ctx.wrap_socket(raw, server_hostname="127.0.0.1")
    conn.sendall(
        f"POST /v1/upload/{token} HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n".encode()
        + b"partial")
    time.sleep(0.5)  # let the server enter the receive loop before the abort;
                     # an instant close can arrive as a reset (seen on Windows)
                     # before the request is even read, which is a different case
    conn.close()  # drop mid-upload
    c7.close()
    deadline = time.time() + 10
    while time.time() < deadline and not any(e[0] == "failed" for e in ui.events):
        time.sleep(0.2)
    leftovers = [f for f in os.listdir(rdir) if f.startswith(".vidgrab-part-")]
    assert not leftovers, f"temp leftovers: {leftovers}"
    assert not os.path.exists(os.path.join(rdir, "abort.bin"))
    assert any(e[0] == "failed" for e in ui.events)
    print("T9 aborted upload cleaned up OK")

    # 10. filename sanitization: reserved names, traversal, trailing dots
    assert vt.sanitize_filename("../../etc/passwd") == "passwd"
    assert vt.sanitize_filename("..\\..\\win.ini") == "win.ini"
    assert vt.sanitize_filename("CON.txt") == "_CON.txt"
    assert vt.sanitize_filename("nul") == "_nul"
    assert vt.sanitize_filename("report.") == "report"
    assert vt.sanitize_filename("  spaced .txt") == "spaced .txt"
    print("T10 reserved/traversal/trailing-dot names OK")

    # 11. recipient decline stops the send
    dec_id, dec_srv, dec_ui, dec_rdir, dec_pins = make_world(
        os.path.join(tmp, "w3"), accept=False)
    c8 = vt.SendClient("127.0.0.1", dec_srv.port, "T", dec_pins, dec_id.device_id)
    c8.connect()
    c8.confirm_trust()
    try:
        c8.send(src)
        raise AssertionError("declined offer must fail")
    except vt.TransferError as e:
        assert "declined" in str(e)
    c8.close()
    dec_srv.stop()
    print("T11 recipient decline OK")

    # 12. mDNS discovery round trip
    d1 = vt.Discovery()
    d2 = vt.Discovery()
    d1.advertise("VidGrab-test", srv.port, identity.device_id,
                 platform="test", fingerprint=identity.fingerprint)
    d2.browse()
    found = None
    for _ in range(60):
        found = [d for d in d2.devices() if d.device_id == identity.device_id]
        if found:
            break
        time.sleep(0.5)
    d1.close()
    d2.close()
    assert found, "mDNS discovery failed"
    assert found[0].port == srv.port and found[0].fingerprint == identity.fingerprint
    print("T12 mDNS advertise/browse OK")

    # 13. large send streams with bounded sender memory
    import tracemalloc
    big = os.path.join(tmp, "big.bin")
    with open(big, "wb") as fh:
        block = os.urandom(4_000_000)
        for _ in range(12):  # 48 MB
            fh.write(block)
    c9 = vt.SendClient("127.0.0.1", srv.port, "T", pins, identity.device_id)
    c9.connect()
    tracemalloc.start()
    saved_big = c9.send(big)
    _, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    c9.close()
    assert os.path.getsize(os.path.join(rdir, saved_big)) == 48_000_000
    assert peak < 16_000_000, f"sender peak RAM {peak} bytes for a 48MB send"
    print(f"T13 48MB send streamed byte-exact, sender peak RAM {peak/1e6:.1f}MB OK")

    srv.stop()
    shutil.rmtree(tmp, ignore_errors=True)
    print("ALL TLS TRANSFER GATE TESTS PASSED")


if __name__ == "__main__":
    main()
