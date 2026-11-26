# VidGrab v1.0.0

First public release. Download videos, send files between your own devices, in English or Arabic.

## What's in it
- **Download videos and files** from links you paste or share. Powered by yt-dlp. Quality choice, progress, speed and ETA. Direct file links work too.
- **YouTube search** on Android and Windows. Pick a result to load it.
- **Android in-app browser** with a "Download this page" button. Files started from web pages go through VidGrab's own downloader, after you confirm.
- **Basic ad blocker** (Android browser): known ad hosts are blocked, pop-ups opened without a tap are refused, and there is a shield button to turn it off per site.
- **Device-to-device transfer** (Android and Windows, same Wi-Fi): TLS with a full fingerprint check on both screens. The receiver accepts every file. The sender is always shown as unverified.
- Light, Dark and Glass themes. English and Arabic with right-to-left layout.
- The Android app shows the yt-dlp version and can update it. Downloads retry once on the nightly yt-dlp channel.

## Downloads
- `VidGrab-windows.zip`: unzip and run VidGrab.exe. Windows may show a SmartScreen warning because the build is not code-signed.
- `vidgrab-android.apk`: arm64 phones (nearly all modern ones). It is a **debug-signed** build, so Android will ask you to allow installs from this source.

## Limits (please read)
- **No DRM bypass.** Protected content from paid streaming services is not supported and will not be added.
- **You are responsible** for having the right to download and keep what you save.
- Site coverage is yt-dlp's coverage. Sites change often, so some links will fail until yt-dlp is updated.
- The **ad blocker is basic**. It will miss ads served from the same host as the content and in-page or video ads. Some web-page downloads (blob: or script-made links) do not work in the in-app browser.
- The Windows app has search and paste-link only. There is no in-app browser on Windows.
- Device-to-device transfer works on the same Wi-Fi only. Many routers isolate devices; a phone hotspot is the workaround. The protocol has not been independently audited.
- The optional browser fallback for devices without the app is plain HTTP on your local network and is **not encrypted**. It is off unless you start it.
- This build has not been security-audited, and no security guarantee is made.
- Licensed GPL-3.0. Third-party credits are in docs/CREDITS.md.
