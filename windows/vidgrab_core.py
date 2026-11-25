"""VidGrab core: URL probing, quality selection and downloading.

This module has no GUI imports so it can be unit-tested on any platform.
It wraps yt-dlp (public domain / Unlicense) and locates an ffmpeg binary
for merging video+audio and for MP3 extraction.
"""

from __future__ import annotations

import os
import shutil
import sys
from dataclasses import dataclass, field
from typing import Callable, Optional

import yt_dlp

APP_NAME = "VidGrab"

# Quality presets. The format strings are yt-dlp format selectors:
# "best video up to N pixels high, plus best audio, merged".
QUALITY_OPTIONS = [
    {"label": "Best quality", "format": "bv*+ba/b", "audio_only": False},
    {"label": "1080p", "format": "bv*[height<=1080]+ba/b[height<=1080]", "audio_only": False},
    {"label": "720p", "format": "bv*[height<=720]+ba/b[height<=720]", "audio_only": False},
    {"label": "480p", "format": "bv*[height<=480]+ba/b[height<=480]", "audio_only": False},
    {"label": "360p", "format": "bv*[height<=360]+ba/b[height<=360]", "audio_only": False},
    {"label": "Audio only (MP3)", "format": "ba/b", "audio_only": True},
]

# Matches http(s) URLs inside arbitrary shared text.
URL_MARKER = ("http://", "https://")


@dataclass
class VideoInfo:
    title: str
    duration_seconds: Optional[int]
    extractor: str
    webpage_url: str
    thumbnail: Optional[str] = None


@dataclass
class Progress:
    percent: float = 0.0
    speed_bytes: Optional[float] = None
    eta_seconds: Optional[int] = None
    status: str = "starting"  # starting | downloading | finished | error
    filename: Optional[str] = None
    error: Optional[str] = None


def extract_url(text: str) -> Optional[str]:
    """Pull the first URL out of arbitrary text (e.g. a shared caption)."""
    if not text:
        return None
    for token in text.split():
        if token.startswith(URL_MARKER):
            return token.strip()
    return None


def find_ffmpeg() -> Optional[str]:
    """Locate an ffmpeg binary.

    Order: bundled next to the frozen exe (PyInstaller), imageio-ffmpeg's
    static binary if installed, then PATH.
    """
    if getattr(sys, "frozen", False):
        exe_dir = os.path.dirname(sys.executable)
        for name in os.listdir(exe_dir):
            if name.startswith("ffmpeg") and name.endswith(".exe"):
                return os.path.join(exe_dir, name)
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        pass
    return shutil.which("ffmpeg")


def probe(url: str) -> VideoInfo:
    """Fetch title/duration/etc. without downloading the video."""
    opts = {"quiet": True, "no_warnings": True, "noplaylist": True, "skip_download": True}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    return VideoInfo(
        title=info.get("title") or "Untitled",
        duration_seconds=info.get("duration"),
        extractor=info.get("extractor_key") or info.get("extractor") or "generic",
        webpage_url=info.get("webpage_url") or url,
        thumbnail=info.get("thumbnail"),
    )


def download(
    url: str,
    quality_index: int,
    out_dir: str,
    progress_cb: Optional[Callable[[Progress], None]] = None,
    ffmpeg_location: Optional[str] = None,
) -> str:
    """Download `url` at the given quality preset into `out_dir`.

    Returns the path of the finished file. Raises yt_dlp.utils.DownloadError
    on failure.
    """
    quality = QUALITY_OPTIONS[quality_index]
    os.makedirs(out_dir, exist_ok=True)
    ffmpeg_location = ffmpeg_location or find_ffmpeg()

    state = Progress()

    def hook(d: dict) -> None:
        if d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            downloaded = d.get("downloaded_bytes") or 0
            state.status = "downloading"
            state.percent = (downloaded / total * 100.0) if total else 0.0
            state.speed_bytes = d.get("speed")
            state.eta_seconds = d.get("eta")
            state.filename = d.get("filename")
        elif d.get("status") == "finished":
            state.status = "finished"
            state.percent = 100.0
            state.filename = d.get("filename")
        if progress_cb:
            progress_cb(state)

    opts = {
        "format": quality["format"],
        "outtmpl": os.path.join(out_dir, "%(title)s.%(ext)s"),
        "noplaylist": True,
        "progress_hooks": [hook],
        "quiet": True,
        "no_warnings": True,
        "merge_output_format": "mp4",
    }
    if ffmpeg_location:
        opts["ffmpeg_location"] = ffmpeg_location
    if quality["audio_only"]:
        opts["postprocessors"] = [
            {
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }
        ]

    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
        path = ydl.prepare_filename(info)

    if quality["audio_only"]:
        path = os.path.splitext(path)[0] + ".mp3"
    elif not os.path.exists(path):
        # merge_output_format can change the container extension.
        path = os.path.splitext(path)[0] + ".mp4"
    return path
