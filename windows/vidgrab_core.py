"""VidGrab core: URL probing, quality selection and downloading.

This module has no GUI imports so it can be unit-tested on any platform.
It wraps yt-dlp (public domain / Unlicense) and locates an ffmpeg binary
for merging video+audio and for MP3 extraction.
"""

from __future__ import annotations

import os
import re
import shutil
import sys
import time
import urllib.parse
import urllib.request
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
    # Estimated bytes per QUALITY_OPTIONS entry (None = unknown).
    sizes: Optional[list] = None


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



@dataclass
class SearchResult:
    video_id: str
    title: str
    channel: str
    duration_seconds: Optional[int]
    url: str


def format_duration(seconds: Optional[int]) -> str:
    if not seconds:
        return ""
    seconds = int(seconds)
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def parse_search_entries(info: dict) -> list:
    """Turn a flat yt-dlp search result into SearchResult rows.
    Skips entries without an id or title."""
    out = []
    for e in (info or {}).get("entries") or []:
        if not e:
            continue
        vid = e.get("id")
        title = e.get("title")
        if not vid or not title:
            continue
        out.append(SearchResult(
            video_id=vid,
            title=title,
            channel=e.get("channel") or e.get("uploader") or "",
            duration_seconds=int(e["duration"]) if e.get("duration") else None,
            url=e.get("url") if str(e.get("url", "")).startswith("http")
            else f"https://www.youtube.com/watch?v={vid}",
        ))
    return out


def search(query: str, count: int = 15) -> list:
    """YouTube search through yt-dlp (flat extraction, no downloads)."""
    query = (query or "").strip()
    if not query:
        return []
    opts = {"quiet": True, "no_warnings": True, "extract_flat": True,
            "skip_download": True}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(f"ytsearch{int(count)}:{query}", download=False)
    return parse_search_entries(info)


def _max_height(selector: str) -> Optional[int]:
    import re
    m = re.search(r"height<=(\d+)", selector)
    return int(m.group(1)) if m else None


def _fmt_size(f: dict, duration: int) -> int:
    size = f.get("filesize") or f.get("filesize_approx") or 0
    if size:
        return int(size)
    tbr = f.get("tbr") or 0
    if tbr and duration:
        return int(tbr * 1000 / 8 * duration)
    return 0


def estimate_size(formats, max_height, audio_only, duration) -> Optional[int]:
    """Estimated download size in bytes from yt-dlp's format list, or None
    when it cannot be known. Never invents a number."""
    formats = formats or []
    duration = duration or 0

    def has_v(f):
        return (f.get("vcodec") or "none") != "none" and (f.get("height") or 0) > 0

    def has_a(f):
        return (f.get("acodec") or "none") != "none"

    audio_only_fmts = [f for f in formats if has_a(f) and not has_v(f)]
    sizes_a = [s for s in (_fmt_size(f, duration) for f in audio_only_fmts) if s > 0]
    if audio_only:
        if sizes_a:
            return max(sizes_a)
        any_a = [s for s in (_fmt_size(f, duration) for f in formats if has_a(f)) if s > 0]
        return min(any_a) if any_a else None
    vids = [f for f in formats
            if has_v(f) and (max_height is None or f["height"] <= max_height)]
    if not vids:
        return None
    top = max(f["height"] for f in vids)
    best = max((f for f in vids if f["height"] == top),
               key=lambda f: _fmt_size(f, duration))
    vs = _fmt_size(best, duration)
    if vs <= 0:
        return None
    if has_a(best):
        return vs
    return vs + max(sizes_a) if sizes_a else None


def format_size(n: int) -> str:
    if n >= 1_000_000_000:
        return f"~{n / 1e9:.1f} GB"
    if n >= 1_000_000:
        return f"~{max(1, int(n / 1e6))} MB"
    return f"~{max(1, int(n / 1e3))} KB"


_VIDEO_EXT = {"mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "flv", "ts"}
_AUDIO_EXT = {"mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "oga"}
_IMAGE_EXT = {"jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif"}


def file_kind(name: str) -> str:
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    if ext in _VIDEO_EXT:
        return "video"
    if ext in _AUDIO_EXT:
        return "audio"
    if ext in _IMAGE_EXT:
        return "image"
    return "other"


def list_downloads(directory: str) -> list:
    """Files in the download folder, newest first: (path, name, size, mtime, kind)."""
    out = []
    try:
        for entry in os.scandir(directory):
            if entry.is_file() and not entry.name.endswith((".part", ".ytdl", ".tmp")):
                st = entry.stat()
                out.append((entry.path, entry.name, st.st_size, st.st_mtime,
                            file_kind(entry.name)))
    except OSError:
        return []
    out.sort(key=lambda r: r[3], reverse=True)
    return out


def pick_clip_url(clip: Optional[str], current: str, last_pasted: Optional[str]) -> Optional[str]:
    """URL to auto-paste from the clipboard, or None (no URL, already in the
    field, or already auto-pasted before)."""
    if not clip:
        return None
    url = extract_url(clip)
    if not url:
        return None
    url = url.rstrip(".,)")
    if url == last_pasted or url in (current or ""):
        return None
    return url


def probe(url: str) -> VideoInfo:
    """Fetch title/duration/etc. without downloading the video."""
    opts = {"quiet": True, "no_warnings": True, "noplaylist": True, "skip_download": True}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    sizes = [estimate_size(info.get("formats"),
                           _max_height(q["format"]), q["audio_only"], info.get("duration"))
             for q in QUALITY_OPTIONS]
    return VideoInfo(
        sizes=sizes,
        title=info.get("title") or "Untitled",
        duration_seconds=info.get("duration"),
        extractor=info.get("extractor_key") or info.get("extractor") or "generic",
        webpage_url=info.get("webpage_url") or url,
        thumbnail=info.get("thumbnail"),
    )


def _direct_file_name(url: str, content_disposition: Optional[str]) -> str:
    """Best-effort filename for a direct link: Content-Disposition first,
    then the URL path basename; always reduced to a safe basename."""
    name = ""
    if content_disposition:
        m = re.search(r"filename\*?=(?:UTF-8'')?\"?([^\";]+)", content_disposition)
        if m:
            name = m.group(1).strip()
    if not name:
        name = os.path.basename(urllib.parse.urlparse(url).path)
    name = urllib.parse.unquote(name)
    name = name.split("/")[-1].split("\\")[-1]
    name = re.sub(r'[<>:"|?*\x00-\x1f]', "_", name).strip().rstrip(". ")
    return name or "download"


def probe_direct(url: str) -> Optional[dict]:
    """Detect a direct file link (image, archive, document, any file).

    Returns {filename, size, content_type} when the URL serves a file, or
    None when it serves an HTML page or cannot be probed. Some servers
    reject HEAD requests; those links simply fall through as not-direct.
    Only plain HTTP(S) file links - protected/DRM media is out of scope.
    """
    req = urllib.request.Request(url, method="HEAD",
                                 headers={"User-Agent": "VidGrab/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            ctype = (resp.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            if not ctype or ctype.startswith("text/html"):
                return None
            try:
                size = int(resp.headers.get("Content-Length") or -1)
            except ValueError:
                size = -1
            return {
                "filename": _direct_file_name(resp.geturl(), resp.headers.get("Content-Disposition")),
                "size": size,
                "content_type": ctype,
                "url": resp.geturl(),
            }
    except Exception:
        return None


def download_direct(
    url: str,
    out_dir: str,
    progress_cb: Optional[Callable[[Progress], None]] = None,
) -> str:
    """Stream a direct file link into out_dir (1 MiB chunks, .part file
    renamed into place only after full receipt). Returns the saved path."""
    os.makedirs(out_dir, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "VidGrab/1.0"})
    state = Progress(status="downloading")
    with urllib.request.urlopen(req, timeout=30) as resp:
        try:
            total = int(resp.headers.get("Content-Length") or 0)
        except ValueError:
            total = 0
        name = _direct_file_name(resp.geturl(), resp.headers.get("Content-Disposition"))
        stem, ext = os.path.splitext(name)
        target = os.path.join(out_dir, name)
        n = 1
        while os.path.exists(target):
            n += 1
            target = os.path.join(out_dir, f"{stem} ({n}){ext}")
        tmp = target + ".part"
        received = 0
        started = time.monotonic()
        try:
            with open(tmp, "wb") as fh:
                while True:
                    chunk = resp.read(1024 * 1024)
                    if not chunk:
                        break
                    fh.write(chunk)
                    received += len(chunk)
                    elapsed = max(time.monotonic() - started, 1e-3)
                    speed = received / elapsed
                    state.percent = (received / total * 100.0) if total else 0.0
                    state.speed_bytes = speed
                    state.eta_seconds = int(max((total - received) / max(speed, 1), 0)) if total else None
                    state.filename = target
                    if progress_cb:
                        progress_cb(state)
                fh.flush()
                os.fsync(fh.fileno())
        except Exception:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
        if total and received != total:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise IOError(f"download incomplete ({received} != {total})")
        os.replace(tmp, target)
    state.status = "finished"
    state.percent = 100.0
    if progress_cb:
        progress_cb(state)
    return target


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

    # Fallback chain for "Requested format is not available": chosen
    # selector -> yt-dlp default (no format) -> "best*".
    info = None
    path = ""
    last_exc = None
    for sel in (quality["format"], None, "best*"):
        attempt = dict(opts)
        if sel is None:
            attempt.pop("format", None)
        else:
            attempt["format"] = sel
        try:
            with yt_dlp.YoutubeDL(attempt) as ydl:
                info = ydl.extract_info(url, download=True)
                path = ydl.prepare_filename(info)
            last_exc = None
            break
        except yt_dlp.utils.DownloadError as exc:
            last_exc = exc
            if "Requested format is not available" not in str(exc):
                raise
    if last_exc is not None:
        raise last_exc

    if quality["audio_only"]:
        path = os.path.splitext(path)[0] + ".mp3"
    elif not os.path.exists(path):
        # merge_output_format can change the container extension.
        path = os.path.splitext(path)[0] + ".mp4"
    return path
