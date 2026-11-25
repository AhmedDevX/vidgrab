@echo off
REM Build VidGrab.exe. Tries py, python, then python3.
cd /d "%~dp0"

set PY=
py --version >nul 2>&1 && set PY=py
if not defined PY (python --version >nul 2>&1 && set PY=python)
if not defined PY (python3 --version >nul 2>&1 && set PY=python3)

if not defined PY (
  echo Python was not found. Install Python 3.10+ and tick "Add python.exe to PATH".
  pause
  exit /b 1
)

%PY% -m pip install -r requirements.txt
%PY% -m pytest test_core.py test_share.py
if not exist vendor\ffmpeg.exe (
  echo Fetching pinned ffmpeg build...
  powershell -ExecutionPolicy Bypass -File vendor\fetch_ffmpeg.ps1
  if errorlevel 1 exit /b 1
)
%PY% -m PyInstaller vidgrab.spec
echo.
echo Done. VidGrab.exe is in the dist folder.
pause
