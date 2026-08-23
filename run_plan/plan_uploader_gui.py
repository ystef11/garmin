#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Графический интерфейс:
  - загрузка plan.json (из run_plan_calculator.html) в Garmin Connect и/или intervals.icu;
  - аналитика: выгрузка тренировок Garmin в локальную SQLite-базу и сборка HTML-отчёта.

Использует как подключаемые библиотеки (сам GUI не содержит бизнес-логики, только форму
и запуск в фоновом потоке):
  - plan_export_garmin.py         (plan_export_garmin.run_import / .connect / .saved_accounts)
  - plan_export_intervals_icu.py  (plan_export_intervals_icu.run_import)
  - garmin_activities_export.py   (garmin_activities_export.export — выгрузка активностей в БД)
  - build_report.py               (build_report.main — сборка HTML-отчёта по БД)

Аккаунт Garmin — ОБЩИЙ для вкладки "Garmin Connect" (загрузка плана) и вкладки "Аналитика"
(выгрузка тренировок): выбирается один раз в блоке "Аккаунт Garmin" вверху окна, токен
хранится в ~/.garth/<логин>, как и раньше.

ЗАПУСК
  python plan_uploader_gui.py

ПОДГОТОВКА (см. подсказки в самих библиотеках):
  pip install garth==0.6.3      # для Garmin
  (intervals.icu — без сторонних зависимостей)
"""

import os
import sys
import json
import calendar
import datetime
import sqlite3
import threading
import queue
import webbrowser
import tkinter as tk
from tkinter import ttk, filedialog, messagebox, simpledialog

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import plan_export_garmin as gp
import plan_export_intervals_icu as ii

CONFIG_PATH = os.path.join(HERE, "uploader_config.json")

# Типы кросс-тренировок, которые понимают библиотеки (объединение обоих словарей) + "all",
# с человекочитаемыми подписями для GUI.
SKIP_CROSS_LABELS = {
    "cycling": "Велосипед",
    "lap_swimming": "Плавание (бассейн)",
    "swimming": "Плавание",
    "cardio_training": "Кардио (устаревший тип)",
    "other": "Прочий кросс (лыжи и др.)",
    "strength_training": "Силовая",
}
SKIP_CROSS_OPTIONS = sorted(
    set(gp.CROSS_SPORT.keys()) | set(ii.CROSS_TYPE.keys()),
    key=lambda code: SKIP_CROSS_LABELS.get(code, code),
)

RU_MONTHS = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
             "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"]
RU_WEEKDAYS = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]

DEFAULT_CATCHUP_OVERLAP_DAYS = 2   # на сколько дней назад от последней записи в базе
                                    # перечитываем при авто-обновлении при старте
                                    # (на случай, если последняя выгрузка была неполной)
INITIAL_LOAD_DAYS = 365            # диапазон по умолчанию для самой первой (ручной) выгрузки


# ---------------------------------------------------------------------------
# Конфиг GUI (НЕ токены Garmin — только «какая база у какого аккаунта» и т.п.)
# ---------------------------------------------------------------------------
def load_config():
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_config(cfg):
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def default_db_name(account):
    """garmin_running_<account>.db — как уже сложилось в проекте (см. garmin_running_ystef.db,
    garmin_running_yurlovama.db): используем локальную часть e-mail до '@', без цифр/спецсимволов."""
    local = (account or "").split("@")[0].lower()
    local = "".join(c for c in local if c.isalnum()) or "account"
    local = local.rstrip("0123456789") or local
    return os.path.join(HERE, f"garmin_running_{local}.db")


def default_report_name(db_path):
    base = os.path.splitext(os.path.basename(db_path))[0]
    suffix = base[len("garmin_running"):] if base.startswith("garmin_running") else ("_" + base)
    return os.path.join(HERE, f"training_report{suffix}.html")


def db_last_activity_date(db_path):
    if not db_path or not os.path.isfile(db_path):
        return None
    try:
        con = sqlite3.connect(db_path)
        try:
            row = con.execute("SELECT MAX(date) FROM activities").fetchone()
            return row[0] if row and row[0] else None
        finally:
            con.close()
    except Exception:
        return None


class CalendarPicker(tk.Toplevel):
    """Простой всплывающий календарь (без сторонних зависимостей) для выбора даты
    в формате ГГГГ-ММ-ДД. Записывает результат в переданную StringVar."""

    def __init__(self, master, var):
        super().__init__(master)
        self.var = var
        self.title("Выбор даты")
        self.resizable(False, False)
        self.transient(master)
        today = datetime.date.today()
        try:
            cur = datetime.date.fromisoformat(var.get().strip()) if var.get().strip() else today
        except ValueError:
            cur = today
        self.year, self.month = cur.year, cur.month
        self._draw()
        self.grab_set()

    def _draw(self):
        for w in self.winfo_children():
            w.destroy()
        hdr = ttk.Frame(self)
        hdr.pack(fill="x", pady=4, padx=4)
        ttk.Button(hdr, text="◀", width=3, command=self._prev_month).pack(side="left")
        ttk.Label(hdr, text=f"{RU_MONTHS[self.month - 1]} {self.year}", width=18, anchor="center") \
            .pack(side="left", expand=True)
        ttk.Button(hdr, text="▶", width=3, command=self._next_month).pack(side="left")

        grid = ttk.Frame(self)
        grid.pack(padx=6, pady=4)
        for col, wd in enumerate(RU_WEEKDAYS):
            ttk.Label(grid, text=wd, width=4, anchor="center").grid(row=0, column=col)
        for row, week in enumerate(calendar.Calendar(firstweekday=0).monthdayscalendar(self.year, self.month), start=1):
            for col, day in enumerate(week):
                if day == 0:
                    ttk.Label(grid, text="", width=4).grid(row=row, column=col, padx=1, pady=1)
                else:
                    ttk.Button(grid, text=str(day), width=4,
                               command=lambda d=day: self._pick(d)).grid(row=row, column=col, padx=1, pady=1)

        ttk.Button(self, text="Сегодня", command=self._pick_today).pack(pady=(2, 6))

    def _prev_month(self):
        self.month -= 1
        if self.month == 0:
            self.month, self.year = 12, self.year - 1
        self._draw()

    def _next_month(self):
        self.month += 1
        if self.month == 13:
            self.month, self.year = 1, self.year + 1
        self._draw()

    def _pick(self, day):
        self.var.set(datetime.date(self.year, self.month, day).isoformat())
        self.destroy()

    def _pick_today(self):
        self.var.set(datetime.date.today().isoformat())
        self.destroy()


class QueueWriter:
    """Подменяет sys.stdout/stderr на время выполнения импорта — строки идут в очередь,
    а GUI (в главном потоке) забирает их таймером и печатает в лог."""

    def __init__(self, q):
        self.q = q

    def write(self, s):
        if s:
            self.q.put(s)

    def flush(self):
        pass


class AddAccountDialog(tk.Toplevel):
    """Модальный диалог входа/добавления аккаунта Garmin.

    Логинится через plan_export_garmin.connect(force_login=True) в фоновом потоке (2FA — через
    обычный simpledialog), по успеху сохраняет токен в ~/.garth/<логин> (это делает сама
    connect()) и уведомляет вызывающий код через колбэк on_done(account_or_None).
    """

    def __init__(self, master, on_done):
        super().__init__(master)
        self.on_done = on_done
        self.title("Аккаунт Garmin — вход")
        self.resizable(False, False)
        self.transient(master)

        pad = {"padx": 8, "pady": 4}
        ttk.Label(self, text="E-mail:").grid(row=0, column=0, sticky="w", **pad)
        self.email_var = tk.StringVar()
        ttk.Entry(self, textvariable=self.email_var, width=32).grid(row=0, column=1, **pad)

        ttk.Label(self, text="Пароль:").grid(row=1, column=0, sticky="w", **pad)
        self.pass_var = tk.StringVar()
        ttk.Entry(self, textvariable=self.pass_var, width=32, show="*").grid(row=1, column=1, **pad)

        self.status_var = tk.StringVar(value="")
        ttk.Label(self, textvariable=self.status_var, foreground="#555").grid(
            row=2, column=0, columnspan=2, sticky="w", **pad)

        btns = ttk.Frame(self)
        btns.grid(row=3, column=0, columnspan=2, pady=(4, 8))
        self.ok_btn = ttk.Button(btns, text="Войти", command=self._go)
        self.ok_btn.pack(side="left", padx=6)
        ttk.Button(btns, text="Отмена", command=self._cancel).pack(side="left", padx=6)

        self.grab_set()
        self.protocol("WM_DELETE_WINDOW", self._cancel)

    def _ask_mfa(self):
        result = {"code": None}
        event = threading.Event()

        def show():
            result["code"] = simpledialog.askstring("Garmin — код 2FA", "Введите код двухфакторной аутентификации:",
                                                      parent=self)
            event.set()

        self.after(0, show)
        event.wait()
        return (result["code"] or "").strip()

    def _go(self):
        email = self.email_var.get().strip()
        pwd = self.pass_var.get()
        if not email or not pwd:
            messagebox.showerror("Ошибка", "Укажи e-mail и пароль.", parent=self)
            return
        self.ok_btn.state(["disabled"])
        self.status_var.set("Вход…")

        def worker():
            try:
                gp.connect(email, password=pwd, mfa_prompt=self._ask_mfa, force_login=True)
                self.after(0, lambda: self._done(email))
            except SystemExit as e:
                self.after(0, lambda: self._fail(str(e)))
            except Exception as e:
                self.after(0, lambda: self._fail(str(e)))

        threading.Thread(target=worker, daemon=True).start()

    def _done(self, email):
        self.status_var.set("Готово.")
        self.on_done(email)
        self.destroy()

    def _fail(self, msg):
        self.ok_btn.state(["!disabled"])
        self.status_var.set(f"Ошибка: {msg}")

    def _cancel(self):
        self.on_done(None)
        self.destroy()


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Garmin / intervals.icu — план и аналитика")
        self.geometry("860x820")
        self.log_queue = queue.Queue()
        self.busy = False
        self.cfg = load_config()
        self._build()
        self.after(150, self._drain_log)
        self.after(700, self._startup_catchup)

    # ---------- UI ----------
    def _build(self):
        pad = {"padx": 6, "pady": 4}

        # --- аккаунт Garmin (общий для вкладок "Garmin Connect" и "Аналитика") ---
        af = ttk.LabelFrame(self, text="Аккаунт Garmin (общий для загрузки плана и аналитики)")
        af.pack(fill="x", **pad)
        self.garmin_account_var = tk.StringVar(value=self.cfg.get("last_account")
                                                or os.environ.get("GARMIN_EMAIL", ""))
        self.garmin_account_combo = ttk.Combobox(af, textvariable=self.garmin_account_var, width=32,
                                                  values=self._saved_accounts(), state="readonly")
        self.garmin_account_combo.grid(row=0, column=0, sticky="w", padx=6, pady=6)
        self.garmin_account_combo.bind("<<ComboboxSelected>>", lambda e: self._on_account_change())
        ttk.Button(af, text="＋ Добавить / войти…", command=self._add_account) \
            .grid(row=0, column=1, sticky="w", padx=6, pady=6)
        ttk.Button(af, text="Обновить список", command=self._refresh_accounts) \
            .grid(row=0, column=2, sticky="w", padx=6, pady=6)
        self.account_status_var = tk.StringVar(value="")
        ttk.Label(af, textvariable=self.account_status_var, foreground="#555") \
            .grid(row=1, column=0, columnspan=3, sticky="w", padx=6)

        accs = self._saved_accounts()
        if len(accs) == 1 and not self.garmin_account_var.get():
            self.garmin_account_var.set(accs[0])
        if self.garmin_account_var.get():
            self._on_account_change(initial=True)

        # --- plan.json ---
        f0 = ttk.LabelFrame(self, text="План")
        f0.pack(fill="x", **pad)
        self.plan_var = tk.StringVar()
        ttk.Entry(f0, textvariable=self.plan_var, width=70).pack(side="left", padx=6, pady=6, fill="x", expand=True)
        ttk.Button(f0, text="Выбрать plan.json…", command=self._pick_plan).pack(side="left", padx=6, pady=6)

        # --- общие опции ---
        f1 = ttk.LabelFrame(self, text="Общие опции загрузки плана")
        f1.pack(fill="x", **pad)
        self.dry_run_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(f1, text="Сухой прогон (--dry-run, ничего не отправлять)",
                         variable=self.dry_run_var).grid(row=0, column=0, columnspan=2, sticky="w", **pad)

        ttk.Label(f1, text="Пропустить кросс (skip-cross, можно выбрать несколько):") \
            .grid(row=1, column=0, sticky="nw", **pad)
        self.skip_cross_list = tk.Listbox(f1, selectmode="multiple", exportselection=False,
                                           height=min(6, len(SKIP_CROSS_OPTIONS)), width=28)
        for code in SKIP_CROSS_OPTIONS:
            self.skip_cross_list.insert("end", SKIP_CROSS_LABELS.get(code, code))
        self.skip_cross_list.grid(row=1, column=1, sticky="w", **pad)

        nb = ttk.Notebook(self)
        nb.pack(fill="both", expand=True, **pad)

        self._build_garmin_tab(nb, pad)
        self._build_intervals_tab(nb, pad)
        self._build_analytics_tab(nb, pad)

        # --- лог ---
        lf = ttk.LabelFrame(self, text="Лог")
        lf.pack(fill="both", expand=True, **pad)
        self.log = tk.Text(lf, height=14, wrap="word", state="disabled")
        self.log.pack(fill="both", expand=True, padx=6, pady=6)

    def _build_garmin_tab(self, nb, pad):
        gf = ttk.Frame(nb)
        nb.add(gf, text="Garmin Connect (загрузка плана)")

        self.garmin_test_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(gf, text="Только первая неделя (--test)",
                         variable=self.garmin_test_var).grid(row=0, column=0, columnspan=2, sticky="w", **pad)

        self.garmin_clear_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(gf, text="Удалить все тренировки плана перед загрузкой (--clear)",
                         variable=self.garmin_clear_var).grid(row=1, column=0, columnspan=2, sticky="w", **pad)

        self.garmin_clear_past_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(gf, text="Удалить только прошедшие тренировки (--clear-past), до:",
                         variable=self.garmin_clear_past_var).grid(row=2, column=0, sticky="w", **pad)
        self.garmin_before_var = tk.StringVar()
        before_row = ttk.Frame(gf)
        before_row.grid(row=2, column=1, sticky="w", **pad)
        ttk.Entry(before_row, textvariable=self.garmin_before_var, width=15).pack(side="left")
        ttk.Button(before_row, text="📅", width=3,
                   command=lambda: CalendarPicker(self, self.garmin_before_var)).pack(side="left", padx=4)
        ttk.Label(gf, text="(ГГГГ-ММ-ДД, по умолчанию сегодня)").grid(row=3, column=0, columnspan=2, sticky="w", padx=6)

        self.garmin_all_dates_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(gf, text="Загрузить весь план целиком, включая прошедшие даты (--all-dates)",
                         variable=self.garmin_all_dates_var).grid(row=4, column=0, columnspan=2, sticky="w", **pad)

        ttk.Label(gf, text="Загрузить начиная с даты (--from), пусто = сегодня:") \
            .grid(row=5, column=0, sticky="w", **pad)
        self.garmin_from_var = tk.StringVar()
        from_row = ttk.Frame(gf)
        from_row.grid(row=5, column=1, sticky="w", **pad)
        ttk.Entry(from_row, textvariable=self.garmin_from_var, width=15).pack(side="left")
        ttk.Button(from_row, text="📅", width=3,
                   command=lambda: CalendarPicker(self, self.garmin_from_var)).pack(side="left", padx=4)
        ttk.Label(gf, text="(игнорируется, если включено --all-dates)").grid(row=6, column=0, columnspan=2, sticky="w", padx=6)

        ttk.Button(gf, text="Отправить в Garmin Connect", command=self._run_garmin) \
            .grid(row=7, column=0, columnspan=2, sticky="w", padx=6, pady=12)

    def _build_intervals_tab(self, nb, pad):
        idf = ttk.Frame(nb)
        nb.add(idf, text="intervals.icu")
        ttk.Label(idf, text="API key:").grid(row=0, column=0, sticky="w", **pad)
        self.intervals_key_var = tk.StringVar(value=os.environ.get("INTERVALS_API_KEY", ""))
        ttk.Entry(idf, textvariable=self.intervals_key_var, width=40, show="*").grid(row=0, column=1, sticky="w", **pad)

        ttk.Label(idf, text="Athlete ID (например i123456):").grid(row=1, column=0, sticky="w", **pad)
        self.intervals_athlete_var = tk.StringVar(value=os.environ.get("INTERVALS_ATHLETE_ID", ""))
        ttk.Entry(idf, textvariable=self.intervals_athlete_var, width=20).grid(row=1, column=1, sticky="w", **pad)

        self.intervals_clear_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(idf, text="Удалить ранее загруженные события плана перед загрузкой (--clear)",
                         variable=self.intervals_clear_var).grid(row=2, column=0, columnspan=2, sticky="w", **pad)

        ttk.Button(idf, text="Отправить в intervals.icu", command=self._run_intervals) \
            .grid(row=3, column=0, columnspan=2, sticky="w", padx=6, pady=12)

    def _build_analytics_tab(self, nb, pad):
        anf = ttk.Frame(nb)
        nb.add(anf, text="Аналитика")

        ttk.Label(anf, text="Локальная база (SQLite):").grid(row=0, column=0, sticky="w", **pad)
        self.analytics_db_var = tk.StringVar()
        db_row = ttk.Frame(anf)
        db_row.grid(row=0, column=1, columnspan=2, sticky="we", **pad)
        ttk.Entry(db_row, textvariable=self.analytics_db_var, width=52).pack(side="left", fill="x", expand=True)
        ttk.Button(db_row, text="Обзор…", command=self._pick_analytics_db).pack(side="left", padx=4)

        self.analytics_last_var = tk.StringVar(value="")
        ttk.Label(anf, textvariable=self.analytics_last_var, foreground="#555") \
            .grid(row=1, column=0, columnspan=3, sticky="w", padx=6)

        ttk.Label(anf, text="Загрузить период — с:").grid(row=2, column=0, sticky="w", **pad)
        self.analytics_from_var = tk.StringVar()
        from_row = ttk.Frame(anf)
        from_row.grid(row=2, column=1, sticky="w", **pad)
        ttk.Entry(from_row, textvariable=self.analytics_from_var, width=15).pack(side="left")
        ttk.Button(from_row, text="📅", width=3,
                   command=lambda: CalendarPicker(self, self.analytics_from_var)).pack(side="left", padx=4)

        ttk.Label(anf, text="по:").grid(row=2, column=2, sticky="w")
        self.analytics_to_var = tk.StringVar(value=datetime.date.today().isoformat())
        to_row = ttk.Frame(anf)
        to_row.grid(row=2, column=3, sticky="w", **pad)
        ttk.Entry(to_row, textvariable=self.analytics_to_var, width=15).pack(side="left")
        ttk.Button(to_row, text="📅", width=3,
                   command=lambda: CalendarPicker(self, self.analytics_to_var)).pack(side="left", padx=4)

        opts = ttk.Frame(anf)
        opts.grid(row=3, column=0, columnspan=4, sticky="w", padx=6)
        self.analytics_wellness_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(opts, text="Выгружать самочувствие (сон/HRV/RHR/Body Battery/ПАНО)",
                         variable=self.analytics_wellness_var).pack(anchor="w")
        self.analytics_cross_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(opts, text="Выгружать кросс-тренировки (вело/лыжи/плавание/силовые)",
                         variable=self.analytics_cross_var).pack(anchor="w")
        self.analytics_force_refresh_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(opts, text="Перевыгрузить/перезаписать данные за уже загруженные дни этого периода",
                         variable=self.analytics_force_refresh_var).pack(anchor="w")

        btns = ttk.Frame(anf)
        btns.grid(row=4, column=0, columnspan=4, sticky="w", padx=6, pady=12)
        ttk.Button(btns, text="Импортировать тренировки в базу", command=self._run_analytics_import) \
            .pack(side="left", padx=(0, 8))
        ttk.Button(btns, text="Собрать и открыть отчёт", command=self._run_build_report) \
            .pack(side="left")

        anf.columnconfigure(1, weight=1)

    # ---------- аккаунт ----------
    def _saved_accounts(self):
        try:
            return gp.saved_accounts()
        except Exception:
            return []

    def _refresh_accounts(self):
        accs = self._saved_accounts()
        self.garmin_account_combo["values"] = accs
        if not accs:
            messagebox.showinfo("Аккаунты", "Сохранённых аккаунтов Garmin не найдено (~/.garth).")

    def _add_account(self):
        def on_done(email):
            if email:
                accs = self._saved_accounts()
                self.garmin_account_combo["values"] = accs
                self.garmin_account_var.set(email)
                self._on_account_change()
                messagebox.showinfo("Аккаунт", f"Вошли как {email}, токен сохранён.")
        AddAccountDialog(self, on_done)

    def _on_account_change(self, initial=False):
        account = self.garmin_account_var.get().strip()
        if not account:
            return
        self.account_status_var.set(f"Активный аккаунт: {account}")
        self.cfg["last_account"] = account
        db = self.cfg.get("dbs", {}).get(account) or default_db_name(account)
        self.analytics_db_var.set(db)
        self._refresh_analytics_last_label()
        if not initial:
            save_config(self.cfg)
        # диапазон по умолчанию для ручной загрузки: от последней даты в базе (если есть)
        # либо INITIAL_LOAD_DAYS назад — и до сегодня.
        last = db_last_activity_date(db)
        if last:
            self.analytics_from_var.set(last)
        else:
            self.analytics_from_var.set(
                (datetime.date.today() - datetime.timedelta(days=INITIAL_LOAD_DAYS)).isoformat())

    def _refresh_analytics_last_label(self):
        db = self.analytics_db_var.get().strip()
        last = db_last_activity_date(db)
        if last:
            self.analytics_last_var.set(f"Последняя тренировка в базе: {last}")
        else:
            self.analytics_last_var.set("База пуста или ещё не создана — укажи период для первой загрузки.")

    def _pick_analytics_db(self):
        path = filedialog.asksaveasfilename(
            title="Файл базы SQLite", defaultextension=".db",
            initialfile=os.path.basename(self.analytics_db_var.get() or "garmin_running.db"),
            filetypes=[("SQLite", "*.db"), ("Все файлы", "*.*")])
        if path:
            self.analytics_db_var.set(path)
            account = self.garmin_account_var.get().strip()
            if account:
                self.cfg.setdefault("dbs", {})[account] = path
                save_config(self.cfg)
            self._refresh_analytics_last_label()

    def _skip_cross_value(self):
        sel = [SKIP_CROSS_OPTIONS[i] for i in self.skip_cross_list.curselection()]
        return ",".join(sel)

    def _pick_plan(self):
        path = filedialog.askopenfilename(title="Выбрать plan.json", filetypes=[("JSON", "*.json"), ("Все файлы", "*.*")])
        if path:
            self.plan_var.set(path)

    # ---------- логирование ----------
    def _log_line(self, s):
        self.log.configure(state="normal")
        self.log.insert("end", s)
        self.log.see("end")
        self.log.configure(state="disabled")

    def _drain_log(self):
        try:
            while True:
                s = self.log_queue.get_nowait()
                self._log_line(s)
        except queue.Empty:
            pass
        self.after(150, self._drain_log)

    def _clear_log(self):
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.configure(state="disabled")

    # ---------- запрос кода 2FA из фонового потока ----------
    def _ask_mfa(self):
        result = {"code": None}
        event = threading.Event()

        def show():
            result["code"] = simpledialog.askstring("Garmin — код 2FA", "Введите код двухфакторной аутентификации:",
                                                      parent=self)
            event.set()

        self.after(0, show)
        event.wait()
        return (result["code"] or "").strip()

    # ---------- запуск в фоне ----------
    def _run_in_thread(self, fn, *args, require_plan=True, on_done=None, **kwargs):
        if self.busy:
            messagebox.showwarning("Занято", "Уже выполняется другая операция.")
            return
        if require_plan:
            plan = self.plan_var.get().strip()
            if not plan:
                messagebox.showerror("Ошибка", "Сначала выберите plan.json.")
                return
        self._clear_log()
        self.busy = True

        def worker():
            old_out, old_err = sys.stdout, sys.stderr
            writer = QueueWriter(self.log_queue)
            sys.stdout = writer
            sys.stderr = writer
            ok = False
            try:
                fn(*args, **kwargs)
                self.log_queue.put("\n[Готово]\n")
                ok = True
            except SystemExit as e:
                self.log_queue.put(f"\n[Остановлено] {e}\n")
            except Exception as e:
                import traceback
                self.log_queue.put(f"\n[Ошибка] {e}\n{traceback.format_exc()}\n")
            finally:
                sys.stdout, sys.stderr = old_out, old_err
                self.busy = False
                if on_done:
                    self.after(0, lambda: on_done(ok))

        threading.Thread(target=worker, daemon=True).start()

    def _run_garmin(self):
        plan = self.plan_var.get().strip()
        self._run_in_thread(
            gp.run_import,
            plan,
            dry_run=self.dry_run_var.get(),
            test=self.garmin_test_var.get(),
            clear=self.garmin_clear_var.get(),
            clear_past=self.garmin_clear_past_var.get(),
            before=self.garmin_before_var.get().strip() or None,
            skip_cross=self._skip_cross_value(),
            account=self.garmin_account_var.get().strip() or None,
            password=None,
            mfa_prompt=self._ask_mfa,
            all_dates=self.garmin_all_dates_var.get(),
            from_date=self.garmin_from_var.get().strip() or None,
        )

    def _run_intervals(self):
        plan = self.plan_var.get().strip()
        self._run_in_thread(
            ii.run_import,
            plan,
            key=self.intervals_key_var.get().strip(),
            athlete=self.intervals_athlete_var.get().strip(),
            skip_cross=self._skip_cross_value(),
            dry_run=self.dry_run_var.get(),
            clear=self.intervals_clear_var.get(),
        )

    # ---------- Аналитика ----------
    def _analytics_namespace(self, start_date, end_date, db_path, force_refresh_wellness):
        """SimpleNamespace, повторяющий значения по умолчанию garmin_activities_export.main() —
        GUI управляет только тем, что реально нужно менять из формы (период/база/аккаунт/
        флаги ниже), остальное — как в CLI по умолчанию."""
        import types
        return types.SimpleNamespace(
            account=self.garmin_account_var.get().strip() or None,
            force_login=False,
            start_date=start_date,
            end_date=end_date,
            days=INITIAL_LOAD_DAYS,
            db=db_path,
            long_threshold=75,
            temperature_unit="c",
            no_detail_confounds=False,
            no_grade_adjusted_pace=False,
            grade_adjusted_min_elevation_m=30,
            dump_raw=None,
            max_chart_size=4000,
            reclassify_only=False,
            export_csv=False,
            export_csv_after=False,
            in_path=None,
            out_format="sqlite",
            out=None,
            no_wellness=not self.analytics_wellness_var.get(),
            force_refresh_wellness=force_refresh_wellness,
            wellness_delay_ms=150,
            wellness_only=False,
            no_cross_training=not self.analytics_cross_var.get(),
            cross_sports="cycling,skiing,swimming,strength_training",
            cross_training_only=False,
            no_lactate_threshold=False,
            dump_wellness_raw=None,
            dump_activity_fields=None,
            dump_activity_details=None,
        )

    def _run_analytics_import(self):
        account = self.garmin_account_var.get().strip()
        if not account:
            messagebox.showerror("Ошибка", "Сначала выбери или добавь аккаунт Garmin вверху окна.")
            return
        db_path = self.analytics_db_var.get().strip() or default_db_name(account)
        start = self.analytics_from_var.get().strip()
        end = self.analytics_to_var.get().strip() or datetime.date.today().isoformat()
        if not start:
            messagebox.showerror("Ошибка", "Укажи начало периода загрузки.")
            return
        self.cfg.setdefault("dbs", {})[account] = db_path
        save_config(self.cfg)

        import garmin_activities_export as gae
        ns = self._analytics_namespace(start, end, db_path, self.analytics_force_refresh_var.get())

        def on_done(ok):
            if ok:
                self._refresh_analytics_last_label()

        self._run_in_thread(gae.export, ns, require_plan=False, on_done=on_done)

    def _run_build_report(self):
        db_path = self.analytics_db_var.get().strip()
        if not db_path or not os.path.isfile(db_path):
            messagebox.showerror("Ошибка", "База не найдена — сначала импортируй тренировки.")
            return
        out_path = default_report_name(db_path)

        import build_report as br

        def on_done(ok):
            if ok and os.path.isfile(out_path):
                webbrowser.open("file://" + os.path.abspath(out_path))

        self._run_in_thread(br.main, db_path, out_path, require_plan=False, on_done=on_done)

    # ---------- авто-обновление при старте ----------
    def _startup_catchup(self):
        if self.busy:
            return
        account = self.garmin_account_var.get().strip()
        if not account:
            return
        db_path = self.analytics_db_var.get().strip()
        if not db_path or not os.path.isfile(db_path):
            return  # первой загрузки ещё не было — ждём ручного действия с явным периодом
        last = db_last_activity_date(db_path)
        if not last:
            return
        try:
            last_d = datetime.date.fromisoformat(last)
        except ValueError:
            return
        start = (last_d - datetime.timedelta(days=DEFAULT_CATCHUP_OVERLAP_DAYS)).isoformat()
        end = datetime.date.today().isoformat()
        if start > end:
            return

        import garmin_activities_export as gae
        ns = self._analytics_namespace(start, end, db_path, force_refresh_wellness=False)

        def on_done(ok):
            if ok:
                self._refresh_analytics_last_label()

        self.log_queue.put(f"[Авто-обновление] {account}: догружаю тренировки с {start} по {end}…\n")
        self._run_in_thread(gae.export, ns, require_plan=False, on_done=on_done)


if __name__ == "__main__":
    App().mainloop()
