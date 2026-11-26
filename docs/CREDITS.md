# Credits, licenses and redistribution notices

VidGrab itself is GPL-3.0 (see LICENSE). This file, the LICENSE, and the
license texts in [docs/licenses/](licenses/) ship with binary releases in
VidGrab-NOTICES.zip. The full corresponding source of VidGrab is this
repository. Bundled third-party binaries are unmodified upstream builds;
their sources are linked per component below.

## Download engine

| Component | Version | License | Source |
|---|---|---|---|
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Windows: bundled by pip; Android: bundled by youtubedl-android via the ytdlp-lazy build) | latest at build time | Unlicense (public domain) | [LICENSE](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE) |
| [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (Android engine wrapper: Python, yt-dlp, ffmpeg, QuickJS; JunkFood02 fork of [yausername/youtubedl-android](https://github.com/yausername/youtubedl-android), from Maven Central `io.github.junkfood02.youtubedl-android`) | 0.18.1 | GPL-3.0 | [LICENSE](https://github.com/JunkFood02/youtubedl-android/blob/master/LICENSE) |

## ffmpeg (GPL-3.0 builds) - corresponding source

Both ffmpeg builds below are unmodified upstream GPL-3.0 binaries with
verified, byte-level provenance. Full details: [docs/licenses/FFMPEG-NOTE.txt](licenses/FFMPEG-NOTE.txt).

- **Windows exe**: ffmpeg n9.0.2-17-g2a571b6068, win64-gpl-9.0 static build from
  [BtbN/FFmpeg-Builds](https://github.com/FFmpeg-Builds/FFmpeg-Builds) release
  `autobuild-2026-09-30-13-08` (zip SHA-256 pinned and verified at fetch time;
  the zip ships a GPLv3 LICENSE.txt). Corresponding source: FFmpeg commit
  [2a571b6068](https://github.com/FFmpeg/FFmpeg/tree/2a571b606854520cf89804d8030c8b328e621689)
  plus the FFmpeg-Builds recipes at that tag; CI also emits
  `ffmpeg-2a571b6068-src.tar.gz` next to the exe.
- **Android APK**: FFmpeg 7.1.1 (n7.1.1, libavcodec 61.19.101), from the Termux
  ffmpeg package, shipped by the youtubedl-android fork 0.18.1. Verified
  byte-identical (SHA-256) between the Maven Central AAR
  `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` and the binaries
  committed upstream in yausername/youtubedl-android master (commit `f2280ae7d8`
  "Bump ffmpeg 7.1.1"), which took them from the Termux package whose recipe
  declared version 7.1.1, GPL-3.0 (termux-packages commit `de5f604e60ba`,
  packages/ffmpeg/build.sh). Corresponding source:
  https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz plus the Termux recipe; CI
  emits the source archive next to the APKs.

## Python runtimes

| Component | License | Source |
|---|---|---|
| CPython embedded in VidGrab.exe by PyInstaller | PSF-2.0 | [license text](licenses/Python-PSF.txt), https://www.python.org |
| CPython 3.12 embedded in the Android app by youtubedl-android (Termux build; byte-identical to upstream master `libpython.zip.so`) | PSF-2.0 | [license text](licenses/Python-PSF.txt), https://github.com/termux/termux-packages |

## Libraries

| Component | License | License text |
|---|---|---|
| [PySide6 / Qt 6](https://www.qt.io) (desktop UI; dynamically linked Qt shared libraries bundled by PyInstaller, replaceable by rebuilding) | LGPL-3.0 | [text](licenses/LGPL-3.0.txt) |
| [cryptography](https://github.com/pyca/cryptography) (desktop TLS identity) | Apache-2.0 OR BSD-3-Clause | [Apache text](licenses/Apache-2.0.txt) |
| [zeroconf](https://github.com/python-zeroconf/python-zeroconf) (desktop mDNS) | LGPL-2.1 | [text](licenses/LGPL-2.1.txt) |
| [Bouncy Castle](https://www.bouncycastle.org) bcprov/bcpkix 1.87 (Android X.509 identity certificates; TLS itself uses the platform `javax.net.ssl`) | MIT-style (Bouncy Castle License) | [text](licenses/BouncyCastle-MIT.md) |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) (Android share + transfer server) | BSD-3-Clause | [text](licenses/NanoHTTPd-BSD.txt) |
| [ZXing core](https://github.com/zxing/zxing) 3.5.4 (Android QR) | Apache-2.0 | [text](licenses/zxing-Apache-2.0.txt) |
| [QuickJS](https://bellard.org/quickjs/) (JS runtime bundled in the Android engine for yt-dlp) | MIT | [text](licenses/QuickJS-MIT.txt) |
| [python-qrcode](https://github.com/lincolnloop/python-qrcode) (Windows QR) | BSD-3-Clause | [text](licenses/python-qrcode-BSD.txt) |
| [Pillow](https://python-pillow.org) (Windows QR image) | HPND (MIT-CMU style) | [text](licenses/Pillow-HPND.txt) |
| [imageio-ffmpeg](https://github.com/imageio/imageio-ffmpeg) (ffmpeg locator for source runs only; the built exe bundles the pinned FFmpeg-Builds binary instead) | BSD-2-Clause | [text](licenses/imageio-ffmpeg-BSD.txt) |
| AndroidX, Material Components, kotlinx-coroutines | Apache-2.0 | https://github.com/androidx |

### Distribution shape and constraints

- The Windows build ships as an UNPACKED directory (PyInstaller onedir),
  not a single-file exe, so bundled components stay inspectable and
  replaceable.
- zeroconf (LGPL-2.1, pure Python) is archived in the app's PYZ, so its
  complete source ships alongside the binary under
  `python-sources/zeroconf/` and the full VidGrab source plus build
  workflow are public in this repository: anyone can rebuild the app with
  a modified zeroconf. We do not assert this satisfies every reading of
  LGPL-2.1; if a stricter reading is required we can swap zeroconf for a
  self-contained mDNS implementation.
- Qt/PySide6 (LGPL-3.0) is bundled as separate shared libraries in the
  onedir layout that can be replaced in place; VidGrab is GPL-3.0, which
  is compatible with linking LGPL-3.0 components, but this is provenance,
  not legal advice.

## Build tooling (not distributed)

[PyInstaller](https://pyinstaller.org): GPL-2.0 with a special exception
allowing bundled apps under any license
([license](https://pyinstaller.org/en/stable/license.html)).

---

This tool is for content you have the right to download. Respect each
site's terms and copyright law.
