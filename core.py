"""crewroster-mobile 코어 — 데스크톱 .db 를 모바일에서 읽고 쓰는 경량 엔진.

openpyxl 등 무거운 의존성 없이 sqlite3 만 사용(안드로이드 python-for-android 호환).
데스크톱 crewroster 의 회전편성 로직(검증 23207/0)과 DB 인코딩을 그대로 이식.
"""
from __future__ import annotations
import datetime as _dt
import re
import sqlite3
from dataclasses import dataclass, field

_EPOCH = _dt.date(1899, 12, 30)
_PAT_COL = {"평평": 0, "평휴": 1, "휴휴": 2, "휴평": 3}
C_NAME, C_EMP, C_TEAM, C_GENDER, C_F, C_G = 2, 3, 4, 5, 6, 7
Q_LAST = 17
UA_START = 302


# ------------------------------------------------------------------ 모델
@dataclass
class Person:
    name: str
    emp_no: str = ""
    team: str = ""
    start_seq: int | None = None
    gender: str = ""
    pattern_group: str = ""
    row: int = 0


@dataclass
class SeqTable:
    team: str
    rows: list = field(default_factory=list)

    @property
    def period(self):
        return len(self.rows)


@dataclass
class Grid:
    year: int = 0
    start_month: int = 0
    work_month: int = 0
    dates: dict = field(default_factory=dict)
    transfer_dates: dict = field(default_factory=dict)
    values: dict = field(default_factory=dict)
    is_manual: dict = field(default_factory=dict)
    person_rows: list = field(default_factory=list)


@dataclass
class Workbook:
    persons: dict = field(default_factory=dict)
    row_person: dict = field(default_factory=dict)
    seqtables: dict = field(default_factory=dict)
    holidays: set = field(default_factory=set)
    grid: Grid = field(default_factory=Grid)
    trainee_mentor: dict = field(default_factory=dict)
    trainee_period: dict = field(default_factory=dict)
    db_path: str = ""


# ------------------------------------------------------------------ 인코딩
def _dec(s):
    if s is None:
        return None
    tag, _, rest = s.partition(":")
    if tag == "DT":
        try:
            return _dt.datetime.fromisoformat(rest)
        except ValueError:
            return rest
    if tag == "D":
        try:
            return _dt.date.fromisoformat(rest)
        except ValueError:
            return rest
    if tag == "T":
        try:
            return _dt.time.fromisoformat(rest)
        except ValueError:
            return rest
    if tag == "B":
        return rest == "1"
    if tag == "N":
        try:
            f = float(rest)
            return int(f) if "." not in rest and "e" not in rest.lower() and f == int(f) else f
        except ValueError:
            return rest
    return rest


def _enc(v):
    if v is None or v == "":
        return "S:"
    return "S:" + str(v)


def _colnum(letter):
    n = 0
    for ch in letter:
        n = n * 26 + (ord(ch) - 64)
    return n


def _collet(n):
    s = ""
    while n > 0:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def _norm(v):
    if v is None:
        return ""
    if isinstance(v, float) and v == int(v):
        return str(int(v))
    return str(v).strip()


def _serial_date(v):
    try:
        return _EPOCH + _dt.timedelta(days=int(float(v)))
    except (TypeError, ValueError):
        return None


def _vnorm(s):
    s = (s or "").strip() if isinstance(s, str) else _norm(s)
    if s and "." in s:
        try:
            f = float(s)
            if f == int(f):
                return str(int(f))
        except ValueError:
            pass
    return s


# ------------------------------------------------------------------ DB 로드
_SEQ_SHEETS = {"1_일반조": "순서표_일반조", "3_지부조": "순서표_지부조",
               "2_광명조": "순서표_광명조", "4_지원기관사조": "순서표_지원기관사조"}


def load(db_path):
    con = sqlite3.connect(db_path)
    try:
        vals, fset = {}, set()
        small = {}
        for sheet, row, col, enc, isf in con.execute(
                "SELECT sheet,row,col,enc,isf FROM sheet_cells"):
            if sheet == "작업양식":
                ref = _collet(col) + str(row)
                v = _dec(enc)
                vals[ref] = "" if v is None else (v if isinstance(v, str) else str(v))
                if isf:
                    fset.add(ref)
            else:
                small.setdefault(sheet, []).append((row, col, _dec(enc)))
        meta = {k: v for k, v in con.execute("SELECT key,value FROM meta")}
    finally:
        con.close()

    def arr(name):
        cells = small.get(name)
        if not cells:
            return []
        mr = max(r for r, c, v in cells)
        mc = max(c for r, c, v in cells)
        g = [[None] * mc for _ in range(mr)]
        for r, c, v in cells:
            g[r - 1][c - 1] = v
        return g

    wb = Workbook(db_path=db_path)
    g = Grid()
    g.year = int(float(vals.get("B1") or 0))
    g.start_month = int(float(vals.get("D1") or 0))
    g.work_month = int(float(vals.get("I1") or 0))
    GRID_MAXCOL = 120
    colL = {c: _collet(c) for c in range(1, GRID_MAXCOL + 1)}
    for c in range(1, GRID_MAXCOL + 1):
        d = _serial_date(vals.get(colL[c] + "2"))
        if d is None:
            continue
        (g.transfer_dates if c <= Q_LAST else g.dates)[c] = d
    day_cols = list(g.transfer_dates) + list(g.dates)
    for r in range(5, 300):
        rs = str(r)
        nm = _vnorm(vals.get(colL[C_NAME] + rs, ""))
        if not nm:
            continue
        g.person_rows.append(r)
        fv = vals.get(colL[C_F] + rs)
        try:
            ss = int(float(fv)) if fv not in (None, "") else None
        except (TypeError, ValueError):
            ss = None
        wb.persons[nm] = Person(name=nm, emp_no=_vnorm(vals.get(colL[C_EMP] + rs, "")),
                                team=_vnorm(vals.get(colL[C_TEAM] + rs, "")),
                                start_seq=ss, gender=_vnorm(vals.get(colL[C_GENDER] + rs, "")),
                                pattern_group=_vnorm(vals.get(colL[C_G] + rs, "")), row=r)
        for c in day_cols:
            ref = colL[c] + rs
            manual = ref not in fset
            g.values[(r, c)] = _vnorm(vals.get(ref, ""))
            g.is_manual[(r, c)] = manual
    wb.grid = g

    # 법정휴일
    for row in arr("법정휴일")[1:]:
        for v in row[:4]:
            if isinstance(v, _dt.datetime):
                wb.holidays.add(v.date())
            elif isinstance(v, _dt.date):
                wb.holidays.add(v)
    # 순서표
    for team, sheet in _SEQ_SHEETS.items():
        rows = arr(sheet)
        if not rows:
            continue
        tbl = SeqTable(team=team)
        for row in rows[2:]:
            b = row[1] if len(row) > 1 else None
            if b in (None, ""):
                break
            tbl.rows.append((_norm(row[3] if len(row) > 3 else ""),
                             _norm(row[4] if len(row) > 4 else ""),
                             _norm(row[5] if len(row) > 5 else ""),
                             _norm(row[6] if len(row) > 6 else "")))
        wb.seqtables[team] = tbl
    # 명단: 견습 매핑
    for row in arr("명단")[1:]:
        nm = _norm(row[1] if len(row) > 1 else "")
        gu = _norm(row[3] if len(row) > 3 else "")
        mentor = _norm(row[8] if len(row) > 8 else "")
        if nm and gu.startswith("8_") and mentor:
            wb.trainee_mentor[nm] = mentor
            sv = row[12] if len(row) > 12 else None
            ev = row[13] if len(row) > 13 else None
            sd = sv.date() if isinstance(sv, _dt.datetime) else (sv if isinstance(sv, _dt.date) else None)
            ed = ev.date() if isinstance(ev, _dt.datetime) else (ev if isinstance(ev, _dt.date) else None)
            if sd or ed:
                wb.trainee_period[nm] = (sd, ed)

    wb.row_person = {p.row: p for p in wb.persons.values()}
    return wb, meta


# ------------------------------------------------------------------ 회전엔진
def is_holiday(d, holidays):
    return d.weekday() >= 5 or d in holidays


def day_pattern(d, holidays):
    a = "휴" if is_holiday(d, holidays) else "평"
    b = "휴" if is_holiday(d + _dt.timedelta(days=1), holidays) else "평"
    return a + b


def planned_diagram(start_seq, team, d, first, seqtables, holidays):
    tbl = seqtables.get(team)
    if not tbl or not start_seq or tbl.period == 0:
        return ""
    idx = (int(start_seq) + (d - first).days) % tbl.period
    return _norm(tbl.rows[idx][_PAT_COL[day_pattern(d, holidays)]])


def effective_value(wb, person_row, col, _depth=0):
    key = (person_row, col)
    if wb.grid.is_manual.get(key):
        return wb.grid.values.get(key, "")
    d = wb.grid.dates.get(col)
    if d is None:
        return wb.grid.values.get(key, "")
    p = wb.row_person.get(person_row)
    if p is None:
        return ""
    if p.team and p.team.startswith("8_") and _depth < 3:
        per = wb.trainee_period.get(p.name)
        if per:
            sd, ed = per
            if (sd and d < sd) or (ed and d > ed):
                return ""
        mentor = wb.persons.get(wb.trainee_mentor.get(p.name))
        if mentor and mentor.row:
            return effective_value(wb, mentor.row, col, _depth + 1)
        return ""
    first = _dt.date(wb.grid.year, wb.grid.start_month, 1)
    return planned_diagram(p.start_seq, p.team, d, first, wb.seqtables, wb.holidays)


# ------------------------------------------------------------------ 저장(수기 셀)
def apply_cell(wb, person_row, col, text):
    """메모리에만 반영(DB 미기록). text='' 면 계획 복원. 전체저장 전 임시 편집용."""
    key = (person_row, col)
    text = (text or "").strip()
    if text == "":
        wb.grid.is_manual[key] = False
        wb.grid.values[key] = ""
    else:
        wb.grid.is_manual[key] = True
        wb.grid.values[key] = text


def commit_all(wb, changes):
    """메모리의 편집분(changes: {(row,col): text})을 DB 에 한 번에 기록."""
    if not changes:
        return 0
    con = sqlite3.connect(wb.db_path)
    try:
        for (person_row, col), text in changes.items():
            text = (text or "").strip()
            if text == "":
                con.execute("INSERT OR REPLACE INTO sheet_cells(sheet,row,col,enc,isf) "
                            "VALUES('작업양식',?,?,?,1)", (person_row, col, _enc("")))
            else:
                con.execute("INSERT OR REPLACE INTO sheet_cells(sheet,row,col,enc,isf) "
                            "VALUES('작업양식',?,?,?,0)", (person_row, col, _enc(text)))
        con.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('updated_utc',?)",
                    (_dt.datetime.now().isoformat(timespec="seconds"),))
        con.commit()
    finally:
        con.close()
    return len(changes)


def save_cell(wb, person_row, col, text):
    """작업양식 셀 수기 저장(메모리+DB 즉시). text='' 면 계획 복원(수식)."""
    apply_cell(wb, person_row, col, text)
    commit_all(wb, {(person_row, col): (text or "").strip()})
