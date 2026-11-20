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

__version__ = "1.7"
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


class HomeScreen(Screen):
    def __init__(self, app, **kw):
        super().__init__(**kw)
        self.app = app
        root = BoxLayout(orientation="vertical")
        bar = BoxLayout(size_hint_y=None, height=dp(52), padding=dp(6), spacing=dp(6))
        self.path_lbl = Label(text="DB를 여세요", shorten=True, halign="left", valign="middle")
        self.path_lbl.bind(size=self.path_lbl.setter("text_size"))
        bar.add_widget(self.path_lbl)
        bar.add_widget(_btn("DB 열기", size_hint_x=None, width=dp(100),
                            on_release=lambda *a: self.app.pick_db()))
        root.add_widget(bar)

        sb = BoxLayout(size_hint_y=None, height=dp(44), padding=dp(6), spacing=dp(6))
        self.search = TextInput(hint_text="이름·사번 검색", multiline=False,
                                size_hint_y=None, height=dp(40))
        self.search.bind(text=lambda *a: self.refresh())
        sb.add_widget(self.search)
        root.add_widget(sb)

        self.info = Label(text=f"{BUILD} · 로드 완료", size_hint_y=None, height=dp(24),
                          color=(.4, .4, .4, 1), font_size=dp(12))
        root.add_widget(self.info)

        sc = make_scroll()
        self.lst = GridLayout(cols=1, size_hint_y=None, spacing=dp(3), padding=dp(4))
        self.lst.bind(minimum_height=self.lst.setter("height"))
        sc.add_widget(self.lst)
        root.add_widget(sc)

        foot = Label(text=f"교번 모바일 {__version__}  ·  제작 smartrain7788",
                     size_hint_y=None, height=dp(22), font_size=dp(11),
                     color=(.55, .55, .55, 1))
        root.add_widget(foot)
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
        self.app._order = list(persons[:600])
        for p in self.app._order:
            b = Row(text=f"  {p.name}    {p.emp_no}    {p.team}", size_hint_y=None, height=dp(52),
                    halign="left", valign="middle", font_size=dp(16),
                    background_normal="", background_color=(.96, .97, .99, 1),
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
        bar = BoxLayout(size_hint_y=None, height=dp(52), padding=dp(5), spacing=dp(5))
        bar.add_widget(_btn("목록", size_hint_x=None, width=dp(66),
                            on_release=lambda *a: setattr(self.app.sm, "current", "home")))
        self.title = Label(text="", bold=True, font_size=dp(17), shorten=True,
                           halign="center", valign="middle")
        self.title.bind(size=self.title.setter("text_size"))
        bar.add_widget(self.title)
        bar.add_widget(_btn("◀", size_hint_x=None, width=dp(52), font_size=dp(20),
                            on_release=lambda *a: self.app.person_nav(-1)))
        bar.add_widget(_btn("▶", size_hint_x=None, width=dp(52), font_size=dp(20),
                            on_release=lambda *a: self.app.person_nav(1)))
        root.add_widget(bar)
        # 요일 머리글
        head = BoxLayout(size_hint_y=None, height=dp(26), padding=[dp(6), 0], spacing=dp(6))
        h1 = Label(text="날짜", size_hint_x=None, width=dp(72), font_size=dp(12), color=(.5, .5, .5, 1))
        head.add_widget(h1)
        head.add_widget(Label(text="근무 (탭하여 수정 · 노랑=수기)", font_size=dp(12),
                              color=(.5, .5, .5, 1), halign="left", valign="middle"))
        root.add_widget(head)
        self.scroll = make_scroll()
        self.lst = GridLayout(cols=1, size_hint_y=None, spacing=dp(3), padding=[dp(4), dp(2)])
        self.lst.bind(minimum_height=self.lst.setter("height"))
        self.scroll.add_widget(self.lst)
        root.add_widget(self.scroll)
        self.add_widget(root)

    def load(self, person):
        self.person = person
        self.title.text = f"{person.name} · {person.team}"
        self.refresh()
        self.scroll.scroll_y = 1  # 새 승무원은 맨 위부터

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
            row = BoxLayout(size_hint_y=None, height=dp(54), spacing=dp(6), padding=[dp(2), 0])
            dl = Label(text=f"{d.month}/{d.day}\n{WD[d.weekday()]}", size_hint_x=None, width=dp(72),
                       halign="center", valign="middle", font_size=dp(14),
                       color=(.78, .12, .12, 1) if hol else (.25, .25, .25, 1))
            dl.bind(size=dl.setter("text_size"))
            row.add_widget(dl)
            cell = _btn(v or "-", font_size=dp(19), halign="center", valign="middle",
                        background_normal="",
                        background_color=(1, .9, .62, 1) if manual else (.92, .95, 1, 1),
                        color=(.1, .1, .1, 1))
            cell.bind(on_release=lambda inst, g=gc, dd=d: self.app.edit_cell(p, g, dd))
            row.add_widget(cell)
            self.lst.add_widget(row)


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
        self._err_shown = False
        self._entered = True
        self.sm = ScreenManager()
        self.home = HomeScreen(self, name="home")
        self.person_scr = PersonScreen(self, name="person")
        self.err_scr = ErrorScreen(name="error")
        self.sm.add_widget(self.home)
        self.sm.add_widget(self.person_scr)
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
        try:
            self.wb, meta = core.load(path)
            self.home.path_lbl.text = os.path.basename(path)
            self.home.refresh()
            self._toast(f"{os.path.basename(path)} 열림 ({len(self.wb.persons)}명)")
        except Exception:
            msg = traceback.format_exc()
            _write_crash(msg)
            self.show_error(msg)

    def open_person(self, p):
        self.person_scr.load(p)
        self.sm.current = "person"

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

    def edit_cell(self, p, gc, d):
        wb = self.wb
        cur = core.effective_value(wb, p.row, gc)
        manual = wb.grid.is_manual.get((p.row, gc), False)
        wd = WD[d.weekday()]
        box = BoxLayout(orientation="vertical", spacing=dp(6), padding=dp(10))
        box.add_widget(Label(text=f"{p.name}  ·  {d.month}/{d.day} ({wd})", bold=True,
                             size_hint_y=None, height=dp(34), font_size=dp(18)))
        box.add_widget(Label(text=f"현재 {'수기수정' if manual else '계획값'}: [{cur or '-'}]",
                             size_hint_y=None, height=dp(22), font_size=dp(13),
                             color=(.45, .45, .45, 1)))
        disp = TextInput(text=cur if manual else "", multiline=False,
                         size_hint_y=None, height=dp(58), font_size=dp(26),
                         halign="center", padding=[dp(8), dp(12), dp(8), dp(8)])
        box.add_widget(disp)
        pop = Popup(title="근무 수정", content=box, size_hint=(.95, .92))

        def do_save(val):
            core.save_cell(wb, p.row, gc, (val or "").strip())
            pop.dismiss()
            self.person_scr.refresh()

        def ins(c):
            disp.text = (disp.text or "") + c

        # 숫자 키패드(다이아번호 빠른 입력)
        pad = GridLayout(cols=3, size_hint_y=None, height=dp(216), spacing=dp(5))
        for c in ["7", "8", "9", "4", "5", "6", "1", "2", "3"]:
            pad.add_widget(_btn(c, font_size=dp(24), on_release=lambda i, ch=c: ins(ch)))
        pad.add_widget(_btn("0", font_size=dp(24), on_release=lambda i: ins("0")))
        pad.add_widget(_btn("지움", font_size=dp(17),
                            on_release=lambda i: setattr(disp, "text", (disp.text or "")[:-1])))
        pad.add_widget(_btn("전체삭제", font_size=dp(15),
                            on_release=lambda i: setattr(disp, "text", "")))
        box.add_widget(pad)

        # 자주 쓰는 값 바로저장
        q = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(5))
        for label, val in [("S", "S"), ("교육", "교육"), ("계획복원", "")]:
            q.add_widget(_btn(label, font_size=dp(16),
                              on_release=lambda i, v=val: do_save(v)))
        box.add_widget(q)

        act = BoxLayout(size_hint_y=None, height=dp(56), spacing=dp(6))
        act.add_widget(_btn("저장", font_size=dp(19), background_normal="",
                            background_color=(.13, .5, .85, 1), color=(1, 1, 1, 1),
                            on_release=lambda i: do_save(disp.text)))
        act.add_widget(_btn("닫기", size_hint_x=None, width=dp(100),
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
