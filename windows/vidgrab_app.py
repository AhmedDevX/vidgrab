"""VidGrab Windows app: download videos and send files to nearby devices.

GUI layer over vidgrab_core (downloader) and vidgrab_share (file sharing).
Run:  python vidgrab_app.py
"""

from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import time
import tkinter.filedialog as filedialog

import customtkinter as ctk

import vidgrab_core as core
import vidgrab_share as share

DOWNLOAD_DIR = os.path.join(os.path.expanduser("~"), "Downloads", "VidGrab")


class VidGrabApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        ctk.set_appearance_mode("dark")
        self.title("VidGrab")
        self.geometry("640x560")

        self.events: queue.Queue = queue.Queue()
        self.session = None  # active ShareSession, if any

        tabs = ctk.CTkTabview(self)
        tabs.pack(fill="both", expand=True, padx=12, pady=12)
        self._build_download_tab(tabs.add("Download video"))
        self._build_send_tab(tabs.add("Send files"))

        self.after(100, self._pump_events)

    # ------------------------------------------------------------------ UI

    def _build_download_tab(self, tab):
        row = ctk.CTkFrame(tab)
        row.pack(fill="x", padx=8, pady=8)
        self.url_entry = ctk.CTkEntry(row, placeholder_text="Paste a video link here")
        self.url_entry.pack(side="left", fill="x", expand=True, padx=(0, 6))
        ctk.CTkButton(row, text="Fetch info", command=self.on_fetch).pack(side="left")

        self.clipboard_var = ctk.BooleanVar(value=False)
        ctk.CTkCheckBox(
            tab,
            text="Watch clipboard for links (off by default)",
            variable=self.clipboard_var,
        ).pack(anchor="w", padx=8)
        self._last_clipboard = ""

        self.info_label = ctk.CTkLabel(tab, text="", wraplength=560, justify="left")
        self.info_label.pack(anchor="w", padx=8, pady=(8, 4))

        self.quality_var = ctk.IntVar(value=0)
        for i, q in enumerate(core.QUALITY_OPTIONS):
            ctk.CTkRadioButton(tab, text=q["label"], variable=self.quality_var, value=i).pack(
                anchor="w", padx=16
            )

        ctk.CTkButton(tab, text="Download", command=self.on_download).pack(pady=10)
        self.progress = ctk.CTkProgressBar(tab)
        self.progress.pack(fill="x", padx=8)
        self.progress.set(0)
        self.status_label = ctk.CTkLabel(tab, text="")
        self.status_label.pack(anchor="w", padx=8, pady=(4, 0))
        ctk.CTkButton(
            tab, text="Open downloads folder", command=self._open_download_dir
        ).pack(pady=8)

    def _build_send_tab(self, tab):
        ctk.CTkButton(tab, text="Choose files to share", command=self.on_pick_files).pack(
            pady=(8, 4)
        )
        self.files_label = ctk.CTkLabel(tab, text="No files selected", wraplength=560)
        self.files_label.pack(anchor="w", padx=8)
        self.share_button = ctk.CTkButton(
            tab, text="Start sharing", command=self.on_toggle_share
        )
        self.share_button.pack(pady=8)
        self.qr_label = ctk.CTkLabel(tab, text="")
        self.qr_label.pack(pady=4)
        self.link_label = ctk.CTkLabel(tab, text="", wraplength=560)
        self.link_label.pack(padx=8)
        ctk.CTkLabel(
            tab,
            text=(
                "Sharing stays on for 10 minutes or until you stop it. The link is a "
                "key: anyone on this network who has it can fetch these files while "
                "sharing is on. The connection is not encrypted."
            ),
            wraplength=560,
            justify="left",
            text_color="gray70",
        ).pack(anchor="w", padx=8, pady=8)
        self._picked_files = []

    # ------------------------------------------------------------- download

    def on_fetch(self):
        url = core.extract_url(self.url_entry.get()) or self.url_entry.get().strip()
        if not url:
            return
        self.info_label.configure(text="Fetching info...")
        threading.Thread(target=self._fetch_worker, args=(url,), daemon=True).start()

    def _fetch_worker(self, url):
        try:
            info = core.probe(url)
            mins = f", {info.duration_seconds // 60} min" if info.duration_seconds else ""
            self.events.put(("info", f"{info.title}  ({info.extractor}{mins})"))
        except Exception as exc:  # surfaced to the user, keep it short
            self.events.put(("info", f"Could not read this link: {exc}"))

    def on_download(self):
        url = core.extract_url(self.url_entry.get()) or self.url_entry.get().strip()
        if not url:
            return
        self.progress.set(0)
        self.status_label.configure(text="Starting...")
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

    # ---------------------------------------------------------------- share

    def on_pick_files(self):
        paths = filedialog.askopenfilenames(title="Choose files to share")
        if paths:
            self._picked_files = list(paths)
            names = ", ".join(os.path.basename(p) for p in paths)
            self.files_label.configure(text=names[:400])

    def on_toggle_share(self):
        if self.session is not None:
            self.session.stop()
            self.session = None
            self.share_button.configure(text="Start sharing")
            self.link_label.configure(text="Sharing stopped.")
            self.qr_label.configure(image=None, text="")
            return
        if not self._picked_files:
            self.files_label.configure(text="Pick files first.")
            return
        try:
            self.session = share.ShareSession(self._picked_files)
            url = self.session.start()
        except Exception as exc:
            self.link_label.configure(text=f"Could not start sharing: {exc}")
            self.session = None
            return
        self.share_button.configure(text="Stop sharing")
        self.link_label.configure(text=f"Open on the other device (same Wi-Fi): {url}")
        self._show_qr(url)

    def _show_qr(self, url):
        try:
            import qrcode

            qr = qrcode.QRCode(border=2, box_size=6)
            qr.add_data(url)
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white").get_image()
            self.qr_label.configure(
                image=ctk.CTkImage(light_image=img, dark_image=img, size=(180, 180)),
                text="",
            )
        except Exception:
            self.qr_label.configure(text="(QR unavailable, use the link above)")

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
                    speed = (
                        f" - {payload.speed_bytes / 1e6:.1f} MB/s"
                        if payload.speed_bytes
                        else ""
                    )
                    self.status_label.configure(
                        text=f"{payload.status} {payload.percent:.0f}%{speed}"
                    )
                elif kind == "done":
                    self.status_label.configure(text=f"Saved: {payload}")
                elif kind == "error":
                    self.status_label.configure(text=f"Failed: {payload}")
        except queue.Empty:
            pass
        if self.session is not None and self.session.expired():
            self.on_toggle_share()
            self.link_label.configure(text="Sharing expired (10 minutes).")
        self.after(150, self._pump_events)

    def destroy(self):
        if self.session is not None:
            self.session.stop()
        super().destroy()


if __name__ == "__main__":
    app = VidGrabApp()
    app.mainloop()
