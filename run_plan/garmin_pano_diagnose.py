#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
garmin_pano_diagnose.py — точечная диагностика того, почему Garmin не отдаёт
историю ПАНО (лактатного порога) ни по одному из известных эндпоинтов.

Контекст: garmin_calibration_fit.py / garmin_activities_export.py используют
fetch_lactate_threshold_range() из garmin_activities_export.py, которая пробует
biometric-service/stats/lactateThreshold{HeartRate,Speed}/range и, как fallback,
biometric-service/biometric/latestLactateThreshold. Если ВСЕ они пустые, скрипт
печатает:
    "ПАНО: Garmin не отдал историю по известным эндпоинтам — используй
    --dump-wellness-raw для диагностики на реальном аккаунте, либо укажи
    --threshold-hr вручную в garmin_calibration_fit.py"

--dump-wellness-raw в garmin_activities_export.py уже дампит эти эндпоинты, но
только для ОДНОЙ даты и только в варианте sport=RUNNING/daily/LATEST. Этот
скрипт идёт на порядок шире, специально для локализации причины отказа ПАНО:

  1. Проверяет саму авторизацию (токен/логин) и то, как резолвится displayName —
     частая причина 403 на части эндпоинтов, см. resolve_display_name() в
     garmin_activities_export.py.
  2. Перебирает ВСЕ известные варианты путей lactateThreshold (v1..v5 из
     fetch_lactate_threshold_range) НЕ по одной дате, а по нескольким диапазонам
     (последние 7/30/90/365 дней) — если Garmin считает ПАНО редко, узкое окно
     --dump-wellness-raw за один день могло промахнуться мимо даты пересчёта.
  3. Перебирает разные значения sport (RUNNING, CYCLING, ALL, без параметра) и
     разные aggregation/aggregationStrategy — вдруг на этом аккаунте Garmin
     считает ПАНО только по другому виду спорта или отдаёт весь ряд, а не LATEST.
  4. Печатает HTTP-статус и текст ошибки КАЖДОГО запроса (garth.connectapi молча
     проглатывает исключение в _safe_get — здесь наоборот, видно 403/404/500).
  5. Сохраняет весь сырой JSON в pano_diagnose_<username>_<timestamp>.json и
     печатает краткую сводку: какой путь(и) впервые вернул непустой ответ, либо
     финальный вывод "ни один эндпоинт не отдал данные — используй --threshold-hr".

Запуск (из папки run_plan, там же где garmin_activities_export.py и garth-токен):
  python garmin_pano_diagnose.py [--account you@mail.com] [--force-login]

Не требует БД (garmin_running.db) — только логин Garmin.
"""

import os
import sys
import json
import datetime
import argparse
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    from garmin_plan_import import connect  # прямой импорт, без ленивой обёртки
except Exception as e:
    sys.exit(
        "Не удалось импортировать connect() из garmin_plan_import.py — запусти "
        "этот скрипт из той же папки (run_plan), где лежат garmin_plan_import.py "
        "и garmin_activities_export.py.\n"
        f"Исходная ошибка: {e!r}"
    )


def call(garth, label, path):
    """В отличие от _safe_get() из garmin_activities_export.py — не проглатывает
    исключение, а возвращает (ok, статус/ошибка, данные) для диагностики."""
    try:
        data = garth.connectapi(path)
        empty = data in (None, [], {}, "")
        return {
            "label": label,
            "path": path,
            "ok": True,
            "empty": empty,
            "error": None,
            "data_preview": _preview(data),
        }, data
    except Exception as e:
        status = getattr(getattr(e, "response", None), "status_code", None)
        return {
            "label": label,
            "path": path,
            "ok": False,
            "empty": True,
            "error": f"{type(e).__name__}: {e}" + (f" [HTTP {status}]" if status else ""),
            "data_preview": None,
        }, None


def _preview(data, max_len=400):
    try:
        s = json.dumps(data, ensure_ascii=False)
    except Exception:
        s = repr(data)
    return s if len(s) <= max_len else s[:max_len] + "…"


def check_auth(garth):
    print("=" * 70)
    print("1. АВТОРИЗАЦИЯ / ИДЕНТИФИКАЦИЯ ПОЛЬЗОВАТЕЛЯ")
    print("=" * 70)
    try:
        username = garth.client.username
        print(f"  garth.client.username (email логина) = {username!r}")
    except Exception as e:
        username = None
        print(f"  garth.client.username недоступен: {e!r}")

    r, prof = call(garth, "socialProfile", "/userprofile-service/socialProfile")
    if r["ok"] and not r["empty"]:
        display_name = (prof or {}).get("displayName")
        print(f"  /userprofile-service/socialProfile -> displayName = {display_name!r}  (OK)")
    else:
        display_name = None
        print(f"  /userprofile-service/socialProfile -> ОШИБКА: {r['error']}")
        print("  Без displayName часть wellness/threshold-эндпоинтов может отвечать "
              "403 (см. докстринг resolve_display_name() в garmin_activities_export.py) — "
              "но lactateThreshold-эндпоинты ниже используют путь БЕЗ username в URL, "
              "так что для ПАНО это не должно быть блокером; просто фиксируем факт.")
    return username, display_name


def diagnose_lactate_threshold(garth, ranges):
    print()
    print("=" * 70)
    print("2. ПЕРЕБОР ЭНДПОИНТОВ ПАНО (lactate threshold) ПО РАЗНЫМ ДИАПАЗОНАМ/ПАРАМЕТРАМ")
    print("=" * 70)

    sports = ["RUNNING", "CYCLING", "ALL", None]
    agg_variants = [
        ("aggregation=daily&aggregationStrategy=LATEST", "&aggregation=daily&aggregationStrategy=LATEST"),
        ("без aggregation-параметров", ""),
        ("aggregation=weekly&aggregationStrategy=LATEST", "&aggregation=weekly&aggregationStrategy=LATEST"),
    ]

    results = []
    first_nonempty = None

    for range_label, start_date, end_date in ranges:
        for metric_name, metric_path in [
            ("lactateThresholdHeartRate", "lactateThresholdHeartRate"),
            ("lactateThresholdSpeed", "lactateThresholdSpeed"),
        ]:
            for sport in sports:
                for agg_label, agg_qs in agg_variants:
                    qs = agg_qs
                    if sport:
                        qs = f"?sport={sport}" + qs
                    else:
                        qs = ("?" + agg_qs[1:]) if agg_qs else ""
                    path = f"/biometric-service/stats/{metric_path}/range/{start_date}/{end_date}{qs}"
                    label = f"v3/v4 {metric_name} [{range_label}, sport={sport}, {agg_label}]"
                    r, data = call(garth, label, path)
                    results.append(r)
                    tag = "OK, ДАННЫЕ ЕСТЬ" if (r["ok"] and not r["empty"]) else (
                        "OK, но ПУСТО" if r["ok"] else f"ОШИБКА: {r['error']}")
                    print(f"  [{tag:22s}] {label}")
                    if r["ok"] and not r["empty"]:
                        print(f"      -> {r['data_preview']}")
                        if first_nonempty is None:
                            first_nonempty = r

    # v5 — latest, без диапазона дат
    print()
    r, data = call(garth, "v5 latestLactateThreshold", "/biometric-service/biometric/latestLactateThreshold")
    results.append(r)
    tag = "OK, ДАННЫЕ ЕСТЬ" if (r["ok"] and not r["empty"]) else (
        "OK, но ПУСТО" if r["ok"] else f"ОШИБКА: {r['error']}")
    print(f"  [{tag:22s}] {r['label']}")
    if r["ok"] and not r["empty"]:
        print(f"      -> {r['data_preview']}")
        if first_nonempty is None:
            first_nonempty = r

    # v1/v2 — старые кандидаты metrics-service, известны как всегда пустые,
    # но перебираем на случай, если Garmin что-то поменял обратно.
    print()
    for label, path in [
        ("v1 metrics-service/lactatethreshold (последний ряд)",
         f"/metrics-service/metrics/lactatethreshold?startDate={ranges[-1][1]}&endDate={ranges[-1][2]}"),
        ("v2 metrics-service/latest/lactatethreshold",
         f"/metrics-service/metrics/latest/lactatethreshold?startDate={ranges[-1][1]}&endDate={ranges[-1][2]}"),
    ]:
        r, data = call(garth, label, path)
        results.append(r)
        tag = "OK, ДАННЫЕ ЕСТЬ" if (r["ok"] and not r["empty"]) else (
            "OK, но ПУСТО" if r["ok"] else f"ОШИБКА: {r['error']}")
        print(f"  [{tag:22s}] {label}")
        if r["ok"] and not r["empty"]:
            print(f"      -> {r['data_preview']}")
            if first_nonempty is None:
                first_nonempty = r

    return results, first_nonempty


def diagnose_related_context(garth, latest_date):
    """Смежные проверки, которые часто объясняют пустой ПАНО: часы/спортивные
    профили Firstbeat, VO2max (тот же движок Firstbeat, если и он пуст — Garmin,
    вероятно, просто не считает эти метрики для этого устройства/аккаунта)."""
    print()
    print("=" * 70)
    print("3. СМЕЖНЫЙ КОНТЕКСТ (помогает понять причину, не сам ПАНО)")
    print("=" * 70)
    checks = [
        ("VO2max (тот же движок Firstbeat, что и ПАНО)",
         f"/metrics-service/metrics/maxmet/latest"),
        ("training status (использует ПАНО внутри, если он есть)",
         f"/metrics-service/metrics/trainingstatus/aggregated/{latest_date}"),
        ("список устройств аккаунта (ПАНО считают только некоторые Garmin-часы/датчики)",
         "/device-service/deviceregistration/devices"),
    ]
    for label, path in checks:
        r, data = call(garth, label, path)
        tag = "OK, ДАННЫЕ ЕСТЬ" if (r["ok"] and not r["empty"]) else (
            "OK, но ПУСТО" if r["ok"] else f"ОШИБКА: {r['error']}")
        print(f"  [{tag:22s}] {label}")
        if r["ok"] and not r["empty"]:
            print(f"      -> {r['data_preview']}")


def main():
    ap = argparse.ArgumentParser(
        description="Расширенная диагностика отсутствия истории ПАНО (лактатного порога) в Garmin Connect API."
    )
    ap.add_argument("--account", help="логин Garmin (email); если не задан — GARMIN_EMAIL / сохранённая учётка")
    ap.add_argument("--force-login", action="store_true", help="не использовать сохранённый токен, войти паролем заново")
    ap.add_argument("--days", type=int, default=None,
                     help="если задано — проверить только один диапазон (последние N дней) вместо стандартного "
                          "набора 7/30/90/365")
    args = ap.parse_args()

    try:
        garth = connect(args.account, force_login=args.force_login)
    except SystemExit:
        raise
    except Exception as e:
        sys.exit(f"Не удалось залогиниться в Garmin: {e!r}\n{traceback.format_exc()}")

    username, display_name = check_auth(garth)

    today = datetime.date.today()
    if args.days:
        ranges = [(f"последние {args.days} дн.", str(today - datetime.timedelta(days=args.days)), str(today))]
    else:
        ranges = [
            (f"последние {n} дн.", str(today - datetime.timedelta(days=n)), str(today))
            for n in (7, 30, 90, 365)
        ]

    lt_results, first_nonempty = diagnose_lactate_threshold(garth, ranges)
    diagnose_related_context(garth, str(today))

    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    safe_user = (username or display_name or "unknown").replace("@", "_at_").replace(".", "_")
    out_file = f"pano_diagnose_{safe_user}_{ts}.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump({
            "username": username,
            "display_name": display_name,
            "ranges_checked": ranges,
            "lactate_threshold_results": lt_results,
        }, f, ensure_ascii=False, indent=2)

    print()
    print("=" * 70)
    print("ИТОГ")
    print("=" * 70)
    print(f"Полный лог всех запросов сохранён в {out_file}")
    if first_nonempty:
        print(f"НАЙДЕН рабочий вариант: {first_nonempty['label']}")
        print(f"  путь: {first_nonempty['path']}")
        print("  -> используй именно эти параметры (диапазон дат / sport / aggregation) "
              "в fetch_lactate_threshold_range() (garmin_activities_export.py), если текущая "
              "версия функции их не пробует.")
    else:
        any_403 = any(r["error"] and "403" in r["error"] for r in lt_results)
        any_404 = any(r["error"] and "404" in r["error"] for r in lt_results)
        if any_403:
            print("Часть запросов вернула 403 Forbidden — вероятно, проблема авторизации/scope токена, "
                  "а не отсутствия данных. Попробуй --force-login (перелогиниться паролем).")
        elif any_404:
            print("Часть запросов вернула 404 — эндпоинт мог измениться на стороне Garmin (переименование пути).")
        else:
            print("ВСЕ варианты вернули пустой ответ без ошибок (не 403/404) — это означает, что Garmin "
                  "действительно НИКОГДА не считал ПАНО (лактатный порог) для этого аккаунта ни за один "
                  "проверенный период. Обычно это бывает, если:")
            print("  - часы/датчик не поддерживают Firstbeat-расчёт ПАНО (проверь VO2max/training status выше — "
                  "если они тоже пустые, это подтверждает: устройство/профиль не считает Firstbeat-метрики);")
            print("  - бег всегда выполнялся без пульсометра или с некорректными данными пульса;")
            print("  - функция ПАНО отключена в настройках Garmin Connect (Metrics > Training Status).")
            print()
            print("В этом случае это НЕ баг скрипта — переключайся на --threshold-hr вручную в "
                  "garmin_calibration_fit.py (значение по своему ПАНО-тесту/оценке).")


if __name__ == "__main__":
    main()
