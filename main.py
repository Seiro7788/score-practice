"""crewroster-mobile — 데스크톱 .db 를 폰에서 열어 근무표 보기·셀 수정·저장.

DB 열기 → 승무원 검색 → 승무원 선택 → 날짜별 근무 보기 → 셀 탭하여 수정(저장 즉시).
회전편성은 데스크톱과 동일 로직(검증 23207/0). 한글 폰트 내장.
"""
import os
import datetime as _dt

from kivy.config import Config
Config.set("input", "mouse", "mouse,multitouch_on_demand")

from kivy.app import App
from kivy.core.text import LabelBase
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.textinput import TextInput
from kivy.uix.scrollview import ScrollView
from kivy.uix.popup import Popup
from kivy.uix.screenmanager import ScreenManager, Screen
from kivy.metrics import dp
from kivy.utils import platform

import core

# 한글 폰트 등록(기본 폰트 교체 → 모든 위젯 한글 렌더)
_FONT = os.path.join(os.path.dirname(__file__), "assets", "NotoSansKR.ttf")
if os.path.exists(_FONT):
    LabelBase.register("Roboto", fn_regular=_FONT)
    LabelBase.register("KR", fn_regular=_FONT)

NAVY = (0.149, 0.259, 0.420, 1)
WD = "월화수목금토일"


def _btn(text, **kw):
    b = Button(text=text, font_size=kw.pop("font_size", dp(15)), **kw)
    return b


class Row(Button):
    pass


class HomeScreen(Screen):
    def __init__(self, app, **kw):
        super().__init__(**kw)
        self.app = app
        root = BoxLayout(orientation="vertical")
        bar = BoxLayout(size_hint_y=None, height=dp(52), padding=dp(6), spacing=dp(6))
        self.path_lbl = Label(text="DB를 여세요", shorten=True, halign="left", valign="middle")
        self.path_lbl.bind(size=self.path_lbl.setter("text_size"))
        bar.add_widget(self.path_lbl)
        bar.add_widget(_btn("DB 열기", size_hint_x=None, width=dp(100), on_release=lambda *a: self.app.pick_db()))
        root.add_widget(bar)

        sb = BoxLayout(size_hint_y=None, height=dp(44), padding=dp(6), spacing=dp(6))
        self.search = TextInput(hint_text="이름·사번 검색", multiline=False, size_hint_y=None, height=dp(40))
        self.search.bind(text=lambda *a: self.refresh())
        sb.add_widget(self.search)
        root.add_widget(sb)

        self.info = Label(text="", size_hint_y=None, height=dp(24), color=(.4, .4, .4, 1), font_size=dp(12))
        root.add_widget(self.info)

        sc = ScrollView()
        self.lst = GridLayout(cols=1, size_hint_y=None, spacing=dp(2), padding=dp(4))
        self.lst.bind(minimum_height=self.lst.setter("height"))
        sc.add_widget(self.lst)
        root.add_widget(sc)
        self.add_widget(root)

    def refresh(self):
        self.lst.clear_widgets()
        wb = self.app.wb
        if not wb:
            return
        q = self.search.text.strip()
        persons = [wb.row_person[r] for r in wb.grid.person_rows if r in wb.row_person]
        persons = [p for p in persons if p.name]
        if q:
            persons = [p for p in persons if q in p.name or q in str(p.emp_no)]
        self.info.text = f"{len(persons)}명 · {wb.grid.work_month}월 작업"
        for p in persons[:600]:
            b = Row(text=f"{p.name}   {p.emp_no}   {p.team}", size_hint_y=None, height=dp(44),
                    halign="left", background_normal="", background_color=(.96, .97, .99, 1),
                    color=(.1, .1, .1, 1))
            b.bind(size=lambda i, s: setattr(i, "text_size", (i.width - dp(20), None)))
            b.bind(on_release=lambda inst, pr=p: self.app.open_person(pr))
            self.lst.add_widget(b)


class PersonScreen(Screen):
    def __init__(self, app, **kw):
        super().__init__(**kw)
        self.app = app
        self.person = None
        root = BoxLayout(orientation="vertical")
        bar = BoxLayout(size_hint_y=None, height=dp(52), padding=dp(6), spacing=dp(6))
        bar.add_widget(_btn("← 목록", size_hint_x=None, width=dp(90),
                            on_release=lambda *a: setattr(self.app.sm, "current", "home")))
        self.title = Label(text="", bold=True)
        bar.add_widget(self.title)
        root.add_widget(bar)
        sc = ScrollView()
        self.lst = GridLayout(cols=1, size_hint_y=None, spacing=dp(1), padding=dp(4))
        self.lst.bind(minimum_height=self.lst.setter("height"))
        sc.add_widget(self.lst)
        root.add_widget(sc)
        self.add_widget(root)

    def load(self, person):
        self.person = person
        self.title.text = f"{person.name} ({person.team})"
        self.refresh()

    def refresh(self):
        self.lst.clear_widgets()
        wb = self.app.wb
        p = self.person
        wm = wb.grid.work_month
        gcs = sorted(((gc, d) for gc, d in wb.grid.dates.items() if d.month == wm),
                     key=lambda kv: kv[1])
        for gc, d in gcs:
            v = core.effective_value(wb, p.row, gc)
            manual = wb.grid.is_manual.get((p.row, gc), False)
            hol = core.is_holiday(d, wb.holidays)
            row = BoxLayout(size_hint_y=None, height=dp(40), spacing=dp(4))
            dl = Label(text=f"{d.month}/{d.day} {WD[d.weekday()]}", size_hint_x=None, width=dp(90),
                       color=(.75, .15, .15, 1) if hol else (.2, .2, .2, 1))
            row.add_widget(dl)
            cell = _btn(v or "-", background_normal="",
                        background_color=(1, .95, .8, 1) if manual else (.93, .96, 1, 1),
                        color=(.1, .1, .1, 1))
            cell.bind(on_release=lambda inst, g=gc, dd=d: self.app.edit_cell(p, g, dd))
            row.add_widget(cell)
            self.lst.add_widget(row)


class RosterApp(App):
    def build(self):
        self.wb = None
        self.sm = ScreenManager()
        self.home = HomeScreen(self, name="home")
        self.person_scr = PersonScreen(self, name="person")
        self.sm.add_widget(self.home)
        self.sm.add_widget(self.person_scr)
        if platform == "android":
            self._req_perms()
            self._autofind_db()
        return self.sm

    def _req_perms(self):
        try:
            from android.permissions import request_permissions, Permission
            request_permissions([Permission.READ_EXTERNAL_STORAGE,
                                 Permission.WRITE_EXTERNAL_STORAGE])
        except Exception:
            pass

    def _autofind_db(self):
        for base in ("/sdcard/Download", "/storage/emulated/0/Download", "/sdcard"):
            try:
                for f in os.listdir(base):
                    if f.endswith(".db"):
                        return self.load_db(os.path.join(base, f))
            except Exception:
                continue

    def pick_db(self):
        try:
            from plyer import filechooser
            filechooser.open_file(on_selection=self._picked,
                                  filters=[["DB", "*.db"]])
        except Exception as ex:
            self._toast(f"파일 선택 불가: {ex}")

    def _picked(self, sel):
        if sel:
            self.load_db(sel[0])

    def load_db(self, path):
        try:
            self.wb, meta = core.load(path)
            self.home.path_lbl.text = os.path.basename(path)
            self.home.refresh()
            self._toast(f"{os.path.basename(path)} 열림 ({len(self.wb.persons)}명)")
        except Exception as ex:
            self._toast(f"열기 실패: {ex}")

    def open_person(self, p):
        self.person_scr.load(p)
        self.sm.current = "person"

    def edit_cell(self, p, gc, d):
        wb = self.wb
        cur = core.effective_value(wb, p.row, gc)
        box = BoxLayout(orientation="vertical", spacing=dp(8), padding=dp(10))
        box.add_widget(Label(text=f"{p.name} · {d.month}/{d.day}", bold=True, size_hint_y=None, height=dp(30)))
        ti = TextInput(text="" if not wb.grid.is_manual.get((p.row, gc)) else cur,
                       hint_text="다이아번호 / S / 교육 등", multiline=False,
                       size_hint_y=None, height=dp(44))
        box.add_widget(ti)
        pop = Popup(title="근무 수정", content=box, size_hint=(.9, None), height=dp(260))

        def do_save(val):
            core.save_cell(wb, p.row, gc, val)
            pop.dismiss()
            self.person_scr.refresh()
            self._toast("저장됨")
        br = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(6))
        br.add_widget(_btn("저장", on_release=lambda *a: do_save(ti.text)))
        br.add_widget(_btn("S", on_release=lambda *a: do_save("S")))
        br.add_widget(_btn("계획복원", on_release=lambda *a: do_save("")))
        box.add_widget(br)
        br2 = BoxLayout(size_hint_y=None, height=dp(44))
        br2.add_widget(_btn("닫기", on_release=lambda *a: pop.dismiss()))
        box.add_widget(br2)
        pop.open()

    def _toast(self, msg):
        try:
            if platform == "android":
                from kivy.clock import Clock
                from plyer import notification  # noqa
        except Exception:
            pass
        p = Popup(title="", content=Label(text=msg), size_hint=(.8, None), height=dp(120),
                  separator_height=0)
        from kivy.clock import Clock
        p.open()
        Clock.schedule_once(lambda *a: p.dismiss(), 1.4)


if __name__ == "__main__":
    RosterApp().run()
