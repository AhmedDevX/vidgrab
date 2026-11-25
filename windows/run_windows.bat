@echo off
REM VidGrab launcher: installs dependencies, then starts the app.
REM Double-click this file. It tries py, python, then python3.
cd /d "%~dp0"

set PY=
py --version >nul 2>&1 && set PY=py
if not defined PY (python --version >nul 2>&1 && set PY=python)
if not defined PY (python3 --version >nul 2>&1 && set PY=python3)

if not defined PY (
  echo.
  echo Python was not found on this PC.
  echo.
  echo Fix: install Python 3.10 or newer from https://www.python.org/downloads/
  echo IMPORTANT: tick "Add python.exe to PATH" during installation.
  echo Or install "Python 3.12" from the Microsoft Store, then run this file again.
  echo.
  pause
  exit /b 1
)

echo Using interpreter: %PY%
%PY% -m pip install -r requirements.txt
if errorlevel 1 (
  echo.
  echo Dependency installation failed. Copy the error above and ask for help.
  pause
  exit /b 1
)

%PY% vidgrab_app.py
if errorlevel 1 pause
