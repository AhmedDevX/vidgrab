"""VidGrab desktop theme system: Light, Dark and Glass.

Palettes are applied manually (every widget reconfigures itself on a theme
change) so the app is not tied to customtkinter's two built-in modes.

Glass: on Windows 11 (build 22000+) we ask DWM for the real Mica backdrop
via DwmSetWindowAttribute; everywhere else Glass falls back to a layered,
bordered dark palette that still looks deliberate without any platform
blur. The Mica path can only be verified on a real Windows 11 machine;
in this build environment only the palette/layout fallback is exercised.
"""

from __future__ import annotations

import sys

PALETTES = {
    "light": {
        "bg": "#F2F2F7",
        "card": "#FFFFFF",
        "card_alt": "#E9E9EF",
        "text": "#1C1C1E",
        "subtext": "#6E6E73",
        "accent": "#5E5CE6",
        "accent_hover": "#4B49C6",
        "on_accent": "#FFFFFF",
        "border": "#D8D8DE",
        "success": "#248A3D",
        "danger": "#D70015",
        "entry": "#FFFFFF",
    },
    "dark": {
        "bg": "#0D0D12",
        "card": "#1C1C22",
        "card_alt": "#2A2A33",
        "text": "#F5F5F7",
        "subtext": "#9C9CA5",
        "accent": "#7D7AFF",
        "accent_hover": "#9B99FF",
        "on_accent": "#FFFFFF",
        "border": "#35353F",
        "success": "#30D158",
        "danger": "#FF453A",
        "entry": "#14141A",
    },
    # Deliberate layered look for when no real platform blur exists.
    "glass": {
        "bg": "#171724",
        "card": "#23233A",
        "card_alt": "#2E2E4A",
        "text": "#F5F5FA",
        "subtext": "#A8A8C0",
        "accent": "#8E8CFF",
        "accent_hover": "#AAA8FF",
        "on_accent": "#101018",
        "border": "#4A4A6A",
        "success": "#42E8B4",
        "danger": "#FF6E7A",
        "entry": "#1B1B2C",
    },
}

THEME_ORDER = ["light", "dark", "glass"]

_state = {"theme": "dark", "mica": None}


def current_palette() -> dict:
    return PALETTES[_state["theme"]]


def theme_name() -> str:
    return _state["theme"]


def set_theme(name: str) -> None:
    if name not in PALETTES:
        name = "dark"
    _state["theme"] = name


def ctk_mode() -> str:
    """Which customtkinter base mode is closest (affects scrollbars etc.)."""
    return "light" if _state["theme"] == "light" else "dark"


def try_enable_mica(window) -> bool:
    """Ask Windows 11's DWM for the Mica backdrop behind this window.

    Returns True only when the OS accepted the attribute. Safe no-op on
    anything else (Linux, macOS, Windows 10). Verified only on real
    Windows 11; other platforms take the palette fallback.
    """
    if _state["mica"] is not None:
        return bool(_state["mica"])
    ok = False
    if sys.platform.startswith("win"):
        try:
            import ctypes

            hwnd = window.wm_frame()
            hwnd = int(hwnd, 16) if isinstance(hwnd, str) else int(hwnd)
            DWMWA_SYSTEMBACKDROP_TYPE = 38
            DWMSBT_MAINWINDOW = 2  # Mica
            value = ctypes.c_int(DWMSBT_MAINWINDOW)
            hr = ctypes.windll.dwmapi.DwmSetWindowAttribute(
                ctypes.c_void_p(hwnd),
                ctypes.c_uint(DWMWA_SYSTEMBACKDROP_TYPE),
                ctypes.byref(value),
                ctypes.sizeof(value),
            )
            ok = hr == 0
        except Exception:
            ok = False
    _state["mica"] = ok
    return ok


def mica_active() -> bool:
    return bool(_state["mica"])
