# PyInstaller spec for VidGrab (builds an UNPACKED VidGrab/ directory).
# Build with:  pyinstaller vidgrab.spec
#
# Onedir (not onefile) is deliberate: Qt/PySide6 (LGPL) ships as separate
# replaceable shared libraries, and zeroconf (LGPL-2.1, pure Python) ships
# with its full source under python-sources/ so the frozen copy can be
# re-built/replaced. See docs/CREDITS.md "Distribution constraints".
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

licenses_dir = os.path.join(here, "..", "docs", "licenses")

import zeroconf  # installed dependency; ship its source for LGPL-2.1 replaceability
zeroconf_src = os.path.dirname(zeroconf.__file__)

a = Analysis(
    ["vidgrab_qt.py"],
    pathex=[],
    binaries=[(ffmpeg_bin, ".")],
    # Third-party notices travel with the binary (Qt/PySide6 LGPL-3.0, and
    # any other texts collected under docs/licenses/); zeroconf's source
    # travels too so the LGPL-2.1 component stays replaceable.
    datas=[(licenses_dir, "licenses"),
           (zeroconf_src, "python-sources/zeroconf")],
    hiddenimports=[],
    hookspath=[],
    runtime_hooks=[],
    excludes=[],
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="VidGrab",
    debug=False,
    strip=False,
    upx=False,
    console=False,  # windowed app, no console
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    name="VidGrab",
)
