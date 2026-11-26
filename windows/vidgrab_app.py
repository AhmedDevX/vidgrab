"""VidGrab desktop app: download videos and move files to nearby devices.

UI layer over:
- vidgrab_core      (downloader, no GUI imports)
- vidgrab_share     (browser fallback sharing, off unless explicitly started)
- vidgrab_transfer  (native device-to-device transfer over verified TLS)
- vidgrab_theme     (Light / Dark / Glass palettes)
- vidgrab_i18n      (English / Arabic strings)

Run:  python vidgrab_app.py
"""

from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import tkinter.filedialog as filedialog

import customtkinter as ctk

import vidgrab_core as core
import vidgrab_share as share
import vidgrab_theme as theme
import vidgrab_transfer as transfer
from vidgrab_i18n import I18N

DOWNLOAD_DIR = os.path.join(os.path.expanduser("~"), "Downloads", "VidGrab")
STORE_DIR = os.path.join(os.path.expanduser("~"), ".vidgrab")
SETTINGS_FILE = os.path.join(STORE_DIR, "settings.json")


def _load_settings() -> dict:
    try:
        import json
        with open(SETTINGS_FILE, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return {}


def _save_settings() -> None:
    try:
        import json
        os.makedirs(STORE_DIR, exist_ok=True)
        with open(SETTINGS_FILE, "w", encoding="utf-8") as fh:
            json.dump({"theme": theme.theme_name(), "language": I18N.language}, fh)
    except Exception:
        pass


def human_size(n: int) -> str:
    size = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"


class VidGrabApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        saved = _load_settings()
        I18N.set_language(saved.get("language") or I18N.detect_system_language())
        theme.set_theme(saved.get("theme") or "dark")
        ctk.set_appearance_mode(theme.ctk_mode())

        self.title(I18N.t("app_title"))
        self.geometry("680x640")
        self.minsize(560, 560)

        self.events: queue.Queue = queue.Queue()
        self.session = None              # browser fallback ShareSession, if any
        self.receiver = None             # transfer.ReceiveServer, if any
        self.discovery = None            # transfer.Discovery, if any
        self.identity = transfer.Identity.load_or_create(STORE_DIR)
        self.pins = transfer.PinStore(os.path.join(STORE_DIR, "pins.json"))
        self._picked_files = []
        self._style_registry = []        # (widget, role) pairs re-themed together

        self.tabs = ctk.CTkTabview(
            self,
            command=lambda: self._restyle(),
        )
        self.tabs.pack(fill="both", expand=True, padx=14, pady=14)
        self._tab_download = self.tabs.add(I18N.t("tab_download"))
        self._tab_send = self.tabs.add(I18N.t("tab_send"))
        self._tab_receive = self.tabs.add(I18N.t("tab_receive"))
        self._tab_settings = self.tabs.add(I18N.t("tab_settings"))

        self._build_download_tab(self._tab_download)
        self._build_send_tab(self._tab_send)
        self._build_receive_tab(self._tab_receive)
        self._build_settings_tab(self._tab_settings)
        self._restyle()

        self.after(100, self._pump_events)

    # ------------------------------------------------------------- styling

    def _reg(self, widget, role):
        self._style_registry.append((widget, role))
        return widget

    def _restyle(self):
        p = theme.current_palette()
        ctk.set_appearance_mode(theme.ctk_mode())
        self.configure(fg_color=p["bg"])
        for widget, role in self._style_registry:
            try:
                if role == "card":
                    widget.configure(fg_color=p["card"], border_color=p["border"])
                elif role == "text":
                    widget.configure(text_color=p["text"])
                elif role == "subtext":
                    widget.configure(text_color=p["subtext"])
                elif role == "accent":
                    widget.configure(fg_color=p["accent"], hover_color=p["accent_hover"],
                                     text_color=p["on_accent"])
                elif role == "ghost":
                    widget.configure(fg_color=p["card_alt"], hover_color=p["border"],
                                     text_color=p["text"])
                elif role == "entry":
                    widget.configure(fg_color=p["entry"], text_color=p["text"],
                                     border_color=p["border"])
                elif role == "progress":
                    widget.configure(progress_color=p["accent"], fg_color=p["card_alt"])
            except Exception:
                pass
        try:
            self.tabs.configure(
                fg_color=p["bg"],
                segmented_button_fg_color=p["card"],
                segmented_button_selected_color=p["accent"],
                segmented_button_selected_hover_color=p["accent_hover"],
                segmented_button_unselected_color=p["card"],
                segmented_button_unselected_hover_color=p["card_alt"],
                text_color=p["text"],
            )
        except Exception:
            pass
        # Glass: real Mica only exists on Windows 11; elsewhere the palette
        # alone carries the look (verified limit, see docs).
        if theme.theme_name() == "glass":
            theme.try_enable_mica(self)

    def _card(self, parent):
        return self._reg(
            ctk.CTkFrame(parent, corner_radius=14, border_width=1), "card"
        )

    def _label(self, parent, key=None, role="text", size=13, bold=False, wrap=560, **fmt):
        anchor = "e" if I18N.rtl else "w"
        justify = "right" if I18N.rtl else "left"
        text = I18N.t(key, **fmt) if key else ""
        lbl = ctk.CTkLabel(
            parent, text=text, wraplength=wrap, justify=justify,
            font=ctk.CTkFont(size=size, weight="bold" if bold else "normal"),
        )
        self._reg(lbl, role)
        lbl._vg_key, lbl._vg_fmt = key, fmt
        return lbl, anchor

    def _button(self, parent, key, command, role="accent"):
        btn = ctk.CTkButton(parent, text=I18N.t(key), command=command,
                            corner_radius=10, height=34)
        self._reg(btn, role)
        btn._vg_key = key
        return btn

    def _retext(self):
        """Re-apply every registered label/button text after a language flip."""
        for widget, role in self._style_registry:
            key = getattr(widget, "_vg_key", None)
            if key:
                try:
                    widget.configure(text=I18N.t(key, **getattr(widget, "_vg_fmt", {})))
                except Exception:
                    pass
        # Tab headers cannot be renamed in place; note for the user instead.

    # -------------------------------------------------------- download tab

    def _build_download_tab(self, tab):
        card = self._card(tab)
        card.pack(fill="x", padx=10, pady=10)

        row = ctk.CTkFrame(card, fg_color="transparent")
        row.pack(fill="x", padx=12, pady=12)
        self.url_entry = self._reg(
            ctk.CTkEntry(row, placeholder_text=I18N.t("url_placeholder"),
                         height=36, corner_radius=10), "entry")
        self.url_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self._button(row, "fetch_info", self.on_fetch, role="ghost").pack(side="left")

        self.clipboard_var = ctk.BooleanVar(value=False)
        self.clip_check = ctk.CTkCheckBox(
            card, text=I18N.t("clipboard_watch"), variable=self.clipboard_var
        )
        self._reg(self.clip_check, "text")
        self.clip_check.pack(anchor="w" if not I18N.rtl else "e", padx=14, pady=(0, 8))
        self._last_clipboard = ""

        self.info_label, anchor = self._label(card, None, role="subtext")
        self.info_label.pack(anchor=anchor, padx=14, pady=(2, 8))

        qcard = self._card(tab)
        qcard.pack(fill="x", padx=10, pady=(0, 10))
        self.quality_var = ctk.IntVar(value=0)
        self._quality_labels = []
        for i, q in enumerate(core.QUALITY_OPTIONS):
            label = q["label"]
            key = None
            if label == "Best quality":
                key = "quality_best"
            elif label.startswith("Audio only"):
                key = "quality_audio"
            rb = ctk.CTkRadioButton(
                qcard, text=I18N.t(key) if key else label,
                variable=self.quality_var, value=i)
            if key:
                rb._vg_key = key
            self._reg(rb, "text")
            rb.pack(anchor="w" if not I18N.rtl else "e", padx=14, pady=3)
            self._quality_labels.append((rb, label))

        self._button(tab, "download", self.on_download).pack(pady=4)

        self.progress = self._reg(ctk.CTkProgressBar(tab, height=10), "progress")
        self.progress.pack(fill="x", padx=16, pady=(6, 2))
        self.progress.set(0)
        self.status_label, anchor = self._label(tab, None, role="subtext")
        self.status_label.pack(anchor=anchor, padx=16)
        self._button(tab, "open_folder", self._open_download_dir, role="ghost").pack(pady=8)

    # ------------------------------------------------------------ send tab

    def _build_send_tab(self, tab):
        card = self._card(tab)
        card.pack(fill="both", expand=True, padx=10, pady=10)

        lbl, anchor = self._label(card, "send_to_device", bold=True, size=15)
        lbl.pack(anchor=anchor, padx=14, pady=(12, 0))
        lbl, anchor = self._label(card, "send_to_device_hint", role="subtext")
        lbl.pack(anchor=anchor, padx=14, pady=(0, 8))

        self._button(card, "choose_files", self.on_pick_files, role="ghost").pack(pady=2)
        self.files_label, anchor = self._label(card, "no_files", role="subtext")
        self.files_label.pack(anchor=anchor, padx=14, pady=(2, 8))

        devrow = ctk.CTkFrame(card, fg_color="transparent")
        devrow.pack(fill="x", padx=14, pady=(0, 4))
        lbl, anchor = self._label(devrow, "no_devices", role="subtext")
        lbl.pack(side="left")
        self.devices_hint = lbl
        self._button(devrow, "refresh", self.on_refresh_devices, role="ghost").pack(side="right")

        self.device_list = ctk.CTkScrollableFrame(card, height=120, corner_radius=10)
        self.device_list.pack(fill="x", padx=14, pady=(0, 8))
        self._device_rows = []

        self._button(card, "pair_and_send", self.on_pair_and_send).pack(pady=4)
        self.send_status, anchor = self._label(card, None, role="subtext")
        self.send_status.pack(anchor=anchor, padx=14, pady=(2, 12))

        # Browser fallback: explicit, off by default, clearly labelled.
        fb = self._card(tab)
        fb.pack(fill="x", padx=10, pady=(0, 10))
        lbl, anchor = self._label(fb, "fallback_title", bold=True)
        lbl.pack(anchor=anchor, padx=14, pady=(10, 0))
        self.share_button = self._button(fb, "fallback_start", self.on_toggle_share, role="ghost")
        self.share_button.pack(pady=6)
        self.qr_label, _ = self._label(fb, None)
        self.qr_label.pack(pady=2)
        self.link_label, anchor = self._label(fb, None, role="subtext")
        self.link_label.pack(anchor=anchor, padx=14)
        lbl, anchor = self._label(fb, "fallback_warning", role="subtext", size=11)
        lbl.pack(anchor=anchor, padx=14, pady=8)

    # ---------------------------------------------------------- receive tab

    def _build_receive_tab(self, tab):
        card = self._card(tab)
        card.pack(fill="both", expand=True, padx=10, pady=10)

        lbl, anchor = self._label(card, "receive_title", bold=True, size=15)
        lbl.pack(anchor=anchor, padx=14, pady=(12, 6))
        self.receive_button = self._button(card, "receive_start", self.on_toggle_receive)
        self.receive_button.pack(pady=4)
        self.receive_status, anchor = self._label(card, "receive_idle", role="subtext")
        self.receive_status.pack(anchor=anchor, padx=14, pady=(2, 8))

        # This device's identity: full fingerprint + QR (public connection
        # identity, not a secret; a sender still needs user acceptance).
        lbl, anchor = self._label(card, "pairing_instructions", role="subtext")
        lbl.pack(anchor=anchor, padx=14, pady=(6, 0))
        fp = self.identity.fingerprint.upper()
        grouped = "  ".join(fp[i:i + 8] for i in range(0, 64, 8))
        self.fp_label = ctk.CTkLabel(
            card, text=grouped, font=ctk.CTkFont(family="Courier", size=13),
            wraplength=560, justify="center",
        )
        self._reg(self.fp_label, "text")
        self.fp_label.pack(padx=14, pady=6)
        self.recv_qr, _ = self._label(card, None)
        self.recv_qr.pack(pady=4)

    # --------------------------------------------------------- settings tab

    def _build_settings_tab(self, tab):
        card = self._card(tab)
        card.pack(fill="x", padx=10, pady=10)

        lbl, anchor = self._label(card, "theme", bold=True)
        lbl.pack(anchor=anchor, padx=14, pady=(12, 2))
        self.theme_seg = ctk.CTkSegmentedButton(
            card,
            values=[I18N.t("theme_light"), I18N.t("theme_dark"), I18N.t("theme_glass")],
            command=self._on_theme_seg,
        )
        self.theme_seg.set(I18N.t("theme_dark"))
        self._reg(self.theme_seg, "ghost")
        self.theme_seg.pack(padx=14, pady=(0, 10))

        lbl, anchor = self._label(card, "language", bold=True)
        lbl.pack(anchor=anchor, padx=14, pady=(2, 2))
        self.lang_seg = ctk.CTkSegmentedButton(
            card, values=["English", "العربية"], command=self._on_lang_seg
        )
        self.lang_seg.set("العربية" if I18N.rtl else "English")
        self._reg(self.lang_seg, "ghost")
        self.lang_seg.pack(padx=14, pady=(0, 10))

        lbl, anchor = self._label(card, "downloads_to", role="subtext", path=DOWNLOAD_DIR)
        lbl.pack(anchor=anchor, padx=14, pady=(2, 12))

    def _on_theme_seg(self, value):
        names = {"light": theme.PALETTES.keys()}
        picked = None
        for name in theme.THEME_ORDER:
            if value == I18N.t(f"theme_{name}"):
                picked = name
        theme.set_theme(picked or "dark")
        _save_settings()
        self._restyle()

    def _on_lang_seg(self, value):
        I18N.set_language("ar" if value == "العربية" else "en")
        _save_settings()
        self._retext()
        self._restyle()

    # ------------------------------------------------------------- download

    def on_fetch(self):
        url = core.extract_url(self.url_entry.get()) or self.url_entry.get().strip()
        if not url:
            return
        self.info_label.configure(text=I18N.t("fetching"))
        threading.Thread(target=self._fetch_worker, args=(url,), daemon=True).start()

    def _fetch_worker(self, url):
        try:
            info = core.probe(url)
            mins = f", {info.duration_seconds // 60} min" if info.duration_seconds else ""
            self.events.put(("info", f"{info.title}  ({info.extractor}{mins})"))
        except Exception as exc:
            self.events.put(("info", I18N.t("fetch_failed", error=str(exc)[:200])))

    def on_download(self):
        url = core.extract_url(self.url_entry.get()) or self.url_entry.get().strip()
        if not url:
            return
        self.progress.set(0)
        self.status_label.configure(text=I18N.t("starting"))
        threading.Thread(
            target=self._download_worker, args=(url, self.quality_var.get()), daemon=True
        ).start()

    def _download_worker(self, url, quality_index):
        def cb(p: core.Progress):
            self.events.put(("progress", p))

        try:
            path = core.download(url, quality_index, DOWNLOAD_DIR, progress_cb=cb)
            self.events.put(("done", path))
        except Exception as exc:
            self.events.put(("error", str(exc)[:300]))

    # --------------------------------------------------------- native send

    def _ensure_discovery(self):
        if self.discovery is None:
            try:
                self.discovery = transfer.Discovery()
                self.discovery.browse()
            except Exception as exc:
                self.devices_hint.configure(text=str(exc)[:120])
                return False
        return True

    def on_refresh_devices(self):
        if not self._ensure_discovery():
            return
        for row in self._device_rows:
            row.destroy()
        self._device_rows = []
        devices = [d for d in self.discovery.devices()
                   if d.device_id != self.identity.device_id]
        if not devices:
            self.devices_hint.configure(text=I18N.t("no_devices"))
            return
        self.devices_hint.configure(text="")
        p = theme.current_palette()
        for dev in devices:
            row = ctk.CTkFrame(self.device_list, fg_color=p["card_alt"], corner_radius=8)
            row.pack(fill="x", pady=2)
            lbl = ctk.CTkLabel(row, text=f"{dev.name}  ({dev.host})", text_color=p["text"])
            lbl.pack(side="left", padx=8, pady=6)
            row._vg_device = dev
            row.bind("<Button-1>", lambda e, r=row: self._select_device(r))
            lbl.bind("<Button-1>", lambda e, r=row: self._select_device(r))
            self._device_rows.append(row)
        self._select_device(self._device_rows[0])

    def _select_device(self, row):
        p = theme.current_palette()
        for r in self._device_rows:
            r.configure(fg_color=p["card_alt"])
        row.configure(fg_color=p["accent"])
        for child in row.winfo_children():
            try:
                child.configure(text_color=p["on_accent"])
            except Exception:
                pass
        self._selected_device = row._vg_device

    def on_pick_files(self):
        paths = filedialog.askopenfilenames(title=I18N.t("choose_files"))
        if paths:
            self._picked_files = list(paths)
            names = ", ".join(os.path.basename(p) for p in paths)
            self.files_label.configure(text=names[:400])

    def on_pair_and_send(self):
        if not self._picked_files:
            self.files_label.configure(text=I18N.t("pick_first"))
            return
        dev = getattr(self, "_selected_device", None)
        if dev is None:
            self.send_status.configure(text=I18N.t("pick_device"))
            return
        self.send_status.configure(text=I18N.t("starting"))
        threading.Thread(target=self._pair_worker, args=(dev,), daemon=True).start()

    def _pair_worker(self, dev):
        """Connect, verify the fingerprint (fail closed), then send each file."""
        try:
            client = transfer.SendClient(
                dev.host, dev.port, sender_name=f"VidGrab-{self.identity.device_id}",
                pin_store=self.pins, device_id=dev.device_id,
            )
            presented = client.connect()  # TLS handshake only; nothing else sent yet
            if not client.trusted:
                # First contact: the user must compare the full fingerprint
                # with the receiver's screen before any request leaves.
                ok = self._ask_fingerprint_sync(dev.name, presented)
                if not ok:
                    self.events.put(("send_status", I18N.t("pair_failed")))
                    return
                client.confirm_trust()
            for path in self._picked_files:
                self.events.put(("send_status",
                                 I18N.t("sending") + " " + os.path.basename(path)))
                saved = client.send(path)
                self.events.put(("send_status", I18N.t("sent_ok", name=saved)))
        except transfer.PinChangedError:
            self.events.put(("send_status", I18N.t("send_failed",
                            error="device identity changed - re-verify on its screen")))
        except Exception as exc:
            self.events.put(("send_status", I18N.t("send_failed", error=str(exc)[:200])))

    def _ask_fingerprint_sync(self, device_name: str, fingerprint: str) -> bool:
        """Show the compare dialog on the UI thread; block for the answer."""
        result: queue.Queue = queue.Queue()
        self.events.put(("compare_fp", (device_name, fingerprint, result)))
        try:
            return bool(result.get(timeout=transfer.PAIR_TTL_SECONDS))
        except queue.Empty:
            return False

    def _show_compare_dialog(self, device_name, fingerprint, result):
        p = theme.current_palette()
        dlg = ctk.CTkToplevel(self)
        dlg.title(I18N.t("pairing_title"))
        dlg.configure(fg_color=p["bg"])
        dlg.grab_set()
        msg = ctk.CTkLabel(dlg, text=I18N.t("pairing_instructions") + f"\n{device_name}",
                           wraplength=420, justify="center", text_color=p["text"])
        msg.pack(padx=20, pady=(18, 6))
        fp = fingerprint.upper()
        grouped = "\n".join("  ".join(fp[r * 32:(r + 1) * 32][i:i + 8]
                                      for i in range(0, 32, 8)) for r in range(2))
        ctk.CTkLabel(dlg, text=grouped, font=ctk.CTkFont(family="Courier", size=14),
                     text_color=p["accent"]).pack(padx=20, pady=6)
        row = ctk.CTkFrame(dlg, fg_color="transparent")
        row.pack(pady=14)

        def answer(ok):
            result.put(ok)
            dlg.destroy()

        ctk.CTkButton(row, text=I18N.t("code_matches"), fg_color=p["success"],
                      command=lambda: answer(True)).pack(side="left", padx=8)
        ctk.CTkButton(row, text=I18N.t("code_mismatch"), fg_color=p["danger"],
                      command=lambda: answer(False)).pack(side="left", padx=8)

    # ------------------------------------------------------ native receive

    def on_toggle_receive(self):
        if self.receiver is not None:
            self.receiver.stop()
            if self.discovery is not None:
                self.discovery.unadvertise()
            self.receiver = None
            self.receive_button.configure(text=I18N.t("receive_start"))
            self.receive_status.configure(text=I18N.t("receive_idle"))
            self.recv_qr.configure(image=None, text="")
            return
        try:
            self.receiver = transfer.ReceiveServer(
                self.identity, DOWNLOAD_DIR, ui=_AppTransferUI(self)
            )
            self.receiver.start()
            if self._ensure_discovery():
                name = f"VidGrab-{self.identity.device_id}"
                self.discovery.advertise(
                    name, self.receiver.port, self.identity.device_id,
                    platform=sys.platform, fingerprint=self.identity.fingerprint,
                )
            else:
                name = "this device"
        except Exception as exc:
            self.receive_status.configure(text=str(exc)[:200])
            self.receiver = None
            return
        self.receive_button.configure(text=I18N.t("receive_stop"))
        self.receive_status.configure(text=I18N.t("receive_active", name=name))
        self._show_recv_qr(name)

    def _show_recv_qr(self, name):
        """QR carries public connection identity only (host/port/fingerprint)."""
        try:
            import qrcode

            payload = (
                f"vidgrab://connect?h={self.receiver.host}&p={self.receiver.port}"
                f"&fp={self.identity.fingerprint}&n={name}"
            )
            qr = qrcode.QRCode(border=2, box_size=5)
            qr.add_data(payload)
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white").get_image()
            self.recv_qr.configure(
                image=ctk.CTkImage(light_image=img, dark_image=img, size=(160, 160)), text=""
            )
        except Exception:
            pass

    # --------------------------------------------------- browser fallback

    def on_toggle_share(self):
        if self.session is not None:
            self.session.stop()
            self.session = None
            self.share_button.configure(text=I18N.t("fallback_start"))
            self.link_label.configure(text=I18N.t("sharing_stopped"))
            self.qr_label.configure(image=None, text="")
            return
        if not self._picked_files:
            self.files_label.configure(text=I18N.t("pick_first"))
            return
        try:
            self.session = share.ShareSession(self._picked_files)
            url = self.session.start()
        except Exception as exc:
            self.link_label.configure(text=str(exc)[:200])
            self.session = None
            return
        self.share_button.configure(text=I18N.t("fallback_stop"))
        self.link_label.configure(text=I18N.t("fallback_link", url=url))
        self._show_share_qr(url)

    def _show_share_qr(self, url):
        try:
            import qrcode

            qr = qrcode.QRCode(border=2, box_size=5)
            qr.add_data(url)
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white").get_image()
            self.qr_label.configure(
                image=ctk.CTkImage(light_image=img, dark_image=img, size=(140, 140)), text=""
            )
        except Exception:
            self.qr_label.configure(text="(QR unavailable)")

    # ---------------------------------------------------------------- misc

    def _open_download_dir(self):
        os.makedirs(DOWNLOAD_DIR, exist_ok=True)
        if sys.platform.startswith("win"):
            os.startfile(DOWNLOAD_DIR)  # type: ignore[attr-defined]
        else:
            subprocess.Popen(["xdg-open", DOWNLOAD_DIR])

    def _check_clipboard(self):
        if not self.clipboard_var.get():
            return
        try:
            text = self.clipboard_get()
        except Exception:
            return
        if text != self._last_clipboard:
            self._last_clipboard = text
            url = core.extract_url(text)
            if url:
                self.url_entry.delete(0, "end")
                self.url_entry.insert(0, url)

    def _pump_events(self):
        self._check_clipboard()
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "info":
                    self.info_label.configure(text=payload)
                elif kind == "progress":
                    self.progress.set(payload.percent / 100.0)
                    speed = (f" - {payload.speed_bytes / 1e6:.1f} MB/s"
                             if payload.speed_bytes else "")
                    status = I18N.t(f"status_{payload.status}") \
                        if payload.status in ("downloading", "finished") else payload.status
                    self.status_label.configure(text=f"{status} {payload.percent:.0f}%{speed}")
                elif kind == "done":
                    self.status_label.configure(text=I18N.t("saved", path=payload))
                elif kind == "error":
                    self.status_label.configure(text=I18N.t("failed", error=payload))
                elif kind == "send_status":
                    self.send_status.configure(text=payload)
                elif kind == "receive_status":
                    self.receive_status.configure(text=payload)
                elif kind == "compare_fp":
                    self._show_compare_dialog(*payload)
                elif kind == "offer":
                    self._show_offer_dialog(*payload)
        except queue.Empty:
            pass
        if self.session is not None and self.session.expired():
            self.on_toggle_share()
            self.link_label.configure(text=I18N.t("sharing_expired"))
        self.after(150, self._pump_events)

    def _show_offer_dialog(self, sender_name, filename, size, respond):
        p = theme.current_palette()
        dlg = ctk.CTkToplevel(self)
        dlg.title(I18N.t("incoming_title"))
        dlg.configure(fg_color=p["bg"])
        dlg.grab_set()
        msg = ctk.CTkLabel(
            dlg,
            text=I18N.t("incoming_offer", sender=sender_name, filename=filename,
                        size=human_size(size)),
            wraplength=420, justify="center", text_color=p["text"],
        )
        msg.pack(padx=20, pady=(18, 8))
        row = ctk.CTkFrame(dlg, fg_color="transparent")
        row.pack(pady=14)

        def answer(ok):
            respond(ok)
            dlg.destroy()

        ctk.CTkButton(row, text=I18N.t("accept"), fg_color=p["success"],
                      command=lambda: answer(True)).pack(side="left", padx=8)
        ctk.CTkButton(row, text=I18N.t("decline"), fg_color=p["danger"],
                      command=lambda: answer(False)).pack(side="left", padx=8)

    def destroy(self):
        try:
            if self.session is not None:
                self.session.stop()
            if self.receiver is not None:
                self.receiver.stop()
            if self.discovery is not None:
                self.discovery.close()
        finally:
            super().destroy()


class _AppTransferUI(transfer.TransferUI):
    """Bridges transfer callbacks (HTTP thread) onto the Tk event loop."""

    def __init__(self, app: VidGrabApp):
        self.app = app

    def decide_offer(self, sender_name, filename, size, respond):
        self.app.events.put(("offer", (sender_name, filename, size, respond)))

    def progress(self, filename, received, total):
        self.app.events.put(("receive_status",
                             f"{I18N.t('status_downloading')} {filename} "
                             f"{received * 100 // max(total, 1)}%"))

    def done(self, filename, saved_path):
        self.app.events.put(("receive_status", I18N.t("received_ok", name=filename)))

    def failed(self, filename, reason):
        self.app.events.put(("receive_status",
                             I18N.t("receive_failed", reason=reason)))


if __name__ == "__main__":
    app = VidGrabApp()
    app.mainloop()
