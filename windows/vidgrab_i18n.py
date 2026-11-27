"""VidGrab bilingual strings (English / Arabic) for the desktop app.

Usage:
    from vidgrab_i18n import I18N
    I18N.set_language("ar")          # or "en"
    label.configure(text=I18N.t("tab_download"))

The app defaults to the system locale and the user can override it in
Settings. Arabic strings are written for right-to-left rendering; widgets
that show them use justify="right" and are anchored east (see vidgrab_app).
"""

from __future__ import annotations

import locale

STRINGS = {
    "app_title": {"en": "VidGrab", "ar": "فيدجراب"},
    "tab_download": {"en": "Download", "ar": "تحميل"},
    "tab_send": {"en": "Send", "ar": "إرسال"},
    "tab_receive": {"en": "Receive", "ar": "استلام"},
    "tab_settings": {"en": "Settings", "ar": "الإعدادات"},
    "tab_files": {"en": "Files", "ar": "الملفات"},
    "size_note": {"en": "Sizes are estimates from the site's data and can differ from the saved file.",
                  "ar": "الأحجام تقديرية من بيانات الموقع وممكن تختلف عن الملف المحفوظ."},
    "size_unknown_q": {"en": "size unknown", "ar": "الحجم غير معروف"},
    "lib_all": {"en": "All", "ar": "الكل"},
    "lib_video": {"en": "Video", "ar": "فيديو"},
    "lib_audio": {"en": "Audio", "ar": "صوت"},
    "lib_images": {"en": "Images", "ar": "صور"},
    "lib_other": {"en": "Other", "ar": "أخرى"},
    "lib_empty_win": {"en": "No downloads yet. Files saved by VidGrab show up here.",
                      "ar": "لا توجد تحميلات بعد. الملفات التي يحفظها فيدجراب تظهر هنا."},
    "lib_open_note": {"en": "Double-click a file to open it in your default player (the Windows app has no built-in player).",
                      "ar": "اضغط مرتين على الملف لفتحه في المشغل الافتراضي (تطبيق ويندوز ليس فيه مشغل داخلي)."},
    "autopaste": {"en": "Auto-paste copied links when the app opens",
                  "ar": "لصق الرابط المنسوخ تلقائياً عند فتح التطبيق"},
    "pasted_note": {"en": "Pasted the copied link. Press Fetch info.",
                    "ar": "تم لصق الرابط المنسوخ. اضغط جلب المعلومات."},
    "search_placeholder": {"en": "Search YouTube", "ar": "ابحث في يوتيوب"},
    "search": {"en": "Search", "ar": "بحث"},
    "searching": {"en": "Searching...", "ar": "جاري البحث..."},
    "search_none": {"en": "No results.", "ar": "لا توجد نتائج."},
    "search_pick": {"en": "Pick a result to load it.", "ar": "اختر نتيجة لتحميل معلوماتها."},
    "search_failed": {"en": "Search failed: {error}", "ar": "فشل البحث: {error}"},

    "url_placeholder": {"en": "Paste a video link here", "ar": "الصق رابط الفيديو هنا"},
    "fetch_info": {"en": "Fetch info", "ar": "جلب المعلومات"},
    "fetching": {"en": "Fetching info...", "ar": "جاري جلب المعلومات..."},
    "fetch_failed": {"en": "Could not read this link: {error}", "ar": "تعذر قراءة هذا الرابط: {error}"},
    "clipboard_watch": {
        "en": "Watch clipboard for links (off by default)",
        "ar": "مراقبة الحافظة للروابط (متوقفة افتراضيًا)",
    },
    "quality_best": {"en": "Best quality", "ar": "أفضل جودة"},
    "quality_audio": {"en": "Audio only (MP3)", "ar": "صوت فقط (MP3)"},
    "download": {"en": "Download", "ar": "تحميل"},
    "starting": {"en": "Starting...", "ar": "جاري البدء..."},
    "open_folder": {"en": "Open downloads folder", "ar": "فتح مجلد التحميلات"},
    "saved": {"en": "Saved: {path}", "ar": "تم الحفظ: {path}"},
    "failed": {"en": "Failed: {error}", "ar": "فشل: {error}"},
    "status_downloading": {"en": "downloading", "ar": "جاري التحميل"},
    "status_finished": {"en": "finishing", "ar": "جاري الإنهاء"},

    "send_to_device": {"en": "Send to a device", "ar": "إرسال إلى جهاز"},
    "send_to_device_hint": {
        "en": "Devices running VidGrab on this Wi-Fi appear here.",
        "ar": "الأجهزة التي تعمل بفيدجراب على نفس الواي فاي تظهر هنا.",
    },
    "no_devices": {"en": "No devices found yet", "ar": "لم يتم العثور على أجهزة بعد"},
    "refresh": {"en": "Refresh", "ar": "تحديث"},
    "pair_and_send": {"en": "Pair and send", "ar": "اقتران وإرسال"},
    "choose_files": {"en": "Choose files to send", "ar": "اختر الملفات للإرسال"},
    "no_files": {"en": "No files selected", "ar": "لم يتم اختيار ملفات"},
    "pick_first": {"en": "Pick files first.", "ar": "اختر الملفات أولًا."},
    "pick_device": {"en": "Pick a device first.", "ar": "اختر جهازًا أولًا."},
    "pairing_title": {"en": "Confirm the code", "ar": "تأكيد الرمز"},
    "pairing_instructions": {
        "en": "Check that the screen of \"{device}\" shows this exact code, then confirm here:",
        "ar": "تأكد أن شاشة \"{device}\" تعرض هذا الرمز بالضبط، ثم أكّد هنا:",
    },
    "identity_hint": {
        "en": "This is your device's fingerprint. A sender checks that their screen "
              "shows this exact code before the first transfer. It is public identity, "
              "not a password.",
        "ar": "هذه بصمة جهازك. يتحقق المُرسل أن شاشته تعرض نفس الرمز بالضبط قبل أول "
              "نقل. هذه هوية علنية وليست كلمة سر.",
    },
    "code_matches": {"en": "Codes match", "ar": "الرمزان متطابقان"},
    "code_mismatch": {"en": "They differ", "ar": "غير متطابقين"},
    "waiting_pair": {"en": "Waiting for the other device to confirm...", "ar": "في انتظار تأكيد الجهاز الآخر..."},
    "pair_failed": {"en": "Pairing failed or was rejected.", "ar": "فشل الاقتران أو تم رفضه."},
    "sending": {"en": "Sending...", "ar": "جاري الإرسال..."},
    "waiting_recipient": {"en": "Waiting for recipient...", "ar": "في انتظار المستلم..."},
    "sent_ok": {"en": "Sent: {name}", "ar": "تم الإرسال: {name}"},
    "send_failed": {"en": "Send failed: {error}", "ar": "فشل الإرسال: {error}"},
    "transfer_progress": {
        "en": "{name} · {pct}% · {speed} · ~{eta} left",
        "ar": "⁦{name}⁩ · {pct}% · {speed} · متبقي ~{eta}",
    },
    "speed_mbps": {"en": "{v:.1f} MB/s", "ar": "{v:.1f} م.ب/ث"},
    "direct_file": {
        "en": "\u2066{name}\u2069 (direct file, {size})",
        "ar": "\u2066{name}\u2069 (ملف مباشر، {size})",
    },
    "size_unknown": {"en": "unknown size", "ar": "حجم غير معروف"},
    "speed_kbps": {"en": "{v:.0f} KB/s", "ar": "{v:.0f} ك.ب/ث"},

    "receive_title": {"en": "Receive on this device", "ar": "الاستلام على هذا الجهاز"},
    "receive_start": {"en": "Start receiving", "ar": "بدء الاستلام"},
    "receive_stop": {"en": "Stop receiving", "ar": "إيقاف الاستلام"},
    "receive_active": {
        "en": "This device is visible as \"{name}\" to VidGrab devices on this Wi-Fi.",
        "ar": "هذا الجهاز ظاهر باسم \"{name}\" لأجهزة فيدجراب على نفس الواي فاي.",
    },
    "receive_idle": {"en": "Receiving is off.", "ar": "الاستلام متوقف."},
    "incoming_title": {"en": "Incoming file", "ar": "ملف وارد"},
    "incoming_offer": {
        "en": "A device calling itself \"{sender}\" (unverified) wants to send you:",
        "ar": "جهاز يعرّف نفسه باسم \"{sender}\" (غير موثّق) يريد إرسال:",
    },
    "accept": {"en": "Accept", "ar": "قبول"},
    "decline": {"en": "Decline", "ar": "رفض"},
    "received_ok": {"en": "Received: {name}", "ar": "تم الاستلام: {name}"},
    "receive_failed": {"en": "Receive failed: {reason}", "ar": "فشل الاستلام: {reason}"},

    "fallback_title": {"en": "Browser fallback (no app needed)", "ar": "البديل عبر المتصفح (بدون تطبيق)"},
    "fallback_start": {"en": "Start browser sharing", "ar": "بدء المشاركة عبر المتصفح"},
    "fallback_stop": {"en": "Stop browser sharing", "ar": "إيقاف المشاركة"},
    "fallback_link": {
        "en": "Open on the other device (same Wi-Fi): {url}",
        "ar": "افتح على الجهاز الآخر (نفس الواي فاي): {url}",
    },
    "fallback_warning": {
        "en": "Sharing stays on for 10 minutes or until you stop it. The link is a key: "
              "anyone on this network who has it can fetch these files while sharing is on. "
              "This fallback connection is not encrypted; direct device transfer above is.",
        "ar": "تستمر المشاركة 10 دقائق أو حتى توقفها. الرابط هو المفتاح: أي شخص على هذه "
              "الشبكة يملكه يمكنه جلب هذه الملفات أثناء المشاركة. هذا البديل غير مشفّر؛ "
              "النقل المباشر بين الأجهزة بالأعلى مشفّر.",
    },
    "sharing_stopped": {"en": "Sharing stopped.", "ar": "توقفت المشاركة."},
    "sharing_expired": {"en": "Sharing expired (10 minutes).", "ar": "انتهت المشاركة (10 دقائق)."},

    "theme": {"en": "Theme", "ar": "المظهر"},
    "theme_light": {"en": "Light", "ar": "فاتح"},
    "theme_dark": {"en": "Dark", "ar": "داكن"},
    "theme_glass": {"en": "Glass", "ar": "زجاجي"},
    "language": {"en": "Language", "ar": "اللغة"},
    "downloads_to": {"en": "Downloads go to: {path}", "ar": "التحميلات إلى: {path}"},
}


class _I18N:
    def __init__(self) -> None:
        self.language = "en"

    def set_language(self, lang: str) -> None:
        self.language = "ar" if lang == "ar" else "en"

    def detect_system_language(self) -> str:
        try:
            loc = locale.getlocale()[0] or locale.getdefaultlocale()[0] or ""
        except Exception:
            loc = ""
        return "ar" if loc.lower().startswith("ar") else "en"

    def t(self, key: str, **kwargs) -> str:
        entry = STRINGS.get(key)
        if entry is None:
            return key
        text = entry.get(self.language) or entry["en"]
        return text.format(**kwargs) if kwargs else text

    @property
    def rtl(self) -> bool:
        return self.language == "ar"


I18N = _I18N()
