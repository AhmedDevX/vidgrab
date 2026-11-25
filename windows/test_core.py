"""Tests for vidgrab_core.

Run:  python -m pytest test_core.py -v
Unit tests run anywhere. The integration test downloads a tiny public
sample file and needs network access:

    python -m pytest test_core.py -v --integration
"""

import os
import sys


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pytest

import vidgrab_core as core


# ---------------- unit tests ----------------

def test_extract_url_from_share_text():
    text = "Check this out! https://example.com/watch?v=abc123 so funny"
    assert core.extract_url(text) == "https://example.com/watch?v=abc123"


def test_extract_url_none():
    assert core.extract_url("no links here") is None
    assert core.extract_url("") is None


def test_quality_presets_cover_video_and_audio():
    labels = [q["label"] for q in core.QUALITY_OPTIONS]
    assert "Best quality" in labels
    assert "Audio only (MP3)" in labels
    assert any(q["audio_only"] for q in core.QUALITY_OPTIONS)
    for q in core.QUALITY_OPTIONS:
        assert q["format"], q


def test_progress_dataclass_defaults():
    p = core.Progress()
    assert p.status == "starting" and p.percent == 0.0


# ---------------- integration (network) ----------------

SAMPLE_MP4 = "https://download.samplelib.com/mp4/sample-5s.mp4"


@pytest.mark.integration
def test_probe_direct_video():
    info = core.probe(SAMPLE_MP4)
    assert info.title
    assert info.webpage_url


@pytest.mark.integration
def test_download_direct_video(tmp_path):
    events = []
    path = core.download(
        SAMPLE_MP4,
        quality_index=0,
        out_dir=str(tmp_path),
        progress_cb=lambda p: events.append((p.status, p.percent)),
    )
    assert os.path.exists(path), path
    assert os.path.getsize(path) > 10_000
    statuses = {s for s, _ in events}
    assert "finished" in statuses
