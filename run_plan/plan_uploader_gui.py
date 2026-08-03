#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Графический интерфейс для загрузки plan.json (из run_plan_calculator.html)
в Garmin Connect и/или intervals.icu.

Использует garmin_plan_import.py и intervals_icu_import.py как подключаемые
библиотеки (garmin_plan_import.run_import / intervals_icu_import.run_import) —
сам GUI не содержит логики импорта, только форму и запуск в фоновом потоке.

ЗАПУСК
  python plan_uploader_gui.py

ПОДГОТОВКА (см. подсказки в самих библиотеках):
  pip install garth==0.6.3      # для Garmin
  (intervals.icu — без сторонних зависимостей)
"""

import os
import sys
import calendar
import datetime
import threading
import queue
import tkinter as tk
from tkinter import ttk, filedialog, messagebox, simpledialog

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import garmin_plan_import as gp
import intervals_icu_import as ii

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


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Загрузка плана в Garmin / intervals.icu")
        self.geometry("820x680")
        self.log_queue = queue.Queue()
        self.busy = False
        self._build()
        self.after(150, self._drain_log)

    # ---------- UI ----------
    def _build(self):
        pad = {"padx": 6, "pady": 4}

        # --- plan.json ---
        f0 = ttk.LabelFrame(self, text="План")
        f0.pack(fill="x", **pad)
        self.plan_var = tk.StringVar()
        ttk.Entry(f0, textvariable=self.plan_var, width=70).pack(side="left", padx=6, pady=6, fill="x", expand=True)
        ttk.Button(f0, text="Выбрать plan.json…", command=self._pick_plan).pack(side="left", padx=6, pady=6)

        # --- общие опции ---
        f1 = ttk.LabelFrame(self, text="Общие опции")
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

        # --- Garmin ---
        gf = ttk.Frame(nb)
        nb.add(gf, text="Garmin Connect")

        ttk.Label(gf, text="Аккаунт:").grid(row=0, column=0, sticky="w", **pad)
        self.garmin_account_var = tk.StringVar(value=os.environ.get("GARMIN_EMAIL", ""))
        self.garmin_account_combo = ttk.Combobox(gf, textvariable=self.garmin_account_var, width=32,
                                                  values=self._saved_accounts())
        self.garmin_account_combo.grid(row=0, column=1, sticky="w", **pad)
        ttk.Button(gf, text="Обновить список", command=self._refresh_accounts) \
            .grid(row=0, column=2, sticky="w", **pad)
        acc = self._saved_accounts()
        if len(acc) == 1 and not self.garmin_account_var.get():
            self.garmin_account_var.set(acc[0])

        ttk.Label(gf, text="Пароль (нужен только при первом входе\nдля этого аккаунта — иначе используется\nсохранённый токен):") \
            .grid(row=1, column=0, sticky="w", **pad)
        self.garmin_password_var = tk.StringVar()
        ttk.Entry(gf, textvariable=self.garmin_password_var, width=32, show="*") \
            .grid(row=1, column=1, sticky="w", **pad)

        self.garmin_test_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(gf, text="Только первая неделя (--test)",
                         variable=self.garmin_test_var).grid(row=2, column=0, columnspan=2, sticky="w", **pad)

        self.garmin_clear_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(gf, text="Удалить все тренировки плана перед загрузкой (--clear)",
                         variable=self.garmin_clear_var).grid(row=3, column=0, columnspan=2, sticky="w", **pad)

        self.garmin_clear_past_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(gf, text="Удалить только прошедшие тренировки (--clear-past), до:",
                         variable=self.garmin_clear_past_var).grid(row=4, column=0, sticky="w", **pad)
        self.garmin_before_var = tk.StringVar()
        before_row = ttk.Frame(gf)
        before_row.grid(row=4, column=1, sticky="w", **pad)
        ttk.Entry(before_row, textvariable=self.garmin_before_var, width=15).pack(side="left")
        ttk.Button(before_row, text="📅", width=3,
                   command=lambda: CalendarPicker(self, self.garmin_before_var)).pack(side="left", padx=4)
        ttk.Label(gf, text="(ГГГГ-ММ-ДД, по умолчанию сегодня)").grid(row=5, column=0, columnspan=2, sticky="w", padx=6)

        ttk.Button(gf, text="Отправить в Garmin Connect", command=self._run_garmin) \
            .grid(row=6, column=0, columnspan=2, sticky="w", padx=6, pady=12)

        # --- intervals.icu ---
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

        # --- лог ---
        lf = ttk.LabelFrame(self, text="Лог")
        lf.pack(fill="both", expand=True, **pad)
        self.log = tk.Text(lf, height=14, wrap="word", state="disabled")
        self.log.pack(fill="both", expand=True, padx=6, pady=6)

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
    def _run_in_thread(self, fn, *args, **kwargs):
        if self.busy:
            messagebox.showwarning("Занято", "Уже выполняется другая операция.")
            return
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
            try:
                fn(*args, **kwargs)
                self.log_queue.put("\n[Готово]\n")
            except SystemExit as e:
                self.log_queue.put(f"\n[Остановлено] {e}\n")
            except Exception as e:
                self.log_queue.put(f"\n[Ошибка] {e}\n")
            finally:
                sys.stdout, sys.stderr = old_out, old_err
                self.busy = False

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
            password=self.garmin_password_var.get() or None,
            mfa_prompt=self._ask_mfa,
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


if __name__ == "__main__":
    App().mainloop()
