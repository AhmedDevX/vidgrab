"""VidGrab desktop app (Qt / PySide6 UI).

Modern card-based UI over:
- vidgrab_core      (downloader, no GUI imports)
- vidgrab_share     (browser fallback sharing, off unless explicitly started)
- vidgrab_transfer  (native device-to-device transfer over verified TLS)
- vidgrab_i18n      (English / Arabic strings; Qt provides real Arabic
                     shaping and bidi, including right-to-left layout)

Run:  python vidgrab_qt.py
"""

from __future__ import annotations

import os
import subprocess
import sys
import threading
import time

from PySide6.QtCore import QObject, Qt, Signal, QSize, QTimer
from PySide6.QtGui import QColor, QFont, QImage, QPixmap
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QDialog, QFileDialog, QFrame, QHBoxLayout,
    QLabel, QLineEdit, QListWidget, QListWidgetItem, QMainWindow,
    QProgressBar, QPushButton, QRadioButton, QScrollArea, QVBoxLayout,
    QWidget, QButtonGroup, QStackedWidget,
)

import vidgrab_core as core
import vidgrab_share as share
import vidgrab_transfer as transfer
from vidgrab_i18n import I18N

DOWNLOAD_DIR = os.path.join(os.path.expanduser("~"), "Downloads", "VidGrab")
STORE_DIR = os.path.join(os.path.expanduser("~"), ".vidgrab")
SETTINGS_FILE = os.path.join(STORE_DIR, "settings.json")

ACCENT = "#6C5CE7"

PALETTES = {
    "light": {
        "bg": "#F2F2F7", "card": "#FFFFFF", "card_alt": "#EBEBF2",
        "text": "#1C1C1E", "subtext": "#6E6E73", "accent": "#5E5CE6",
        "accent_hover": "#4B49C6", "on_accent": "#FFFFFF",
        "border": "#D9D9E3", "success": "#248A3D", "danger": "#D70015",
        "entry": "#FFFFFF", "glass_grad": None,
    },
    "dark": {
        "bg": "#0D0D12", "card": "#1B1B23", "card_alt": "#272732",
        "text": "#F5F5F7", "subtext": "#9C9CA5", "accent": "#7D7AFF",
        "accent_hover": "#9B99FF", "on_accent": "#14142B",
        "border": "#34343F", "success": "#30D158", "danger": "#FF453A",
        "entry": "#13131A", "glass_grad": None,
    },
    "glass": {
        "bg": "#16162A", "card": "#25254A", "card_alt": "#30305C",
        "text": "#F5F5FA", "subtext": "#ABABC9", "accent": "#8E8CFF",
        "accent_hover": "#AAA8FF", "on_accent": "#14142A",
        "border": "#4E4E86", "success": "#42E8B4", "danger": "#FF6E7A",
        "entry": "#1C1C38",
        # Gradient backdrop makes Glass deliberately distinct from Dark.
        "glass_grad": ("#131324", "#232349"),
    },
}


def human_size(n: float) -> str:
    size = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"


def load_settings() -> dict:
    try:
        import json
        with open(SETTINGS_FILE, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return {}


def save_settings(theme: str, language: str) -> None:
    try:
        import json
        os.makedirs(STORE_DIR, exist_ok=True)
        with open(SETTINGS_FILE, "w", encoding="utf-8") as fh:
            json.dump({"theme": theme, "language": language}, fh)
    except Exception:
        pass


def build_qss(p: dict) -> str:
    grad = p["glass_grad"]
    bg_rule = (
        f"background: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 {grad[0]}, stop:1 {grad[1]});"
        if grad else f"background: {p['bg']};"
    )
    return f"""
QMainWindow, QDialog {{ {bg_rule} }}
QWidget {{ color: {p['text']}; font-size: 14px; }}
QFrame#card {{
    background: {p['card']}; border: 1px solid {p['border']};
    border-radius: 16px;
}}
QFrame#rail {{
    background: {p['card']}; border: 1px solid {p['border']};
    border-radius: 18px;
}}
QLabel#wordmark {{ font-size: 24px; font-weight: 800; color: {p['text']}; }}
QLabel#wordmarkAccent {{ color: {p['accent']}; font-size: 24px; font-weight: 800; }}
QLabel#subtitle {{ color: {p['subtext']}; font-size: 12.5px; }}
QLabel#h1 {{ font-size: 17px; font-weight: 700; }}
QLabel#muted {{ color: {p['subtext']}; }}
QLabel#mono {{ font-family: 'Cascadia Mono', 'Noto Sans Mono', monospace; font-size: 13px; }}
QPushButton {{
    background: {p['accent']}; color: {p['on_accent']};
    border: none; border-radius: 10px; padding: 9px 18px; font-weight: 600;
}}
QPushButton:hover {{ background: {p['accent_hover']}; }}
QPushButton#ghost {{
    background: {p['card_alt']}; color: {p['text']};
    border: 1px solid {p['border']};
}}
QPushButton#ghost:hover {{ border-color: {p['accent']}; }}
QPushButton#ghost:checked {{
    background: {p['accent']}; color: {p['on_accent']}; border-color: {p['accent']};
}}
QPushButton#nav {{
    background: transparent; color: {p['subtext']}; text-align: center;
    border-radius: 12px; padding: 10px 6px; font-weight: 600;
}}
QPushButton#navActive {{
    background: {p['accent']}; color: {p['on_accent']};
    border-radius: 12px; padding: 10px 6px; font-weight: 700;
}}
QLineEdit {{
    background: {p['entry']}; color: {p['text']};
    border: 1px solid {p['border']}; border-radius: 10px; padding: 9px 12px;
}}
QLineEdit:focus {{ border-color: {p['accent']}; }}
QProgressBar {{
    background: {p['card_alt']}; border: none; border-radius: 5px; height: 10px;
}}
QProgressBar::chunk {{ background: {p['accent']}; border-radius: 5px; }}
QListWidget {{
    background: {p['entry']}; border: 1px solid {p['border']};
    border-radius: 12px; padding: 4px;
}}
QListWidget::item {{ border-radius: 8px; padding: 8px; }}
QListWidget::item:selected {{ background: {p['accent']}; color: {p['on_accent']}; }}
QCheckBox, QRadioButton {{ spacing: 8px; }}
QRadioButton::indicator {{
    width: 16px; height: 16px; border-radius: 9px;
    border: 2px solid {p['subtext']}; background: transparent;
}}
QRadioButton::indicator:checked {{
    border: 2px solid {p['accent']}; background: {p['accent']};
}}
QCheckBox::indicator {{
    width: 16px; height: 16px; border-radius: 4px;
    border: 2px solid {p['subtext']}; background: transparent;
}}
QCheckBox::indicator:checked {{
    border: 2px solid {p['accent']}; background: {p['accent']};
}}
QScrollArea {{ border: none; background: transparent; }}
QWidget#pageRoot {{ background: transparent; }}
QWidget#qt_scrollarea_viewport {{ background: transparent; }}
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 2px; }}
QScrollBar::handle:vertical {{
    background: {p['card_alt']}; border-radius: 5px; min-height: 30px;
}}
QScrollBar::handle:vertical:hover {{ background: {p['accent']}; }}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}
QScrollBar:horizontal {{ background: transparent; height: 10px; margin: 2px; }}
QScrollBar::handle:horizontal {{
    background: {p['card_alt']}; border-radius: 5px; min-width: 30px;
}}
QScrollBar::handle:horizontal:hover {{ background: {p['accent']}; }}
QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal {{ width: 0; }}
"""


def _fmt_transfer_progress(name: str, received: int, total: int,
                           started: float) -> str:
    """One progress line: percent, current speed and ETA, localized."""
    pct = int(received * 100 / max(total, 1))
    elapsed = max(time.monotonic() - started, 1e-3)
    bps = received / elapsed
    if bps >= 1_000_000:
        speed = I18N.t("speed_mbps", v=bps / 1_000_000)
    else:
        speed = I18N.t("speed_kbps", v=bps / 1_000)
    eta_s = int(max((total - received) / max(bps, 1), 0))
    eta = f"{eta_s // 60}:{eta_s % 60:02d}"
    return I18N.t("transfer_progress", name=name, pct=pct, speed=speed, eta=eta)


class Bridge(QObject):
    """Thread-safe channel from worker/HTTP threads into the Qt loop."""
    info = Signal(str)
    progress = Signal(float, str)
    downloadDone = Signal(str)
    downloadError = Signal(str)
    sendStatus = Signal(str)
    sendProgress = Signal(int)
    receiveStatus = Signal(str)
    offer = Signal(str, str, int, object)        # sender, filename, size, respond
    compareFp = Signal(str, str, object)         # device name, fingerprint, result-fn
    devices = Signal()


class TransferUICallbacks(transfer.TransferUI):
    def __init__(self, bridge: Bridge):
        self.bridge = bridge
        self._last_file = None
        self._started = time.monotonic()

    def decide_offer(self, sender_name, filename, size, respond):
        self.bridge.offer.emit(sender_name, filename, size, respond)

    def progress(self, filename, received, total):
        if filename != self._last_file:
            self._last_file = filename
            self._started = time.monotonic()
        self.bridge.receiveStatus.emit(
            _fmt_transfer_progress(filename, received, total, self._started))

    def done(self, filename, saved_path):
        self.bridge.receiveStatus.emit(I18N.t("received_ok", name=filename))

    def failed(self, filename, reason):
        self.bridge.receiveStatus.emit(I18N.t("receive_failed", reason=reason))


class Card(QFrame):
    def __init__(self):
        super().__init__(objectName="card")
        self.lay = QVBoxLayout(self)
        self.lay.setContentsMargins(18, 16, 18, 16)
        self.lay.setSpacing(10)


class FingerprintDialog(QDialog):
    """First-trust dialog: compare the FULL fingerprint with the receiver's
    screen (or import from its QR). Nothing is sent before this passes."""

    def __init__(self, parent, device_name: str, fingerprint: str):
        super().__init__(parent)
        self.result = False
        p = PALETTES[parent.theme]
        self.setStyleSheet(parent.styleSheet())
        self.setWindowTitle(I18N.t("pairing_title"))
        lay = QVBoxLayout(self)
        lay.setContentsMargins(24, 22, 24, 22)
        lay.setSpacing(12)
        lbl = QLabel(I18N.t("pairing_instructions", device=device_name))
        lbl.setWordWrap(True)
        lbl.setAlignment(Qt.AlignCenter)
        lay.addWidget(lbl)
        fp = fingerprint.upper()
        mono = QLabel("\n".join("  ".join(fp[r * 32:(r + 1) * 32][i:i + 8]
                                        for i in range(0, 32, 8)) for r in range(2)))
        mono.setObjectName("mono")
        mono.setAlignment(Qt.AlignCenter)
        mono.setStyleSheet(f"color: {p['accent']};")
        mono.setTextInteractionFlags(Qt.TextSelectableByMouse)
        lay.addWidget(mono)
        row = QHBoxLayout()
        yes = QPushButton(I18N.t("code_matches"))
        yes.setStyleSheet(f"background: {p['success']}; color: white;")
        no = QPushButton(I18N.t("code_mismatch"))
        no.setStyleSheet(f"background: {p['danger']}; color: white;")
        yes.clicked.connect(lambda: self._done(True))
        no.clicked.connect(lambda: self._done(False))
        row.addWidget(yes)
        row.addWidget(no)
        lay.addLayout(row)

    def _done(self, ok: bool):
        self.result = ok
        self.accept()


class OfferDialog(QDialog):
    def __init__(self, parent, sender_name: str, filename: str, size: int):
        super().__init__(parent)
        self.result = False
        p = PALETTES[parent.theme]
        self.setStyleSheet(parent.styleSheet())
        self.setWindowTitle(I18N.t("incoming_title"))
        lay = QVBoxLayout(self)
        lay.setContentsMargins(24, 22, 24, 22)
        lay.setSpacing(12)
        lbl = QLabel(I18N.t("incoming_offer", sender=sender_name))
        lbl.setWordWrap(True)
        lbl.setAlignment(Qt.AlignCenter)
        lay.addWidget(lbl)
        # Filename is arbitrary sender-controlled text: isolate it on its
        # own LTR line so it cannot spoof surrounding RTL copy.
        file_lbl = QLabel(f"{filename}  ({human_size(size)})", objectName="mono")
        file_lbl.setLayoutDirection(Qt.LeftToRight)
        file_lbl.setAlignment(Qt.AlignCenter)
        file_lbl.setWordWrap(True)
        lay.addWidget(file_lbl)
        row = QHBoxLayout()
        yes = QPushButton(I18N.t("accept"))
        yes.setStyleSheet(f"background: {p['success']}; color: white;")
        no = QPushButton(I18N.t("decline"))
        no.setStyleSheet(f"background: {p['danger']}; color: white;")
        yes.clicked.connect(lambda: self._done(True))
        no.clicked.connect(lambda: self._done(False))
        row.addWidget(yes)
        row.addWidget(no)
        lay.addLayout(row)

    def _done(self, ok: bool):
        self.result = ok
        self.accept()


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        saved = load_settings()
        self.theme = saved.get("theme", "dark")
        I18N.set_language(saved.get("language") or I18N.detect_system_language())

        self.identity = transfer.Identity.load_or_create(STORE_DIR)
        self.pins = transfer.PinStore(os.path.join(STORE_DIR, "pins.json"))
        self.bridge = Bridge()
        self.session = None
        self.receiver = None
        self.discovery = None
        self._picked_files: list[str] = []
        self._devices: list = []

        self.setWindowTitle("VidGrab")
        self.resize(860, 680)
        self._build()
        self._apply_theme()
        self._apply_language(first=True)
        self._wire_bridge()

    # ------------------------------------------------------------- build

    def _build(self):
        central = QWidget()
        self.setCentralWidget(central)
        root = QHBoxLayout(central)
        root.setContentsMargins(16, 16, 16, 16)
        root.setSpacing(14)

        # Left navigation rail with branding.
        rail = QFrame(objectName="rail")
        rail.setFixedWidth(190)
        rl = QVBoxLayout(rail)
        rl.setContentsMargins(14, 18, 14, 18)
        rl.setSpacing(8)
        wm = QHBoxLayout()
        self.wordmark = QLabel("VidGrab", objectName="wordmark")
        dot = QLabel(".", objectName="wordmarkAccent")
        wm.addWidget(self.wordmark)
        wm.addWidget(dot)
        wm.addStretch(1)
        rl.addLayout(wm)
        self.tagline = QLabel(objectName="subtitle")
        rl.addWidget(self.tagline)
        rl.addSpacing(14)
        self.nav_buttons = []
        self.nav_group = QButtonGroup(self)
        for i in range(4):
            b = QPushButton(objectName="nav")
            b.setCheckable(True)
            b.clicked.connect(lambda _=False, idx=i: self._nav(idx))
            self.nav_group.addButton(b, i)
            self.nav_buttons.append(b)
            rl.addWidget(b)
        rl.addStretch(1)
        root.addWidget(rail)

        # Pages.
        self.pages = QStackedWidget()
        root.addWidget(self.pages, 1)
        self.pages.addWidget(self._page_download())
        self.pages.addWidget(self._page_send())
        self.pages.addWidget(self._page_receive())
        self.pages.addWidget(self._page_settings())
        self._nav(0)

    def _page_download(self) -> QWidget:
        page = QScrollArea()
        page.setWidgetResizable(True)
        inner = QWidget(objectName="pageRoot")
        page.setWidget(inner)
        lay = QVBoxLayout(inner)
        lay.setContentsMargins(4, 4, 4, 4)
        lay.setSpacing(14)

        card = Card()
        self.url_entry = QLineEdit()
        self.url_entry.setLayoutDirection(Qt.LeftToRight)  # URLs stay LTR
        row = QHBoxLayout()
        row.addWidget(self.url_entry, 1)
        self.fetch_btn = QPushButton(objectName="ghost")
        self.fetch_btn.clicked.connect(self.on_fetch)
        row.addWidget(self.fetch_btn)
        card.lay.addLayout(row)
        self.clip_check = QCheckBox()
        card.lay.addWidget(self.clip_check)
        self.info_label = QLabel(objectName="muted")
        self.info_label.setWordWrap(True)
        card.lay.addWidget(self.info_label)
        lay.addWidget(card)

        qcard = Card()
        self.quality_group = QButtonGroup(self)
        self.quality_buttons = []
        for i, q in enumerate(core.QUALITY_OPTIONS):
            rb = QRadioButton(q["label"])
            self.quality_group.addButton(rb, i)
            qcard.lay.addWidget(rb)
            self.quality_buttons.append(rb)
        self.quality_buttons[0].setChecked(True)
        lay.addWidget(qcard)

        self.download_btn = QPushButton()
        self.download_btn.clicked.connect(self.on_download)
        lay.addWidget(self.download_btn, alignment=Qt.AlignHCenter)
        self.progress = QProgressBar()
        self.progress.setValue(0)
        self.progress.setTextVisible(False)
        lay.addWidget(self.progress)
        self.status_label = QLabel(objectName="muted")
        self.status_label.setWordWrap(True)
        lay.addWidget(self.status_label)
        self.open_btn = QPushButton(objectName="ghost")
        self.open_btn.clicked.connect(self._open_download_dir)
        lay.addWidget(self.open_btn, alignment=Qt.AlignHCenter)
        lay.addStretch(1)
        return page

    def _page_send(self) -> QWidget:
        page = QScrollArea()
        page.setWidgetResizable(True)
        inner = QWidget(objectName="pageRoot")
        page.setWidget(inner)
        lay = QVBoxLayout(inner)
        lay.setContentsMargins(4, 4, 4, 4)
        lay.setSpacing(14)

        card = Card()
        self.send_title = QLabel(objectName="h1")
        card.lay.addWidget(self.send_title)
        self.send_hint = QLabel(objectName="muted")
        self.send_hint.setWordWrap(True)
        card.lay.addWidget(self.send_hint)
        self.pick_btn = QPushButton(objectName="ghost")
        self.pick_btn.clicked.connect(self.on_pick_files)
        card.lay.addWidget(self.pick_btn)
        self.files_label = QLabel(objectName="muted")
        self.files_label.setWordWrap(True)
        card.lay.addWidget(self.files_label)
        devrow = QHBoxLayout()
        self.devices_hint = QLabel(objectName="muted")
        devrow.addWidget(self.devices_hint, 1)
        self.refresh_btn = QPushButton(objectName="ghost")
        self.refresh_btn.clicked.connect(self.on_refresh_devices)
        devrow.addWidget(self.refresh_btn)
        card.lay.addLayout(devrow)
        self.device_list = QListWidget()
        self.device_list.setMaximumHeight(150)
        card.lay.addWidget(self.device_list)
        self.pair_btn = QPushButton()
        self.pair_btn.clicked.connect(self.on_pair_and_send)
        card.lay.addWidget(self.pair_btn, alignment=Qt.AlignHCenter)
        self.send_progress = QProgressBar()
        self.send_progress.setValue(0)
        self.send_progress.setTextVisible(False)
        card.lay.addWidget(self.send_progress)
        self.send_status = QLabel(objectName="muted")
        self.send_status.setWordWrap(True)
        card.lay.addWidget(self.send_status)
        lay.addWidget(card)

        fb = Card()
        self.fb_title = QLabel(objectName="h1")
        fb.lay.addWidget(self.fb_title)
        self.share_btn = QPushButton(objectName="ghost")
        self.share_btn.clicked.connect(self.on_toggle_share)
        fb.lay.addWidget(self.share_btn, alignment=Qt.AlignHCenter)
        self.share_qr = QLabel()
        self.share_qr.setAlignment(Qt.AlignCenter)
        fb.lay.addWidget(self.share_qr)
        self.share_link = QLabel(objectName="muted")
        self.share_link.setWordWrap(True)
        self.share_link.setTextInteractionFlags(Qt.TextSelectableByMouse)
        fb.lay.addWidget(self.share_link)
        self.fb_warning = QLabel(objectName="muted")
        self.fb_warning.setWordWrap(True)
        fb.lay.addWidget(self.fb_warning)
        lay.addWidget(fb)
        lay.addStretch(1)
        return page

    def _page_receive(self) -> QWidget:
        page = QScrollArea()
        page.setWidgetResizable(True)
        inner = QWidget(objectName="pageRoot")
        page.setWidget(inner)
        lay = QVBoxLayout(inner)
        lay.setContentsMargins(4, 4, 4, 4)
        lay.setSpacing(14)

        card = Card()
        self.recv_title = QLabel(objectName="h1")
        card.lay.addWidget(self.recv_title)
        self.recv_btn = QPushButton()
        self.recv_btn.clicked.connect(self.on_toggle_receive)
        card.lay.addWidget(self.recv_btn, alignment=Qt.AlignHCenter)
        self.recv_status = QLabel(objectName="muted")
        self.recv_status.setWordWrap(True)
        card.lay.addWidget(self.recv_status)
        lay.addWidget(card)

        idcard = Card()
        self.id_title = QLabel(objectName="h1")
        idcard.lay.addWidget(self.id_title)
        self.id_hint = QLabel(objectName="muted")
        self.id_hint.setWordWrap(True)
        idcard.lay.addWidget(self.id_hint)
        fp = self.identity.fingerprint.upper()
        self.fp_label = QLabel("\n".join(
            "  ".join(fp[r * 32:(r + 1) * 32][i:i + 8] for i in range(0, 32, 8))
            for r in range(2)), objectName="mono")
        self.fp_label.setAlignment(Qt.AlignCenter)
        self.fp_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        idcard.lay.addWidget(self.fp_label)
        self.recv_qr = QLabel()
        self.recv_qr.setAlignment(Qt.AlignCenter)
        idcard.lay.addWidget(self.recv_qr)
        lay.addWidget(idcard)
        lay.addStretch(1)
        return page

    def _page_settings(self) -> QWidget:
        page = QWidget(objectName="pageRoot")
        lay = QVBoxLayout(page)
        lay.setContentsMargins(4, 4, 4, 4)
        lay.setSpacing(14)
        card = Card()
        self.theme_title = QLabel(objectName="h1")
        card.lay.addWidget(self.theme_title)
        self.theme_buttons = []
        trow = QHBoxLayout()
        for name in ("light", "dark", "glass"):
            b = QPushButton(objectName="ghost")
            b.setCheckable(True)
            b.clicked.connect(lambda _=False, n=name: self._set_theme(n))
            self.theme_buttons.append(b)
            trow.addWidget(b)
        card.lay.addLayout(trow)
        self.lang_title = QLabel(objectName="h1")
        card.lay.addWidget(self.lang_title)
        lrow = QHBoxLayout()
        self.lang_en = QPushButton("English", objectName="ghost")
        self.lang_en.setCheckable(True)
        self.lang_en.clicked.connect(lambda: self._set_language("en"))
        self.lang_ar = QPushButton("العربية", objectName="ghost")
        self.lang_ar.setCheckable(True)
        self.lang_ar.clicked.connect(lambda: self._set_language("ar"))
        lrow.addWidget(self.lang_en)
        lrow.addWidget(self.lang_ar)
        card.lay.addLayout(lrow)
        self.dl_path = QLabel(objectName="muted")
        self.dl_path.setWordWrap(True)
        self.dl_path.setTextInteractionFlags(Qt.TextSelectableByMouse)
        card.lay.addWidget(self.dl_path)
        lay.addWidget(card)
        lay.addStretch(1)
        return page

    # ------------------------------------------------------ theme / language

    def _apply_theme(self):
        self.setStyleSheet(build_qss(PALETTES[self.theme]))

    def _set_theme(self, name: str):
        self.theme = name
        save_settings(self.theme, I18N.language)
        self._apply_theme()
        self._retext()

    def _set_language(self, lang: str):
        I18N.set_language(lang)
        save_settings(self.theme, I18N.language)
        self._apply_language()

    def _apply_language(self, first: bool = False):
        QApplication.instance().setLayoutDirection(
            Qt.RightToLeft if I18N.rtl else Qt.LeftToRight)
        self.url_entry.setLayoutDirection(Qt.LeftToRight)
        self._retext()

    def _nav(self, idx: int):
        self.pages.setCurrentIndex(idx)
        for i, btn in enumerate(self.nav_buttons):
            btn.setChecked(i == idx)
            btn.setObjectName("navActive" if i == idx else "nav")
            btn.style().unpolish(btn)
            btn.style().polish(btn)

    def _retext(self):
        p = PALETTES[self.theme]
        self.tagline.setText(
            "حمّل. شارك. تم." if I18N.rtl else "Download. Share. Done.")
        for btn, key in zip(self.nav_buttons,
                            ("tab_download", "tab_send", "tab_receive", "tab_settings")):
            btn.setText(I18N.t(key))
            btn.setObjectName("navActive" if btn.isChecked() else "nav")
        self.setMinimumSize(760, 640)
        self.url_entry.setPlaceholderText(I18N.t("url_placeholder"))
        self.fetch_btn.setText(I18N.t("fetch_info"))
        self.clip_check.setText(I18N.t("clipboard_watch"))
        for rb, q in zip(self.quality_buttons, core.QUALITY_OPTIONS):
            label = q["label"]
            if label == "Best quality":
                label = I18N.t("quality_best")
            elif label.startswith("Audio only"):
                label = I18N.t("quality_audio")
            rb.setText(label)
        self.download_btn.setText(I18N.t("download"))
        self.open_btn.setText(I18N.t("open_folder"))
        self.send_title.setText(I18N.t("send_to_device"))
        self.send_hint.setText(I18N.t("send_to_device_hint"))
        self.pick_btn.setText(I18N.t("choose_files"))
        if not self._picked_files:
            self.files_label.setText(I18N.t("no_files"))
        self.refresh_btn.setText(I18N.t("refresh"))
        if self.device_list.count() == 0:
            self.devices_hint.setText(I18N.t("no_devices"))
        self.pair_btn.setText(I18N.t("pair_and_send"))
        self.fb_title.setText(I18N.t("fallback_title"))
        self.share_btn.setText(I18N.t(
            "fallback_stop" if self.session else "fallback_start"))
        self.fb_warning.setText(I18N.t("fallback_warning"))
        self.recv_title.setText(I18N.t("receive_title"))
        self.recv_btn.setText(I18N.t(
            "receive_stop" if self.receiver else "receive_start"))
        self.id_title.setText(I18N.t("pairing_title"))
        self.id_hint.setText(I18N.t("identity_hint"))
        self.theme_title.setText(I18N.t("theme"))
        for b, name in zip(self.theme_buttons, ("light", "dark", "glass")):
            b.setText(I18N.t(f"theme_{name}"))
            b.setChecked(name == self.theme)
        self.lang_title.setText(I18N.t("language"))
        self.lang_en.setChecked(not I18N.rtl)
        self.lang_ar.setChecked(I18N.rtl)
        self.dl_path.setText(I18N.t("downloads_to", path=DOWNLOAD_DIR))
        if not self.receiver:
            self.recv_status.setText(I18N.t("receive_idle"))
        # Force style re-evaluation for nav objectName changes.
        for btn in self.nav_buttons:
            btn.style().unpolish(btn)
            btn.style().polish(btn)

    # ------------------------------------------------------------- workers

    def _wire_bridge(self):
        self.bridge.info.connect(self.info_label.setText)
        self.bridge.progress.connect(self._on_progress)
        self.bridge.downloadDone.connect(
            lambda path: self.status_label.setText(I18N.t("saved", path=path)))
        self.bridge.downloadError.connect(
            lambda err: self.status_label.setText(I18N.t("failed", error=err)))
        self.bridge.sendStatus.connect(self.send_status.setText)
        self.bridge.sendProgress.connect(self.send_progress.setValue)
        self.bridge.receiveStatus.connect(self.recv_status.setText)
        self.bridge.offer.connect(self._on_offer)
        self.bridge.compareFp.connect(self._on_compare_fp)

    def on_fetch(self):
        url = core.extract_url(self.url_entry.text()) or self.url_entry.text().strip()
        if not url:
            return
        self.info_label.setText(I18N.t("fetching"))
        threading.Thread(target=self._fetch_worker, args=(url,), daemon=True).start()

    def _fetch_worker(self, url):
        self._direct_url = None
        try:
            info = core.probe(url)
            mins = f", {info.duration_seconds // 60} min" if info.duration_seconds else ""
            self.bridge.info.emit(f"{info.title}  ({info.extractor}{mins})")
        except Exception as exc:
            direct = core.probe_direct(url)
            if direct is not None:
                self._direct_url = url
                size = (f"{direct['size'] / 1e6:.1f} MB"
                        if direct["size"] > 0 else I18N.t("size_unknown"))
                self.bridge.info.emit(I18N.t(
                    "direct_file", name=direct["filename"], size=size))
            else:
                self.bridge.info.emit(I18N.t("fetch_failed", error=str(exc)[:200]))

    def on_download(self):
        url = core.extract_url(self.url_entry.text()) or self.url_entry.text().strip()
        if not url:
            return
        self.progress.setValue(0)
        self.status_label.setText(I18N.t("starting"))
        idx = self.quality_group.checkedId()
        threading.Thread(target=self._download_worker,
                         args=(url, max(idx, 0)), daemon=True).start()

    def _download_worker(self, url, quality_index):
        def cb(p: core.Progress):
            speed = f" - {p.speed_bytes / 1e6:.1f} MB/s" if p.speed_bytes else ""
            self.bridge.progress.emit(p.percent, f"{p.status} {p.percent:.0f}%{speed}")
        try:
            if getattr(self, "_direct_url", None) == url:
                path = core.download_direct(url, DOWNLOAD_DIR, progress_cb=cb)
            else:
                path = core.download(url, quality_index, DOWNLOAD_DIR, progress_cb=cb)
            self.bridge.downloadDone.emit(path)
        except Exception as exc:
            self.bridge.downloadError.emit(str(exc)[:300])

    def _on_progress(self, percent: float, text: str):
        self.progress.setValue(int(percent))
        self.status_label.setText(text)

    # ------------------------------------------------------------ transfer

    def _ensure_discovery(self) -> bool:
        if self.discovery is None:
            try:
                self.discovery = transfer.Discovery()
                self.discovery.browse()
            except Exception as exc:
                self.devices_hint.setText(str(exc)[:150])
                return False
        return True

    def on_refresh_devices(self):
        if not self._ensure_discovery():
            return
        self.device_list.clear()
        self._devices = [d for d in self.discovery.devices()
                         if d.device_id != self.identity.device_id]
        if not self._devices:
            self.devices_hint.setText(I18N.t("no_devices"))
            return
        self.devices_hint.setText("")
        for dev in self._devices:
            QListWidgetItem(f"{dev.name}   ({dev.host})", self.device_list)
        self.device_list.setCurrentRow(0)

    def on_pick_files(self):
        paths, _ = QFileDialog.getOpenFileNames(self, I18N.t("choose_files"))
        if paths:
            self._picked_files = list(paths)
            names = ", ".join(os.path.basename(p) for p in paths)
            self.files_label.setText(names[:400])

    def on_pair_and_send(self):
        if not self._picked_files:
            self.files_label.setText(I18N.t("pick_first"))
            return
        row = self.device_list.currentRow()
        if row < 0 or row >= len(self._devices):
            self.send_status.setText(I18N.t("pick_device"))
            return
        dev = self._devices[row]
        self.send_status.setText(I18N.t("starting"))
        self.send_progress.setValue(0)
        threading.Thread(target=self._pair_worker, args=(dev,), daemon=True).start()

    def _pair_worker(self, dev):
        try:
            client = transfer.SendClient(
                dev.host, dev.port,
                sender_name=f"VidGrab-{self.identity.device_id}",
                pin_store=self.pins, device_id=dev.device_id)
            presented = client.connect()  # handshake only; nothing else sent yet
            if not client.trusted:
                answer: list = []

                def ask():
                    ev = threading.Event()
                    holder = {}

                    def on_result(ok):
                        holder["ok"] = ok
                        ev.set()

                    self.bridge.compareFp.emit(dev.name, presented, on_result)
                    ev.wait(transfer.PAIR_TTL_SECONDS)
                    return holder.get("ok", False)

                if not ask():
                    self.bridge.sendStatus.emit(I18N.t("pair_failed"))
                    client.close()
                    return
                client.confirm_trust()
            for path in self._picked_files:
                base = os.path.basename(path)
                self.bridge.sendStatus.emit(I18N.t("sending") + " " + base)
                self.bridge.sendProgress.emit(0)
                t0 = time.monotonic()

                def num_cb(sent, total, _t0=t0, _base=base):
                    self.bridge.sendProgress.emit(
                        int(sent * 100 / max(total, 1)))
                    self.bridge.sendStatus.emit(
                        _fmt_transfer_progress(_base, sent, total, _t0))

                saved = client.send(path, num_cb=num_cb)
                self.bridge.sendStatus.emit(I18N.t("sent_ok", name=saved))
                self.bridge.sendProgress.emit(100)
            client.close()
        except transfer.PinChangedError:
            self.bridge.sendStatus.emit(I18N.t(
                "send_failed",
                error="device identity changed - re-verify on its screen"))
        except Exception as exc:
            self.bridge.sendStatus.emit(I18N.t("send_failed", error=str(exc)[:200]))

    def _on_compare_fp(self, device_name, fingerprint, on_result):
        dlg = FingerprintDialog(self, device_name, fingerprint)
        dlg.exec()
        on_result(dlg.result)

    def on_toggle_receive(self):
        if self.receiver is not None:
            self.receiver.stop()
            if self.discovery is not None:
                self.discovery.unadvertise()
            self.receiver = None
            self.recv_qr.clear()
            self._retext()
            return
        try:
            self.receiver = transfer.ReceiveServer(
                self.identity, DOWNLOAD_DIR,
                ui=TransferUICallbacks(self.bridge))
            self.receiver.start()
            name = f"VidGrab-{self.identity.device_id}"
            if self._ensure_discovery():
                self.discovery.advertise(
                    name, self.receiver.port, self.identity.device_id,
                    platform=sys.platform,
                    fingerprint=self.identity.fingerprint)
            self.recv_status.setText(I18N.t("receive_active", name=name))
            self._show_recv_qr(name)
        except Exception as exc:
            self.recv_status.setText(str(exc)[:200])
            self.receiver = None
        self._retext()

    def _show_recv_qr(self, name):
        """QR carries public connection identity only (host/port/fingerprint)."""
        try:
            import qrcode
            payload = (f"vidgrab://connect?h={self.receiver.host}"
                       f"&p={self.receiver.port}"
                       f"&fp={self.identity.fingerprint}&n={name}")
            self.recv_qr.setPixmap(_qr_pixmap(payload, 170))
        except Exception:
            pass

    def _on_offer(self, sender_name, filename, size, respond):
        dlg = OfferDialog(self, sender_name, filename, size)
        dlg.exec()
        respond(dlg.result)

    # --------------------------------------------------- browser fallback

    def on_toggle_share(self):
        if self.session is not None:
            self.session.stop()
            self.session = None
            self.share_link.setText(I18N.t("sharing_stopped"))
            self.share_qr.clear()
            self._retext()
            return
        if not self._picked_files:
            self.files_label.setText(I18N.t("pick_first"))
            return
        try:
            self.session = share.ShareSession(self._picked_files)
            url = self.session.start()
        except Exception as exc:
            self.share_link.setText(str(exc)[:200])
            self.session = None
            return
        self.share_link.setText(I18N.t("fallback_link", url=url))
        self.share_link.setLayoutDirection(Qt.LeftToRight)
        try:
            self.share_qr.setPixmap(_qr_pixmap(url, 150))
        except Exception:
            pass
        self._retext()

    # ---------------------------------------------------------------- misc

    def _open_download_dir(self):
        os.makedirs(DOWNLOAD_DIR, exist_ok=True)
        if sys.platform.startswith("win"):
            os.startfile(DOWNLOAD_DIR)  # type: ignore[attr-defined]
        else:
            subprocess.Popen(["xdg-open", DOWNLOAD_DIR])

    def closeEvent(self, event):
        try:
            if self.session is not None:
                self.session.stop()
            if self.receiver is not None:
                self.receiver.stop()
            if self.discovery is not None:
                self.discovery.close()
        finally:
            super().closeEvent(event)


def _qr_pixmap(payload: str, size: int) -> QPixmap:
    import qrcode
    qr = qrcode.QRCode(border=2, box_size=6)
    qr.add_data(payload)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").get_image().convert("RGB")
    data = img.tobytes("raw", "RGB")
    qimg = QImage(data, img.width, img.height, img.width * 3,
                  QImage.Format_RGB888)
    return QPixmap.fromImage(qimg).scaled(
        QSize(size, size), Qt.KeepAspectRatio, Qt.SmoothTransformation)


def pick_font() -> QFont:
    from PySide6.QtGui import QFontDatabase
    families = set(QFontDatabase.families())
    for candidate in ("Segoe UI", "Noto Sans", "Ubuntu", "DejaVu Sans"):
        if candidate in families:
            return QFont(candidate, 11)
    return QFont()


def _selftest_shot(win, spec: str) -> None:
    """Internal QA hook: VIDGRAB_SHOT="page,dialog,out.png" grabs the
    rendered window (or a dialog) after real navigation and quits. Used by
    CI/dev to verify frozen builds render identically to source runs.
    Never active unless the env var is set."""
    page_s, dialog, out = (spec.split(",") + ["0", "", ""])[:3]
    page = int(page_s or 0)
    win.resize(900, 760)
    win.nav_buttons[page].click()
    target = win
    fp = "9f2ac7d14b0e88f3a1c45d6e7b8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b"
    if dialog == "fp":
        target = FingerprintDialog(win, "Device-7F3A", fp)
        target.show()
    elif dialog == "offer":
        target = OfferDialog(win, "Device-7F3A", "Family trip video.mp4", 734003200)
        target.show()

    def snap():
        QApplication.instance().processEvents()
        target.grab().save(out)
        QApplication.instance().quit()

    QTimer.singleShot(1500, snap)


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("VidGrab")
    app.setFont(pick_font())
    win = MainWindow()
    win.show()
    shot = os.environ.get("VIDGRAB_SHOT")
    if shot:
        _selftest_shot(win, shot)
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
