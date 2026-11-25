"""Security and behavior tests for vidgrab_share.

Run:  python -m pytest test_share.py -v
All tests run locally against a real server on localhost; no external network.
"""

import http.client
import os
import sys
import time

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from vidgrab_share import ShareSession


@pytest.fixture()
def shared(tmp_path):
    chosen = tmp_path / "chosen.txt"
    chosen.write_bytes(b"vidgrab-test-payload" * 100)
    secret = tmp_path / "not-selected.txt"
    secret.write_bytes(b"must never be served")
    session = ShareSession([str(chosen)], port=0, host="127.0.0.1")
    session.start()
    yield session, tmp_path
    session.stop()


def get(port, path):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    conn.request("GET", path)
    resp = conn.getresponse()
    body = resp.read()
    conn.close()
    return resp.status, body


def test_correct_token_lists_only_selected_files(shared):
    session, _ = shared
    status, body = get(session.port, f"/{session.token}/")
    assert status == 200
    assert b"chosen.txt" in body
    assert b"not-selected.txt" not in body


def test_file_bytes_served(shared):
    session, _ = shared
    status, listing = get(session.port, f"/{session.token}/")
    file_id = listing.decode().split(f"{session.token}/")[1].split("/")[0]
    status, body = get(session.port, f"/{session.token}/{file_id}/chosen.txt")
    assert status == 200
    assert body == b"vidgrab-test-payload" * 100


def test_wrong_or_missing_token_rejected(shared):
    session, _ = shared
    for path in ("/", "/wrong-token/", f"/{session.token}x/"):
        status, _ = get(session.port, path)
        assert status == 404, path


def test_unselected_file_not_reachable(shared):
    session, _ = shared
    status, _ = get(session.port, f"/{session.token}/deadbeef/not-selected.txt")
    assert status == 404


def test_directory_traversal_rejected(shared):
    session, _ = shared
    for path in (
        f"/{session.token}/../not-selected.txt",
        f"/{session.token}/%2e%2e/not-selected.txt",
        f"/{session.token}/..%2f..%2fetc%2fpasswd",
        f"/{session.token}//etc/passwd",
    ):
        status, _ = get(session.port, path)
        assert status == 404, path


def test_expired_session_serves_nothing(tmp_path):
    f = tmp_path / "a.txt"
    f.write_bytes(b"x")
    session = ShareSession([str(f)], port=0, expiry_seconds=1, host="127.0.0.1")
    session.start()
    assert get(session.port, f"/{session.token}/")[0] == 200
    time.sleep(1.2)
    assert get(session.port, f"/{session.token}/")[0] == 404
    session.stop()


def test_stopped_session_closes_port(shared):
    session, _ = shared
    port = session.port
    session.stop()
    with pytest.raises(OSError):
        get(port, f"/{session.token}/")


def test_empty_selection_rejected(tmp_path):
    with pytest.raises(ValueError):
        ShareSession([], host="127.0.0.1")
    with pytest.raises(ValueError):
        ShareSession([str(tmp_path)], host="127.0.0.1")  # a directory
