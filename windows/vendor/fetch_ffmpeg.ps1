# Downloads the pinned BtbN/FFmpeg-Builds Windows ffmpeg (GPL build) into
# windows/vendor/ffmpeg.exe, verifying the published SHA-256 first.
$Url    = "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-09-30-13-08/ffmpeg-n9.0.2-17-g2a571b6068-win64-gpl-9.0.zip"
$Sha256 = "a0e45723c72141975f51d8666302e614711745f3102b704ca3f82c897a58d278"
$Zip    = Join-Path $env:TEMP "vidgrab-ffmpeg.zip"
$Dir    = Split-Path -Parent $MyInvocation.MyCommand.Path
if (Test-Path (Join-Path $Dir "ffmpeg.exe")) { Write-Host "vendor/ffmpeg.exe already present"; exit 0 }
Write-Host "Downloading $Url"
Invoke-WebRequest -Uri $Url -OutFile $Zip
$Actual = (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLower()
if ($Actual -ne $Sha256) { throw "SHA-256 mismatch: got $Actual, expected $Sha256" }
Expand-Archive $Zip -DestinationPath (Join-Path $env:TEMP "vidgrab-ffmpeg-x") -Force
Copy-Item (Get-ChildItem (Join-Path $env:TEMP "vidgrab-ffmpeg-x") -Recurse -Filter ffmpeg.exe).FullName (Join-Path $Dir "ffmpeg.exe")
Copy-Item (Get-ChildItem (Join-Path $env:TEMP "vidgrab-ffmpeg-x") -Recurse -Filter ffprobe.exe).FullName (Join-Path $Dir "ffprobe.exe")
Remove-Item $Zip -Force; Remove-Item (Join-Path $env:TEMP "vidgrab-ffmpeg-x") -Recurse -Force
Write-Host "OK: vendor/ffmpeg.exe + ffprobe.exe (ffmpeg n9.0.2-17-g2a571b6068, GPL build)"
