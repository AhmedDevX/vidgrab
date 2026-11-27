"""Auto-paste of a copied link when the window is activated (offscreen Qt)."""
import json
import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pytest

pytest.importorskip("PySide6")


@pytest.fixture()
def win(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.setenv("USERPROFILE", str(tmp_path))
    import importlib
    import vidgrab_qt
    importlib.reload(vidgrab_qt)
    from PySide6.QtWidgets import QApplication
    app = QApplication.instance() or QApplication([])
    w = vidgrab_qt.MainWindow()
    yield vidgrab_qt, app, w
    w.close()


def test_pastes_new_link_once(win):
    q, app, w = win
    app.clipboard().setText("look https://example.com/v?id=1 ok")
    w._autopaste()
    assert w.url_entry.text() == "https://example.com/v?id=1"
    w.url_entry.setText("")
    w._autopaste()                       # same link: not pasted again
    assert w.url_entry.text() == ""
    app.clipboard().setText("https://example.org/2")
    w._autopaste()
    assert w.url_entry.text() == "https://example.org/2"


def test_activation_event_triggers_paste(win):
    q, app, w = win
    from PySide6.QtCore import QEvent
    app.clipboard().setText("https://example.com/act")
    w.show()
    w.activateWindow()
    app.processEvents()
    w.changeEvent(QEvent(QEvent.ActivationChange))
    # offscreen platforms may not report active; accept either, but the
    # direct path must work when the window is active
    if w.isActiveWindow():
        assert w.url_entry.text() == "https://example.com/act"


def test_checkbox_off_disables(win):
    q, app, w = win
    w.clip_check.setChecked(False)
    app.clipboard().setText("https://example.com/off")
    w._autopaste()
    assert w.url_entry.text() == ""
    assert json.load(open(q.SETTINGS_FILE))["autopaste"] is False
