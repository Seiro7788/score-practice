"""crewroster-mobile — 데스크톱 .db 를 폰에서 열어 근무표 보기·셀 수정·저장.

DB 열기 → 승무원 검색 → 승무원 선택 → 날짜별 근무 보기 → 셀 탭하여 수정(저장 즉시).
회전편성은 데스크톱과 동일 로직(검증 23207/0). 한글 폰트 내장.
"""
import os
import sys
import traceback
import datetime as _dt

from kivy.config import Config
Config.set("input", "mouse", "mouse,multitouch_on_demand")

__version__ = "2.0"
BUILD = __version__


def _android_app_dir():
    """앱 전용 외부폴더(/sdcard/Android/data/<pkg>/files) — 권한 없이 읽기/쓰기 가능."""
    try:
        from android import mActivity  # noqa
        f = mActivity.getExternalFilesDir(None)
        if f:
            return f.getAbsolutePath()
    except Exception:
        pass
    return None


def _crash_dirs():
    d = []
    a = _android_app_dir()
    if a:
        d.append(a)
    d += ["/sdcard/Download", "/storage/emulated/0/Download", "/sdcard",
          "/storage/emulated/0", os.path.dirname(os.path.abspath(__file__))]
    return d


def _has_all_files():
    """안드로이드 11+ '모든 파일 접근' 권한 보유 여부(폴더 자유 탐색에 필요)."""
    try:
        from jnius import autoclass
        Environment = autoclass("android.os.Environment")
        return bool(Environment.isExternalStorageManager())
    except Exception:
        return True  # 안드로이드가 아니거나 구버전이면 통과


def _request_all_files():
    """'모든 파일 접근' 허용 설정 화면을 연다."""
    try:
        from jnius import autoclass
        Intent = autoclass("android.content.Intent")
        Settings = autoclass("android.provider.Settings")
        Uri = autoclass("android.net.Uri")
        PythonActivity = autoclass("org.kivy.android.PythonActivity")
        act = PythonActivity.mActivity
        try:
            intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.setData(Uri.parse("package:" + act.getPackageName()))
            act.startActivity(intent)
        except Exception:
            act.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    except Exception:
        pass


def _write_crash(msg):
    for base in _crash_dirs():
        try:
            p = os.path.join(base, "crewroster_error.txt")
            with open(p, "w", encoding="utf-8") as f:
                f.write(msg)
            return p
        except Exception:
            continue
    return None


def _excepthook(et, e, tb):
    _write_crash("".join(traceback.format_exception(et, e, tb)))
    sys.__excepthook__(et, e, tb)


sys.excepthook = _excepthook

from kivy.app import App
from kivy.base import ExceptionHandler, ExceptionManager
from kivy.clock import Clock
from kivy.core.text import LabelBase
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.textinput import TextInput
from kivy.uix.scrollview import ScrollView
from kivy.uix.popup import Popup
from kivy.uix.screenmanager import ScreenManager, Screen
from kivy.uix.recycleview import RecycleView
from kivy.uix.recycleboxlayout import RecycleBoxLayout
from kivy.uix.recycleview.views import RecycleDataViewBehavior
from kivy.uix.behaviors import ButtonBehavior
from kivy.properties import StringProperty, NumericProperty, BooleanProperty
from kivy.lang import Builder
from kivy.metrics import dp
from kivy.utils import platform

import core

# 한글 폰트 등록(기본 폰트 교체 → 모든 위젯 한글 렌더)
_FONT = os.path.join(os.path.dirname(__file__), "assets", "NotoSansKR.ttf")
_FONT_OK = False
try:
    if os.path.exists(_FONT):
        LabelBase.register("Roboto", fn_regular=_FONT)
        LabelBase.register("KR", fn_regular=_FONT)
        _FONT_OK = True
except Exception:
    _FONT_OK = False

NAVY = (0.149, 0.259, 0.420, 1)
WD = "월화수목금토일"


def _btn(text, **kw):
    b = Button(text=text, font_size=kw.pop("font_size", dp(15)), **kw)
    return b


def make_scroll():
    """버튼이 많은 목록에서도 스크롤 제스처가 잘 잡히도록 튜닝한 ScrollView."""
    return ScrollView(do_scroll_x=False, scroll_type=["content", "bars"],
                      bar_width=dp(6), bar_color=(.45, .45, .45, .7),
                      bar_inactive_color=(.45, .45, .45, .25),
                      scroll_distance=dp(10), scroll_timeout=220)


class Row(Button):
    pass


# ---- RecycleView 행(긴 목록 스크롤이 확실히 동작) + 디자인 --------------------
class PersonRowRV(ButtonBehavior, BoxLayout):
    name = StringProperty("")
    sub = StringProperty("")
    idx = NumericProperty(-1)


class DayRowRV(BoxLayout):
    date_text = StringProperty("")
    value = StringProperty("")
    idx = NumericProperty(-1)
    manual = BooleanProperty(False)
    holiday = BooleanProperty(False)


Builder.load_string("""
<PersonRowRV>:
    orientation: 'vertical'
    size_hint_y: None
    height: dp(62)
    padding: dp(14), dp(8)
    on_release: app.open_person_idx(self.idx)
    canvas.before:
        Color:
            rgba: (0.86,0.91,1,1) if self.state=='down' else (1,1,1,1)
        RoundedRectangle:
            pos: self.x+dp(5), self.y+dp(3)
            size: self.width-dp(10), self.height-dp(6)
            radius: [dp(12),]
    Label:
        text: root.name
        font_size: dp(18)
        bold: True
        color: 0.1,0.12,0.18,1
        halign: 'left'
        valign: 'bottom'
        text_size: self.width, self.height
    Label:
        text: root.sub
        font_size: dp(12.5)
        color: 0.5,0.52,0.58,1
        halign: 'left'
        valign: 'top'
        text_size: self.width, self.height

<DayRowRV>:
    orientation: 'horizontal'
    size_hint_y: None
    height: dp(58)
    spacing: dp(8)
    padding: dp(8), dp(4)
    canvas.before:
        Color:
            rgba: 1,1,1,1
        RoundedRectangle:
            pos: self.x+dp(2), self.y+dp(1)
            size: self.width-dp(4), self.height-dp(2)
            radius: [dp(8),]
    Label:
        text: root.date_text
        size_hint_x: None
        width: dp(66)
        halign: 'center'
        valign: 'middle'
        text_size: self.size
        font_size: dp(14)
        color: (0.82,0.12,0.12,1) if root.holiday else (0.28,0.3,0.36,1)
    Button:
        text: root.value
        font_size: dp(20)
        bold: True
        background_normal: ''
        background_down: ''
        background_color: (1,0.86,0.5,1) if root.manual else (0.9,0.94,1,1)
        color: 0.12,0.12,0.16,1
        on_release: app.edit_cell_idx(root.idx)
""")

GRID_COLS = 7     # 한 페이지(주)에 보여줄 날짜 수
GRID_NAMEW = 74   # 이름 칸 너비(dp)
GRID_CELLW = 42   # 셀 너비(dp)


class GridRowRV(RecycleDataViewBehavior, BoxLayout):
    """전체보기 한 행: 이름(고정) + 그 주의 날짜 셀들."""
    index = -1

    def __init__(self, **kw):
        super().__init__(orientation="horizontal", size_hint_y=None, height=dp(44),
                         spacing=dp(2), **kw)
        self.name_lbl = Label(size_hint_x=None, width=dp(GRID_NAMEW), halign="left",
                              valign="middle", font_size=dp(13), color=(.12, .14, .2, 1),
                              shorten=True)
        self.name_lbl.bind(size=self.name_lbl.setter("text_size"))
        self.add_widget(self.name_lbl)
        self.cells = []
        for i in range(GRID_COLS):
            b = Button(size_hint_x=None, width=dp(GRID_CELLW), font_size=dp(14),
                       background_normal="", background_down="", color=(.1, .1, .14, 1))
            b.bind(on_release=lambda inst, ci=i: App.get_running_app().grid_edit(self.index, ci))
            self.cells.append(b)
            self.add_widget(b)

    def refresh_view_attrs(self, rv, index, data):
        self.index = index
        self.name_lbl.text = data["name"]
        cells = data["cells"]
        for i, b in enumerate(self.cells):
            if i < len(cells):
                val, manual = cells[i]
                b.text = val or "-"
                b.background_color = (1, .86, .5, 1) if manual else (.93, .96, 1, 1)
                b.disabled = False
                b.opacity = 1
            else:
                b.text = ""
                b.disabled = True
                b.opacity = 0
        return super().refresh_view_attrs(rv, index, data)


class GridScreen(Screen):
    def __init__(self, app, **kw):
        super().__init__(**kw)
        self.app = app
        self.page = 0          # 주 페이지
        self.weeks = []        # [[(gc,d)..7], ...]
        self.persons = []
        root = BoxLayout(orientation="vertical")
        bar = BoxLayout(size_hint_y=None, height=dp(54), padding=[dp(6), dp(5)], spacing=dp(5))
        with bar.canvas.before:
            from kivy.graphics import Color, Rectangle
            Color(*NAVY)
            self._barbg = Rectangle(pos=bar.pos, size=bar.size)
        bar.bind(pos=lambda w, v: setattr(self._barbg, "pos", v),
                 size=lambda w, v: setattr(self._barbg, "size", v))
        bar.add_widget(_btn("목록", size_hint_x=None, width=dp(62), font_size=dp(14),
                            background_normal="", background_color=(1, 1, 1, .22), color=(1, 1, 1, 1),
                            on_release=lambda *a: setattr(self.app.sm, "current", "home")))
        bar.add_widget(Label(text="전체보기", bold=True, font_size=dp(17), color=(1, 1, 1, 1)))
        self.save_btn = _btn("전체저장", size_hint_x=None, width=dp(108), font_size=dp(14), bold=True,
                             background_normal="", background_color=(.55, .58, .62, 1), color=(1, 1, 1, 1),
                             on_release=lambda *a: self.app.commit_pending())
        bar.add_widget(self.save_btn)
        root.add_widget(bar)
        # 주 이동
        wk = BoxLayout(size_hint_y=None, height=dp(42), padding=[dp(6), dp(2)], spacing=dp(6))
        wk.add_widget(_btn("◀ 이전주", size_hint_x=None, width=dp(96), font_size=dp(14),
                           on_release=lambda *a: self.go_week(-1)))
        self.wk_lbl = Label(text="", font_size=dp(14), color=(.2, .22, .3, 1))
        wk.add_widget(self.wk_lbl)
        wk.add_widget(_btn("다음주 ▶", size_hint_x=None, width=dp(96), font_size=dp(14),
                           on_release=lambda *a: self.go_week(1)))
        root.add_widget(wk)
        # 날짜 머리글
        self.head = BoxLayout(size_hint_y=None, height=dp(34), spacing=dp(2), padding=[dp(6), 0])
        root.add_widget(self.head)
        # 본문(세로 가상 스크롤)
        self.rv = RecycleView(scroll_type=["content", "bars"], bar_width=dp(6),
                              bar_color=(.4, .4, .45, .7))
        rbl = RecycleBoxLayout(orientation="vertical", default_size=(None, dp(44)),
                               default_size_hint=(1, None), size_hint_y=None, spacing=dp(2),
                               padding=[dp(6), dp(3)])
        rbl.bind(minimum_height=rbl.setter("height"))
        self.rv.add_widget(rbl)
        self.rv.viewclass = "GridRowRV"
        root.add_widget(self.rv)
        self.add_widget(root)

    def open(self):
        wb = self.app.wb
        if not wb:
            return
        wm = wb.grid.work_month
        days = sorted(((gc, d) for gc, d in wb.grid.dates.items() if d.month == wm),
                      key=lambda kv: kv[1])
        self.weeks = [days[i:i + GRID_COLS] for i in range(0, len(days), GRID_COLS)] or [[]]
        self.persons = list(getattr(self.app, "_order", []))
        if not self.persons:
            self.persons = [wb.row_person[r] for r in wb.grid.person_rows
                            if r in wb.row_person and wb.row_person[r].name]
        self.page = min(self.page, len(self.weeks) - 1)
        self.refresh()

    def go_week(self, delta):
        self.page = max(0, min(self.page + delta, len(self.weeks) - 1))
        self.refresh()

    def cur_days(self):
        return self.weeks[self.page] if self.weeks else []

    def refresh(self):
        wb = self.app.wb
        days = self.cur_days()
        # 머리글
        self.head.clear_widgets()
        self.head.add_widget(Label(size_hint_x=None, width=dp(GRID_NAMEW), text="이름",
                                   font_size=dp(12), color=(.5, .5, .55, 1)))
        for gc, d in days:
            hol = core.is_holiday(d, wb.holidays)
            self.head.add_widget(Label(size_hint_x=None, width=dp(GRID_CELLW),
                                       text=f"{d.day}\n{WD[d.weekday()]}", font_size=dp(12),
                                       halign="center", valign="middle",
                                       color=(.82, .12, .12, 1) if hol else (.3, .32, .4, 1)))
        if days:
            d0, d1 = days[0][1], days[-1][1]
            self.wk_lbl.text = f"{d0.month}/{d0.day} ~ {d1.month}/{d1.day}  ({self.page+1}/{len(self.weeks)}주)"
        # 데이터
        data = []
        for p in self.persons:
            cells = []
            for gc, d in days:
                v = core.effective_value(wb, p.row, gc)
                manual = wb.grid.is_manual.get((p.row, gc), False)
                cells.append((v, bool(manual)))
            data.append({"name": f"{p.name}", "cells": cells})
        self.rv.data = data

    def grid_edit_target(self, row_idx, col_idx):
        days = self.cur_days()
        if 0 <= row_idx < len(self.persons) and 0 <= col_idx < len(days):
            return self.persons[row_idx], days[col_idx][0], days[col_idx][1]
        return None


class HomeScreen(Screen):
    def __init__(self, app, **kw):
        super().__init__(**kw)
        self.app = app
        root = BoxLayout(orientation="vertical")
        with root.canvas.before:
            from kivy.graphics import Color, Rectangle
            self._bg = Color(0.95, 0.96, 0.98, 1)
            self._bgr = Rectangle(pos=root.pos, size=root.size)
        root.bind(pos=lambda w, v: setattr(self._bgr, "pos", v),
                  size=lambda w, v: setattr(self._bgr, "size", v))

        bar = BoxLayout(size_hint_y=None, height=dp(56), padding=[dp(10), dp(6)], spacing=dp(6))
        self.path_lbl = Label(text="DB를 여세요", shorten=True, halign="left", valign="middle",
                              bold=True, font_size=dp(15), color=(.12, .14, .2, 1))
        self.path_lbl.bind(size=self.path_lbl.setter("text_size"))
        bar.add_widget(self.path_lbl)
        self.save_btn = _btn("전체저장", size_hint_x=None, width=dp(104), font_size=dp(14),
                             bold=True, background_normal="", background_color=(.55, .58, .62, 1),
                             color=(1, 1, 1, 1), on_release=lambda *a: self.app.commit_pending())
        bar.add_widget(self.save_btn)
        bar.add_widget(_btn("DB 열기", size_hint_x=None, width=dp(92), font_size=dp(14),
                            background_normal="", background_color=NAVY, color=(1, 1, 1, 1),
                            on_release=lambda *a: self.app.pick_db()))
        root.add_widget(bar)

        sb = BoxLayout(size_hint_y=None, height=dp(48), padding=[dp(10), dp(2)], spacing=dp(6))
        self.search = TextInput(hint_text="이름·사번 검색", multiline=False,
                                size_hint_y=None, height=dp(42), font_size=dp(16),
                                padding=[dp(10), dp(10)])
        self.search.bind(text=lambda *a: self.refresh())
        sb.add_widget(self.search)
        sb.add_widget(_btn("전체보기", size_hint_x=None, width=dp(96), font_size=dp(14),
                           background_normal="", background_color=(.18, .45, .35, 1), color=(1, 1, 1, 1),
                           on_release=lambda *a: self.app.open_grid()))
        root.add_widget(sb)

        self.info = Label(text=f"{BUILD} · 로드 완료", size_hint_y=None, height=dp(22),
                          color=(.45, .45, .5, 1), font_size=dp(12))
        root.add_widget(self.info)

        self.rv = RecycleView(scroll_type=["content", "bars"], bar_width=dp(6),
                              bar_color=(.4, .4, .45, .7))
        rbl = RecycleBoxLayout(orientation="vertical", default_size=(None, dp(62)),
                               default_size_hint=(1, None), size_hint_y=None, spacing=dp(4),
                               padding=[dp(4), dp(4)])
        rbl.bind(minimum_height=rbl.setter("height"))
        self.rv.add_widget(rbl)
        self.rv.viewclass = "PersonRowRV"
        root.add_widget(self.rv)

        foot = Label(text=f"교번 모바일 {__version__}  ·  제작 smartrain7788",
                     size_hint_y=None, height=dp(22), font_size=dp(11),
                     color=(.55, .55, .6, 1))
        root.add_widget(foot)
        self.add_widget(root)

    def refresh(self):
        wb = self.app.wb
        if not wb:
            self.rv.data = []
            return
        q = self.search.text.strip()
        persons = [wb.row_person[r] for r in wb.grid.person_rows if r in wb.row_person]
        persons = [p for p in persons if p.name]
        if q:
            persons = [p for p in persons if q in p.name or q in str(p.emp_no)]
        self.info.text = f"{len(persons)}명 · {wb.grid.work_month}월 작업"
        self.app._order = list(persons)
        self.rv.data = [
            {"name": p.name, "sub": f"{p.emp_no}   ·   {p.team}", "idx": i}
            for i, p in enumerate(self.app._order)]
        self.rv.scroll_y = 1


class PersonScreen(Screen):
    def __init__(self, app, **kw):
        super().__init__(**kw)
        self.app = app
        self.person = None
        self.days = []   # [(gc, d), ...] — edit_cell_idx 가 참조
        root = BoxLayout(orientation="vertical")
        bar = BoxLayout(size_hint_y=None, height=dp(56), padding=[dp(6), dp(5)], spacing=dp(5))
        with bar.canvas.before:
            from kivy.graphics import Color, Rectangle
            Color(*NAVY)
            self._barbg = Rectangle(pos=bar.pos, size=bar.size)
        bar.bind(pos=lambda w, v: setattr(self._barbg, "pos", v),
                 size=lambda w, v: setattr(self._barbg, "size", v))
        bar.add_widget(_btn("목록", size_hint_x=None, width=dp(64), font_size=dp(14),
                            background_normal="", background_color=(1, 1, 1, .22), color=(1, 1, 1, 1),
                            on_release=lambda *a: setattr(self.app.sm, "current", "home")))
        self.title = Label(text="", bold=True, font_size=dp(17), shorten=True,
                           halign="center", valign="middle", color=(1, 1, 1, 1))
        self.title.bind(size=self.title.setter("text_size"))
        bar.add_widget(self.title)
        bar.add_widget(_btn("◀", size_hint_x=None, width=dp(50), font_size=dp(20),
                            background_normal="", background_color=(1, 1, 1, .22), color=(1, 1, 1, 1),
                            on_release=lambda *a: self.app.person_nav(-1)))
        bar.add_widget(_btn("▶", size_hint_x=None, width=dp(50), font_size=dp(20),
                            background_normal="", background_color=(1, 1, 1, .22), color=(1, 1, 1, 1),
                            on_release=lambda *a: self.app.person_nav(1)))
        root.add_widget(bar)
        # 월 집계 요약
        self.summary = Label(text="", size_hint_y=None, height=dp(30), font_size=dp(13),
                             color=(.3, .32, .4, 1), halign="center", valign="middle")
        self.summary.bind(size=self.summary.setter("text_size"))
        root.add_widget(self.summary)

        self.rv = RecycleView(scroll_type=["content", "bars"], bar_width=dp(6),
                              bar_color=(.4, .4, .45, .7))
        rbl = RecycleBoxLayout(orientation="vertical", default_size=(None, dp(58)),
                               default_size_hint=(1, None), size_hint_y=None, spacing=dp(3),
                               padding=[dp(4), dp(3)])
        rbl.bind(minimum_height=rbl.setter("height"))
        self.rv.add_widget(rbl)
        self.rv.viewclass = "DayRowRV"
        root.add_widget(self.rv)

        savebar = BoxLayout(size_hint_y=None, height=dp(60), padding=[dp(8), dp(6)], spacing=dp(6))
        self.save_btn = _btn("전체저장", font_size=dp(18), bold=True, background_normal="",
                             background_color=(.55, .58, .62, 1), color=(1, 1, 1, 1),
                             on_release=lambda *a: self.app.commit_pending())
        savebar.add_widget(self.save_btn)
        root.add_widget(savebar)
        self.add_widget(root)

    def load(self, person):
        self.person = person
        self.title.text = f"{person.name} · {person.team}"
        self.refresh()
        self.rv.scroll_y = 1  # 새 승무원은 맨 위부터

    def refresh(self):
        wb = self.app.wb
        p = self.person
        wm = wb.grid.work_month
        self.days = sorted(((gc, d) for gc, d in wb.grid.dates.items() if d.month == wm),
                           key=lambda kv: kv[1])
        data = []
        n_work = n_s = n_edu = n_manual = 0
        for i, (gc, d) in enumerate(self.days):
            v = core.effective_value(wb, p.row, gc)
            manual = wb.grid.is_manual.get((p.row, gc), False)
            hol = core.is_holiday(d, wb.holidays)
            data.append({"date_text": f"{d.month}/{d.day}\n{WD[d.weekday()]}",
                         "value": v or "-", "idx": i, "manual": bool(manual), "holiday": bool(hol)})
            if manual:
                n_manual += 1
            if v == "S":
                n_s += 1
            elif v and ("교육" in v):
                n_edu += 1
            elif v and v not in ("-", ""):
                n_work += 1
        self.rv.data = data
        self.summary.text = f"근무 {n_work} · S {n_s} · 교육 {n_edu} · 수기수정 {n_manual}"


class StartScreen(Screen):
    """최소 화면 — 이 화면이 보이면 Kivy·SDL·한글폰트는 정상.
    여기서 '앱 시작'을 눌러 본 UI 로 전환(어느 단계에서 튕기는지 범위 좁히기용)."""
    def __init__(self, app, **kw):
        super().__init__(**kw)
        self.app = app
        root = BoxLayout(orientation="vertical", padding=dp(24), spacing=dp(16))
        root.add_widget(Label(
            text=f"교번 모바일 {__version__}\n\n이 화면이 보이면 정상입니다.\n"
                 "아래 '앱 시작'을 눌러 주세요.",
            halign="center", valign="middle", font_size=dp(18)))
        root.add_widget(_btn("앱 시작 ▶", size_hint_y=None, height=dp(56),
                             font_size=dp(18),
                             on_release=lambda *a: self.app.enter_app()))
        self.add_widget(root)


class ErrorScreen(Screen):
    def __init__(self, **kw):
        super().__init__(**kw)
        root = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(6))
        root.add_widget(Label(text="오류가 발생했습니다 (화면 캡처해 보내주세요)",
                              size_hint_y=None, height=dp(34), bold=True, color=(.85, .1, .1, 1)))
        sc = ScrollView()
        self.lbl = Label(text="", size_hint_y=None, halign="left", valign="top",
                         font_size=dp(12), color=(.1, .1, .1, 1))
        self.lbl.bind(width=lambda i, w: setattr(i, "text_size", (w, None)),
                      texture_size=lambda i, s: setattr(i, "height", s[1]))
        sc.add_widget(self.lbl)
        root.add_widget(sc)
        self.add_widget(root)


class _KivyErr(ExceptionHandler):
    def handle_exception(self, inst):
        msg = traceback.format_exc()
        _write_crash(msg)
        try:
            App.get_running_app().show_error(msg)
        except Exception:
            pass
        return ExceptionManager.PASS


class RosterApp(App):
    def build(self):
        ExceptionManager.add_handler(_KivyErr())
        try:
            return self._build()
        except Exception:
            msg = traceback.format_exc()
            _write_crash(msg)
            return self._error_root(msg)

    def _build(self):
        self.wb = None
        self.pending = {}          # {(person_row, col): text} — 아직 DB 미기록
        self._err_shown = False
        self._entered = True
        self.sm = ScreenManager()
        self.home = HomeScreen(self, name="home")
        self.person_scr = PersonScreen(self, name="person")
        self.grid_scr = GridScreen(self, name="grid")
        self.err_scr = ErrorScreen(name="error")
        self.sm.add_widget(self.home)
        self.sm.add_widget(self.person_scr)
        self.sm.add_widget(self.grid_scr)
        self.sm.add_widget(self.err_scr)
        self.sm.current = "home"
        if not _FONT_OK:
            self.home.info.text = f"{BUILD} · 폰트 미로드"
        return self.sm

    def enter_app(self):
        """'앱 시작' 탭 시 본 UI 를 구성·전환. 여기서 문제가 나면 ErrorScreen 에 표시됨."""
        try:
            if not self._entered:
                self.home = HomeScreen(self, name="home")
                self.person_scr = PersonScreen(self, name="person")
                self.sm.add_widget(self.home)
                self.sm.add_widget(self.person_scr)
                self._entered = True
                if not _FONT_OK:
                    self.home.info.text = f"{BUILD} · 폰트 미로드"
            self.sm.current = "home"
        except Exception:
            msg = traceback.format_exc()
            _write_crash(msg)
            self.show_error(msg)

    def _error_root(self, msg):
        root = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(6))
        root.add_widget(Label(text="시작 중 오류 (화면 캡처해 보내주세요)", size_hint_y=None,
                              height=dp(34), bold=True, color=(.85, .1, .1, 1)))
        sc = ScrollView()
        lbl = Label(text=msg, size_hint_y=None, halign="left", valign="top",
                    font_size=dp(12), color=(.1, .1, .1, 1))
        lbl.bind(width=lambda i, w: setattr(i, "text_size", (w, None)),
                 texture_size=lambda i, s: setattr(i, "height", s[1]))
        sc.add_widget(lbl)
        root.add_widget(sc)
        return root

    def show_error(self, msg):
        def _do(*a):
            try:
                self.err_scr.lbl.text = msg
                self.sm.current = "error"
            except Exception:
                pass
        if not getattr(self, "_err_shown", False):
            self._err_shown = True
            Clock.schedule_once(_do, 0)

    # ---------------------------------------------------------------- 권한
    def pick_db(self):
        """DB 열기: 권한 확인 후 폴더 탐색기 표시."""
        if platform == "android" and not _has_all_files():
            self._perm_dialog()
            return
        self.open_browser()

    def _perm_dialog(self):
        box = BoxLayout(orientation="vertical", spacing=dp(10), padding=dp(14))
        box.add_widget(Label(
            text="폴더를 탐색하려면 [b]모든 파일 접근[/b] 권한이 필요합니다.\n\n"
                 "아래 '권한 설정 열기'를 누른 뒤\n"
                 "[b]교번 모바일[/b] 항목을 [b]허용[/b]으로 켜고\n"
                 "뒤로가기로 돌아와 다시 'DB 열기'를 누르세요.",
            markup=True, halign="center"))
        br = BoxLayout(size_hint_y=None, height=dp(50), spacing=dp(8))
        br.add_widget(_btn("권한 설정 열기",
                           on_release=lambda *a: (pop.dismiss(), _request_all_files())))
        br.add_widget(_btn("닫기", on_release=lambda *a: pop.dismiss()))
        box.add_widget(br)
        pop = Popup(title="권한 필요", content=box, size_hint=(.9, None), height=dp(320))
        pop.open()

    # ---------------------------------------------------------------- 폴더 탐색기
    def _default_root(self):
        for d in ("/storage/emulated/0", "/sdcard"):
            if os.path.isdir(d):
                return d
        return os.path.dirname(os.path.abspath(__file__))

    def open_browser(self, start=None):
        self._browse_dir = start or getattr(self, "_last_dir", None) or self._default_root()
        self._browse_pop = Popup(title="DB 파일 선택", size_hint=(.96, .9))
        self._render_browser()
        self._browse_pop.open()

    def _browse_into(self, name):
        self._browse_dir = os.path.join(self._browse_dir, name)
        self._render_browser()

    def _browse_up(self):
        parent = os.path.dirname(self._browse_dir.rstrip("/"))
        if parent and os.path.isdir(parent):
            self._browse_dir = parent
            self._render_browser()

    def _render_browser(self):
        d = self._browse_dir
        box = BoxLayout(orientation="vertical", spacing=dp(4), padding=dp(8))
        top = BoxLayout(size_hint_y=None, height=dp(42), spacing=dp(6))
        top.add_widget(_btn("위로", size_hint_x=None, width=dp(72),
                            on_release=lambda *a: self._browse_up()))
        pl = Label(text=d, shorten=True, halign="left", valign="middle", font_size=dp(12),
                   color=(.2, .2, .2, 1))
        pl.bind(size=pl.setter("text_size"))
        top.add_widget(pl)
        box.add_widget(top)

        sc = make_scroll()
        lst = GridLayout(cols=1, size_hint_y=None, spacing=dp(2))
        lst.bind(minimum_height=lst.setter("height"))
        try:
            entries = sorted(os.listdir(d), key=lambda s: s.lower())
            folders = [e for e in entries if os.path.isdir(os.path.join(d, e)) and not e.startswith(".")]
            dbs = [e for e in entries if e.lower().endswith(".db")]
            for fo in folders:
                b = _btn("[폴더] " + fo, size_hint_y=None, height=dp(46), halign="left",
                         background_normal="", background_color=(.92, .95, 1, 1), color=(.1, .1, .1, 1))
                b.bind(size=lambda i, s: setattr(i, "text_size", (i.width - dp(20), None)))
                b.bind(on_release=lambda inst, n=fo: self._browse_into(n))
                lst.add_widget(b)
            for fi in dbs:
                full = os.path.join(d, fi)
                b = _btn("[DB] " + fi, size_hint_y=None, height=dp(48), halign="left",
                         background_normal="", background_color=(1, .93, .75, 1), color=(.1, .1, .1, 1))
                b.bind(size=lambda i, s: setattr(i, "text_size", (i.width - dp(20), None)))
                b.bind(on_release=lambda inst, p=full: (
                    setattr(self, "_last_dir", d), self._browse_pop.dismiss(), self.load_db(p)))
                lst.add_widget(b)
            if not folders and not dbs:
                lst.add_widget(Label(text="(이 폴더에 폴더나 .db 파일이 없습니다)",
                                     size_hint_y=None, height=dp(40), color=(.5, .5, .5, 1)))
        except Exception as e:
            lst.add_widget(Label(
                text=f"폴더를 열 수 없습니다.\n'모든 파일 접근' 권한을 확인하세요.\n\n{e}",
                size_hint_y=None, height=dp(120), halign="center"))
        sc.add_widget(lst)
        box.add_widget(sc)

        bottom = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(6))
        bottom.add_widget(_btn("다운로드 폴더", size_hint_x=None, width=dp(130),
                               on_release=lambda *a: self._goto_download()))
        bottom.add_widget(_btn("닫기", on_release=lambda *a: self._browse_pop.dismiss()))
        box.add_widget(bottom)
        self._browse_pop.content = box

    def _goto_download(self):
        for d in ("/storage/emulated/0/Download", "/sdcard/Download"):
            if os.path.isdir(d):
                self._browse_dir = d
                break
        self._render_browser()

    def load_db(self, path):
        if self.pending:
            self._confirm_discard(lambda: self._do_load(path))
        else:
            self._do_load(path)

    def _do_load(self, path):
        try:
            self.wb, meta = core.load(path)
            self.pending = {}
            self._refresh_save_ui()
            self.home.path_lbl.text = os.path.basename(path)
            self.home.refresh()
            self._toast(f"{os.path.basename(path)} 열림 ({len(self.wb.persons)}명)")
        except Exception:
            msg = traceback.format_exc()
            _write_crash(msg)
            self.show_error(msg)

    def _confirm_discard(self, proceed):
        box = BoxLayout(orientation="vertical", spacing=dp(10), padding=dp(14))
        box.add_widget(Label(text=f"저장하지 않은 변경 {len(self.pending)}건이 있습니다.\n"
                                  "먼저 전체저장할까요?", halign="center"))
        br = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(6))
        br.add_widget(_btn("전체저장 후 진행", background_normal="",
                           background_color=(.13, .5, .85, 1), color=(1, 1, 1, 1),
                           on_release=lambda *a: (pop.dismiss(), self.commit_pending(), proceed())))
        br.add_widget(_btn("저장않고 진행",
                           on_release=lambda *a: (pop.dismiss(), proceed())))
        box.add_widget(br)
        box.add_widget(_btn("취소", size_hint_y=None, height=dp(44),
                            on_release=lambda *a: pop.dismiss()))
        pop = Popup(title="저장하지 않은 변경", content=box, size_hint=(.9, None), height=dp(300))
        pop.open()

    # ---------------------------------------------------------------- 임시편집 / 전체저장
    def record_edit(self, person_row, col, text):
        """셀 편집을 메모리에만 반영하고 전체저장 대기열에 등록(DB 미기록)."""
        text = (text or "").strip()
        core.apply_cell(self.wb, person_row, col, text)
        self.pending[(person_row, col)] = text
        self._refresh_save_ui()

    def commit_pending(self):
        """대기 중인 모든 편집분을 DB 에 한 번에 기록."""
        if not self.pending:
            self._toast("저장할 변경사항이 없습니다")
            return
        try:
            n = core.commit_all(self.wb, self.pending)
            self.pending = {}
            self._refresh_save_ui()
            self._toast(f"{n}건을 DB에 저장했습니다")
        except Exception:
            msg = traceback.format_exc()
            _write_crash(msg)
            self.show_error(msg)

    def _refresh_save_ui(self):
        n = len(self.pending)
        for scr in (getattr(self, "home", None), getattr(self, "person_scr", None),
                    getattr(self, "grid_scr", None)):
            btn = getattr(scr, "save_btn", None)
            if btn:
                btn.text = f"전체저장 ({n})" if n else "전체저장"
                btn.background_color = (.9, .36, .13, 1) if n else (.55, .58, .62, 1)

    def open_grid(self):
        if not self.wb:
            self._toast("먼저 DB를 여세요")
            return
        self.grid_scr.open()
        self.sm.current = "grid"

    def grid_edit(self, row_idx, col_idx):
        tgt = self.grid_scr.grid_edit_target(row_idx, col_idx)
        if tgt:
            p, gc, d = tgt
            self.edit_cell(p, gc, d, after=self.grid_scr.refresh)

    def open_person(self, p):
        self.person_scr.load(p)
        self.sm.current = "person"

    def open_person_idx(self, idx):
        order = getattr(self, "_order", [])
        if 0 <= idx < len(order):
            self.open_person(order[idx])

    def edit_cell_idx(self, idx):
        days = self.person_scr.days
        if 0 <= idx < len(days):
            gc, d = days[idx]
            self.edit_cell(self.person_scr.person, gc, d)

    def person_nav(self, delta):
        """목록 순서대로 이전/다음 승무원으로 이동."""
        order = getattr(self, "_order", [])
        cur = self.person_scr.person
        try:
            i = order.index(cur)
        except ValueError:
            return
        j = i + delta
        if 0 <= j < len(order):
            self.person_scr.load(order[j])

    def edit_cell(self, p, gc, d, after=None):
        wb = self.wb
        cur = core.effective_value(wb, p.row, gc)
        manual = wb.grid.is_manual.get((p.row, gc), False)
        plan = core.planned_diagram(p.start_seq, p.team,
                                    d, _dt.date(wb.grid.year, wb.grid.start_month, 1),
                                    wb.seqtables, wb.holidays) if d else ""
        wd = WD[d.weekday()]
        box = BoxLayout(orientation="vertical", spacing=dp(6), padding=dp(10))
        box.add_widget(Label(text=f"{p.name}  ·  {d.month}/{d.day} ({wd})", bold=True,
                             size_hint_y=None, height=dp(32), font_size=dp(18),
                             color=(.1, .12, .18, 1)))
        box.add_widget(Label(text=f"계획값 [{plan or '-'}]   현재 [{cur or '-'}]"
                                  f"{'  (수기)' if manual else ''}",
                             size_hint_y=None, height=dp(20), font_size=dp(12.5),
                             color=(.45, .45, .5, 1)))
        disp = TextInput(text=cur if manual else "", multiline=False,
                         size_hint_y=None, height=dp(56), font_size=dp(28),
                         halign="center", padding=[dp(8), dp(12), dp(8), dp(8)])
        box.add_widget(disp)
        pop = Popup(title="근무 수정 (임시)", content=box, size_hint=(.95, .9))

        def do_save(val):
            self.record_edit(p.row, gc, (val or "").strip())  # 메모리만 — 전체저장 전까지 DB 미기록
            pop.dismiss()
            (after or self.person_scr.refresh)()

        def ins(c):
            disp.text = (disp.text or "") + c

        # 숫자 키패드(가변 높이 → 작은 화면에서도 저장 버튼이 항상 보임)
        pad = GridLayout(cols=3, size_hint_y=1, spacing=dp(5))
        for c in ["7", "8", "9", "4", "5", "6", "1", "2", "3"]:
            pad.add_widget(_btn(c, font_size=dp(26),
                                on_release=lambda i, ch=c: ins(ch)))
        pad.add_widget(_btn("0", font_size=dp(26), on_release=lambda i: ins("0")))
        pad.add_widget(_btn("←지움", font_size=dp(17),
                            on_release=lambda i: setattr(disp, "text", (disp.text or "")[:-1])))
        pad.add_widget(_btn("전체삭제", font_size=dp(14),
                            on_release=lambda i: setattr(disp, "text", "")))
        box.add_widget(pad)

        # 자주 쓰는 값 바로저장
        q = BoxLayout(size_hint_y=None, height=dp(50), spacing=dp(5))
        for label, val in [("S", "S"), ("교육", "교육"), ("계획복원", "")]:
            q.add_widget(_btn(label, font_size=dp(16), background_normal="",
                              background_color=(.9, .93, .98, 1), color=(.15, .15, .2, 1),
                              on_release=lambda i, v=val: do_save(v)))
        box.add_widget(q)

        act = BoxLayout(size_hint_y=None, height=dp(58), spacing=dp(6))
        act.add_widget(_btn("적용", font_size=dp(20), bold=True, background_normal="",
                            background_color=(.13, .5, .85, 1), color=(1, 1, 1, 1),
                            on_release=lambda i: do_save(disp.text)))
        act.add_widget(_btn("닫기", size_hint_x=None, width=dp(96), background_normal="",
                            background_color=(.8, .82, .86, 1), color=(.15, .15, .2, 1),
                            on_release=lambda i: pop.dismiss()))
        box.add_widget(act)
        pop.open()

    def _toast(self, msg):
        p = Popup(title="", content=Label(text=msg), size_hint=(.8, None), height=dp(120),
                  separator_height=0)
        p.open()
        Clock.schedule_once(lambda *a: p.dismiss(), 1.4)


if __name__ == "__main__":
    RosterApp().run()
