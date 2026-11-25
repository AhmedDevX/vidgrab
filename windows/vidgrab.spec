# PyInstaller spec for VidGrab (builds a single VidGrab.exe).
# Build with:  pyinstaller vidgrab.spec
#
# The bundled ffmpeg is the pinned BtbN/FFmpeg-Builds GPL build fetched into
# vendor/ by vendor/fetch_ffmpeg.ps1 (or .sh). Run that first; its SHA-256 is
# verified by the fetch script. Provenance: docs/CREDITS.md.
import os

here = os.path.abspath(globals().get("SPECPATH", os.getcwd()))
ffmpeg_bin = os.path.join(here, "vendor", "ffmpeg.exe")
if not os.path.isfile(ffmpeg_bin):
    raise SystemExit(
        "vendor/ffmpeg.exe is missing - run vendor/fetch_ffmpeg.ps1 first "
        "(it downloads the pinned ffmpeg build and verifies its SHA-256)."
    )

a = Analysis(
    ["vidgrab_app.py"],
    pathex=[],
    binaries=[(ffmpeg_bin, ".")],
    datas=[],
    hiddenimports=[],
    hookspath=[],
    runtime_hooks=[],
    excludes=[],
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="VidGrab",
    debug=False,
    strip=False,
    upx=False,
    console=False,  # windowed app, no console
)
