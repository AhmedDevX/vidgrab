# vendored ffmpeg (Windows builds)

`ffmpeg.exe` / `ffprobe.exe` are NOT stored in this repository. Run
`fetch_ffmpeg.ps1` (Windows) or `fetch_ffmpeg.sh` (Linux/macOS/CI) once to
download the pinned build and verify its SHA-256 before building the exe.

Pinned build: BtbN/FFmpeg-Builds (now FFmpeg-Builds/FFmpeg-Builds),
release `autobuild-2026-09-30-13-08`, asset
`ffmpeg-n9.0.2-17-g2a571b6068-win64-gpl-9.0.zip`
SHA-256: `a0e45723c72141975f51d8666302e614711745f3102b704ca3f82c897a58d278`

This is an unmodified upstream GPL-3.0 build. Its build scripts are the
ffmpeg build recipes in the FFmpeg-Builds repository at that tag, and the
FFmpeg source it was compiled from is FFmpeg git commit
2a571b606854520cf89804d8030c8b328e621689 (n9.0.2-17-g2a571b6068).
See docs/CREDITS.md and docs/licenses/FFMPEG-NOTE.txt.
