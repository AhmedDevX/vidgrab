#!/usr/bin/env bash
# Linux/macOS twin of fetch_ffmpeg.ps1 (used by CI and local builds).
set -euo pipefail
URL="https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-09-30-13-08/ffmpeg-n9.0.2-17-g2a571b6068-win64-gpl-9.0.zip"
SHA="a0e45723c72141975f51d8666302e614711745f3102b704ca3f82c897a58d278"
DIR="$(cd "$(dirname "$0")" && pwd)"
[ -f "$DIR/ffmpeg.exe" ] && { echo "vendor/ffmpeg.exe already present"; exit 0; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
curl -fL "$URL" -o "$TMP/ffmpeg.zip"
echo "$SHA  $TMP/ffmpeg.zip" | sha256sum -c -
unzip -q -o "$TMP/ffmpeg.zip" -d "$TMP/x"
cp "$TMP"/x/*/bin/ffmpeg.exe "$TMP"/x/*/bin/ffprobe.exe "$DIR/"
echo "OK: vendor/ffmpeg.exe + ffprobe.exe (ffmpeg n9.0.2-17-g2a571b6068, GPL build)"
