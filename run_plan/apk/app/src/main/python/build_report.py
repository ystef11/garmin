#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генератор HTML-отчёта по беговым тренировкам на основе:
  - garmin_running_ystef.db (SQLite: activities/intervals/lactate_threshold) — единственный
    обязательный вход.

Калибровочный JSON (calibration_profile*.json от garmin_calibration_fit.py) для построения
отчёта БОЛЬШЕ НЕ ТРЕБУЕТСЯ: калибровка дорожки (fit_treadmill_pace_calibration) и оптимальный
объём/спад EF (analyze_volume_ef_response, портировано из garmin_calibration_fit.py) считаются
напрямую по БД. json_path остаётся необязательным третьим аргументом только как ручной override
max_hr/rest_hr/sex (calib['meta']['load_params']) — если не передан, эти параметры оцениваются
по самим данным (max_hr — по факт. максимуму пульса в БД).

Запуск:
    python3 build_report.py <path_to_db> <output_html> [path_to_json]

ВАЖНО
1. калибровка дорожки: distance_m/avg_pace_s_per_km для sport=='treadmill_running'
корректируются коэффициентом из fit_treadmill_pace_calibration() ДО всех остальных расчётов —
это влияет на объём (п.7), EF/VDOT (п.4), ACWR по км (п.9), поиск оптимального пульса (п.2),
темп по зонам (п.8, п.9). Коррекция применяется и к activities, и к intervals (лапам).
2. калибровка сезонности ДО всех остальных расчётов
"""

import sys
import json
import math
import sqlite3
import base64
from io import BytesIO
from datetime import datetime

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
from matplotlib.patches import Patch
from matplotlib.colors import to_rgb

plt.rcParams["figure.facecolor"] = "white"
plt.rcParams["axes.facecolor"] = "white"
plt.rcParams["font.size"] = 10

TYPE_COLORS = {
    "easy": "#4C9F70",
    "long": "#3B7DD8",
    "interval": "#E0574C",
    "threshold": "#E0A62C",
    "mixed": "#9B6BC7",
    "marathon_tempo": "#E67E22",
}
TYPE_LABELS_RU = {
    "easy": "лёгкий",
    "long": "длительный",
    "interval": "интервалы",
    "threshold": "пороговый",
    "mixed": "смешанный",
    "marathon_tempo": "марафонские отрезки",
}

# Фиксированная категориальная палитра для графиков "наложение по годам" (п.4, п.6) — цвет
# закреплён за годом по порядку возрастания (2024 всегда первым цветом и т.д.), а не назначается
# циклически, чтобы один и тот же год был одним и тем же цветом на обоих графиках отчёта.
YEAR_PALETTE = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"]


def year_color_map(years):
    years_sorted = sorted({int(y) for y in years if y is not None and not (isinstance(y, float) and np.isnan(y))})
    return {y: YEAR_PALETTE[i % len(YEAR_PALETTE)] for i, y in enumerate(years_sorted)}


MONTH_ABBR_RU = ["янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"]

# Рекомендуемое распределение по пульсовым зонам для разных целевых дистанций (раздел 12 и
# интерактивный график render_zone_distance_comparison). Идея: чем короче дистанция, тем
# больше доля высокой интенсивности (Z4/Z5); чем длиннее (особенно ультра) — тем больше доля
# чистой аэробной базы (Z1/Z2), а "гоночный/специфичный темп" (Z3) смещается вниз, потому что
# на ультрах реальный соревновательный темп физиологически является аэробным, а не
# "марафонской" интенсивностью. Колонка 42 км — тот же якорь, что использовался в отчёте
# раньше (15/62/12/6/5), остальные дистанции достроены вокруг неё по этому принципу. Это
# эвристика (общепринятая логика периодизации по дистанции, Daniels/Pfitzinger/ультра-
# литература), не подгонка под фактические данные этого атлета.
DIST_KM = [3, 5, 10, 21, 42, 60, 80, 100, 120, 160]
DIST_TARGETS = {
    #        Z1  Z2  Z3  Z4  Z5
    3:   {"Z1": 10, "Z2": 40, "Z3": 7,  "Z4": 18, "Z5": 25},
    5:   {"Z1": 11, "Z2": 44, "Z3": 8,  "Z4": 17, "Z5": 20},
    10:  {"Z1": 12, "Z2": 49, "Z3": 10, "Z4": 17, "Z5": 12},
    21:  {"Z1": 15, "Z2": 54, "Z3": 12, "Z4": 13, "Z5": 6},
    42:  {"Z1": 15, "Z2": 62, "Z3": 12, "Z4": 6,  "Z5": 5},
    60:  {"Z1": 20, "Z2": 65, "Z3": 9,  "Z4": 4,  "Z5": 2},
    80:  {"Z1": 22, "Z2": 67, "Z3": 7,  "Z4": 3,  "Z5": 1},
    100: {"Z1": 24, "Z2": 68, "Z3": 5,  "Z4": 2,  "Z5": 1},
    120: {"Z1": 26, "Z2": 68, "Z3": 4,  "Z4": 2,  "Z5": 0},
    160: {"Z1": 28, "Z2": 68, "Z3": 3,  "Z4": 1,  "Z5": 0},
}
for _d, _t in DIST_TARGETS.items():
    assert sum(_t.values()) == 100, f"дистанция {_d}км: сумма долей должна быть 100%, получилось {sum(_t.values())}"

TREADMILL_SPORT = "treadmill_running"


# --------------------------------------------------------------------------
# УТИЛИТЫ
# --------------------------------------------------------------------------

def fig_to_base64(fig):
    buf = BytesIO()
    fig.savefig(buf, format="png", dpi=140, bbox_inches="tight")
    plt.close(fig)
    buf.seek(0)
    return base64.b64encode(buf.read()).decode("ascii")


def img_tag(b64, alt=""):
    return f'<img src="data:image/png;base64,{b64}" alt="{alt}" style="width:100%;max-width:1100px;display:block;margin:0 auto;">'


def fmt_pace(s_per_km):
    if s_per_km is None or (isinstance(s_per_km, float) and np.isnan(s_per_km)):
        return "-"
    m = int(s_per_km // 60)
    s = int(round(s_per_km - m * 60))
    if s == 60:
        m += 1
        s = 0
    return f"{m}:{s:02d}/км"


def load_data(db_path, json_path=None):
    """json_path необязателен (по умолчанию None): раньше отчёт требовал калибровочный JSON
    (calibration_profile*.json от garmin_calibration_fit.py) для калибровки дорожки и
    volume_ef_response — обе эти вещи теперь считаются напрямую по БД (см.
    fit_treadmill_pace_calibration и analyze_volume_ef_response ниже), калибровочный файл ни для
    чего из этого больше не нужен. json_path оставлен только как необязательный ручной override
    для max_hr/rest_hr/sex (calib['meta']['load_params']) — если файла нет, используются
    значения, оценённые по самим данным."""
    conn = sqlite3.connect(db_path)
    activities = pd.read_sql_query(
        "SELECT * FROM activities ORDER BY date", conn, parse_dates=["date"]
    )
    intervals = pd.read_sql_query("SELECT * FROM intervals", conn)
    lt = pd.read_sql_query(
        "SELECT * FROM lactate_threshold ORDER BY date", conn, parse_dates=["date"]
    )
    # wellness.rhr — нужен для карвоненовских (%HRR) пульсовых зон (см. build_zones, диалог
    # 2026-08-18): резерв пульса = max_hr - rhr, а не только max_hr, как раньше.
    wellness = pd.read_sql_query(
        "SELECT date, rhr FROM wellness WHERE rhr IS NOT NULL ORDER BY date", conn,
        parse_dates=["date"]
    )
    conn.close()

    calib = {}
    if json_path:
        with open(json_path, encoding="utf-8") as f:
            calib = json.load(f)

    return activities, intervals, lt, wellness, calib


# --------------------------------------------------------------------------
# КАЛИБРОВКА БЕГОВОЙ ДОРОЖКИ
# --------------------------------------------------------------------------
# Раньше коэффициент коррекции читался готовым из calibration_profile.json (поле
# treadmill_calibration), а комментарий ссылался на garmin_calibration_fit.py,
# fit_treadmill_pace_calibration. Проверено: такой функции (и вообще какой-либо логики
# калибровки дорожки) в garmin_calibration_fit.py НЕТ и не было в git-истории репозитория —
# комментарий был устаревшим/ошибочным, а JSON, судя по всему, когда-то посчитан отдельным
# разовым скриптом, которого сейчас нет на диске. Метод ниже восстановлен по текстовому
# описанию в поле treadmill_calibration.note того JSON (лог-линейная регрессия EF~пульс,
# уличные тренировки vs тредмил, в пересекающемся диапазоне пульса) и считается прямо по БД —
# calibration_profile.json для этого больше не нужен.

def fit_treadmill_pace_calibration(activities, min_n=30, clamp_range=(0.85, 1.35)):
    """Оценивает коэффициент коррекции distance_m для treadmill_running: во сколько раз нужно
    растянуть тредмильную дистанцию, чтобы EF (скорость/пульс) тредмильных тренировок легла на
    EF уличных тренировок при том же пульсе (предпосылка: EF человека при данном пульсе не
    должна зависеть от типа поверхности — если лента показывает более низкую EF при том же
    пульсе, значит, она занижает пройденную дистанцию/скорость).

    Сравнение — только в диапазоне пульса, где есть И уличные, И тредмильные тренировки
    (иначе сравниваются разные по тяжести усилия). В обеих группах отдельно строится
    лог-линейная регрессия log(EF) ~ пульс, коэффициент = отношение предсказанных EF-кривых,
    усреднённое по сетке пульса в пересекающемся диапазоне. Итог клампится в clamp_range —
    чтобы шумная регрессия по малому числу тренировок не могла задать физически
    неправдоподобную поправку (лента реально может занижать/завышать дистанцию на единицы-
    десятки процентов, не в разы)."""
    df = activities[
        activities["avg_hr"].notna() & activities["distance_m"].notna() &
        activities["duration_s"].notna() & (activities["distance_m"] > 500) &
        (activities["duration_s"] > 0) &
        activities["type_guess"].isin(["easy", "long"])
    ].copy()
    df["speed"] = df["distance_m"] / df["duration_s"]
    df["ef"] = df["speed"] / df["avg_hr"]

    outdoor = df[df["sport"] != TREADMILL_SPORT]
    treadmill = df[df["sport"] == TREADMILL_SPORT]
    if len(outdoor) < min_n or len(treadmill) < min_n:
        return {
            "applied": False,
            "reason": f"недостаточно данных для сравнения (уличных={len(outdoor)}, "
                      f"тредмильных={len(treadmill)}, нужно >= {min_n} каждой)",
        }

    lo = max(outdoor["avg_hr"].min(), treadmill["avg_hr"].min())
    hi = min(outdoor["avg_hr"].max(), treadmill["avg_hr"].max())
    outdoor_m = outdoor[(outdoor["avg_hr"] >= lo) & (outdoor["avg_hr"] <= hi)]
    treadmill_m = treadmill[(treadmill["avg_hr"] >= lo) & (treadmill["avg_hr"] <= hi)]
    if len(outdoor_m) < min_n or len(treadmill_m) < min_n:
        return {
            "applied": False,
            "reason": f"недостаточно точек в пересекающемся диапазоне пульса "
                      f"{lo:.0f}-{hi:.0f} (уличных={len(outdoor_m)}, тредмильных={len(treadmill_m)}, "
                      f"нужно >= {min_n} каждой)",
        }

    def log_ef_fit(sub):
        return np.polyfit(sub["avg_hr"].values.astype(float), np.log(sub["ef"].values), 1)

    coef_out = log_ef_fit(outdoor_m)
    coef_tm = log_ef_fit(treadmill_m)

    hr_grid = np.linspace(lo, hi, 50)
    log_ratio = (coef_out[0] * hr_grid + coef_out[1]) - (coef_tm[0] * hr_grid + coef_tm[1])
    raw_ratio = float(np.exp(np.mean(log_ratio)))

    factor = min(max(raw_ratio, clamp_range[0]), clamp_range[1])
    clamped = abs(factor - raw_ratio) > 1e-9

    return {
        "applied": True,
        "correction_factor": round(factor, 3),
        "correction_pct": round((factor - 1) * 100, 1),
        "direction": ("лента занижает темп (реальная дистанция больше репортированной)" if factor > 1
                      else "лента завышает темп (реальная дистанция меньше репортированной)"),
        "n_outdoor": int(len(outdoor)),
        "n_treadmill": int(len(treadmill)),
        "n_treadmill_hr_matched": int(len(treadmill_m)),
        "outdoor_hr_range": [round(float(lo), 1), round(float(hi), 1)],
        "raw_ratio_before_clamp": round(raw_ratio, 3) if clamped else None,
        "note": (
            f"distance_m на treadmill_running умножены на {factor:.3f} "
            f"({'+' if factor >= 1 else ''}{(factor - 1) * 100:.1f}%), оценено по "
            f"{len(treadmill_m)} тредмильным тренировкам с ЧСС в диапазоне {lo:.0f}-{hi:.0f} "
            f"(сравнением с EF уличных пробежек того же диапазона ЧСС, лог-линейная регрессия "
            f"EF~avg_hr). Это оценка по прокси (EF), не прямое измерение."
        ),
        "source": "auto (EF vs уличные пробежки, HR-matched; посчитано напрямую по БД)",
    }

def apply_treadmill_calibration(activities, intervals, tc):
    """Домножает distance_m (и пересчитывает avg_pace_s_per_km) для treadmill_running на
    коэффициент из tc (результат fit_treadmill_pace_calibration(), посчитанный напрямую по БД —
    calibration_profile.json больше не требуется). Затрагивает и activities, и intervals (по
    лапам внутри тредмильных тренировок) — иначе п.8 (темп по зонам из интервалов) считался бы
    по некалиброванным сплитам. Исходные значения сохраняются в *_raw."""
    factor = tc.get("correction_factor")
    applied = tc.get("applied", False)

    activities = activities.copy()
    intervals = intervals.copy()
    activities["distance_m_raw"] = activities["distance_m"]
    activities["avg_pace_s_per_km_raw"] = activities["avg_pace_s_per_km"]

    if not applied or not factor:
        return activities, intervals, {"applied": False}

    is_tm = activities["sport"] == TREADMILL_SPORT
    activities.loc[is_tm, "distance_m"] = activities.loc[is_tm, "distance_m"] * factor
    # темп = время/дистанция -> при росте дистанции на factor темп (с/км) падает во столько же раз
    activities.loc[is_tm, "avg_pace_s_per_km"] = activities.loc[is_tm, "avg_pace_s_per_km_raw"] / factor

    tm_ids = set(activities.loc[is_tm, "activity_id"])
    is_tm_lap = intervals["activity_id"].isin(tm_ids)
    intervals["distance_m_raw"] = intervals["distance_m"]
    intervals["avg_pace_s_per_km_raw"] = intervals["avg_pace_s_per_km"]
    intervals.loc[is_tm_lap, "distance_m"] = intervals.loc[is_tm_lap, "distance_m"] * factor
    intervals.loc[is_tm_lap, "avg_pace_s_per_km"] = intervals.loc[is_tm_lap, "avg_pace_s_per_km_raw"] / factor

    info = {
        "applied": True,
        "factor": factor,
        "pct": tc.get("correction_pct"),
        "n_treadmill_activities": int(is_tm.sum()),
        "note": tc.get("note", ""),
    }
    return activities, intervals, info


def apply_grade_adjustment(activities, intervals):
    """Делает темп с учётом уклона (GAP, Garmin-овское avg_grade_adjusted_pace_s_per_km)
    ОСНОВНЫМ значением avg_pace_s_per_km для ВСЕХ дальнейших расчётов (HR-эффективность,
    построение зон/оптимального пульса лёгких, детекция срыва темпа на гонке, decoupling,
    классификация рабочих отрезков, темп по зонам и т.п.) — и на уровне лапов, и на уровне
    активности в целом.

    Зачем централизованно, а не точечно в отдельных функциях: на холмистой трассе (у нас
    медиана ~11 м/км суммарного набора+сброса на км) до 44% лапов расходятся с GAP больше
    чем на 5 с/км, до 17% — больше чем на 10 с/км. Без коррекции рельеф подмешивается в
    любой анализ, где темп используется как прокси усилия/усталости, и любая новая метрика,
    написанная "как обычно" через avg_pace_s_per_km, унаследует этот шум. Централизация здесь
    гарантирует, что новый код по умолчанию получает уже очищенный от рельефа темп.

    GAP есть только для уличных пробежек (sport == 'running'; на дорожке/indoor уклона нет,
    Garmin туда GAP не пишет). Где GAP отсутствует (дорожка, indoor, старые записи без него) —
    остаётся обычный (откалиброванный для дорожки) темп, поведение не меняется.

    Исходный "плоский" темп (без поправки на уклон, но с учётом калибровки дорожки) сохраняется
    в avg_pace_s_per_km_flat — используем его там, где нужен именно реальный, "как было
    показано на часах" темп (например, отображение в таблицах для человека), а не темп как
    прокси физиологического усилия."""
    activities = activities.copy()
    intervals = intervals.copy()

    activities["avg_pace_s_per_km_flat"] = activities["avg_pace_s_per_km"]
    if "avg_grade_adjusted_pace_s_per_km" in activities.columns:
        mask = activities["avg_grade_adjusted_pace_s_per_km"].notna()
        activities.loc[mask, "avg_pace_s_per_km"] = activities.loc[mask, "avg_grade_adjusted_pace_s_per_km"]
        n_act_adjusted = int(mask.sum())
    else:
        n_act_adjusted = 0

    intervals["avg_pace_s_per_km_flat"] = intervals["avg_pace_s_per_km"]
    if "avg_grade_adjusted_pace_s_per_km" in intervals.columns:
        mask_iv = intervals["avg_grade_adjusted_pace_s_per_km"].notna()
        intervals.loc[mask_iv, "avg_pace_s_per_km"] = intervals.loc[mask_iv, "avg_grade_adjusted_pace_s_per_km"]
        n_iv_adjusted = int(mask_iv.sum())
    else:
        n_iv_adjusted = 0

    info = {
        "n_activities_adjusted": n_act_adjusted,
        "n_activities_total": int(len(activities)),
        "n_laps_adjusted": n_iv_adjusted,
        "n_laps_total": int(len(intervals)),
        "note": (
            f"avg_pace_s_per_km переведён на grade-adjusted pace (GAP) везде, где он есть "
            f"({n_act_adjusted}/{len(activities)} активностей, {n_iv_adjusted}/{len(intervals)} "
            f"лапов — все уличные пробежки). Плоский темп сохранён в avg_pace_s_per_km_flat "
            f"для отображения."
        ),
    }
    return activities, intervals, info


# --------------------------------------------------------------------------
# СЕЗОННАЯ КАЛИБРОВКА EF (портировано из garmin_calibration_fit.py, seasonal_detrend)
# --------------------------------------------------------------------------
# Гармоническая регрессия log(EF) по дню года (годовой + полугодовой цикл), ТОЛЬКО по уличным
# тренировкам (sport != treadmill_running) — на дорожке нет физического "зима хуже лета" эффекта
# (температура/ветер/покрытие контролируются помещением), поэтому подмешивание тредмильных точек
# только размывает оценку шумом. Тредмильные точки возвращаются без изменения (они уже
# откалиброваны отдельно, см. apply_treadmill_calibration выше). Если охвата данных недостаточно
# (< min_span_days или < min_points уличных точек) — коррекция не применяется (applied=False).

def seasonal_detrend_ef(dates, values, is_outdoor, min_span_days=300, min_points=20):
    values = np.asarray(values, dtype=float)
    is_outdoor = np.asarray(is_outdoor, dtype=bool)
    dates = pd.to_datetime(pd.Series(dates)).dt.to_pydatetime()

    dates_out = [d for d, o in zip(dates, is_outdoor) if o]
    values_out = values[is_outdoor]
    span = (max(dates_out) - min(dates_out)).days if len(dates_out) else 0
    if span < min_span_days or len(values_out) < min_points:
        return values, {
            "applied": False,
            "reason": f"недостаточно охвата для годового цикла по уличным тренировкам "
                      f"(span={span}д, n_outdoor={len(values_out)}, нужно >= {min_span_days}д и >= {min_points})",
        }

    doy_out = np.array([d.timetuple().tm_yday for d in dates_out], dtype=float)
    w = 2 * np.pi * doy_out / 365.25
    X = np.column_stack([np.ones_like(w), np.sin(w), np.cos(w), np.sin(2 * w), np.cos(2 * w)])
    logv = np.log(values_out)
    coef, *_ = np.linalg.lstsq(X, logv, rcond=None)

    doy_all = np.array([d.timetuple().tm_yday for d in dates], dtype=float)
    w_all = 2 * np.pi * doy_all / 365.25
    X_all = np.column_stack([np.ones_like(w_all), np.sin(w_all), np.cos(w_all), np.sin(2 * w_all), np.cos(2 * w_all)])
    seasonal_log_all = X_all @ coef - coef[0]
    seasonal_log_all[~is_outdoor] = 0.0  # тредмильные точки не трогаем
    adjusted = values / np.exp(seasonal_log_all)

    doy_grid = np.arange(1, 367, dtype=float)
    wg = 2 * np.pi * doy_grid / 365.25
    Xg = np.column_stack([np.ones_like(wg), np.sin(wg), np.cos(wg), np.sin(2 * wg), np.cos(2 * wg)])
    seasonal_curve = Xg @ coef - coef[0]
    peak_doy = int(doy_grid[int(np.argmax(seasonal_curve))])
    trough_doy = int(doy_grid[int(np.argmin(seasonal_curve))])
    drop_pct = round(float((np.exp(seasonal_curve.max()) - np.exp(seasonal_curve.min())) / np.exp(seasonal_curve.max()) * 100), 1)
    return adjusted, {
        "applied": True,
        "peak_around": pd.Timestamp(2001, 1, 1) + pd.Timedelta(days=peak_doy - 1),
        "trough_around": pd.Timestamp(2001, 1, 1) + pd.Timedelta(days=trough_doy - 1),
        "drop_pct": drop_pct,
    }


def add_seasonally_adjusted_ef(easy_df):
    """Добавляет колонку ef_seasadj к датафрейму лёгких пробежек (нужны колонки date, ef, sport)."""
    easy_df = easy_df.copy()
    is_outdoor = (easy_df["sport"] != TREADMILL_SPORT).values
    adjusted, info = seasonal_detrend_ef(easy_df["date"].values, easy_df["ef"].values, is_outdoor)
    easy_df["ef_seasadj"] = adjusted
    return easy_df, info


# --------------------------------------------------------------------------
# 1. ОБЪЁМ ПО НЕДЕЛЯМ
# --------------------------------------------------------------------------

def weekly_volume(activities):
    df = activities.copy()
    df["week"] = df["date"].dt.to_period("W-SUN").apply(lambda p: p.start_time)
    wk = df.groupby("week")["distance_m"].sum() / 1000.0
    all_weeks = pd.date_range(wk.index.min(), wk.index.max(), freq="7D")
    wk = wk.reindex(all_weeks, fill_value=0.0)
    return wk


def plot_weekly_volume(wk_km, xlim=None):
    fig, ax = plt.subplots(figsize=(11, 3.2))
    ax.bar(wk_km.index, wk_km.values, width=5.5, color="#3B7DD8", alpha=0.85)
    roll4 = wk_km.rolling(4, min_periods=1).mean()
    ax.plot(roll4.index, roll4.values, color="#1A3A5C", linewidth=2, label="Скольз. 4-нед. среднее")
    ax.set_title("7. Объём бега по неделям (км, с учётом калибровки дорожки)")
    ax.set_ylabel("км/неделю")
    ax.legend(loc="upper left", fontsize=8)
    ax.xaxis.set_major_locator(mdates.MonthLocator(interval=2))
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%b %y"))
    if xlim:
        ax.set_xlim(*xlim)
    fig.autofmt_xdate()
    return fig_to_base64(fig)


# --------------------------------------------------------------------------
# 2. ДОЛИ ТИПОВ ТРЕНИРОВОК ПО НЕДЕЛЯМ (+ доля марафонских отрезков)
# --------------------------------------------------------------------------

MARATHON_PACE_BAND_S_PER_KM = (285, 335)  # см. диалог: фактический темп гонок Утрехт (299) и
                                           # Гронинген (317) — реальный марафонский темп атлета;
                                           # используется для темповых/интервальных вставок (см.
                                           # interval_threshold_vdot_points и т.п.), но БОЛЬШЕ НЕ
                                           # для marathon_time_per_activity (см. ниже, диалог
                                           # 2026-08-20 — статичный темповый коридор не подходит
                                           # для тренировок из периодов с другой формой).


def marathon_time_per_activity(activities, intervals, zones):
    """Для каждой тренировки считает суммарную длительность лапов внутри long/easy с пульсом
    внутри HR-зоны 'Z3 — марафонский темп' (см. build_zones) — это и есть марафонские вставки
    внутри длительных/лёгких тренировок.

    ИЗМЕНЕНО 2026-08-20 (см. диалог, тренировка 21872979699 от 2026-02-15): раньше отбор шёл по
    ФИКСИРОВАННОМУ темповому коридору MARATHON_PACE_BAND_S_PER_KM (285-335 с/км), откалиброванному
    по фактическому темпу недавних гонок (Утрехт 299, Гронинген 317). Эта тренировка — 191 минута
    с устойчивым пульсом 142-160 (то есть по сути ровно Z3) весь забег, темп при этом 346-420 с/км —
    полностью МИМО фиксированного коридора, потому что в феврале 2026 (при более низкой форме, см.
    рост VDOT с ~31 до ~48 за 2 года) 'усилие уровня марафона' физически означало заметно более
    медленный абсолютный темп. Статичный темповый коридор, один на всю двухлетнюю историю, такие
    тренировки из периодов другой формы систематически не ловит. Пульсовая зона Z3 (в отличие от
    темпового коридора) НЕ завязана на конкретный темп — она сама уже подстроена под этого атлета
    (Карвонен %HRR + ПАНО, см. build_zones), поэтому одинаково применима к любому периоду истории.

    Темповые/интервальные вставки (не марафонские) по-прежнему считаются по темповым коридорам
    (MARATHON_PACE_BAND_S_PER_KM и др. — см. interval_threshold_vdot_points и т.п.) — решение
    менять именно и только marathon_time_per_activity, см. диалог 2026-08-20."""
    z3 = next(z for z in zones if z[0].startswith("Z3"))
    hr_lo, hr_hi = z3[1], z3[2]
    iv = intervals[
        intervals["activity_id"].isin(
            activities.loc[activities["type_guess"].isin(["long", "easy"]), "activity_id"]
        ) &
        intervals["avg_hr"].notna() &
        (intervals["avg_hr"] >= hr_lo) &
        (intervals["avg_hr"] <= hr_hi) &
        (intervals["distance_m"] > 300)
    ]
    per_act = iv.groupby("activity_id")["duration_s"].sum().rename("marathon_time_s")
    out = activities[["activity_id", "duration_s"]].merge(per_act, on="activity_id", how="left")
    out["marathon_time_s"] = out["marathon_time_s"].fillna(0.0).clip(upper=out["duration_s"])
    return out.set_index("activity_id")["marathon_time_s"]


def weekly_type_shares(activities, intervals, zones):
    df = activities.copy()
    df["week"] = df["date"].dt.to_period("W-SUN").apply(lambda p: p.start_time)
    df["type_g"] = df["type_guess"].fillna("unknown")

    mara = marathon_time_per_activity(activities, intervals, zones)
    df = df.merge(mara.rename("marathon_time_s"), on="activity_id", how="left")
    df["marathon_time_s"] = df["marathon_time_s"].fillna(0.0)
    df["type_duration_s"] = df["duration_s"] - df["marathon_time_s"]  # остаток исходного типа

    pivot_type = df.pivot_table(index="week", columns="type_g", values="type_duration_s",
                                 aggfunc="sum", fill_value=0.0)
    pivot_mara = df.groupby("week")["marathon_time_s"].sum().rename("marathon_tempo")

    pivot = pivot_type.join(pivot_mara, how="outer").fillna(0.0)
    all_weeks = pd.date_range(pivot.index.min(), pivot.index.max(), freq="7D")
    pivot = pivot.reindex(all_weeks, fill_value=0.0)
    shares = pivot.div(pivot.sum(axis=1).replace(0, np.nan), axis=0).fillna(0.0)
    return shares


def plot_weekly_type_shares(shares, xlim=None):
    fig, ax = plt.subplots(figsize=(11, 3.2))
    preferred = ["easy", "long", "marathon_tempo", "threshold", "interval", "mixed"]
    cols = [c for c in preferred if c in shares.columns]
    others = [c for c in shares.columns if c not in cols]
    cols = cols + others
    colors = [TYPE_COLORS.get(c, "#999999") for c in cols]
    ax.stackplot(shares.index, [shares[c].values * 100 for c in cols],
                 labels=[TYPE_LABELS_RU.get(c, c) for c in cols], colors=colors, alpha=0.85)
    ax.set_title("10. Доли типов тренировок по неделям, включая марафонские отрезки (% от времени)")
    ax.set_ylabel("%")
    ax.set_ylim(0, 100)
    ax.legend(loc="upper left", ncol=6, fontsize=7.5, bbox_to_anchor=(0, 1.28))
    ax.xaxis.set_major_locator(mdates.MonthLocator(interval=2))
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%b %y"))
    if xlim:
        ax.set_xlim(*xlim)
    fig.autofmt_xdate()
    return fig_to_base64(fig)


# --------------------------------------------------------------------------
# 3. ПАНО ПО НЕПРЕРЫВНЫМ ЭФФОРТАМ >= ~35 МИН
# --------------------------------------------------------------------------

def garmin_pano_estimate(lt, recent_days=120):
    """ПАНО напрямую из истории Гармина (lactate_threshold, считается Гармином через Firstbeat
    по фактическим тренировкам) — это собственный показатель Гармина, а не наша реконструкция,
    поэтому он приоритетный источник ПАНО. Берём среднее threshold_hr за последние recent_days
    дней от последней доступной записи (устойчивее к разовому шумному замеру, чем последняя
    точка). Если данных нет вообще — возвращает (None, info) и вызывающий код переходит на
    резервную оценку по непрерывным эффортам (см. pano_table)."""
    df = lt[lt["threshold_hr"].notna()].copy()
    if not len(df):
        return None, {"source": "none", "n": 0}
    cutoff = df["date"].max() - pd.Timedelta(days=recent_days)
    recent = df[df["date"] >= cutoff]
    if not len(recent):
        recent = df
    pano = int(round(recent["threshold_hr"].mean()))
    return pano, {
        "source": "garmin_lactate_threshold",
        "n": int(len(recent)),
        "window_days": recent_days,
        "last_date": df["date"].max().strftime("%Y-%m-%d"),
        "mean": pano,
    }


def estimate_resting_hr(wellness, recent_days=90, fallback_rhr=None):
    """Пульс покоя для карвоненовского резерва (%HRR) — среднее wellness.rhr за последние
    recent_days дней от последней записи (устойчивее разового замера, тот же приём, что и
    garmin_pano_estimate). Если wellness пуст/недоступен — fallback_rhr (например, из
    calib['meta']['load_params']), а если и его нет — дефолт 50."""
    df = wellness[wellness["rhr"].notna()].copy() if wellness is not None and len(wellness) else pd.DataFrame()
    if not len(df):
        rhr = fallback_rhr if fallback_rhr else 50
        return rhr, {"source": "fallback", "n": 0}
    cutoff = df["date"].max() - pd.Timedelta(days=recent_days)
    recent = df[df["date"] >= cutoff]
    if not len(recent):
        recent = df
    rhr = float(recent["rhr"].mean())
    return rhr, {
        "source": "wellness.rhr",
        "n": int(len(recent)),
        "window_days": recent_days,
        "last_date": df["date"].max().strftime("%Y-%m-%d"),
        "mean": round(rhr, 1),
    }


def estimate_max_hr(activities, fallback_max_hr=None, abs_ceiling=215, max_spread=60):
    """Устойчивая оценка max_hr — защита от разовых выбросов датчика (см. диалог 2026-08-18:
    на одной из баз найден max_hr=230 при avg_hr=153 в 'лёгкой' тредмильной тренировке — разрыв
    77 уд/мин, физиологически невозможный скачок на фоне низкого среднего усилия; типичный
    артефакт оптического пульсометра на запястье от вибрации тредмила).

    Берём max(max_hr) НЕ по всем тренировкам подряд, а только по тем, где:
      1) max_hr <= abs_ceiling (215 — почти никто не превышает это даже в молодости/спринте);
      2) разрыв (max_hr - avg_hr) в пределах одной тренировки <= max_spread (60 уд/мин) —
         больший разрыв на фоне общего среднего пульса тренировки означает, что пик, скорее
         всего, не 'заработан' самой тренировкой, а является выбросом.
    Порог max_spread=60 подобран так, чтобы не резать легитимные фартлек/интервальные тренировки
    с большими перепадами (разброс там обычно до ~45-50), но отсекать явные скачки вроде
    230/153=77.

    Если после фильтра не осталось валидных значений — fallback_max_hr (например, из
    calibration_profile), иначе дефолт 195, как и раньше."""
    df = activities.dropna(subset=["avg_hr", "max_hr"]).copy()
    if len(df):
        df["spread"] = df["max_hr"] - df["avg_hr"]
        clean = df[(df["max_hr"] <= abs_ceiling) & (df["spread"] <= max_spread)]
    else:
        clean = df
    if len(clean):
        max_hr = int(round(clean["max_hr"].max()))
        n_excluded = len(df) - len(clean)
        return max_hr, {"source": "activities.max_hr (после фильтра выбросов)",
                         "n_excluded": int(n_excluded)}
    if fallback_max_hr:
        return int(round(fallback_max_hr)), {"source": "fallback", "n_excluded": 0}
    return 195, {"source": "default", "n_excluded": 0}


def pano_table(activities, min_hr=None):
    # Только НЕПРЕРЫВНЫЕ эффорты (не интервальная структура с паузами/восстановлением):
    # type_guess == 'threshold' — единственная категория, где алгоритм фиксирует устойчивое
    # плато пульса на непрерывном отрезке (см. type_reason: "устойчивый пульс X >= порога зоны Y").
    # Порог "устойчиво высокий пульс" больше не хардкодится: если известно ПАНО (обычно из
    # garmin_pano_estimate), берём порог с отступом вниз от него (плато незадолго до ПАНО,
    # а не обязательно на его уровне); если ПАНО неизвестно — резервная оценка по 85-му
    # перцентилю пульса среди собственных активностей атлета.
    if min_hr is None:
        min_hr = activities["avg_hr"].quantile(0.85) if activities["avg_hr"].notna().any() else None
    df = activities[
        (activities["duration_s"] >= 2100) &            # >= 35 минут
        (activities["duration_s"] <= 3900) &             # <= 65 минут (не марафон/полумарафон)
        (activities["avg_hr"].notna()) &
        (min_hr is None or activities["avg_hr"] >= min_hr) &
        (activities["type_guess"] == "threshold")
    ].copy()
    df = df.sort_values("date")
    out = df[["date", "name", "avg_hr", "avg_pace_s_per_km_flat", "duration_s"]].copy()
    out["Дата"] = out["date"].dt.strftime("%Y-%m-%d")
    out["Название"] = out["name"]
    out["Пульс"] = out["avg_hr"].astype(int)
    # реальный (плоский) темп для человека — GAP тут был бы контринтуитивен в таблице
    out["Темп"] = out["avg_pace_s_per_km_flat"].apply(fmt_pace)
    out["Длительность"] = (out["duration_s"] / 60).round(0).astype(int).astype(str) + " мин"
    out = out[["Дата", "Название", "Пульс", "Темп", "Длительность"]]
    pano_estimate = df["avg_hr"].mean() if len(df) else np.nan
    return out, pano_estimate, df


# --------------------------------------------------------------------------
# 4a. EF и VDOT (по гонкам) ПО ВРЕМЕНИ
# --------------------------------------------------------------------------

def compute_easy_ef(activities):
    """Единая точка расчёта EF для лёгких пробежек — с сезонной поправкой (см. add_seasonally_adjusted_ef).
    Используется и для тренда EF (п.4), и для поиска пика эффективности по пульсу (п.2), чтобы
    оба расчёта были на одних и тех же (сезонно скорректированных) числах."""
    easy = activities[
        (activities["type_guess"] == "easy") &
        activities["avg_hr"].notna() & activities["avg_pace_s_per_km"].notna() &
        (activities["distance_m"] > 2000)
    ].copy()
    easy["speed"] = 1000.0 / easy["avg_pace_s_per_km"]
    easy["ef"] = easy["speed"] / easy["avg_hr"] * 1000
    easy, seasonal_info = add_seasonally_adjusted_ef(easy)
    return easy, seasonal_info


def weekly_ef(easy):
    easy = easy.copy()
    easy["week"] = easy["date"].dt.to_period("W-SUN").apply(lambda p: p.start_time)
    wk_ef = easy.groupby("week").apply(lambda g: np.average(g["ef_seasadj"], weights=g["distance_m"]))
    all_weeks = pd.date_range(wk_ef.index.min(), wk_ef.index.max(), freq="7D")
    wk_ef_full = wk_ef.reindex(all_weeks).interpolate(limit_direction="both")
    wk_ef_roll = wk_ef_full.rolling(4, min_periods=1, center=True).mean()
    return wk_ef_roll, wk_ef


def daniels_vdot(distance_m, time_s):
    """Приближённая формула Джека Дэниэлса для VDOT."""
    t_min = time_s / 60.0
    v = distance_m / t_min  # м/мин
    pct_max = 0.8 + 0.1894393 * np.exp(-0.012778 * t_min) + 0.2989558 * np.exp(-0.1932605 * t_min)
    vo2 = -4.60 + 0.182258 * v + 0.000104 * v ** 2
    return vo2 / pct_max


def detect_race_blowup(activity_id, intervals, min_clean_km=15.0,
                        slowdown_ratio=1.20, hr_rise_required=1.0):
    """Ищет 'срыв' темпа на гоночной дистанции, НЕ объяснимый физиологическим утомлением
    (см. диалог — Groningen 2026: км 34-40 темп упал с 300 до 379 с/км, а пульс при этом
    НЕ вырос, а УПАЛ со 148 до 129 — это подпись вынужденной остановки/перехода на шаг
    (ЖКТ, механика, и т.п.), а не 'стены': при настоящем гликогеновом/кардио-отказе пульс
    обычно держится высоким или растёт, а не падает одновременно с замедлением).

    Алгоритм: идём по км-сплитам, сравниваем каждый лап со скользящей медианой темпа/пульса
    по уже пройденной 'чистой' части. Если темп резко проседает (>slowdown_ratio от базового)
    БЕЗ соответствующего роста пульса (пульс <= базовый + hr_rise_required) — это точка срыва.
    Возвращает (clean_distance_m, clean_time_s, blowup_km) или None, если срыва не найдено
    или чистый участок короче min_clean_km."""
    iv = intervals[
        (intervals["activity_id"] == activity_id) &
        intervals["avg_hr"].notna() & intervals["avg_pace_s_per_km"].notna() &
        (intervals["distance_m"] > 500) & (intervals["distance_m"] < 2000)
    ].sort_values("idx").reset_index(drop=True)
    if len(iv) < 15:
        return None

    cum_dist = iv["distance_m"].cumsum()
    cum_time = iv["duration_s"].cumsum()

    blowup_idx = None
    for i in range(8, len(iv)):  # первые км не трогаем — старт ещё не показателен
        baseline_pace = iv.loc[max(0, i - 5):i - 1, "avg_pace_s_per_km"].median()
        baseline_hr = iv.loc[max(0, i - 5):i - 1, "avg_hr"].median()
        this_pace = iv.loc[i, "avg_pace_s_per_km"]
        this_hr = iv.loc[i, "avg_hr"]
        if this_pace >= baseline_pace * slowdown_ratio and this_hr <= baseline_hr + hr_rise_required:
            blowup_idx = i
            break

    if blowup_idx is None:
        return None

    clean_dist = cum_dist.iloc[blowup_idx - 1]
    clean_time = cum_time.iloc[blowup_idx - 1]
    if clean_dist < min_clean_km * 1000:
        return None

    return {
        "clean_distance_m": clean_dist,
        "clean_time_s": clean_time,
        "blowup_km": round(clean_dist / 1000, 1),
        "total_km": round(cum_dist.iloc[-1] / 1000, 1),
    }


def race_vdot_points(activities, pano, intervals=None):
    """VDOT считаем только по гонкам, где эффорт подтверждён пульсом, а не только названием.
    Порог зависит от ДИСТАНЦИИ, а не от фиксированной границы зоны: чем короче гонка, тем выше
    ожидаемый % от ПАНО у эффорта в полную силу (см. диалог — Rotterdam 'Зеленый марафон',
    5.1км при HR=142 = 79% от ПАНО, это Z2/лёгкий темп, а не гонка; для 5-10км в полную силу
    ожидается >=95% ПАНО, для полу-марафонных дистанций >=90%, для полного марафона >=80%,
    т.к. марафон физиологически бежится существенно ниже порога на всей дистанции).

    Дополнительно (см. диалог, Groningen 2026): если на дистанции обнаружен 'срыв' темпа без
    роста пульса (см. detect_race_blowup — сигнатура ЖКТ/непрофильной причины, а не усталости),
    ДЛЯ ТАКИХ ГОНОК ПРИОРИТЕТНОЙ становится VDOT по чистому участку ДО срыва, а не по полной
    дистанции — полная дистанция занижает истинную форму на момент гонки."""
    candidates = activities[
        ((activities["name"].str.contains("Race|Марафон|марафон|Marathon", case=False, na=False)) |
         (activities["distance_m"] >= 40000)) &  # полная марафонская дистанция — race-кандидат
                                                   # независимо от названия (см. диалог: 'Marathon'
                                                   # латиницей и 'Utrecht Белые Ночи' без ключевых
                                                   # слов пропускались старым regex-фильтром)
        (activities["distance_m"] > 4000) &
        activities["duration_s"].notna() &
        activities["avg_hr"].notna()
    ].copy()

    def min_pct_pano(distance_m):
        if distance_m <= 12000:
            return 0.95
        elif distance_m <= 25000:
            return 0.90
        else:
            return 0.80

    candidates["min_pct_required"] = candidates["distance_m"].apply(min_pct_pano)
    candidates["pct_of_pano"] = candidates["avg_hr"] / pano
    is_real_effort = candidates["pct_of_pano"] >= candidates["min_pct_required"]

    races = candidates[is_real_effort].copy()
    excluded = candidates[~is_real_effort].copy()

    races["vdot_full"] = races.apply(lambda r: daniels_vdot(r["distance_m"], r["duration_s"]), axis=1)
    races["blowup_detected"] = False
    races["blowup_km"] = np.nan
    races["vdot_clean"] = np.nan
    races["vdot"] = races["vdot_full"]

    if intervals is not None:
        for idx, r in races.iterrows():
            info = detect_race_blowup(r["activity_id"], intervals)
            if info is not None:
                vdot_clean = daniels_vdot(info["clean_distance_m"], info["clean_time_s"])
                races.loc[idx, "blowup_detected"] = True
                races.loc[idx, "blowup_km"] = info["blowup_km"]
                races.loc[idx, "vdot_clean"] = vdot_clean
                races.loc[idx, "vdot"] = vdot_clean  # приоритет чистому участку для тренда

    races = races.sort_values("date")
    excluded = excluded.sort_values("date")
    return races[["date", "name", "distance_m", "duration_s", "avg_hr", "vdot_full",
                   "blowup_detected", "blowup_km", "vdot_clean", "vdot"]], \
           excluded[["date", "name", "distance_m", "duration_s", "avg_hr", "pct_of_pano", "min_pct_required"]]


def interval_threshold_vdot_points(activities, intervals, pano=None, min_conf=0.15,
                                    pano_corr_a=-44.841, pano_corr_b=40.973, pano_corr_clip=0.20):
    """Вспомогательная (гораздо более шумная, чем гоночная) оценка VDOT по интервальным и
    пороговым тренировкам — заполняет пробелы там, где гонок не было вовсе (см. диалог —
    январь 2026: провал EF/объёма без единой гонки рядом, оценить изменение формы в моменте
    можно было только по 4b (ПАНО) или вот этим путём).

    Метод:
    1. Внутри каждой interval/threshold-тренировки лапы с темпом заметно быстрее собственной
       медианы лапов ЭТОЙ тренировки считаются 'рабочими' (порог относительный, а не абсолютный,
       т.к. темп рабочих отрезков сильно разный от сессии к сессии). Если лапы почти одинаковые
       по темпу (низкий CV) — интервальную структуру выделить не получается, тренировка пропускается,
       а не насильно превращается в шумную точку.
    2. Для каждого рабочего лапа считаем 'сырой' VDOT формулой Дэниэлса (distance/duration лапа) —
       но с достоверностью (весом), зависящей от длительности:
         - < 90с — вес 0.3 (спринт, доминирует анаэробная составляющая и задержка пульса);
         - 90-150с — вес 0.6;
         - 150-900с (2.5-15 мин) — вес 1.0 (наиболее сопоставимо с гоночным VDOT);
         - 900-1800с — вес 0.85 и сама оценка занижается на 2% (одиночный длинный лап без внутренних
           сплитов — возможный незамеченный провал темпа во второй половине, как в detect_race_blowup,
           но здесь сплитов внутри лапа нет, поэтому это фиксированная поправка, а не детектор);
         - > 1800с — вес 0.55 и оценка занижается на 5% (тот же риск провала темпа, но выше).
    3. Рабочие лапы одной тренировки сворачиваются в ОДНУ точку — взвешенная медиана (устойчивее
       к одному шумному лапу, чем среднее). Итоговая достоверность тренировки = средний вес лапов,
       дополнительно штрафуется, если сами лапы сильно расходятся между собой по VDOT (большой
       разброс = ненадёжная тренировка, а не стабильный маркер формы).
    4. Поправка на фактический % от ПАНО рабочих лапов (см. диалог, тест на реальных данных
       пользователя, 2026-08-18): формула Дэниэлса предполагает устойчивое состояние VO2,
       а рабочие отрезки интервальной/пороговой тренировки часто бегутся заметно ниже порога
       (пульс не успевает/не должен подняться до ПАНО на коротких повторах). Сравнение с
       эталонной кривой VDOT по гонкам (линейная интерполяция по времени) на 207 точках этого
       пользователя показало сильную корреляцию (r=0.57) между заниженностью точки и средним
       pct_of_pano рабочих лапов: <80% ПАНО -> occasion занижение на ~16 очков VDOT, 95-100% ПАНО
       -> ~4 очка. Линейная поправка resid = a + b*pct_of_pano, обученная на первых 70% точек по
       времени и проверенная на последних 30% (не участвовавших в подборе a/b), снизила RMSE
       относительно гоночной кривой с 10.1 до 6.5 (~35%) на данных, которые модель не видела —
       то есть эффект не переобучение. Поправка применяется только если передан pano (иначе
       пропускается, чтобы вызов без pano не ломался — обратная совместимость), и ограничена
       клипом +-pano_corr_clip от величины VDOT точки (по умолчанию 20%), чтобы не улетать в
       крайности на разреженных/нетипичных тренировках. Коэффициенты a/b подобраны один раз по
       истории конкретного пользователя (см. диалог) — если тренировочный профиль сильно
       изменится (новый вид часов/датчика пульса, другая калибровка ПАНО), их стоит переоценить
       заново по актуальным данным, а не считать раз и навсегда верными.
    5. Финальный робастный отброс экстремумов по ВСЕЙ серии точек (устойчивый MAD-фильтр,
       не долевой IQR) — не точка-по-точке против гонок, а против медианы самих интервальных
       точек, чтобы не потерять реальные периоды провала/подъёма формы, а только выкинуть сбои
       (GPS/пульсометр, случайно размеченная тренировка).

    Возвращает DataFrame: date, name, vdot, weight (0..1, для размера/прозрачности маркера),
    n_work_laps, pct_of_pano (NaN, если pano не передан). Пустой DataFrame, если пригодных точек нет.

    ВАЖНО (см. диалог): раньше здесь стоял фильтр type_guess.isin(['interval','threshold']) —
    это ОШИБКА, которую поймал пользователь. Жёсткие отрезки (пикапы, фартлек-вставки,
    марафонский темп внутри длительной) сплошь и рядом попадают в тренировки, размеченные как
    'long'/'easy'/'mixed' — сама разметка type_guess не про наличие рабочих отрезков, а про
    общий характер тренировки. Фильтр по type_guess отсекал именно такие вставки и годился
    только для оценки формы по выделенным интервальным/пороговым дням, но не для честного
    подсчёта 'сколько качественной работы было на самом деле' — этим и занимается CV-фильтр
    ниже (он уже сам отделяет тренировки со структурой от равномерных), доп. ограничение по
    типу тренировки было избыточным и маскировало реальную качественную работу."""
    candidates = activities[activities["avg_hr"].notna()]

    points = []
    for _, act in candidates.iterrows():
        iv = intervals[
            (intervals["activity_id"] == act["activity_id"]) &
            intervals["avg_hr"].notna() & intervals["avg_pace_s_per_km"].notna() &
            (intervals["distance_m"] >= 150) &
            (intervals["duration_s"] >= 30) &
            (intervals["avg_pace_s_per_km"] < 900)  # отсекаем ходьбу/паузы (медленнее 15 мин/км)
        ].copy()
        if len(iv) < 3:
            continue

        median_pace = iv["avg_pace_s_per_km"].median()
        if not median_pace or median_pace <= 0:
            continue
        cv = iv["avg_pace_s_per_km"].std() / median_pace
        if cv < 0.06:
            continue  # лапы почти одинаковые — рабочие/восстановительные отрезки не отделить

        work = iv[iv["avg_pace_s_per_km"] <= median_pace * 0.94]
        if not len(work):
            continue

        raw_vdots, weights, hrs = [], [], []
        for _, lap in work.iterrows():
            dur = lap["duration_s"]
            vdot = daniels_vdot(lap["distance_m"], dur)
            if dur < 90:
                w = 0.3
            elif dur < 150:
                w = 0.6
            elif dur <= 900:
                w = 1.0
            elif dur <= 1800:
                w = 0.85
                vdot *= 0.98
            else:
                w = 0.55
                vdot *= 0.95
            raw_vdots.append(vdot)
            weights.append(w)
            hrs.append(lap["avg_hr"])

        raw_vdots = np.array(raw_vdots)
        weights = np.array(weights)

        order = np.argsort(raw_vdots)
        sv, sw = raw_vdots[order], weights[order]
        cw = np.cumsum(sw)
        med_idx = int(np.searchsorted(cw, cw[-1] / 2.0))
        vdot_point = float(sv[min(med_idx, len(sv) - 1)])

        spread = float(raw_vdots.std() / vdot_point) if vdot_point else 1.0
        consistency_penalty = max(0.3, 1.0 - spread) if len(work) > 1 else 0.6  # 1 лап — разброс
                                                                                  # не проверить, доверия меньше
        n_laps_penalty = min(1.0, len(work) / 3.0)  # мало лапов -> агрегат менее устойчив
        conf = float(np.average(weights)) * consistency_penalty * n_laps_penalty
        if conf < min_conf:
            continue

        # поправка на % от ПАНО рабочих лапов (см. докстринг, п.4) — только если pano передан
        pct_of_pano = np.nan
        if pano:
            avg_hr_work = float(np.average(hrs, weights=weights))
            pct_of_pano = avg_hr_work / pano
            correction = pano_corr_a + pano_corr_b * pct_of_pano
            correction = float(np.clip(correction, -vdot_point * pano_corr_clip,
                                        vdot_point * pano_corr_clip))
            vdot_point = vdot_point - correction  # resid = vdot - ref, поэтому вычитаем поправку

        points.append({
            "date": act["date"], "name": act["name"], "vdot": vdot_point,
            "weight": min(conf, 1.0), "n_work_laps": int(len(work)),
            "pct_of_pano": pct_of_pano,
        })

    df = pd.DataFrame(points)
    if not len(df):
        return df

    med = df["vdot"].median()
    mad = (df["vdot"] - med).abs().median()
    if mad > 0:
        df = df[(df["vdot"] - med).abs() <= 3.5 * mad]

    return df.sort_values("date").reset_index(drop=True)


def plot_ef_vdot(wk_ef_roll, races, interval_points=None, xlim=None):
    """Наложение по годам: месяц (1-12) по оси X вместо календарной даты — один и тот же месяц
    в разные годы попадает в одну и ту же точку по горизонтали, поэтому виден и сезонный ход
    внутри года, и год-к-году сравнение (год = цвет, из year_color_map). Раньше график шёл одной
    сплошной лентой через всю историю: многолетний тренд и сезонность были неразделимы. Разбит
    на два подграфика (EF сверху, VDOT снизу) вместо прежней двойной оси Y — общая ось X (месяц)
    и общая цветовая легенда по годам под обоими. xlim больше не используется (ось X теперь
    месяц, а не дата), параметр оставлен только чтобы не ломать вызывающий код.
    """
    ef = wk_ef_roll.dropna()
    ef_df = pd.DataFrame({"date": ef.index, "ef": ef.values})
    ef_df["year"] = ef_df["date"].dt.year
    ef_df["month"] = ef_df["date"].dt.month
    ef_monthly = ef_df.groupby(["year", "month"])["ef"].mean().reset_index()

    years = set(ef_monthly["year"])
    if len(races):
        years |= set(races["date"].dt.year)
    if interval_points is not None and len(interval_points):
        years |= set(interval_points["date"].dt.year)
    colors = year_color_map(years)

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(11, 7.6), sharex=True)

    for year, g in ef_monthly.groupby("year"):
        g = g.sort_values("month")
        ax1.plot(g["month"], g["ef"], color=colors[year], linewidth=2, marker="o", markersize=4)
    ax1.set_ylabel("EF (скорость/пульс)")
    ax1.set_title("4а. EF лёгкого бега (сезонно скорр., помесячное среднее скольз. 4нед. тренда) — наложение по годам")
    ax1.grid(axis="y", color="#e1e0d9", linewidth=0.8)

    if interval_points is not None and len(interval_points):
        ip = interval_points.copy()
        ip["year"] = ip["date"].dt.year
        ip["month"] = ip["date"].dt.month
        for year, g in ip.groupby("year"):
            wy = g["weight"].clip(0, 1)
            base_rgb = to_rgb(colors[year])
            rgba = [(base_rgb[0], base_rgb[1], base_rgb[2], 0.18 + wi * 0.42) for wi in wy]
            ax2.scatter(g["month"], g["vdot"], s=12 + wy * 40, c=rgba, edgecolors="none",
                        zorder=2, marker="^")

    if len(races):
        rdf = races.copy()
        rdf["year"] = rdf["date"].dt.year
        rdf["month"] = rdf["date"].dt.month
        blown = rdf[rdf.get("blowup_detected", False) == True]
        clean = rdf[rdf.get("blowup_detected", False) != True]
        for year, g in clean.groupby("year"):
            ax2.scatter(g["month"], g["vdot"], color=colors[year], s=55, zorder=5)
        for year, g in blown.groupby("year"):
            ax2.scatter(g["month"], g["vdot"], color=colors[year], s=90, zorder=5,
                        edgecolors="black", linewidths=1.5, marker="D")
    ax2.set_ylabel("VDOT")
    ax2.set_xlabel("месяц")
    ax2.set_title("4б. VDOT по месяцам — наложение по годам (● гонка, ◆ гонка со срывом темпа, ▲ оценка по интервалам)")
    ax2.grid(axis="y", color="#e1e0d9", linewidth=0.8)

    ax2.set_xticks(range(1, 13))
    ax2.set_xticklabels(MONTH_ABBR_RU)
    ax2.set_xlim(0.5, 12.5)

    year_handles = [plt.Line2D([0], [0], color=colors[y], linewidth=2, marker="o", markersize=5, label=str(y))
                    for y in sorted(colors)]
    fig.legend(handles=year_handles, loc="upper center", bbox_to_anchor=(0.5, 0.015),
               ncol=min(len(year_handles), 8), fontsize=8, frameon=True, title="год")
    fig.tight_layout(rect=(0, 0.07, 1, 1))
    return fig_to_base64(fig)


# --------------------------------------------------------------------------
# 4b. "ГАРМИНОВСКИЙ" VO2max
# --------------------------------------------------------------------------
# В выгрузке БД нет прямого поля vo2max (проверено: ни в activities, ни в wellness,
# ни где-либо ещё). Единственный собственный фитнес-показатель Гармина, который
# ЕСТЬ в базе — это история ПАНО (таблица lactate_threshold, считается Гармином через
# Firstbeat по фактическим тренировкам). Строим из неё VO2max-эквивалент по той же
# формуле Дэниэлса, что и VDOT по гонкам (п.4), считая ПАНО эффортом, устойчивым ~60 мин
# — это стандартное допущение в спортивной физиологии (Jack Daniels, Joe Friel), но это
# ОЦЕНКА, а не собственно внутреннее число Гармина, которое в выгрузке отсутствует.

def garmin_vo2max_proxy(lt):
    df = lt[lt["threshold_hr"].notna() & lt["threshold_pace_s_per_km"].notna()].copy()
    if not len(df):
        return df
    # см. диалог: сырые значения threshold_pace_s_per_km физиологически правдоподобны
    # только после деления на 10 (иначе получается ~40+ мин/км)
    df["pace_corrected_s_per_km"] = df["threshold_pace_s_per_km"] / 10.0
    df["velocity_m_per_min"] = 60000.0 / df["pace_corrected_s_per_km"]
    t_min = 60.0
    pct_max = 0.8 + 0.1894393 * np.exp(-0.012778 * t_min) + 0.2989558 * np.exp(-0.1932605 * t_min)
    vo2 = -4.60 + 0.182258 * df["velocity_m_per_min"] + 0.000104 * df["velocity_m_per_min"] ** 2
    df["vo2max_proxy"] = vo2 / pct_max
    return df


def plot_garmin_vo2max(lt_vo2, xlim=None):
    fig, ax = plt.subplots(figsize=(11, 3.2))
    if len(lt_vo2):
        ax.plot(lt_vo2["date"], lt_vo2["vo2max_proxy"], color="#8C2A22", linewidth=1.6, marker="o", markersize=3)
        roll = lt_vo2.set_index("date")["vo2max_proxy"].rolling(5, min_periods=1).mean()
        ax.plot(roll.index, roll.values, color="#1A3A5C", linewidth=2, label="Скольз. среднее (5 точек)")
    ax.set_title("5. VO2max-прокси по истории ПАНО Garmin (поля vo2max нет в выгрузке — см. примечание)")
    ax.set_ylabel("VO2max, мл/кг/мин (оценка)")
    ax.legend(loc="upper left", fontsize=8)
    ax.xaxis.set_major_locator(mdates.MonthLocator(interval=2))
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%b %y"))
    if xlim:
        # история ПАНО в Garmin обычно короче полной истории тренировок (см. диалог 2026-08-20
        # про единую временную ось всех графиков) — здесь данные не заполняют весь диапазон,
        # это ожидаемо, а не ошибка; xlim только выравнивает ось с остальными графиками отчёта.
        ax.set_xlim(*xlim)
    fig.autofmt_xdate()
    return fig_to_base64(fig)


# --------------------------------------------------------------------------
# 4c. ЧАСТОТА КАЧЕСТВЕННЫХ ТРЕНИРОВОК И РАБОЧИХ ОТРЕЗКОВ ПО НЕДЕЛЯМ
# --------------------------------------------------------------------------
# См. диалог: изначально здесь стоял фильтр type_guess.isin(['interval','threshold']) и
# делался вывод про "почти полное отсутствие тренировок" в ноябре 2025 - феврале 2026. Это
# было ДВОЙНОЙ ОШИБКОЙ, которую поймал пользователь по факту: (1) общий недельный объём бега
# в этот период на самом деле был нормальным, местами 60-80 км/нед (см. п.7) — не "почти нулевой";
# (2) рабочие/жёсткие отрезки (пикапы, фартлек, марафонский темп) сплошь и рядом сидят ВНУТРИ
# тренировок, размеченных как 'long'/'easy'/'mixed' — фильтр по type_guess их отсекал и занижал
# реальный объём качественной работы. Правильно — искать рабочие отрезки во ВСЕХ тренировках
# с пульсом, а не только в тех, что целиком помечены как интервальные/пороговые; CV-фильтр по
# темпу лапов сам по себе уже отделяет тренировки со структурой от равномерных, доп. ограничение
# по типу было избыточным. После исправления реальная картина мягче: не "тренировок не было",
# а "рабочих отрезков в среднем стало ~3.3/нед вместо ~11.5/нед в здоровый период" — падение
# качественной работы всё ещё есть, но не такое, как выглядело по неверной методике.

def weekly_quality_training_frequency(activities, intervals):
    """Для каждой тренировки с известным пульсом считает число 'рабочих' лапов (лапы заметно
    быстрее собственной медианы лапов ЭТОЙ тренировки, а CV темпа тренировки >= 0.06, иначе
    рабочие/восстановительные отрезки не отделить и n_work_laps = 0). НЕ ограничивается
    type_guess — рабочие отрезки ищутся во ВСЕХ тренировках (в т.ч. размеченных long/easy/mixed),
    иначе теряются пикапы/вставки внутри длительных и лёгких (см. диалог выше). Достоверность/
    веса, как в interval_threshold_vdot_points, здесь не нужны — это просто счётчик фактов
    (тренировка была / рабочих отрезков было N), включая тренировки с n_work_laps = 0."""
    candidates = activities[activities["avg_hr"].notna()].copy()
    candidates["week"] = candidates["date"].dt.to_period("W-SUN").apply(lambda p: p.start_time)

    rows = []
    for _, act in candidates.iterrows():
        n_work = 0
        iv = intervals[
            (intervals["activity_id"] == act["activity_id"]) &
            intervals["avg_hr"].notna() & intervals["avg_pace_s_per_km"].notna() &
            (intervals["distance_m"] >= 150) &
            (intervals["duration_s"] >= 30) &
            (intervals["avg_pace_s_per_km"] < 900)
        ]
        if len(iv) >= 3:
            median_pace = iv["avg_pace_s_per_km"].median()
            if median_pace and median_pace > 0:
                cv = iv["avg_pace_s_per_km"].std() / median_pace
                if cv >= 0.06:
                    n_work = int((iv["avg_pace_s_per_km"] <= median_pace * 0.94).sum())
        rows.append({"week": act["week"], "n_work_laps": n_work})

    df = pd.DataFrame(rows)
    if not len(df):
        return pd.DataFrame(columns=["n_sessions", "n_work_laps", "n_sessions_roll", "n_work_laps_roll"])

    weekly = df.groupby("week").agg(n_sessions=("n_work_laps", "size"),
                                     n_work_laps=("n_work_laps", "sum"))
    all_weeks = pd.date_range(weekly.index.min(), weekly.index.max(), freq="7D")
    weekly = weekly.reindex(all_weeks, fill_value=0)
    weekly["n_sessions_roll"] = weekly["n_sessions"].rolling(4, min_periods=1).mean()
    weekly["n_work_laps_roll"] = weekly["n_work_laps"].rolling(4, min_periods=1).mean()
    return weekly


# Те же множества типов лап, что и LAP_ACTIVE_TYPES/LAP_REST_TYPES в garmin_activities_export.py
# (classify()) — продублированы здесь, чтобы build_report.py не тянул зависимость от экспортёра.
# Если множества там поменяются — поправить и здесь.
LAP_ACTIVE_TYPES = {"INTERVAL_ACTIVE", "ACTIVE", "INTERVAL", "REPEAT", "WORK"}
LAP_REST_TYPES = {"INTERVAL_REST", "RECOVERY", "REST", "RECOVERY_ACTIVE"}


def _merge_continuous_work_blocks(iv_sorted):
    """Склеивает подряд идущие типизированные Garmin work-лапы (без rest-лапа между ними, т.е.
    без разрыва на отдых) в непрерывные рабочие блоки — тот же приём, что и в classify()
    (garmin_activities_export.py, см. диалог 2026-08-20: Garmin дополнительно бьёт один
    непрерывный отрезок автолапами по километру). iv_sorted — лапы ОДНОЙ тренировки в
    хронологическом порядке (по idx). Возвращает список {duration_s, avg_hr} — avg_hr взвешен
    по длительности лапов внутри блока."""
    blocks = []
    cur_dur, cur_hr_weighted = 0.0, 0.0
    for _, lap in iv_sorted.iterrows():
        if lap["lap_type"] in LAP_ACTIVE_TYPES:
            cur_dur += lap["duration_s"]
            if pd.notna(lap["avg_hr"]):
                cur_hr_weighted += lap["avg_hr"] * lap["duration_s"]
        else:
            if cur_dur:
                blocks.append({"duration_s": cur_dur, "avg_hr": cur_hr_weighted / cur_dur if cur_dur else np.nan})
            cur_dur, cur_hr_weighted = 0.0, 0.0
    if cur_dur:
        blocks.append({"duration_s": cur_dur, "avg_hr": cur_hr_weighted / cur_dur if cur_dur else np.nan})
    return blocks


def detect_quality_work_laps(activities, intervals, min_dur_s=90, max_dur_s=1800):
    """Единая детекция 'качественных' рабочих отрезков, используемая ВЕЗДЕ, где отчёт говорит о
    структурированной работе (3c и 3d) — раньше 3c детектировал рабочие лапы так, а 3d (доля
    времени по пульсовым зонам) считал по ВСЕМ лапам любых тренировок вообще, из-за чего 3d не
    показывал спад качественной работы, видимый на 3c: лёгкие/длинные пробежки, где пульс от
    жары/дрейфа/рельефа заходил в ту же HR-зону, что и целевая пороговая работа, "разбавляли"
    факт и маскировали реальную нехватку структурированного стимула (см. диалог).

    ИЗМЕНЕНО 2026-08-20 (см. диалог, тренировка 23536204499 'Порог 2x20'' от 2026-07-09,
    id 23536204499): если Garmin сам типизировал лапы (work/rest) — это теперь ПРИОРИТЕТНЫЙ
    источник, ТЕ ЖЕ правила, что и в classify() (garmin_activities_export.py): подряд идущие
    typed work-лапы без rest между ними склеиваются в непрерывные блоки (см.
    _merge_continuous_work_blocks). Без этого CV-эвристика по медиане либо теряла такую
    тренировку целиком (когда работа — БОЛЬШИНСТВО сессии, как в этом примере: 10 из 18 валидных
    лапов ACTIVE — медиана темпа тренировки сама оказывается близко к темпу работы, и порог
    'быстрее медианы на 6%' никогда не срабатывает, раздел 8/11 показывали 0 обнаруженных
    рабочих лапов, хотя раздел 10 по type_guess уже корректно относит её к threshold), либо
    дробила непрерывный 20-минутный блок на 5 отдельных км-автолапов вместо одного цельного
    порогового блока. CV-эвристика по медиане (прежний критерий: CV темпа лапов >= 0.06, лапа
    быстрее медианы минимум на 6%) остаётся ТОЛЬКО фолбэком — для тренировок БЕЗ типизации
    Гармином (ручные/авто-лапы без разметки work/rest), где typed-путь неприменим.

    Возвращает DataFrame с одной строкой на обнаруженный рабочий лап/блок: activity_id, week,
    duration_s, avg_hr (нужен для классификации по зоне в 3d)."""
    candidates = activities[activities["avg_hr"].notna()].copy()
    candidates["week"] = candidates["date"].dt.to_period("W-SUN").apply(lambda p: p.start_time)

    rows = []
    for _, act in candidates.iterrows():
        iv_all = intervals[intervals["activity_id"] == act["activity_id"]]
        if "idx" in iv_all.columns:
            iv_all = iv_all.sort_values("idx")
        typed_active = iv_all[iv_all["lap_type"].isin(LAP_ACTIVE_TYPES)]
        typed_rest = iv_all[iv_all["lap_type"].isin(LAP_REST_TYPES)]

        if len(typed_active) >= 2 and len(typed_rest) >= 1:
            # Гармин сам типизировал структуру — используем её напрямую (склейка блоков),
            # без CV-эвристики по медиане (см. докстринг выше).
            for block in _merge_continuous_work_blocks(iv_all):
                dur = block["duration_s"]
                if dur < min_dur_s or dur > max_dur_s:
                    continue
                rows.append({
                    "activity_id": act["activity_id"],
                    "week": act["week"],
                    "duration_s": dur,
                    "avg_hr": block["avg_hr"],
                })
            continue

        # Фолбэк: нет типизации Гармином — прежняя CV-эвристика по медиане темпа.
        iv = iv_all[
            iv_all["avg_hr"].notna() & iv_all["avg_pace_s_per_km"].notna() &
            (iv_all["distance_m"] >= 150) &
            (iv_all["duration_s"] >= 30) &
            (iv_all["avg_pace_s_per_km"] < 900)
        ]
        if len(iv) < 3:
            continue
        median_pace = iv["avg_pace_s_per_km"].median()
        if not median_pace or median_pace <= 0:
            continue
        cv = iv["avg_pace_s_per_km"].std() / median_pace
        if cv < 0.06:
            continue
        work = iv[iv["avg_pace_s_per_km"] <= median_pace * 0.94]
        for _, lap in work.iterrows():
            dur = lap["duration_s"]
            if dur < min_dur_s or dur > max_dur_s:
                continue
            rows.append({
                "activity_id": act["activity_id"],
                "week": act["week"],
                "duration_s": dur,
                "avg_hr": lap["avg_hr"],
            })

    return pd.DataFrame(rows, columns=["activity_id", "week", "duration_s", "avg_hr"])


def weekly_mpk_threshold_minutes(activities, intervals, mpk_max_s=360, threshold_max_s=1800):
    """Делит обнаруженные рабочие отрезки (см. detect_quality_work_laps) на МПК/VO2max (короткие
    быстрые повторы, <= mpk_max_s = 6 мин — классический диапазон интервалов на VO2max) и
    пороговые (длинные непрерывные усилия, mpk_max_s < duration <= threshold_max_s = 30 мин) ПО
    ФИЗИОЛОГИЧЕСКОЙ ДЛИТЕЛЬНОСТИ, а не по названию/типу тренировки — короткая быстрая вставка
    внутри тренировки, помеченной 'threshold', всё равно МПК-стимул, и наоборот.

    См. диалог: доля МПК в качественном объёме падала до 11% в феврале 2026 (всего ~9 мин за
    месяц) на фоне того, что общий объём бега и даже число тренировок с рабочими отрезками
    оставались в целом нормальными — именно нехватка МПК-стимула, а не общая нехватка
    тренировок, лучше объясняет застой/провал VDOT в этот период (корреляция месячных МПК-минут
    с EF того же периода r≈0.58 против r≈0.26 для одной лишь ДОЛИ МПК — важен абсолютный объём,
    не только пропорция).

    Возвращает DataFrame по неделям: mpk_min, threshold_min и их скользящее среднее за 4 недели
    (mpk_min_roll, threshold_min_roll), в минутах/неделю."""
    laps = detect_quality_work_laps(activities, intervals, min_dur_s=90, max_dur_s=threshold_max_s)
    if not len(laps):
        return pd.DataFrame(columns=["mpk_min", "threshold_min", "mpk_min_roll", "threshold_min_roll"])

    laps = laps.copy()
    laps["bucket"] = np.where(laps["duration_s"] <= mpk_max_s, "mpk", "threshold")
    laps["min"] = laps["duration_s"] / 60.0

    piv = laps.pivot_table(index="week", columns="bucket", values="min", aggfunc="sum", fill_value=0.0)
    for c in ("mpk", "threshold"):
        if c not in piv.columns:
            piv[c] = 0.0
    piv = piv.rename(columns={"mpk": "mpk_min", "threshold": "threshold_min"})

    all_weeks = pd.date_range(piv.index.min(), piv.index.max(), freq="7D")
    piv = piv.reindex(all_weeks, fill_value=0.0)
    piv["mpk_min_roll"] = piv["mpk_min"].rolling(4, min_periods=1).mean()
    piv["threshold_min_roll"] = piv["threshold_min"].rolling(4, min_periods=1).mean()
    return piv[["mpk_min", "threshold_min", "mpk_min_roll", "threshold_min_roll"]]


def plot_weekly_quality_frequency(weekly, weekly_mpk_thr=None, xlim=None):
    fig, ax1 = plt.subplots(figsize=(11, 3.8))
    if len(weekly):
        ax1.bar(weekly.index, weekly["n_sessions"], width=5, color="#9B6BC7", alpha=0.28,
                label="Тренировок с рабочими отрезками за неделю")
        ax1.plot(weekly.index, weekly["n_sessions_roll"], color="#6A3D99", linewidth=1.6,
                 label="Тренировок/нед., скольз. 4нед.")
    ax1.set_ylabel("Тренировок/неделю", color="#6A3D99")
    ax1.tick_params(axis="y", labelcolor="#6A3D99")

    # две линии по запросу: МПК/VO2max и пороговая работа отдельно (не суммарные рабочие
    # отрезки, как раньше) — именно СОСТАВ качественной работы, а не только её наличие,
    # см. диалог про падение доли МПК до 11% в феврале 2026
    ax2 = ax1.twinx()
    if weekly_mpk_thr is not None and len(weekly_mpk_thr):
        ax2.plot(weekly_mpk_thr.index, weekly_mpk_thr["mpk_min_roll"], color="#E0574C",
                 linewidth=2.2, label="МПК/VO2max, мин/нед. (скольз. 4нед.)")
        ax2.plot(weekly_mpk_thr.index, weekly_mpk_thr["threshold_min_roll"], color="#E0A62C",
                 linewidth=2.2, linestyle="--", label="Пороговая работа, мин/нед. (скольз. 4нед.)")
    ax2.set_ylabel("Минут качественной работы/неделю", color="#333333")
    ax2.tick_params(axis="y", labelcolor="#333333")

    ax1.set_title("8. Частота качественных тренировок и МПК/пороговая работа по неделям")
    ax1.xaxis.set_major_locator(mdates.MonthLocator(interval=2))
    ax1.xaxis.set_major_formatter(mdates.DateFormatter("%b %y"))
    if xlim:
        ax1.set_xlim(*xlim)
    fig.autofmt_xdate()
    lines1, labels1 = ax1.get_legend_handles_labels()
    lines2, labels2 = ax2.get_legend_handles_labels()
    fig.legend(lines1 + lines2, labels1 + labels2, loc="upper center",
               bbox_to_anchor=(0.5, 0.02), ncol=2, fontsize=7.5, frameon=True)
    fig.tight_layout(rect=(0, 0.16, 1, 1))
    return fig_to_base64(fig)


# --------------------------------------------------------------------------
# 5. ACWR — ДВУМЯ СПОСОБАМИ (Garmin training load И объём км)
# --------------------------------------------------------------------------

def compute_acwr(activities):
    df = activities.copy()
    df["date_only"] = df["date"].dt.normalize()
    daily_load = df.groupby("date_only")["activity_training_load"].sum()
    daily_km = df.groupby("date_only")["distance_m"].sum() / 1000.0

    all_days = pd.date_range(daily_load.index.min(), daily_load.index.max(), freq="D")
    daily_load = daily_load.reindex(all_days, fill_value=0.0)
    daily_km = daily_km.reindex(all_days, fill_value=0.0)

    def acwr(series):
        acute = series.rolling(7, min_periods=1).sum()
        chronic = series.rolling(28, min_periods=1).sum() / 4.0
        return acute / chronic.replace(0, np.nan)

    acwr_load = acwr(daily_load)
    acwr_km = acwr(daily_km)
    return acwr_load, acwr_km


def plot_acwr(acwr_load, acwr_km, xlim=None):
    fig, axes = plt.subplots(2, 1, figsize=(11, 5.2), sharex=True)
    for ax, series, title, color in [
        (axes[0], acwr_load, "9а. ACWR по Garmin training load (7д/28д)", "#E0574C"),
        (axes[1], acwr_km, "9б. ACWR по объёму (км, с учётом калибровки дорожки, 7д/28д)", "#3B7DD8"),
    ]:
        ax.plot(series.index, series.values, color=color, linewidth=1.2)
        ax.axhspan(0.8, 1.3, color="green", alpha=0.08)
        ax.axhline(1.5, color="red", linestyle="--", linewidth=1, label="Порог перегрузки (1.5)")
        ax.axhline(0.8, color="orange", linestyle="--", linewidth=1, label="Порог недогрузки (0.8)")
        ax.set_title(title)
        ax.set_ylabel("ACWR")
        ax.set_ylim(0, min(4, np.nanmax(series.values) * 1.1 if len(series) else 3))
        ax.legend(loc="upper left", fontsize=7)
    axes[1].xaxis.set_major_locator(mdates.MonthLocator(interval=2))
    axes[1].xaxis.set_major_formatter(mdates.DateFormatter("%b %y"))
    if xlim:
        # daily-ряд ACWR (7д/28д) начинается/заканчивается на 1 день уже, чем недельные графики
        # отчёта (см. compute_acwr: reindex по daily_load.index.min()/max(), а не по общей
        # недельной сетке) — xlim выравнивает ось с остальными графиками (см. диалог 2026-08-20).
        axes[0].set_xlim(*xlim)
    fig.autofmt_xdate()
    fig.tight_layout()
    return fig_to_base64(fig)


# --------------------------------------------------------------------------
# 6. ОПТИМАЛЬНЫЙ ПУЛЬС МЕДЛЕННОГО БЕГА (EF vs HR bins)
# --------------------------------------------------------------------------

def easy_ef_by_hr(easy, pano=None):
    """Бины — для наглядности графика. 'Пик' ищется квадратичной аппроксимацией EF~HR на
    сезонно скорректированном EF (устойчивее к шуму бинов, чем argmax по одному бину).

    Диапазон фита и пробуемые нижние границы больше не хардкодятся абсолютными числами —
    считаются из фактического распределения пульса лёгких пробежек этого атлета (перцентили)
    и, если известно, из ПАНО (верхняя граница фита не должна заходить выше околопороговой
    зоны). Так калибровка переносится на другого атлета без правки кода.

    ЧЕСТНАЯ ПРОВЕРКА УСТОЙЧИВОСТИ (см. диалог): пробуем несколько нижних границ диапазона
    фита. Если вершина параболы исчезает (кривизна >=0, т.е. нет внутреннего максимума) или
    гуляет более чем на ±10 уд/мин между вариантами — считаем, что выраженного пика
    эффективности НЕТ (EF практически плоский в рабочем диапазоне), и в качестве прагматичного
    центра Z2 берём медиану пульса лёгких пробежек, а не фиктивную точку 'максимума'.
    Проверено дополнительно: смешивание recovery/aerobic пробежек внутри категории 'easy' и
    сезонность не объясняют эту плоскую форму — выраженного пика нет даже после устранения
    обоих факторов."""
    easy = easy.copy()

    hr_lo = float(easy["avg_hr"].quantile(0.02))
    hr_hi_data = float(easy["avg_hr"].quantile(0.98))
    hr_hi = min(hr_hi_data, pano - 15) if pano is not None else hr_hi_data
    if hr_hi <= hr_lo:  # защита от вырожденного диапазона (мало данных / низкий ПАНО)
        hr_hi = hr_hi_data
    fit_range = (int(round(hr_lo)), int(round(hr_hi)))
    hr_median = float(easy["avg_hr"].median())

    easy["hrbin"] = (easy["avg_hr"] // 5 * 5).astype(int)
    g = easy.groupby("hrbin")["ef_seasadj"].agg(["mean", "count"]).reset_index()
    g = g[g["count"] >= 5]

    # несколько нижних границ фита между началом диапазона и медианой — та же идея, что и
    # раньше (100/110/115/120/127), но теперь относительно фактических данных атлета
    cutoffs = sorted(set(int(round(v)) for v in np.linspace(fit_range[0], hr_median, 5)))
    vertices = []
    for lo_cut in cutoffs:
        mask = (easy["avg_hr"] >= lo_cut) & (easy["avg_hr"] <= fit_range[1])
        hr = easy.loc[mask, "avg_hr"].values.astype(float)
        ef = easy.loc[mask, "ef_seasadj"].values
        if len(hr) < 20:
            continue
        coef = np.polyfit(hr, ef, 2)
        if coef[0] < 0:
            vertex = -coef[1] / (2 * coef[0])
            if fit_range[0] - 15 <= vertex <= fit_range[1] + 15:
                vertices.append(vertex)

    peak_found = False
    peak_center = None
    if len(vertices) >= 3 and (max(vertices) - min(vertices)) <= 10:
        peak_found = True
        peak_center = int(round(np.median(vertices)))
    else:
        peak_center = int(round(easy["avg_hr"].median()))

    return g, easy, peak_center, peak_found, vertices


def plot_easy_ef_by_hr(g, peak_center, peak_found, mean_col="mean"):
    fig, ax = plt.subplots(figsize=(11, 3.4))
    peak_bin = (peak_center // 5) * 5
    colors = ["#E0A62C" if b == peak_bin else "#4C9F70" for b in g["hrbin"]]
    ax.bar(g["hrbin"], g[mean_col], width=4, color=colors)
    for _, row in g.iterrows():
        ax.text(row["hrbin"] + 2, row[mean_col] + 0.15, f"n={int(row['count'])}", ha="center", fontsize=7)
    title_suffix = "с учётом калибровки дорожки и сезонности"
    ax.set_title(f"2а. Эффективность (EF) медленного бега в зависимости от пульса ({title_suffix})")
    ax.set_xlabel("Пульс, уд/мин (бины по 5)")
    ax.set_ylabel("EF (скорость/пульс, сезонно скорр.)")
    if peak_found:
        ax.axvline(peak_center, color="#8C2A22", linestyle="--", linewidth=1,
                   label=f"Пик эффективности (квадр. аппрокс.) ~{peak_center}")
    else:
        ax.axvline(peak_center, color="#666666", linestyle=":", linewidth=1,
                   label=f"Выраженного пика нет — медиана пульса лёгких пробежек ~{peak_center}")
    ax.legend(loc="lower right", fontsize=8)
    return fig_to_base64(fig)


# --------------------------------------------------------------------------
# 6b. ПРОДОЛЬНАЯ ПРОВЕРКА: ПУЛЬС НА EASY (ВРЕМЯ ПОД НАГРУЗКОЙ, БЕЗ ТЕМПА) vs ОТКЛИК ФОРМЫ
# --------------------------------------------------------------------------
# См. диалог 2026-08-18: гипотеза "зимой лёгкие/восстановительные бежались на завышенном
# пульсе, что предшествовало провалу формы янв-май 2026". easy_ef_by_hr() выше — КРОСС-
# СЕКЦИОННЫЙ метод (экономичность прямо сейчас при разном пульсе, в один момент времени);
# у этого атлета он часто не находит пика (EF~HR плоская, peak_found=False) и тогда падает
# в медиану пульса ЗА ВСЮ ИСТОРИЮ — а медиана как раз загрязнена периодами вроде зимы
# 2025-2026, где пульс на лёгких был завышен. Функция ниже — ПРОДОЛЬНАЯ проверка: связан ли
# пульс на easy (взвешенный по времени под нагрузкой, а НЕ по темпу) в квартале с изменением
# формы (пороговый темп из lactate_threshold, ниже = лучше) в СЛЕДУЮЩЕМ квартале. Даёт
# независимую, основанную на исходе, оценку потолка ЧСС — используется как страховка от
# fallback-медианы, когда пика EF нет.
#
# ЧЕСТНО О ГРАНИЦАХ МЕТОДА (проверено на всей истории 2024-07..2026-08): прямая линейная
# корреляция "% от ПАНО -> Δ темпа в следующем квартале" слабая (~0.04) на всех 8 точках —
# слишком мало кварталов и слишком много других факторов (объём, гонки, тейпер), чтобы это
# было строгим доказательством причинности. Но есть устойчивый локальный паттерн: единственный
# двухквартальный провал формы (2025Q3->2025Q4, оба ~81-82% от ПАНО при рекордном объёме) и
# лучший разворот формы за весь период (2026Q2, ~76% от ПАНО, рекордный порог) — так что как
# качественная поправка к fallback-медиане метод оправдан, как самостоятельное строгое
# доказательство — нет. Текст в отчёте ниже отражает эту оговорку явно.

def easy_hr_fitness_response(easy, lt, pano_final, min_activities_per_q=3, min_quarters=4):
    """Квартальная агрегация: взвешенный по duration_s пульс 'easy' (то же множество, что и
    easy_ef_by_hr/compute_easy_ef — в гарминовской type_guess это уже recovery+лёгкие вместе)
    против среднего порогового темпа Гармина (lactate_threshold) в ЭТОМ и СЛЕДУЮЩЕМ квартале.

    Возвращает (df, info, resp_ceiling_hr). info=None и resp_ceiling_hr=None, если данных
    недостаточно для содержательного вывода (см. min_quarters) — в этом случае вызывающий код
    должен просто не показывать блок и не трогать easy_center."""
    e = easy.dropna(subset=["avg_hr", "duration_s", "date"]).copy()
    e = e[e["avg_hr"] > 60]
    if e.empty:
        return pd.DataFrame(), None, None
    e["q"] = e["date"].dt.to_period("Q")

    lt2 = lt.dropna(subset=["threshold_hr", "threshold_pace_s_per_km", "date"]).copy()
    if lt2.empty:
        return pd.DataFrame(), None, None
    lt2["q"] = lt2["date"].dt.to_period("Q")
    lt_q = lt2.groupby("q").agg(thr_hr=("threshold_hr", "mean"),
                                 thr_pace=("threshold_pace_s_per_km", "mean"))

    q = e.groupby("q").apply(lambda x: pd.Series({
        "n": len(x),
        "load_min": x["duration_s"].sum() / 60.0,
        "weighted_hr": np.average(x["avg_hr"], weights=x["duration_s"]),
    }))
    q = q[q["n"] >= min_activities_per_q]

    df = q.join(lt_q, how="inner").sort_index()
    if len(df) < min_quarters:
        return df, None, None

    df["pct_pano"] = df["weighted_hr"] / pano_final * 100.0
    df["thr_pace_next"] = df["thr_pace"].shift(-1)
    df["delta_next"] = df["thr_pace_next"] - df["thr_pace"]

    valid = df.dropna(subset=["delta_next"])
    corr = None
    if len(valid) >= 4 and valid["pct_pano"].std() > 0 and valid["delta_next"].std() > 0:
        corr = float(np.corrcoef(valid["pct_pano"], valid["delta_next"])[0, 1])

    improved = valid[valid["delta_next"] < 0]
    worsened = valid[valid["delta_next"] >= 0]

    # Потолок ЧСС: медиана % от ПАНО в кварталах, ПОСЛЕ которых форма росла (медиана, а не
    # минимум/среднее — устойчивее к единичному выбросу). Только если таких кварталов хотя бы 2,
    # иначе оценка недостаточно надёжна, чтобы на неё опираться.
    resp_ceiling_pct = float(improved["pct_pano"].median()) if len(improved) >= 2 else None
    resp_ceiling_hr = int(round(resp_ceiling_pct / 100.0 * pano_final)) if resp_ceiling_pct else None

    info = {
        "corr": corr,
        "n_quarters_valid": len(valid),
        "n_improved": len(improved),
        "n_worsened": len(worsened),
        "resp_ceiling_pct": resp_ceiling_pct,
        "resp_ceiling_hr": resp_ceiling_hr,
    }
    return df, info, resp_ceiling_hr


def plot_hr_fitness_response(df):
    """Столбец = пульс на лёгких/восстановительных В ЭТОМ квартале (подпись над столбцом —
    сам пульс в уд/мин, взвешенный по времени под нагрузкой, БЕЗ учёта темпа; высота столбца —
    он же, в % от ПАНО, чтобы разброс между кварталами было видно на глаз). Цвет столбца — что
    произошло с формой ПОСЛЕ него, в следующем квартале. Линия (правая ось, перевёрнута: ниже
    в секундах = быстрее = лучше форма) — пороговый темп Гармина в ЭТОМ ЖЕ квартале, для которого
    посчитан столбец; чтобы увидеть ЭФФЕКТ (что было дальше), нужно сравнить цвет столбца текущего
    квартала со следующей точкой линии.

    Автомасштаб оси столбцов зумит на фактический разброс данных (а не от нуля) — иначе на всех
    этих 8 кварталах разница в 10-15 п.п. % от ПАНО почти не видна. Легенда вынесена ПОД график
    (fig.legend, а не ax.legend поверх осей) — раньше перекрывала столбцы."""
    fig, ax1 = plt.subplots(figsize=(11, 4.2))
    x = np.arange(len(df))
    labels = [str(p) for p in df.index]

    colors = []
    for d in df["delta_next"]:
        if pd.isna(d):
            colors.append("#B0B0B0")
        elif d < 0:
            colors.append("#4C9F70")
        else:
            colors.append("#8C2A22")

    bars = ax1.bar(x, df["pct_pano"], color=colors, width=0.6, zorder=3)
    ax1.set_xticks(x)
    ax1.set_xticklabels(labels, rotation=45, ha="right", fontsize=8)
    ax1.set_ylabel("Пульс на easy, % от ПАНО\n(взвеш. по времени под нагрузкой, БЕЗ учёта темпа)",
                    fontsize=8.5)
    fig.suptitle("2б. Пульс на лёгких/восстановительных по кварталам (столбцы, левая ось)\n"
                 "и пороговый темп Гармина (линия, правая ось)", fontsize=10, y=1.04)

    # автомасштаб: зум на фактический разброс % от ПАНО, а не от 0 — иначе колебания незаметны
    lo, hi = df["pct_pano"].min(), df["pct_pano"].max()
    pad = max(2.0, (hi - lo) * 0.25)
    ax1.set_ylim(lo - pad, hi + pad * 1.6)  # запас сверху побольше — там подписи пульса в уд/мин

    for xi, row in zip(x, bars):
        hr_val = df["weighted_hr"].iloc[xi]
        ax1.text(xi, row.get_height() + pad * 0.25, f"{hr_val:.0f} уд/мин",
                  ha="center", fontsize=7.5)

    ax2 = ax1.twinx()
    line, = ax2.plot(x, df["thr_pace"], color="#2C5F8C", marker="o", linewidth=1.5, zorder=4)
    ax2.set_ylabel("Пороговый темп Garmin, с/км (ниже = лучше форма)", color="#2C5F8C")
    ax2.invert_yaxis()
    ax2.tick_params(axis="y", colors="#2C5F8C")

    legend_el = [
        Patch(facecolor="#4C9F70", label="цвет столбца: дальше форма росла (порог быстрее)"),
        Patch(facecolor="#8C2A22", label="цвет столбца: дальше форма стагнировала/ухудшалась"),
        Patch(facecolor="#B0B0B0", label="следующего квартала пока нет в данных"),
        line,
    ]
    line.set_label("пороговый темп в этом же квартале (правая ось)")
    fig.legend(handles=legend_el, loc="upper center", bbox_to_anchor=(0.5, 0.02),
               ncol=2, fontsize=7.5, frameon=False)
    fig.tight_layout(rect=[0, 0.14, 0.94, 0.92])
    return fig_to_base64(fig)


# --------------------------------------------------------------------------
# 7. ПУЛЬСОВЫЕ ЗОНЫ + АКТУАЛЬНЫЙ ТЕМП (последние 4-8 недель, диапазон)
# --------------------------------------------------------------------------

def build_zones(rhr, pano, max_hr, z1_hrr=0.50, z2_hrr=0.60, z3_hrr=0.70):
    """
    Z1/Z2/Z3 нижние границы — по Карвонену (%HRR = резерв пульса = max_hr - rhr), а не
    center±6, как раньше (см. диалог 2026-08-18). Причина смены метода:

    1. Прежний easy_center брался либо из пика EF~HR (кросс-секционная экономичность), либо,
       когда пика нет (частый случай для этого атлета — EF практически плоская), из медианы
       пульса ВСЕЙ 'easy'-выборки Гармина (type_guess=='easy' объединяет recovery+лёгкие).
       Медиана смеси двух разных по интенсивности популяций — это не центр ни одной из них,
       а точка где-то на стыке между ними; center±6 после такой медианы давал слишком узкую
       и сдвинутую вниз Z2 (см. жалобу пользователя: реальная практика "лёгких" ~142-144
       уд/мин не попадала во вторую зону вообще).
    2. Разбивать выборку по названию тренировки ("Recovery"/"Easy") тоже нельзя — это
       использовало бы прежние предположения о зонах как вход для построения новых зон
       (circular reasoning).
    3. Проверено два независимых способа, не использующих ярлыки: (a) форма распределения
       пульса (взвешенная по времени под нагрузкой KDE, вход только пульс) — устойчиво (при
       Scott/Silverman и вручную заданных ширинах окна, после отсечения сенсорных выбросов
       avg_hr<105) даёт два кластера ~125 и ~145 с разрывом плотности в районе 130-136;
       (b) бэктест по стандартным методикам (Карвонен %HRR, %MaxHR, Friel/Coggan %LTHR) —
       доля времени в зоне каждой методики за квартал против изменения порогового темпа
       Гармина в СЛЕДУЮЩЕМ квартале. Карвоненовская Z2 (60-70% HRR) дала самую заметную (хоть
       и на n=8 кварталах, не строгую) связь, и её граница Z1/Z2 (~136 при rhr~45, max_hr~196)
       совпала с независимо найденным разрывом плотности пульса. Оба способа сошлись в одной
       точке — это и есть обоснование выбора именно Карвонена, а не подгонка под желаемый ответ.

    rhr — пульс покоя (см. estimate_resting_hr, wellness.rhr, скользящее окно 90 дней).
    max_hr — из calibration_profile (meta.load_params.max_hr) или максимума по БД, как раньше.
    pano — верхняя граница Z4 (порог), измеренный Гармином (lactate_threshold) — оставлен как
    измеренная величина, а не % от HRR, потому что это прямой физиологический показатель этого
    атлета, а не общая формула.
    z1_hrr/z2_hrr/z3_hrr — пороги %HRR для границ Z1/Z2/Z3 (по умолчанию стандартные
    50/60/70%, см. диалог 2026-08-18 — z2_hrr=0.60 подтверждён бэктестом на истории этого
    атлета, z1_hrr/z3_hrr — стандартные значения методики, отдельно на этой истории не
    перебирались, чтобы не переобучаться на 8 кварталах).
    """
    hrr = max_hr - rhr

    def at(pct):
        return rhr + pct * hrr

    z1_lo = int(round(at(z1_hrr)))
    z2_lo = int(round(at(z2_hrr)))
    z3_lo_hrr = int(round(at(z3_hrr)))
    z1_hi = z2_lo - 1

    z4_hi = pano
    z2_hi = z3_lo_hrr - 1
    z3_lo = z2_hi + 1
    z4_lo = int(round((z3_lo + z4_hi) / 2))
    z3_hi = z4_lo - 1
    z5_lo, z5_hi = pano + 1, max_hr

    zones = [
        ("Z1 — восстановление", z1_lo, z1_hi),
        ("Z2 — лёгкий/аэробный", z2_lo, z2_hi),
        ("Z3 — марафонский темп", z3_lo, z3_hi),
        ("Z4 — пороговый (до ПАНО)", z4_lo, z4_hi),
        ("Z5 — VO2max/выше ПАНО", z5_lo, z5_hi),
    ]
    return zones


def recent_pace_by_zone(intervals, zones, activities, weeks_back=8):
    """Темп по зонам ТОЛЬКО за последние weeks_back недель (актуальная форма, не вся
    история) — с учётом калибровки дорожки. Возвращает медиану и IQR (25-75 перц.)."""
    cutoff = activities["date"].max() - pd.Timedelta(weeks=weeks_back)
    recent_ids = set(activities.loc[activities["date"] >= cutoff, "activity_id"])

    iv = intervals[
        intervals["activity_id"].isin(recent_ids) &
        intervals["avg_hr"].notna() & intervals["avg_pace_s_per_km"].notna() &
        (intervals["distance_m"] > 150) & (intervals["avg_pace_s_per_km"] < 900)
    ].copy()

    def zone_of(hr):
        for name, lo, hi in zones:
            if lo <= hr <= hi:
                return name
        return None

    iv["zone"] = iv["avg_hr"].apply(zone_of)
    iv = iv.dropna(subset=["zone"])

    rows = []
    for name, lo, hi in zones:
        sub = iv[iv["zone"] == name]
        if len(sub) >= 5:
            p25, p50, p75 = np.percentile(sub["avg_pace_s_per_km"], [25, 50, 75])
            rows.append({"zone": name, "p25": p25, "p50": p50, "p75": p75, "n": len(sub)})
        else:
            rows.append({"zone": name, "p25": np.nan, "p50": np.nan, "p75": np.nan, "n": len(sub)})
    return pd.DataFrame(rows).set_index("zone"), cutoff


def zones_table_with_recent_pace(zones, pano, recent_pace_df, weeks_back):
    rows = []
    for name, lo, hi in zones:
        pct_lo = round(lo / pano * 100)
        pct_hi = round(hi / pano * 100)
        r = recent_pace_df.loc[name] if name in recent_pace_df.index else None
        if r is not None and not np.isnan(r["p25"]):
            # p25 темпа (с/км) = БЫСТРЕЕ, p75 = медленнее -> диапазон "быстрый-медленный"
            pace_range = f"{fmt_pace(r['p25'])} – {fmt_pace(r['p75'])} (медиана {fmt_pace(r['p50'])})"
            n_note = f"n={int(r['n'])}"
        else:
            pace_range = "недостаточно данных за период"
            n_note = f"n={int(r['n'])}" if r is not None else "n=0"
        rows.append({
            "Зона": name,
            "Пульс, уд/мин": f"{lo}-{hi}",
            "% от ПАНО": f"{pct_lo}-{pct_hi}%",
            f"Темп, последние {weeks_back} нед.": pace_range,
            "Кол-во сплитов": n_note,
        })
    return pd.DataFrame(rows)


# --------------------------------------------------------------------------
# 8. ТЕМП ПО ЗОНАМ В ДИНАМИКЕ (по кварталам, не единое число за 2 года)
# --------------------------------------------------------------------------
# За 2 года фитнес поменялся (VDOT плавал от ~31 до ~48, см. п.4) — усреднять темп по зоне
# ЗА ВСЮ ИСТОРИЮ в одно число некорректно: это смешивает темп на разных уровнях формы.
# Вместо статичного среднего показываем темп по зоне ПО КВАРТАЛАМ — это и есть требуемое
# "смотреть в динамике" (альтернатива VDOT-нормировке, которая по сути давала бы то же самое,
# но с дополнительным слоем допущений о форме кривой VDOT~pace).

def pace_by_zone_monthly(intervals, zones, activities):
    """Как раньше pace_by_zone_quarterly, но группировка по (год, месяц) вместо квартала — чтобы
    графики можно было наложить по годам и сравнивать динамику помесячно (см.
    plot_pace_by_zone_monthly_by_year): один и тот же календарный месяц в разные годы ложится в
    одну и ту же точку по оси X, разным цветом на год. Порог по числу сплитов снижен с 8 (было
    на квартал) до 5 — месяц уже квартала втрое, и с порогом 8 половина месяцев проваливалась бы
    в NaN."""
    act_m = activities[["activity_id", "date"]].copy()
    act_m["year"] = act_m["date"].dt.year
    act_m["month"] = act_m["date"].dt.month
    iv = intervals.merge(act_m[["activity_id", "year", "month"]], on="activity_id", how="inner")
    iv = iv[
        iv["avg_hr"].notna() & iv["avg_pace_s_per_km"].notna() &
        (iv["distance_m"] > 150) & (iv["avg_pace_s_per_km"] < 900)
    ].copy()

    def zone_of(hr):
        for name, lo, hi in zones:
            if lo <= hr <= hi:
                return name
        return None

    iv["zone"] = iv["avg_hr"].apply(zone_of)
    iv = iv.dropna(subset=["zone"])

    def wavg_pace(g):
        # Средний темп = суммарное время / суммарная дистанция (взвешенный по дистанции темп,
        # см. докстринг прежней pace_by_zone_quarterly) — не обратное от взвешенного среднего
        # арифметического скорости, которое систематически занижает темп.
        if len(g) < 5:
            return np.nan
        return g["duration_s"].sum() / (g["distance_m"].sum() / 1000.0)

    grouped = iv.groupby(["year", "month", "zone"]).apply(wavg_pace).rename("pace").reset_index()
    counts = iv.groupby(["year", "month", "zone"]).size().rename("n").reset_index()
    grouped = grouped.merge(counts, on=["year", "month", "zone"])
    return grouped.dropna(subset=["pace"])


def plot_pace_by_zone_monthly_by_year(grouped, zones, xlim=None):
    """Сетка мелких графиков (facet), по одному на зону; внутри каждого — темп ПО МЕСЯЦАМ с
    наложением линий по годам (цвет = год, year_color_map, общий с графиком EF/VDOT п.4). Так
    виден и сезонный ход темпа внутри года, и год-к-году сравнение одного и того же месяца —
    раньше единая лента по кварталам через всю историю смешивала оба эффекта. xlim больше не
    используется (ось X — месяц, не дата), параметр оставлен только чтобы не ломать вызывающий
    код."""
    zone_order = [z[0] for z in zones]
    zone_names = [z for z in zone_order if z in set(grouped["zone"])] or zone_order
    years = sorted(grouped["year"].dropna().unique())
    colors = year_color_map(years)

    ncols = 2
    nrows = max(1, math.ceil(len(zone_names) / ncols))
    fig, axes = plt.subplots(nrows, ncols, figsize=(11, 3.1 * nrows), sharex=True)
    axes = np.atleast_1d(axes).flatten()

    for i, zname in enumerate(zone_names):
        ax = axes[i]
        zdf = grouped[grouped["zone"] == zname]
        for year, g in zdf.groupby("year"):
            g = g.sort_values("month")
            if len(g) >= 2:
                ax.plot(g["month"], g["pace"], color=colors[year], linewidth=1.8, marker="o",
                        markersize=4)
        ax.set_title(zname, fontsize=9.5)
        ax.invert_yaxis()
        ax.set_xticks(range(1, 13))
        ax.set_xticklabels(MONTH_ABBR_RU, fontsize=7.5)
        ax.set_xlim(0.5, 12.5)
        ax.grid(axis="y", color="#e1e0d9", linewidth=0.8)
        if i % ncols == 0:
            ax.set_ylabel("темп, с/км")

    for j in range(len(zone_names), len(axes)):
        axes[j].axis("off")

    fig.suptitle("6. Темп по пульсовым зонам, по месяцам — наложение по годам (интервалы/сплиты, калибровка дорожки)",
                 fontsize=11, y=1.0)
    year_handles = [plt.Line2D([0], [0], color=colors[y], linewidth=2, marker="o", markersize=5, label=str(int(y)))
                    for y in years]
    fig.legend(handles=year_handles, loc="upper center", bbox_to_anchor=(0.5, 0.0),
               ncol=min(len(year_handles), 8), fontsize=8, frameon=True, title="год")
    fig.tight_layout(rect=(0, 0.05, 1, 0.96))
    return fig_to_base64(fig)


# --------------------------------------------------------------------------
# ОБЪЁМ БЕГА -> БУДУЩЕЕ ИЗМЕНЕНИЕ EF (портировано из garmin_calibration_fit.py,
# analyze_volume_ef_response — используется в разделе 12)
# --------------------------------------------------------------------------
# В отличие от оригинала в garmin_calibration_fit.py, здесь оставлена только
# объёмная (by_rolling_volume_km_per_week) версия анализа — вариант по ACWR
# (by_acwr) в отчёте не используется, а его расчёт тянул за собой отдельную
# инфраструктуру (daily_total_load/stimulus_map/rest_hr/sex), которая иначе
# нигде в build_report.py не нужна. Сезонная поправка EF переиспользуется из
# compute_easy_ef()/add_seasonally_adjusted_ef() (единая точка расчёта EF, см.
# п.4/п.2) — считать её заново не нужно.

def pearsonr_approx(x, y):
    """Коэффициент корреляции + грубая p-value (нормальное приближение t-статистики, без
    scipy) — только для ориентировочной оценки величины связи, не formal significance test."""
    if len(x) < 3 or np.std(x) < 1e-9 or np.std(y) < 1e-9:
        return None, None
    r = float(np.corrcoef(x, y)[0, 1])
    n = len(x)
    denom = max(1e-9, 1 - r ** 2)
    t = r * math.sqrt((n - 2) / denom)
    p_approx = 2 * (1 - 0.5 * (1 + math.erf(abs(t) / math.sqrt(2))))
    return round(r, 3), round(float(p_approx), 4)


def analyze_volume_ef_response(activities, easy_ef_df, rolling_weeks=4, horizon_weeks=4,
                                n_bins=5, min_points=20):
    """Дозозависимость 'объём бега -> будущее изменение EF': для каждой недели считается
    скользящий за rolling_weeks недель объём бега (км/нед, ВСЕ беговые тренировки) и сезонно-
    детрендированная EF лёгкого бега (easy_ef_df/ef_seasadj — переиспользуем расчёт из
    compute_easy_ef, а не считаем сезонность заново), затем — изменение EF через horizon_weeks
    недель вперёд. Недели бьются на n_bins квантильных корзин по объёму, и по каждой корзине
    считается среднее будущее изменение EF — так находится (a) объём с лучшим последующим
    ростом EF, (b) объём, выше которого рост в среднем устойчиво сменяется спадом.

    Не formal dose-response фит, а описательная оценка направления и порога; недельные окна
    пересекаются (скользящее окно) — correlation_p_approx не учитывает автокорреляцию и
    оптимистична, смотрите на n_weeks и величину r, а не на p как на строгий тест значимости."""
    if len(easy_ef_df) < min_points:
        return {"ok": False, "reason": f"недостаточно EF-измерений лёгкого бега "
                                        f"({len(easy_ef_df)}, нужно >= {min_points})"}

    day0 = activities["date"].min().normalize()
    day1 = activities["date"].max().normalize()
    n_days = (day1 - day0).days + 1
    n_weeks = n_days // 7
    if n_weeks < 2 * (rolling_weeks + horizon_weeks):
        return {"ok": False, "reason": f"недостаточно недель охвата ({n_weeks}) для окна "
                                        f"{rolling_weeks}+{horizon_weeks} нед."}

    daily_km = activities.groupby(activities["date"].dt.normalize())["distance_m"].sum() / 1000.0
    all_days = pd.date_range(day0, day1, freq="D")
    daily_km = daily_km.reindex(all_days, fill_value=0.0).values

    ef_idx = (easy_ef_df["date"].dt.normalize() - day0).dt.days.values
    ef_vals = easy_ef_df["ef_seasadj"].values
    ef_week = np.full(n_weeks, np.nan)
    for w in range(n_weeks):
        mask = (ef_idx >= w * 7) & (ef_idx < (w + 1) * 7)
        if mask.any():
            ef_week[w] = float(np.mean(ef_vals[mask]))
    have = ~np.isnan(ef_week)
    if have.sum() < min_points:
        return {"ok": False, "reason": "недостаточно недель с EF-измерениями после недельной агрегации"}
    ef_week_interp = np.interp(np.arange(n_weeks), np.flatnonzero(have), ef_week[have])

    vol_roll = np.array([
        daily_km[max(0, (w + 1) * 7 - rolling_weeks * 7):(w + 1) * 7].sum() / rolling_weeks
        for w in range(n_weeks)
    ])

    valid_w = np.arange(n_weeks - horizon_weeks)
    delta_ef = ef_week_interp[valid_w + horizon_weeks] - ef_week_interp[valid_w]
    vol_w = vol_roll[valid_w]

    mask = ~np.isnan(vol_w) & ~np.isnan(delta_ef) & (vol_w > 0)
    x, y = vol_w[mask], delta_ef[mask]
    if len(x) < n_bins * 3:
        return {"ok": False, "reason": f"недостаточно недель с валидным объёмом ({len(x)})"}

    order = np.argsort(x)
    x_sorted, y_sorted = x[order], y[order]
    edges = np.array_split(np.arange(len(x_sorted)), n_bins)
    delta_key = f"mean_delta_ef_next_{horizon_weeks}w"
    bins = []
    for e in edges:
        if len(e) == 0:
            continue
        bins.append({
            "rolling_volume_km_per_week_range": [round(float(x_sorted[e].min()), 2), round(float(x_sorted[e].max()), 2)],
            "rolling_volume_km_per_week_mean": round(float(x_sorted[e].mean()), 2),
            delta_key: round(float(y_sorted[e].mean()), 4),
            "n_weeks": int(len(e)),
        })
    best = max(bins, key=lambda b: b[delta_key])
    decline_threshold = None
    for i in range(len(bins) - 1, -1, -1):
        if bins[i][delta_key] < 0 and all(b[delta_key] < 0 for b in bins[i:]):
            decline_threshold = bins[i]["rolling_volume_km_per_week_range"][0]
        else:
            break
    r, p = pearsonr_approx(x, y)

    return {
        "ok": True,
        "method": f"недельная агрегация; объём = скользящие {rolling_weeks} нед. (км/нед, все "
                  f"беговые тренировки), EF = сезонно-детрендированная (лёгкий бег, интерполяция "
                  f"между неделями с измерениями); изменение EF считается через {horizon_weeks} "
                  f"нед. вперёд; корзины квантильные по {n_bins}.",
        "n_weeks_total": int(n_weeks),
        "by_rolling_volume_km_per_week": {
            "ok": True,
            "n_weeks": int(len(x)),
            "bins": bins,
            "best_bin": best,
            "decline_threshold": decline_threshold,
            "correlation_r": r,
            "correlation_p_approx": p,
        },
    }


# --------------------------------------------------------------------------
# 9. ТЕКСТОВЫЙ ОТЧЁТ (оптимальный объём и структура, всё в %, сумма = 100%)
# --------------------------------------------------------------------------

def current_zone_shares(intervals, zones, activities, weeks_back=12):
    cutoff = activities["date"].max() - pd.Timedelta(weeks=weeks_back)
    recent_ids = set(activities.loc[activities["date"] >= cutoff, "activity_id"])
    iv = intervals[intervals["activity_id"].isin(recent_ids) & intervals["avg_hr"].notna()].copy()

    def zone_of(hr):
        for name, lo, hi in zones:
            if lo <= hr <= hi:
                return name
        return None

    iv["zone"] = iv["avg_hr"].apply(zone_of)
    iv = iv.dropna(subset=["zone"])
    tot = iv["duration_s"].sum()
    shares = iv.groupby("zone")["duration_s"].sum() / tot * 100
    order = [z[0] for z in zones]
    shares = shares.reindex(order).fillna(0.0)
    return shares


def zone_share_time_series(intervals, zones, activities, window_weeks=4, n_easy_zones=3):
    """Композиция недельного объёма В ДИНАМИКЕ (скользящее окно window_weeks недель на каждую
    неделю истории, знаменатель — общий объём, ВСЕ лапы с известным пульсом), 5 линий:

    - первые n_easy_zones зон из списка zones (по умолчанию Z1 восстановление, Z2 лёгкий/
      аэробный, Z3 марафонский темп) — числитель ВСЕ лапы в этой HR-зоне, как и раньше:
      эти зоны набираются обычным непрерывным бегом, а не 'рабочими' отрезками;
    - "Порог" и "МПК" — НЕ по зоне пульса, а по ФИЗИЧЕСКОЙ ДЛИТЕЛЬНОСТИ обнаруженного рабочего
      отрезка (detect_quality_work_laps, та же детекция и те же пороги 6/30 минут, что и в
      weekly_mpk_threshold_minutes для 3c), независимо от того, в какую HR-зону попал пульс.

    См. диалог 2026-08-17: сначала "качественные" линии здесь тоже строились по HR-зоне (Z4/Z5)
    — но это ДРУГАЯ классификация, чем в 3c (там — по длительности отрезка), и в отдельных
    периодах (напр. май2025-янв2026) они расходились: короткие МПК-интервалы, где пульс не успел
    разогнаться до Z5, физиологически попадают в Z4, и "маскировали" в Z4-зоне провал именно
    ДЛИННОЙ пороговой работы, который чётко виден в 3c. Теперь "Порог"/"МПК" здесь считаются
    ТЕМ ЖЕ способом, что и в 3c (по длительности, а не по зоне) — поэтому график должен сходиться
    с 3c по форме (просто в % от объёма, а не в абсолютных минутах).

    ЭТО ВАЖНОЕ ОГРАНИЧЕНИЕ, ЛЕГКО ТЕРЯЕТСЯ ПРИ РЕФАКТОРИНГЕ (уже трижды случайно откатывалось
    при правке соседних функций, см. диалог 2026-08-17) — не трогать без явной необходимости и
    обязательно проверять на реальной БД перед коммитом: (1) все 5 линий ненулевые, (2) "Порог"
    в этой функции по форме похож на threshold_min_roll из 3c, а не на долю времени в Z4.

    Окно window_weeks по умолчанию совпадает с 3c (4 недели)."""
    def zone_of(hr):
        for name, lo, hi in zones:
            if lo <= hr <= hi:
                return name
        return None

    easy_names = [z[0] for z in zones][:n_easy_zones]
    category_names = easy_names + ["Порог (6–30 мин)", "МПК (≤6 мин)"]

    # знаменатель: общий объём (секунды) по неделям, по ВСЕМ лапам с известным пульсом.
    iv_all = intervals[intervals["avg_hr"].notna()].copy()
    iv_all = iv_all.merge(activities[["activity_id", "date"]], on="activity_id", how="inner")
    iv_all["week"] = iv_all["date"].dt.to_period("W-SUN").apply(lambda p: p.start_time)
    total_weekly = iv_all.groupby("week")["duration_s"].sum()

    # Z1/Z2/Z3: числитель — все лапы в этой HR-зоне (как и раньше).
    iv_all["zone"] = iv_all["avg_hr"].apply(zone_of)
    weekly_zones = iv_all[iv_all["zone"].isin(easy_names)].pivot_table(
        index="week", columns="zone", values="duration_s", aggfunc="sum", fill_value=0.0)
    for name in easy_names:
        if name not in weekly_zones.columns:
            weekly_zones[name] = 0.0

    # Порог/МПК: числитель — обнаруженные рабочие отрезки (см. detect_quality_work_laps), те же
    # пороги длительности, что и в weekly_mpk_threshold_minutes (3c) — НЕ по зоне пульса.
    laps = detect_quality_work_laps(activities, intervals)
    if len(laps):
        laps = laps.copy()
        laps["bucket"] = np.where(laps["duration_s"] <= 360, "МПК (≤6 мин)", "Порог (6–30 мин)")
        weekly_bucket = laps.pivot_table(
            index="week", columns="bucket", values="duration_s", aggfunc="sum", fill_value=0.0)
    else:
        weekly_bucket = pd.DataFrame(columns=["Порог (6–30 мин)", "МПК (≤6 мин)"])
    for name in ["Порог (6–30 мин)", "МПК (≤6 мин)"]:
        if name not in weekly_bucket.columns:
            weekly_bucket[name] = 0.0

    if not len(weekly_zones) and not len(weekly_bucket):
        return pd.DataFrame(columns=category_names)

    weekly = pd.DataFrame(index=sorted(set(weekly_zones.index) | set(weekly_bucket.index)))
    for name in easy_names:
        weekly[name] = weekly_zones[name].reindex(weekly.index).fillna(0.0) if len(weekly_zones) else 0.0
    for name in ["Порог (6–30 мин)", "МПК (≤6 мин)"]:
        weekly[name] = weekly_bucket[name].reindex(weekly.index).fillna(0.0) if len(weekly_bucket) else 0.0
    weekly = weekly[category_names]

    all_weeks = pd.date_range(
        min(weekly.index.min(), total_weekly.index.min()),
        max(weekly.index.max(), total_weekly.index.max()),
        freq="7D",
    )
    weekly = weekly.reindex(all_weeks, fill_value=0.0)
    total_weekly = total_weekly.reindex(all_weeks, fill_value=0.0)

    rolled = weekly.rolling(window_weeks, min_periods=1).sum()
    total_rolled = total_weekly.rolling(window_weeks, min_periods=1).sum()
    shares = rolled.div(total_rolled.replace(0, np.nan), axis=0) * 100
    return shares

def render_zone_time_chart(zones, zone_shares_ts, window_weeks):
    """Интерактивный график 'факт по зонам В ДИНАМИКЕ' (линии, скользящее окно window_weeks
    недель, см. zone_share_time_series) vs 'рекомендовано' (горизонтальная пунктирная линия
    того же цвета; уровень зависит от выбранной в <select> целевой дистанции).

    ОТДЕЛЬНЫЙ SVG-график НА КАЖДУЮ КАТЕГОРИЮ, друг под другом, шириной как остальные графики
    отчёта (max-width как у img_tag) — раньше у категорий с единицами процентов на фоне тех, где
    десятки процентов, на общей шкале 0-70% динамика была не видна (линия почти прижата к нулю),
    даже если внутри себя она менялась в разы. У каждого графика своя ось Y.

    Категории — это zone_shares_ts.columns из zone_share_time_series: первые n_easy_zones
    зон по HR (обычно Z1/Z2/Z3), плюс "Порог"/"МПК" по длительности отрезка (не по зоне пульса,
    см. докстринг zone_share_time_series — важно, чтобы "Порог" здесь сходился по форме с
    threshold_min_roll из 3c). Здесь эта функция общая и просто рисует то, что ей передали —
    имена категорий не хардкодятся, только их порядок (последние два — "качественные").

    Масштаб оси Y ПЕРЕСЧИТЫВАЕТСЯ при каждой смене дистанции в <select> (а не фиксируется один
    раз по максимуму рекомендованной доли СРЕДИ ВСЕХ дистанций) — иначе для малопроцентных
    категорий ("Порог"/"МПК") ось подстраивалась бы под худший случай (напр. рекомендация для
    3 км) и при выбранной по умолчанию дистанции 42 км (где рекомендация намного ниже) факт и
    цель были бы прижаты к низу оси. Теперь ось строится под данные + цель ИМЕННО для выбранной
    сейчас дистанции.

    Заменяет прежний render_zone_distance_comparison (статичный срез последних N недель) —
    теперь сразу видно, за какой период держится факт, а не только его последнее значение.
    Самодостаточный SVG/JS-блок без внешних зависимостей (без графических библиотек) — работает
    и при открытии отчёта локально без интернета."""
    category_names = list(zone_shares_ts.columns) if len(zone_shares_ts) else \
        [z[0] for z in zones][:3] + ["Порог (6–30 мин)", "МПК (≤6 мин)"]
    # своя палитра (не TYPE_COLORS — та про типы тренировок, а не про эти категории) — первые
    # 3 цвета для HR-зон Z1/Z2/Z3, последние 2 (красный/тёмно-красный) для Порог/МПК — те же
    # цвета, что раньше были у Z4/Z5, для визуальной преемственности.
    zone_palette = ["#3B7DD8", "#4C9F70", "#E0A62C", "#E0574C", "#8C2A22"]
    colors = {name: zone_palette[i % len(zone_palette)] for i, name in enumerate(category_names)}
    ids = {name: f"zoneTimeSvg_{i}" for i, name in enumerate(category_names)}
    zone_names = category_names

    if len(zone_shares_ts):
        dates = [d.strftime("%Y-%m-%d") for d in zone_shares_ts.index]
        series = {
            name: [None if pd.isna(v) else round(float(v), 1) for v in zone_shares_ts[name]]
            for name in zone_names
        }
    else:
        dates, series = [], {name: [] for name in zone_names}

    # сопоставление категории -> буква зоны в DIST_TARGETS: свои зоны как есть (Z1/Z2/Z3),
    # "Порог" условно приравнен к рекомендации для Z4 (пороговая зона), "МПК" — к Z5 (VO2max) —
    # это тот же смысл, просто другая классификация (по длительности, а не по пульсу).
    target_letter_by_name = {}
    for z in zones[:3]:
        target_letter_by_name[z[0]] = z[0].split(" ")[0]
    if "Порог (6–30 мин)" in zone_names:
        target_letter_by_name["Порог (6–30 мин)"] = "Z4"
    if "МПК (≤6 мин)" in zone_names:
        target_letter_by_name["МПК (≤6 мин)"] = "Z5"

    targets_by_dist = {
        str(d): {name: DIST_TARGETS[d][target_letter_by_name[name]] for name in zone_names}
        for d in DIST_KM
    }
    default_dist = 42 if 42 in DIST_KM else DIST_KM[len(DIST_KM) // 2]
    options_html = "".join(
        f'<option value="{d}"{" selected" if d == default_dist else ""}>{d} км</option>'
        for d in DIST_KM
    )

    dates_json = json.dumps(dates)
    series_json = json.dumps(series)
    colors_json = json.dumps(colors, ensure_ascii=False)
    ids_json = json.dumps(ids, ensure_ascii=False)
    targets_json = json.dumps(targets_by_dist, ensure_ascii=False)
    zone_names_json = json.dumps(zone_names, ensure_ascii=False)

    # ширина как у остальных графиков отчёта (см. img_tag: max-width:1100px)
    charts_html = "".join(
        f'''
      <div style="margin-bottom:14px;">
        <div style="font-size:14px;font-weight:600;color:{colors[name]};margin-bottom:2px;">{name}</div>
        <svg id="{ids[name]}" viewBox="0 0 1100 220" style="width:100%;max-width:1100px;display:block;margin:0 auto;background:#fff;"></svg>
      </div>'''
        for name in zone_names
    )

    return f'''
    <div>
      <label for="zoneTimeDistSelect" style="font-size:14px;">Целевая дистанция (пунктирная линия — рекомендовано): </label>
      <select id="zoneTimeDistSelect" style="font-size:14px;padding:4px 8px;">{options_html}</select>
      <div style="margin-top:10px;">
        {charts_html}
      </div>
      <p class="meta">Сплошная линия — факт (доля недельного объёма, скользящее окно
      {window_weeks} недель — то же окно, что и в разделе 8). "Восстановление"/"Лёгкий"/"Марафонский темп"
      считаются по пульсовой зоне (как раньше); "Порог" и "МПК" — НЕ по зоне пульса, а по
      физической длительности рабочего отрезка (6-30 мин и ≤6 мин соответственно, та же детекция,
      что и в разделе 8) — поэтому "Порог" здесь должен по форме сходиться с оранжевой линией в разделе 8, а не
      с долей времени в пульсовой зоне Z4 (короткие МПК-интервалы физиологически часто попадают в
      Z4 по пульсу, не успев разогнаться до Z5, и раньше маскировали в HR-зоне провал именно
      длинной пороговой работы — см. диалог 2026-08-17). Пунктирная горизонтальная линия —
      рекомендуемая доля для выбранной дистанции (см. раздел 12; для "Порог"/"МПК" использована
      рекомендация для зон Z4/Z5 соответственно), не меняется во времени, это ориентир, а не факт.
      У каждой категории своя ось Y, масштаб которой пересчитывается под факт и рекомендацию
      именно выбранной дистанции.</p>
    </div>
    <script>
      (function() {{
        var DATES = {dates_json};
        var SERIES = {series_json};
        var COLORS = {colors_json};
        var IDS = {ids_json};
        var TARGETS = {targets_json};
        var ZONE_NAMES = {zone_names_json};
        var n = DATES.length;

        function svgEl(tag, attrs) {{
          var el = document.createElementNS("http://www.w3.org/2000/svg", tag);
          for (var k in attrs) el.setAttribute(k, attrs[k]);
          return el;
        }}

        // ось X форматируем так же, как matplotlib в 3c/3a/3b (mdates.DateFormatter("%b %y"),
        // MonthLocator(interval=2)) — сокращённое англ. название месяца + 2-значный год,
        // тик на каждый 2-й месяц, а не "каждая n-я неделя".
        var MONTH_ABBR = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
        function fmtMonthYear(dateStr) {{
          var y = parseInt(dateStr.slice(0, 4), 10);
          var m = parseInt(dateStr.slice(5, 7), 10) - 1;
          return MONTH_ABBR[m] + " " + String(y).slice(2);
        }}

        var W = 1100, H = 220, PAD_L = 46, PAD_R = 12, PAD_T = 10, PAD_B = 34;
        var plotW = W - PAD_L - PAD_R, plotH = H - PAD_T - PAD_B;

        function niceStep(maxVal) {{
          var raw = maxVal / 4;
          var pow10 = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
          var candidates = [1, 2, 2.5, 5, 10];
          for (var i = 0; i < candidates.length; i++) {{
            var step = candidates[i] * pow10;
            if (step >= raw) return step;
          }}
          return 10 * pow10;
        }}

        function xAt(i) {{ return PAD_L + (n <= 1 ? 0 : i / (n - 1) * plotW); }}

        // полная перерисовка ОДНОЙ зоны под ТЕКУЩУЮ выбранную дистанцию — ось Y считается
        // заново каждый раз (dataMax этой зоны + рекомендация именно для этой дистанции), а
        // не один раз по худшему случаю среди всех дистанций.
        function drawZone(name, dist) {{
          var svg = document.getElementById(IDS[name]);
          while (svg.firstChild) svg.removeChild(svg.firstChild);

          var vals = SERIES[name].filter(function(v) {{ return v !== null; }});
          var dataMax = vals.length ? Math.max.apply(null, vals) : 0;
          var targetVal = TARGETS[String(dist)][name];
          var maxY = Math.max(dataMax, targetVal) * 1.15;
          if (!isFinite(maxY) || maxY <= 0) maxY = 1;
          var step = niceStep(maxY);
          maxY = Math.ceil(maxY / step) * step;

          function yAt(v) {{ return PAD_T + plotH - (v / maxY) * plotH; }}

          if (n > 0) {{
            for (var gy = 0; gy <= maxY + 1e-9; gy += step) {{
              svg.appendChild(svgEl("line", {{x1: PAD_L, x2: W - PAD_R, y1: yAt(gy), y2: yAt(gy), stroke: "#eee"}}));
              var lbl = svgEl("text", {{x: PAD_L - 6, y: yAt(gy) + 3, "text-anchor": "end", "font-size": "10", fill: "#666"}});
              lbl.textContent = Math.round(gy * 10) / 10 + "%";
              svg.appendChild(lbl);
            }}

            var lastMonthKey = null, tickCount = 0;
            for (var i = 0; i < n; i++) {{
              var monthKey = DATES[i].slice(0, 7);
              if (monthKey === lastMonthKey) continue;
              lastMonthKey = monthKey;
              if (tickCount % 2 === 0) {{
                var t = svgEl("text", {{x: xAt(i), y: H - PAD_B + 16, "text-anchor": "middle", "font-size": "9", fill: "#666"}});
                t.textContent = fmtMonthYear(DATES[i]);
                svg.appendChild(t);
              }}
              tickCount++;
            }}

            var d = "";
            var rawVals = SERIES[name];
            for (var j = 0; j < n; j++) {{
              if (rawVals[j] === null) continue;
              d += (d === "" ? "M" : "L") + xAt(j) + "," + yAt(rawVals[j]) + " ";
            }}
            svg.appendChild(svgEl("path", {{d: d, fill: "none", stroke: COLORS[name], "stroke-width": 2}}));
          }} else {{
            var noData = svgEl("text", {{x: W / 2, y: H / 2, "text-anchor": "middle", "font-size": "12", fill: "#999"}});
            noData.textContent = "Недостаточно данных";
            svg.appendChild(noData);
          }}

          var ty = yAt(targetVal);
          svg.appendChild(svgEl("line", {{x1: PAD_L, x2: W - PAD_R, y1: ty, y2: ty, stroke: COLORS[name],
                                           "stroke-width": 1.6, "stroke-dasharray": "6,4", opacity: 0.85}}));
        }}

        function renderAll(dist) {{
          ZONE_NAMES.forEach(function(name) {{ drawZone(name, dist); }});
        }}

        var sel = document.getElementById("zoneTimeDistSelect");
        sel.addEventListener("change", function() {{ renderAll(this.value); }});
        renderAll(sel.value);
      }})();
    </script>
    '''


def build_text_report(volume_ef_response, zones, current_shares_pct, weeks_back):
    vol = volume_ef_response.get("by_rolling_volume_km_per_week", {}) if volume_ef_response.get("ok") else {}
    best_bin = vol.get("best_bin", {})
    decline = vol.get("decline_threshold")

    parts = []
    parts.append("<h2>12. Оптимальный объём и структура недельного бега</h2>")

    if not volume_ef_response.get("ok"):
        parts.append(
            f"<p class='meta'>Оптимальный объём по модели 'объём -> будущее изменение EF' не "
            f"посчитан: {volume_ef_response.get('reason', 'недостаточно данных')}.</p>"
        )
    if best_bin:
        lo, hi = best_bin.get("rolling_volume_km_per_week_range", [None, None])
        parts.append(
            f"<p><b>Оптимальный недельный объём (по модели 'объём -> будущее изменение EF', "
            f"скользящие 4 недели):</b> "
            f"~<b>{lo:.0f}–{hi:.0f} км/неделю</b> "
            f"(среднее в лучшей корзине {best_bin.get('rolling_volume_km_per_week_mean', float('nan')):.0f} км/нед, "
            f"n={best_bin.get('n_weeks')} недель) — именно в этом диапазоне рост объёма даёт наибольший "
            f"прирост эффективности (EF) в следующие 4 недели.</p>"
        )
        if decline is not None:
            parts.append(
                f"<p>Выше <b>~{decline:.0f} км/неделю</b> отклик становится отрицательным "
                f"(корреляция объёма и роста EF: r={vol.get('correlation_r')}, "
                f"p≈{vol.get('correlation_p_approx')}) — переизбыток объёма ассоциирован со снижением "
                f"эффективности в последующие недели, вероятно из-за накопленного утомления.</p>"
            )
    elif volume_ef_response.get("ok"):
        parts.append("<p>Не удалось выделить корзину объёма с приростом EF по имеющимся данным.</p>")

    # ---- рекомендуемое распределение по зонам ДЛЯ РАЗНЫХ ЦЕЛЕВЫХ ДИСТАНЦИЙ ----
    # DIST_KM/DIST_TARGETS — теперь константы модуля (см. выше файла), а не локальные
    # переменные: нужны также render_zone_distance_comparison() для интерактивного графика.
    zone_key = {  # короткий ключ Z1..Z5 -> полное имя зоны из build_zones
        name.split(" ")[0]: name for name, _, _ in zones
    }

    parts.append(f"<h3>Текущее фактическое распределение по пульсовым зонам (последние {weeks_back} недель)</h3>")
    parts.append(
        "<p class='meta'>Важно: здесь Z3 — это ВСЁ время с пульсом 140-159, в любых тренировках "
        "(широкое понятие пульсовой зоны). Это НЕ то же самое, что 'марафонские отрезки' из раздела 10 "
        "(там — узкое понятие: только вставки в темпе 285-335 с/км внутри длительных/лёгких). Пульсовая "
        "зона шире: в неё попадает и снос пульса к концу длинной, и просто умеренно-тяжёлый бег — "
        "поэтому её текущая доля (см. таблицу) заметно больше доли собственно марафонских вставок.</p>"
    )
    parts.append("<table><tr><th>Зона</th><th>Сейчас, % времени</th></tr>")
    for name, _, _ in zones:
        cur = current_shares_pct.get(name, 0.0)
        parts.append(f"<tr><td>{name}</td><td>{cur:.1f}%</td></tr>")
    parts.append(f"<tr><td><b>Сумма</b></td><td><b>{current_shares_pct.sum():.1f}%</b></td></tr>")
    parts.append("</table>")

    parts.append("<h3>Рекомендуемое распределение по зонам для разных целевых дистанций, %</h3>")
    parts.append(
        "<p class='meta'>Таблица транспонирована: строки — пульсовые зоны, столбцы — целевая дистанция "
        "забега (км). Каждый столбец в сумме даёт 100% недельного времени бега. Колонка 42 км — тот же "
        "ориентир марафонской подготовки, что и выше по отчёту.</p>"
    )
    parts.append("<table><tr><th>Зона</th>")
    for d in DIST_KM:
        parts.append(f"<th>{d} км</th>")
    parts.append("</tr>")
    for zkey in ["Z1", "Z2", "Z3", "Z4", "Z5"]:
        full_name = zone_key.get(zkey, zkey)
        parts.append(f"<tr><td>{full_name}</td>")
        for d in DIST_KM:
            parts.append(f"<td>{DIST_TARGETS[d][zkey]}%</td>")
        parts.append("</tr>")
    parts.append("<tr><td><b>Сумма</b></td>")
    for d in DIST_KM:
        parts.append(f"<td><b>{sum(DIST_TARGETS[d].values())}%</b></td>")
    parts.append("</tr></table>")

    parts.append(
        "<div class='note'>Логика таблицы: короче дистанция → больше доля высокой интенсивности "
        "(Z4/Z5 — темп забега там объективно требует работы у порога и выше); длиннее дистанция "
        "(особенно ультра, 60+ км) → доля Z1/Z2 растёт, а Z3 ('гоночный/специфичный' темп) наоборот "
        "снижается, потому что на ультрах реальный соревновательный темп физиологически является "
        "аэробным, а не 'марафонской' интенсивностью. Колонка 42 км согласована с фактической "
        "историей тренировок этого атлета (см. таблицу выше и раздел 10 — ограничение доли марафонских "
        "вставок ~18-20%); остальные дистанции достроены по общепринятой логике периодизации "
        "(Daniels/Pfitzinger для шоссе, стандартная ультра-практика для 60+ км) — это не подгонка "
        "под фактические данные этого атлета на этих дистанциях, а ориентир.</div>"
    )
    return "".join(parts)


# --------------------------------------------------------------------------
# СБОРКА HTML
# --------------------------------------------------------------------------

HTML_HEAD = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<title>Отчёт по тренировкам</title>
<style>
  body { font-family: -apple-system, Segoe UI, Roboto, Arial, sans-serif; max-width: 1150px;
         margin: 0 auto; padding: 24px; color: #222; background: #fafafa; }
  h1 { border-bottom: 3px solid #3B7DD8; padding-bottom: 8px; }
  h2 { margin-top: 40px; color: #1A3A5C; border-bottom: 1px solid #ddd; padding-bottom: 4px; }
  h3 { color: #333; }
  .chart-block { background: white; padding: 16px; margin: 16px 0; border-radius: 8px;
                 box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
  table { border-collapse: collapse; width: 100%; margin: 12px 0; background: white; }
  th, td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; font-size: 13.5px; }
  th { background: #eef3fa; }
  tr:nth-child(even) { background: #f7f9fc; }
  .meta { color: #666; font-size: 13px; }
  .note { background: #fff8e6; border-left: 4px solid #E0A62C; padding: 10px 14px; margin: 12px 0; font-size: 13.5px; }
  ul { font-size: 14px; }
</style>
</head>
<body>
"""

HTML_TAIL = "</body></html>"


def main(db_path, out_path, json_path=None):
    activities_raw, intervals_raw, lt, wellness, calib = load_data(db_path, json_path)

    # --- калибровка дорожки, ДО всех расчётов ---
    # Коэффициент считается прямо по БД (fit_treadmill_pace_calibration) — calibration_profile.json
    # для этого больше не нужен.
    tc = fit_treadmill_pace_calibration(activities_raw)
    activities, intervals, tm_info = apply_treadmill_calibration(activities_raw, intervals_raw, tc)

    # --- коррекция на уклон (GAP), ДО всех расчётов, СРАЗУ после калибровки дорожки ---
    # С этого момента avg_pace_s_per_km везде далее по пайплайну — это уже темп с учётом уклона
    # (там, где он доступен, т.е. на уличных пробежках). Реальный "плоский" темп — в
    # avg_pace_s_per_km_flat. См. docstring apply_grade_adjustment().
    activities, intervals, gap_info = apply_grade_adjustment(activities, intervals)

    # Единая временная ось для графиков отчёта, где ось X — календарная дата (см. диалог
    # 2026-08-20) — раньше каждый такой график масштабировал ось X под диапазон СВОИХ данных:
    # например, история ПАНО Гармина (раздел 5) короче истории тренировок на 1-2 недели с каждой
    # стороны, а дневной ряд ACWR (раздел 9) на 1 день уже недельной сетки остальных графиков
    # (compute_acwr реиндексируется по своему daily_load.index.min()/max(), а не по общей сетке
    # недель) — из-за этого при сравнении графиков друг с другом (одна и та же неделя должна быть
    # в одном и том же месте по горизонтали) они были на пиксель-два не совпадающими. WEEKLY_XLIM
    # — для недельных/дневных графиков (объём, доли типов, VO2max, качество, ACWR). EF/VDOT (п.4)
    # и темп по зонам (п.6) больше не используют дату по оси X — оба перерисованы "наложением по
    # годам" (месяц 1-12 по горизонтали, цвет = год), поэтому единая календарная ось им не нужна.
    week_start = activities["date"].min().to_period("W-SUN").start_time
    week_end = activities["date"].max().to_period("W-SUN").start_time
    WEEKLY_XLIM = (week_start, week_end)

    charts = {}
    tables = {}

    # Раздел 1 (объём по неделям)
    wk_km = weekly_volume(activities)
    charts["1"] = plot_weekly_volume(wk_km, xlim=WEEKLY_XLIM)

    # Раздел 6 (ПАНО): приоритетно из собственной истории Гармина (lactate_threshold, Firstbeat),
    # это прямой показатель Гармина, а не наша реконструкция. pano_table() ниже — по-прежнему
    # считается (как самостоятельная диагностика по непрерывным эффортам и как резерв на
    # случай отсутствия истории lactate_threshold), но её порог "устойчиво высокий пульс"
    # теперь берётся с отступом от гарминовского ПАНО, а не хардкодится (было 165).
    pano_garmin, pano_garmin_info = garmin_pano_estimate(lt)
    pano_table_min_hr = (pano_garmin - 15) if pano_garmin is not None else None
    pano_df, pano_estimate, pano_raw = pano_table(activities, min_hr=pano_table_min_hr)
    tables["3"] = pano_df

    if pano_garmin is not None:
        pano_final = pano_garmin
        pano_source_note = (
            f"Источник — история ПАНО Гармина (lactate_threshold): среднее {pano_garmin} уд/мин "
            f"за последние {pano_garmin_info['window_days']} дн. до {pano_garmin_info['last_date']} "
            f"(n={pano_garmin_info['n']} точек)."
        )
    elif not np.isnan(pano_estimate):
        pano_final = int(round(pano_estimate))
        pano_source_note = (
            "История ПАНО Гармина недоступна — оценка по непрерывным эффортам ≥35 мин из таблицы выше."
        )
    else:
        pano_final = int(round(activities["avg_hr"].quantile(0.95)))
        pano_source_note = (
            "Ни история ПАНО Гармина, ни непрерывные эффорты недоступны — грубая оценка "
            "по 95-му перцентилю пульса среди всех тренировок."
        )

    # max_hr — из калибровочного профиля (meta.load_params.max_hr), если задан вручную; иначе
    # оценка по самим тренировкам, но НЕ голым activities["max_hr"].max() (см. диалог 2026-08-18:
    # на одной из баз голый max() давал 230 уд/мин из-за одиночного выброса датчика на тредмиле) —
    # estimate_max_hr() отсекает разовые скачки, несовместимые с усилием самой тренировки.
    max_hr_manual = calib.get("meta", {}).get("load_params", {}).get("max_hr")
    if max_hr_manual:
        max_hr, max_hr_info = int(max_hr_manual), {"source": "calibration_profile (ручной ввод)", "n_excluded": 0}
    else:
        max_hr, max_hr_info = estimate_max_hr(activities)

    # общий расчёт EF лёгких пробежек с сезонной поправкой — используется и в п.4, и в п.2
    easy_ef_df, seasonal_info = compute_easy_ef(activities)

    # Раздел 7 (оптимальный пульс лёгкого бега) — считаем заранее, он нужен для построения зон
    ef_hr_bins, easy_df, peak_center, peak_found, peak_vertices = easy_ef_by_hr(easy_ef_df, pano=pano_final)
    charts["6"] = plot_easy_ef_by_hr(ef_hr_bins, peak_center, peak_found)

    # Раздел 6b (продольная проверка: пульс на easy по времени под нагрузкой vs отклик формы
    # в следующем квартале, см. диалог 2026-08-18). Считается независимо от EF-пика — это
    # страховка для fallback-медианы, а не альтернативный основной метод.
    hr_resp_df, hr_resp_info, resp_ceiling_hr = easy_hr_fitness_response(easy_ef_df, lt, pano_final)
    if hr_resp_info is not None:
        charts["6b"] = plot_hr_fitness_response(hr_resp_df)
        tables["6b"] = hr_resp_df.reset_index().rename(columns={"index": "q"}).assign(
            Квартал=lambda d: d["q"].astype(str),
            **{
                "ЧСС (взвеш. по времени), уд/мин": hr_resp_df["weighted_hr"].round(1).values,
                "% от ПАНО": hr_resp_df["pct_pano"].round(1).values,
                "Объём, мин": hr_resp_df["load_min"].round(0).values,
                "Пороговый темп, с/км": hr_resp_df["thr_pace"].round(0).values,
                "Δ темпа в след. кв., с/км": hr_resp_df["delta_next"].round(0).values,
            }
        )[["Квартал", "ЧСС (взвеш. по времени), уд/мин", "% от ПАНО", "Объём, мин",
           "Пороговый темп, с/км", "Δ темпа в след. кв., с/км"]]

    # easy_center — оставлен только как ИНФОРМАЦИОННАЯ точка для раздела 2/2б (пик EF или,
    # если пика нет, медиана 'easy' по всей истории). Раньше он же использовался как центр Z2
    # (center±6) — от этого отказались (см. диалог 2026-08-18): медиана смешанной выборки
    # (type_guess=='easy' объединяет recovery+лёгкие) — это не центр зоны, а точка где-то на
    # стыке двух разных по интенсивности популяций; center±6 после неё давал слишком узкую и
    # сдвинутую вниз Z2 (реальная практика "лёгких" ~142-144 не попадала во вторую зону).
    # Разбивать выборку по названию тренировки ("Recovery"/"Easy") тоже нельзя — это было бы
    # использованием прежних предположений о зонах как входа для их же построения.
    easy_center = peak_center

    # Пульс покоя — для карвоненовских (%HRR) границ Z1/Z2/Z3 в build_zones (см. её докстринг:
    # два независимых способа без ярлыков — форма распределения пульса и бэктест по методикам —
    # сошлись на границе Z1/Z2 ~136 при этом rhr/max_hr, что соответствует Карвонену 60% HRR).
    fallback_rhr = calib.get("meta", {}).get("load_params", {}).get("rest_hr")
    rhr, rhr_info = estimate_resting_hr(wellness, fallback_rhr=fallback_rhr)

    # Пульсовые зоны (используются в разделах 3, 11 и в тексте раздела 12)
    zones = build_zones(rhr, pano_final, max_hr)

    # Раздел 2 (доля марафонских отрезков — по HR-зоне Z3, см. marathon_time_per_activity)
    shares = weekly_type_shares(activities, intervals, zones)
    charts["2"] = plot_weekly_type_shares(shares, xlim=WEEKLY_XLIM)

    # Раздел 3a (EF/VDOT по гонкам)
    wk_ef_roll, wk_ef = weekly_ef(easy_ef_df)
    races, excluded_races = race_vdot_points(activities, pano_final, intervals=intervals)
    interval_vdot_df = interval_threshold_vdot_points(activities, intervals, pano=pano_final)
    charts["4a"] = plot_ef_vdot(wk_ef_roll, races, interval_points=interval_vdot_df)
    tables["4a"] = races.assign(
        Дата=races["date"].dt.strftime("%Y-%m-%d"),
        Дистанция=(races["distance_m"] / 1000).round(2).astype(str) + " км",
        Время=(races["duration_s"] / 60).round(1).astype(str) + " мин",
        Пульс=races["avg_hr"].astype(int),
        **{"VDOT (вся дистанция)": races["vdot_full"].round(1),
           "Срыв темпа?": races.apply(
               lambda r: f"да, после {r['blowup_km']:g} км" if r["blowup_detected"] else "нет", axis=1),
           "VDOT (использован)": races["vdot"].round(1)}
    )[["Дата", "name", "Дистанция", "Время", "Пульс", "VDOT (вся дистанция)",
       "Срыв темпа?", "VDOT (использован)"]].rename(columns={"name": "Название"})
    tables["4a_excluded"] = excluded_races.assign(
        Дата=excluded_races["date"].dt.strftime("%Y-%m-%d"),
        Дистанция=(excluded_races["distance_m"] / 1000).round(2).astype(str) + " км",
        Время=(excluded_races["duration_s"] / 60).round(1).astype(str) + " мин",
        Пульс=excluded_races["avg_hr"].astype(int),
        **{"% от ПАНО": (excluded_races["pct_of_pano"] * 100).round(0).astype(int).astype(str) + "%",
           "Требовалось": (excluded_races["min_pct_required"] * 100).round(0).astype(int).astype(str) + "%"}
    )[["Дата", "name", "Дистанция", "Время", "Пульс", "% от ПАНО", "Требовалось"]].rename(columns={"name": "Название"})

    # Раздел 3b (VO2max-прокси по истории ПАНО Garmin)
    lt_vo2 = garmin_vo2max_proxy(lt)
    charts["4b"] = plot_garmin_vo2max(lt_vo2, xlim=WEEKLY_XLIM)

    # Раздел 3c (частота качественных тренировок + МПК/пороговые минуты по неделям)
    weekly_quality = weekly_quality_training_frequency(activities, intervals)
    weekly_mpk_thr = weekly_mpk_threshold_minutes(activities, intervals)
    charts["4c"] = plot_weekly_quality_frequency(weekly_quality, weekly_mpk_thr=weekly_mpk_thr, xlim=WEEKLY_XLIM)

    # Раздел 3d: интерактивный график факт (в динамике) vs рекомендовано (выбор дистанции). Статичный
    # срез current_shares для раздела 12 (weeks_back=12) считается отдельно и ниже, здесь он не
    # нужен — 3d показывает всю историю, а не один срез.
    ZONE_TS_WINDOW_WEEKS = 4  # согласовано с окном 3c (weekly_mpk_threshold_minutes), чтобы графики были сопоставимы
    zone_shares_ts = zone_share_time_series(intervals, zones, activities, window_weeks=ZONE_TS_WINDOW_WEEKS)
    # СТОРОЖ: zone_share_time_series для Z1/Z2/Z3 должна считать по ВСЕМ лапам зоны (не только по
    # обнаруженным рабочим отрезкам), а "Порог"/"МПК" — по длительности отрезка, НЕ по HR-зоне
    # (см. её докстринг) — эта логика уже трижды случайно откатывалась при правках соседних
    # функций (диалог 2026-08-17) и молча либо обнуляла лёгкий/восстановительный бег, либо
    # превращала "Порог"/"МПК" обратно в долю времени в HR-зоне (что не сходится с 3c). Если
    # регрессия повторится, падаем здесь явно, а не отдаём отчёт с тихо сломанным графиком.
    if len(zone_shares_ts):
        _easy_names = [z[0] for z in zones][:3]
        _easy_means = zone_shares_ts[_easy_names].mean()
        _dead = _easy_means[_easy_means < 1.0]
        if len(_dead):
            raise AssertionError(
                "zone_share_time_series: подозрительно низкая (<1%) средняя доля у "
                f"'лёгких' зон {list(_dead.index)} — похоже, регрессия из диалога 2026-08-17 "
                "снова откатила фильтр 'только рабочие отрезки' на эти зоны. "
                "Проверь docstring zone_share_time_series перед тем, как это игнорировать."
            )
        # доля "Порога" по этой функции не обязана численно совпадать с 3c (там абсолютные
        # минуты, тут % от объёма), но должна коррелировать по динамике — если "Порог" почти
        # всегда 0, а в 3c threshold_min_roll заметно ненулевой, значит опять перепутали
        # классификацию (по зоне вместо по длительности).
        if "Порог (6–30 мин)" in zone_shares_ts.columns:
            _thr_mean = zone_shares_ts["Порог (6–30 мин)"].mean()
            _thr_min_mean = weekly_mpk_thr["threshold_min_roll"].mean() if len(weekly_mpk_thr) else 0.0
            if _thr_min_mean > 1.0 and _thr_mean < 0.05:
                raise AssertionError(
                    "zone_share_time_series: 'Порог' почти всегда 0%, хотя threshold_min_roll в "
                    "3c заметно ненулевой — похоже, 'Порог'/'МПК' снова считаются по HR-зоне, а "
                    "не по длительности отрезка (см. докстринг zone_share_time_series)."
                )
    zone_time_html = render_zone_time_chart(zones, zone_shares_ts, ZONE_TS_WINDOW_WEEKS)

    # Раздел 4a/4b (ACWR по нагрузке Garmin и по объёму)
    acwr_load, acwr_km = compute_acwr(activities)
    charts["5"] = plot_acwr(acwr_load, acwr_km, xlim=WEEKLY_XLIM)

    # Раздел 8: темп по зонам, последние 4-8 недель (берём 8)
    WEEKS_BACK_PACE = 8
    recent_pace_df, cutoff_date = recent_pace_by_zone(intervals, zones, activities, weeks_back=WEEKS_BACK_PACE)
    tables["7"] = zones_table_with_recent_pace(zones, pano_final, recent_pace_df, WEEKS_BACK_PACE)

    # Раздел 6 (вся история по кварталам, для сравнения с "актуальным" разделом 3)
    pace_grouped_m = pace_by_zone_monthly(intervals, zones, activities)
    charts["8"] = plot_pace_by_zone_monthly_by_year(pace_grouped_m, zones)

    # Раздел 5 (текст): оптимальный объём -> будущее изменение EF считается напрямую по БД,
    # calibration_profile.json не нужен
    current_shares = current_zone_shares(intervals, zones, activities, weeks_back=12)
    volume_ef_response = analyze_volume_ef_response(activities, easy_ef_df)
    text9 = build_text_report(volume_ef_response, zones, current_shares, weeks_back=12)

    # ---------------- сборка HTML ----------------
    # Период/кол-во тренировок берём прямо из БД, а не из calib['meta'] — так шапка отчёта не
    # зависит от того, передан ли calibration_profile.json.
    period = [activities["date"].min().strftime("%Y-%m-%d"), activities["date"].max().strftime("%Y-%m-%d")]
    html = [HTML_HEAD]
    html.append(f"<h1>Отчёт по тренировкам</h1>")
    html.append(f'<p class="meta">Период данных: {period[0]} — {period[1]} | '
                f'Всего тренировок: {len(activities)} | '
                f'Сформировано: {datetime.now().strftime("%Y-%m-%d %H:%M")}</p>')

    if tm_info.get("applied"):
        html.append(
            '<div class="note">Калибровка беговой дорожки применена: distance_m/темп для '
            f'{tm_info["n_treadmill_activities"]} тредмил-тренировок скорректированы на '
            f'{tm_info["factor"]:.3f} ({"+" if tm_info["pct"]>=0 else ""}{tm_info["pct"]}%). '
            'Это учтено во ВСЕХ расчётах ниже (объём, EF, VDOT, ACWR по км, зоны, темп по зонам).</div>'
        )

    if seasonal_info.get("applied"):
        html.append(
            '<div class="note">Сезонная калибровка EF применена (только по уличным тренировкам, '
            'дорожка не корректируется — там нет физического "зима хуже лета" эффекта): пик формы '
            f'около {seasonal_info["peak_around"].strftime("%d.%m")}, спад около '
            f'{seasonal_info["trough_around"].strftime("%d.%m")}, амплитуда {seasonal_info["drop_pct"]}%. '
            'Учтено в тренде EF (п.4) и в поиске оптимального пульса (п.2).</div>'
        )
    else:
        html.append(
            f'<div class="note">Сезонная калибровка EF НЕ применена: {seasonal_info.get("reason", "")}.</div>'
        )

    # Порядок разделов (перегруппировано 2026-08-20): сначала пульсовые зоны (1-3, они нужны для
    # всего остального), затем блок "Анализ прогресса" (4-6: EF/VDOT, VO2max, темп по зонам по
    # кварталам), затем блок "Анализ объёмов" (7-12: объём, частота качественной работы, ACWR,
    # марафонские отрезки, интерактивный факт/рекомендация, текстовые выводы по объёму).

    # ---- Блок "Анализ пульса" (разделы 1-3) ----
    html.append('<h1 style="margin-top:56px;">Анализ пульса</h1>')

    # Раздел 1 (ПАНО, было 6)
    html.append('<div class="chart-block">')
    html.append("<h2>1. Пульс ПАНО по непрерывным эффортам ≥ ~35 минут</h2>")
    min_hr_note = f"&ge;{int(round(pano_table_min_hr))}" if pano_table_min_hr is not None else "без ограничения снизу (нет данных для отступа от ПАНО)"
    html.append(
        f'<p class="meta">Отобраны тренировки длительностью 35-65 минут с устойчиво высоким пульсом '
        f'({min_hr_note}, плато, не интервальная структура) — типичный диапазон 10К-гонок и жёстких '
        'темповых тестов. Порог отступа считается от ПАНО (см. ниже), а не хардкодится.</p>'
    )
    html.append(tables["3"].to_html(index=False, escape=False))
    html.append(f"<p><b>Итоговая оценка ПАНО, используемая в отчёте: {pano_final} уд/мин.</b> {pano_source_note}</p>")
    html.append("</div>")

    # Раздел 2 (оптимальный пульс лёгкого бега, было 7) — общий заголовок, 2а/2б — два независимых метода
    html.append('<div class="chart-block">')
    html.append("<h2>2. Оптимальный пульс лёгкого бега — две независимые проверки</h2>")
    html.append(
        '<p class="meta">2а — кросс-секционная проверка: при каком пульсе бег экономичнее ПРЯМО '
        'СЕЙЧАС (в один момент времени). 2б ниже — продольная проверка: как пульс на лёгких связан '
        'с изменением формы В БУДУЩЕМ. Обе диагностические — не используются напрямую для построения '
        'зон в разделе 3 (там — методика Карвонена, см. её обоснование там же); это независимая '
        'сверка, сходится ли она с результатом Карвонена.</p>'
    )
    html.append(img_tag(charts["6"]))
    if peak_found:
        html.append(
            f'<p><b>Пульс, дающий максимальную эффективность на медленном беге: '
            f"~{peak_center} уд/мин</b> (устойчиво по разным диапазонам фита: "
            f"{[round(v) for v in peak_vertices]}).</p>"
        )
    else:
        html.append(
            '<div class="note">Выраженного пика эффективности НЕТ: EF практически не зависит от пульса '
            'в рабочем диапазоне лёгкого бега (проверено на сезонно скорректированных данных; '
            'вершина параболы либо отсутствует, либо гуляет более чем на 10 уд/мин между разными '
            'диапазонами фита — смешивание recovery/aerobic пробежек и сезонность отдельно проверены '
            'и не объясняют эту плоскую форму). Показана '
            f'<b>медиана пульса лёгких пробежек ~{peak_center} уд/мин</b> — это описательная точка, '
            'а не найденный оптимум (сравнение с зонами раздела 3 — там же).</div>'
        )
    html.append("</div>")

    # Раздел 2б (продольная проверка: пульс на easy по времени под нагрузкой vs отклик формы)
    if hr_resp_info is not None:
        html.append('<div class="chart-block">')
        html.append(img_tag(charts["6b"]))
        corr_txt = f"{hr_resp_info['corr']:.2f}" if hr_resp_info["corr"] is not None else "н/д (мало точек)"
        html.append(
            '<p class="meta">Столбец = пульс на лёгких/восстановительных в этом квартале '
            '(<b>% от ПАНО</b>, взвешено по времени под нагрузкой — БЕЗ учёта темпа). '
            'Цвет столбца = что случилось с пороговым темпом Гармина в <b>следующем</b> квартале: '
            'зелёный — форма выросла (порог стал быстрее), красный — стагнация/ухудшение, серый — '
            'следующего квартала ещё нет в данных. Точные цифры — в таблице под графиком. Линейная '
            f'корреляция по всей истории слабая (r={corr_txt}, n={hr_resp_info["n_quarters_valid"]} '
            'кварталов) — слишком мало точек и слишком много посторонних факторов (объём, гонки, '
            'тейпер), чтобы это было строгим доказательством причинности. Не используется напрямую '
            'для построения зон (см. раздел 3) — только как один из двух независимых аргументов в '
            'пользу того, где проходит граница Z1/Z2 (см. докстринг build_zones).</p>'
        )
        html.append(tables["6b"].to_html(index=False, escape=False))
        html.append("</div>")

    # Раздел 3 (пульсовые зоны и темп по ним, последние недели, было 8)
    html.append('<div class="chart-block">')
    html.append("<h2>3. Пульсовые зоны и актуальный темп (последние 8 недель)</h2>")
    z2_lo, z2_hi = zones[1][1], zones[1][2]
    ef_cross_check = (
        f'Для сравнения: {"пик эффективности EF~HR" if peak_found else "медиана пульса из смешанной выборки recovery+лёгкие"} '
        f'из раздела 2 составляет {easy_center} уд/мин — '
        f'{"внутри" if z2_lo <= easy_center <= z2_hi else "вне"} границ Z2.'
    )
    html.append(
        f'<p class="meta">Z1/Z2/Z3 построены по методике Карвонена (%HRR = резерв пульса = max_hr '
        f'{"−"} rhr): Z2 = 60-70% HRR при rhr={rhr:.0f} уд/мин ({rhr_info["source"]}, '
        f'{"скользящее среднее за " + str(rhr_info["window_days"]) + " дн." if rhr_info["source"]=="wellness.rhr" else "нет данных wellness"}) '
        f'и max_hr={max_hr} уд/мин ({max_hr_info["source"]}'
        + (f', отсеяно {max_hr_info["n_excluded"]} тренировок с неправдоподобным скачком max_hr '
           f'относительно среднего пульса той же тренировки — см. estimate_max_hr()'
           if max_hr_info.get("n_excluded") else '')
        + '). Смена метода с прежнего "центр±6" на Карвонена и обоснование — '
        f'см. докстринг build_zones() и диалог 2026-08-18: два независимых способа без ярлыков '
        f'("Recovery"/"Easy") — форма распределения пульса и бэктест по методикам — сошлись на границе '
        f'Z1/Z2 ≈60% HRR. {ef_cross_check} '
        f'Верхняя граница Z4 = ПАНО ({pano_final} уд/мин). Темп — с учётом калибровки дорожки, '
        f"только по тренировкам с {cutoff_date.strftime('%Y-%m-%d')} (последние 8 недель), "
        f"диапазон = 25-75 перцентиль по сплитам, чтобы отражать актуальную форму, а не всю историю.</p>"
    )
    html.append(tables["7"].to_html(index=False, escape=False))
    html.append("</div>")

    # ---- Блок "Анализ прогресса" (было 3a, 3b, 9) ----
    html.append('<h1 style="margin-top:56px;">Анализ прогресса</h1>')

    # Раздел 4 (график EF/VDOT, было 3a)
    html.append('<div class="chart-block">')
    html.append("<h2>4. Тренд эффективности (EF) и VDOT по гонкам — наложение по месяцам между годами</h2>")
    html.append(
        '<p class="meta">Оба подграфика — по месяцам (январь-декабрь), с отдельной линией/точками на каждый '
        'год (год = цвет, единый со списком годов внизу графика): так виден и сезонный ход внутри года, и '
        'сравнение одного и того же месяца между годами напрямую, без смешения с многолетним трендом формы.</p>'
    )
    html.append(img_tag(charts["4a"]))
    if len(tables["4a"]):
        html.append("<h3>Гонки, использованные для VDOT</h3>")
        html.append(tables["4a"].to_html(index=False, escape=False))
        html.append(
            '<p class="meta">"Срыв темпа" — автоматически обнаруженный участок, где темп резко проседает '
            "БЕЗ соответствующего роста пульса (пульс не растёт или даже падает вместе с замедлением) — "
            "это подпись вынужденной остановки/перехода на шаг (ЖКТ, механика и т.п.), а не физиологической "
            "усталости: при настоящем гликогеновом/кардио-отказе пульс обычно держится высоким или растёт, "
            "а не падает. Пример: Groningen 2026 — км 34-40, темп упал с 300 до 379 с/км, пульс при этом "
            "упал со 148 до 129. Для таких гонок в колонке VDOT (использован) и на графике выше "
            "используется оценка по чистому участку ДО срыва, а не по полной дистанции — иначе форма "
            "занижается на пустом месте.</p>"
        )
    if interval_vdot_df is not None and len(interval_vdot_df):
        html.append(
            f'<p class="meta">Треугольники (▲) на п.4б — вспомогательная оценка VDOT по интервальным/пороговым '
            f'тренировкам ({len(interval_vdot_df)} шт., заполняют периоды без гонок), не по гонкам; цвет — тот '
            'же год, что и у гонок этого года. Внутри тренировки лапы заметно быстрее её собственной медианы '
            'считаются "рабочими"; коротким рабочим лапам (спринт, &lt;90с) даётся низкий вес — доминирует '
            'анаэробная составляющая и задержка пульса; лапам 2.5-15 мин — максимальный вес (ближе всего к '
            'гоночному VDOT); очень длинным лапам (&gt;15 мин) вес снижен и сама оценка занижена на 2-5% как '
            'поправка на вероятный незаметный провал темпа во второй половине лапа (в лапах нет внутренних '
            'сплитов, поэтому это фиксированная поправка, а не автодетект, как для гонок). Прозрачность и '
            'размер точки = итоговая достоверность тренировки (вес лапов минус штраф за разброс между ними). '
            'Из общей серии робастно (по MAD) отброшены явные выбросы. Считать эти точки наравне с гоночным '
            'VDOT нельзя — это ориентир для периодов без стартов, не замена гонкам.</p>'
        )
    if len(tables["4a_excluded"]):
        html.append(
            '<div class="note">Исключены из VDOT (пульс ниже ожидаемого для эффорта в полную силу '
            'на такой дистанции):</div>'
        )
        html.append(tables["4a_excluded"].to_html(index=False, escape=False))
    html.append("</div>")

    # Раздел 5 (VO2max-прокси, было 3b)
    html.append('<div class="chart-block">')
    html.append("<h2>5. VO2max-прокси по истории ПАНО Garmin</h2>")
    html.append(img_tag(charts["4b"]))
    html.append(
        '<p class="meta">В выгрузке БД нет прямого поля Garmin vo2max — ни в activities, ни в wellness. '
        'Единственный собственный фитнес-показатель Гармина в базе — история ПАНО (lactate_threshold, '
        'считается через Firstbeat по фактическим тренировкам). Эта кривая — VO2max-эквивалент, '
        'посчитанный из истории ПАНО той же формулой Дэниэлса, что и VDOT в п.4 (эффорт ~60 мин) — '
        'это оценка, а не собственно внутреннее число Гармина.</p>'
    )
    html.append("</div>")

    # Раздел 6 (темп по зонам по кварталам, вся история, было 9)
    html.append('<div class="chart-block">')
    html.append("<h2>6. Темп по пульсовым зонам по месяцам — наложение по годам (вся история)</h2>")
    html.append(img_tag(charts["8"]))
    html.append(
        '<p class="meta">По подграфику на зону; внутри — темп ПО МЕСЯЦАМ (январь-декабрь), отдельная линия на '
        'каждый год (цвет = год, тот же, что и на графике п.4). Раньше график шёл единой лентой по кварталам '
        "через всю историю — за прошедшее время фитнес менялся (VDOT от ~31 до ~48, см. п.4), и такая лента "
        'смешивала сезонный ход темпа с многолетним трендом формы; наложение по годам их разделяет. Точка на '
        'графике = месяц, где в зоне набралось ≥5 сплитов (иначе пропуск, не рисуется). Темп посчитан по '
        'лапам/сплитам (intervals), не по среднему за тренировку целиком — исключает искажение от '
        "разминки/заминки. Сравни с разделом 3 (последние 8 недель, детальнее).</p>"
    )
    html.append("</div>")

    # ---- Блок "Анализ объёмов" (было 1, 3c, 4a/4b, 2, 3d, 5) ----
    html.append('<h1 style="margin-top:56px;">Анализ объёмов</h1>')

    # Раздел 7 (объём по неделям, было 1)
    html.append('<div class="chart-block">')
    html.append("<h2>7. Объём бега по неделям</h2>")
    html.append(img_tag(charts["1"]))
    html.append("</div>")

    # Раздел 8 (частота качественных тренировок / МПК-порог, было 3c)
    html.append('<div class="chart-block">')
    html.append("<h2>8. Частота качественных тренировок и минуты МПК/порог</h2>")
    html.append(img_tag(charts["4c"]))
    html.append(
        '<p class="meta">Фиолетовые столбики — в скольких тренировках за неделю обнаружены "рабочие" '
        'отрезки (лапы заметно быстрее собственной медианы лапов ЭТОЙ тренировки; фиолетовая линия — '
        'скользящее среднее за 4 недели). Красная и жёлтая линии — не суммарные рабочие отрезки, а '
        'РАЗДЕЛЬНО минуты МПК/VO2max-работы (короткие быстрые повторы, ≤6 мин) и минуты пороговой '
        'работы (длинные непрерывные усилия, 6-30 мин) за неделю, тоже скользящее среднее за 4 недели '
        '— важен именно СОСТАВ качественной работы, а не только её наличие. Обе ищутся во ВСЕХ '
        'тренировках с пульсом, а не только в размеченных как interval/threshold — иначе теряются '
        'пикапы/вставки марафонского темпа внутри длительных и лёгких (см. п.7 про недельный объём). '
        'Видно не просто падение объёма, а смену состава: '
        'в здоровые периоды роста VDOT (напр. июнь-август 2025) качественная работа почти вся МПК '
        '(96-100% от суммы МПК+порог), а с января по апрель 2026 пропорция резко переворачивается — '
        'в феврале 2026 МПК-работы было всего ~9 минут за месяц (11% от качественного объёма), '
        'доминировал порог. Абсолютные минуты МПК коррелируют с EF того же периода заметно сильнее '
        '(r≈0.58, по месячным данным), чем ДОЛЯ МПК от общего качественного объёма (r≈0.26) — похоже, '
        'дело не только в пропорции, а в том, что МПК-стимула физически стало мало.</p>'
    )
    html.append("</div>")

    # Раздел 9 (ACWR, было 4a/4b)
    html.append('<div class="chart-block">')
    html.append("<h2>9. ACWR — острая/хроническая нагрузка</h2>")
    html.append(img_tag(charts["5"]))
    html.append(
        '<p class="meta">Способ 1: ACWR по тренировочной нагрузке Garmin (activity_training_load). '
        "Способ 2: ACWR по объёму (км, с учётом калибровки дорожки). Зелёная зона 0.8-1.3 — "
        "оптимальная нагрузка, выше 1.5 — риск перегрузки/травмы.</p>"
    )
    html.append("</div>")

    # Раздел 10 (доля марафонских отрезков по неделям, было 2)
    html.append('<div class="chart-block">')
    html.append("<h2>10. Доли типов тренировок по неделям</h2>")
    html.append(img_tag(charts["2"]))
    html.append(
        '<p class="meta">"Марафонские отрезки" — время лапов внутри длительных/лёгких тренировок '
        'с пульсом в зоне Z3 "марафонский темп" (см. раздел 3, методика Карвонена); вычтено из доли '
        'исходного типа тренировки (long/easy), чтобы сумма долей оставалась 100%. Раньше отбор шёл '
        'по фиксированному темповому коридору (285-335 с/км, по фактическому темпу гонок Утрехт/'
        'Гронинген) — статичный коридор систематически не ловил такие тренировки из периодов с '
        'другой формой (см. диалог 2026-08-20, тренировка от 2026-02-15: 191 мин с устойчивым '
        'пульсом в Z3 весь забег, но темпом 346-420 с/км — вне тогдашнего коридора). Пульсовая зона '
        'Z3, в отличие от темпового коридора, сама уже подстроена под этого атлета и не завязана на '
        'конкретный темп — поэтому одинаково применима к любому периоду истории. Темповые/'
        'интервальные вставки (не марафонские) по-прежнему определяются по темповым коридорам.</p>'
    )
    html.append("</div>")

    # Раздел 11: интерактивный график факт (в динамике) vs рекомендовано (выбор дистанции, было 3d)
    html.append('<div class="chart-block">')
    html.append("<h2>11. Факт vs рекомендовано по зонам, в динамике — выбор целевой дистанции</h2>")
    html.append(
        '<p class="meta">Факт показан ПО ВРЕМЕНИ (скользящее окно '
        f'{ZONE_TS_WINDOW_WEEKS} недель на каждую неделю истории, то же окно, что и в разделе 8), а '
        'рекомендуемая доля для выбранной дистанции — горизонтальными пунктирными линиями того же '
        'цвета, что и соответствующая зона, для сравнения на глаз в любой момент истории, а не '
        'только "сейчас". С 2026-08-17 числитель факта — только обнаруженные рабочие отрезки '
        '(та же детекция, что в разделе 8), а не любая лапа с этим пульсом: раньше лёгкие/длинные '
        'пробежки, где пульс случайно заходил в ту же зону (жара, дрейф, рельеф), маскировали на '
        'этом графике спад качественной работы, видимый в разделе 8.</p>'
    )
    html.append(zone_time_html)
    html.append("</div>")

    # Раздел 12 (текст: оптимальный объём и структура, было 5)
    html.append('<div class="chart-block">')
    html.append(text9)
    html.append("</div>")

    html.append(HTML_TAIL)

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("".join(html))

    print(f"OK: report written to {out_path}")
    print(f"Treadmill calibration: {tm_info}")
    print(f"PANO estimate: {pano_final}")
    print(f"Easy HR center: {peak_center} (peak_found={peak_found}, vertices={peak_vertices})")
    print("Zones:", zones)


if __name__ == "__main__":
    # json_path необязателен: калибровка дорожки и volume_ef_response считаются напрямую по БД
    # (см. fit_treadmill_pace_calibration/analyze_volume_ef_response) — calibration_profile.json
    # больше не нужен для построения отчёта. Если передан третьим аргументом — используется
    # только как необязательный ручной override max_hr/rest_hr/sex.
    if len(sys.argv) not in (3, 4):
        print("Usage: python3 build_report.py <db_path> <output_html> [json_path]")
        sys.exit(1)
    db_arg, out_arg = sys.argv[1], sys.argv[2]
    json_arg = sys.argv[3] if len(sys.argv) == 4 else None
    main(db_arg, out_arg, json_arg)
