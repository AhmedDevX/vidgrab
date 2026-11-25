# Extending VidGrab

## Where things live

| What | Windows | Android |
|---|---|---|
| Quality presets | `windows/vidgrab_core.py` (`QUALITY_OPTIONS`) | `android/.../Quality.kt` (`QUALITIES`) |
| Download logic | `vidgrab_core.download()` | `DownloadService.kt` |
| Video info lookup | `vidgrab_core.probe()` | `MainActivity.fetchInfo()` |
| Share server | `windows/vidgrab_share.py` | `ShareServer.kt` + `ShareServerService.kt` |
| UI | `vidgrab_app.py` | `res/layout/*.xml` + the Activity classes |

## Supporting more sites

Site support comes from yt-dlp, not from VidGrab code.

1. **Update the engine first.** On Android use the in-app "Update download engine" button; on Windows run `pip install -U yt-dlp`. Most "this site stopped working" issues are fixed upstream this way.
2. If a site is still unsupported, check [yt-dlp's supported sites](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md) and open/request an extractor upstream. Writing one is documented in the yt-dlp repo (`yt_dlp/extractor/`, see "adding support for a new site" in their CONTRIBUTING). Once it lands in a yt-dlp release, VidGrab picks it up on the next engine update.
3. DRM-protected streams are out of scope by design.

## Changing quality options

Edit the preset lists named above. The format strings are standard yt-dlp
[format selectors](https://github.com/yt-dlp/yt-dlp#format-selection), e.g.
`bv*[height<=720]+ba/b[height<=720]` means "best video up to 720p plus best
audio, merged". After changing, re-run the Windows tests
(`pytest test_core.py`) to catch typos.

## Adding features safely

- Keep GUI-free logic in the core modules (`vidgrab_core.py`, `vidgrab_share.py` on Windows; plain Kotlin classes on Android) so it stays testable.
- The share server must never serve anything the user did not explicitly pick. `windows/test_share.py` covers this; extend those tests if you touch the server.
- Keep the app ad-free and tracker-free. That is a project value, not an accident.
