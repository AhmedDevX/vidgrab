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
| [youtubedl-android](https://github.com/yausername/youtubedl-android) (Android engine wrapper: Python, yt-dlp, ffmpeg) | 0.14.0 | GPL-3.0 | [LICENSE](https://github.com/yausername/youtubedl-android/blob/master/LICENSE) |

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
- **Android APK**: FFmpeg 5.1 (n5.1), from the Termux ffmpeg package, shipped by
  youtubedl-android 0.14.0. Verified byte-identical (SHA-256, all 8 arm64-v8a
  libraries) to youtubedl-android PR #186 (commit `a8a636e6b3`), which took the
  binaries from the Termux package whose recipe declared version 5.1, GPL-3.0
  (termux-packages commit `3d6eb59`, packages/ffmpeg/build.sh). Corresponding
  source: https://ffmpeg.org/releases/ffmpeg-5.1.tar.xz plus the Termux recipe;
  CI also emits `ffmpeg-5.1-src.tar.xz` next to the APKs.

## Python runtimes

| Component | License | Source |
|---|---|---|
| CPython embedded in VidGrab.exe by PyInstaller | PSF-2.0 | [license text](licenses/Python-PSF.txt), https://www.python.org |
| CPython 3.8 embedded in the Android app by youtubedl-android (Termux build) | PSF-2.0 | [license text](licenses/Python-PSF.txt), https://github.com/termux/termux-packages |

## Libraries

| Component | License | License text |
|---|---|---|
| [CustomTkinter](https://github.com/TomSchimansky/CustomTkinter) (Windows UI) | MIT | [text](licenses/CustomTkinter-MIT.txt) |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) (Android share server) | BSD-3-Clause | [text](licenses/NanoHTTPd-BSD.txt) |
| [ZXing core](https://github.com/zxing/zxing) (Android QR) | Apache-2.0 | [text](licenses/zxing-Apache-2.0.txt) |
| [python-qrcode](https://github.com/lincolnloop/python-qrcode) (Windows QR) | BSD-3-Clause | [text](licenses/python-qrcode-BSD.txt) |
| [Pillow](https://python-pillow.org) (Windows QR image) | HPND (MIT-CMU style) | [text](licenses/Pillow-HPND.txt) |
| [imageio-ffmpeg](https://github.com/imageio/imageio-ffmpeg) (ffmpeg locator for source runs only; the built exe bundles the pinned FFmpeg-Builds binary instead) | BSD-2-Clause | [text](licenses/imageio-ffmpeg-BSD.txt) |
| AndroidX, Material Components, kotlinx-coroutines | Apache-2.0 | https://github.com/androidx |

## Build tooling (not distributed)

[PyInstaller](https://pyinstaller.org): GPL-2.0 with a special exception
allowing bundled apps under any license
([license](https://pyinstaller.org/en/stable/license.html)).

---

This tool is for content you have the right to download. Respect each
site's terms and copyright law.
