"""crewroster-mobile — 1.3 최소 진단 빌드.

목적: 이 기기(갤S25)에서 Kivy/SDL 앱이 '라벨 한 줄'이라도 뜨는지 확인.
- 커스텀 폰트 없음(기본 폰트, 영문만)
- ScreenManager/TextInput/DB 로직 전부 없음
이 화면이 뜨면 => 기기/빌드는 정상, 문제는 앱 UI(폰트/화면전환 등)에 있음.
이것도 튕기면 => 그래픽/빌드(기기) 레벨 문제로 확정.
"""
__version__ = "1.4"

import os
import sys
import traceback


def _write_crash(msg):
    for base in ("/sdcard/Download", "/storage/emulated/0/Download", "/sdcard",
                 os.path.dirname(os.path.abspath(__file__))):
        try:
            with open(os.path.join(base, "crewroster_error.txt"), "w", encoding="utf-8") as f:
                f.write(msg)
            return
        except Exception:
            continue


def _excepthook(et, e, tb):
    _write_crash("".join(traceback.format_exception(et, e, tb)))
    sys.__excepthook__(et, e, tb)


sys.excepthook = _excepthook

from kivy.app import App
from kivy.uix.label import Label


class RosterApp(App):
    def build(self):
        return Label(text="crewroster 1.4 16KB-NDK test\nbase render OK",
                     halign="center", valign="middle")


if __name__ == "__main__":
    RosterApp().run()
