#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Выгрузка беговых тренировок из Garmin Connect для калибровки адаптивной модели
(см. c:\\Users\\ystef\\Claude\\Projects\\Run\\adaptive_model_architecture.md).

Для каждой пробежки выгружается:
  - дата и время старта
  - суммарная длительность / дистанция / средний и макс. пульс / средний темп
  - список интервалов (лапов): длительность, дистанция, пульс (сред./макс.), темп
    каждого лапа — либо "типизированные" лапы Garmin (INTERVAL_ACTIVE/INTERVAL_REST/
    WARMUP/COOLDOWN/RECOVERY — если тренировка была структурированной на часах),
    либо обычные авто/ручные лапы
  - эвристический тип тренировки (easy/long/threshold/interval/mixed), определяемый
    по суммарной длительности и по разбросу темпа/пульса между интервалами
  - КОНФАУНДЫ EF (см. п.14 ниже / обсуждение garmin_calibration_fit.py "EF как прокси
    формы искажается рельефом/погодой/техникой") — набор из полей, которые уже отдаёт
    сам список активностей Garmin, без дополнительного запроса на активность:
    elevation_gain_m/elevation_loss_m (суммарный набор/сброс высоты), avg_temperature_c
    (среднее из min/max температуры за тренировку), avg_cadence_spm/avg_stride_length_m
    (каденс и длина шага — прокси техники/усталости независимо от пульса), calories,
    aerobic_training_effect/anaerobic_training_effect (готовая оценка Garmin/Firstbeat),
    manual_activity (1 — активность добавлена вручную, GPS/пульс могут быть неполными
    или отсутствовать, elevation в частности будет пустым или нулевым), elevation_corrected
    (1 — высота скорректирована Garmin по цифровой модели рельефа, точнее сырого
    барометра/GPS). ВАЖНО: точные ключи и единицы этих полей в JSON Garmin нигде
    официально не задокументированы и могут отличаться от аккаунта к аккаунту (в
    частности единица температуры зависит от настроек пользователя в Garmin Connect) —
    проверяй через --dump-raw ACTIVITY_ID и поправь ACTIVITY_TEMPERATURE_UNIT/при
    необходимости имена полей в _extract_ef_confounds(), если после экспорта колонки
    пустые там, где в самом Garmin Connect для этой активности данные точно есть.

Дополнительно (по умолчанию, если не указан --no-wellness) выгружаются wellness-метрики
за тот же период — сон (общий скор, стадии, средняя частота дыхания), HRV (за ночь и
недельная средняя, статус), пульс покоя, Body Battery (мин/макс/заряжено/потрачено за
день), стресс, training readiness — источники восстановления/системного утомления,
которых нет в самих тренировках (см. обсуждение калибровки systemic-категории в
garmin_calibration_fit.py). Эндпоинты неофициальные — Garmin может их поменять без
предупреждения, каждый запрос best-effort (если метрика не отдалась — просто None).

  ВАЖНО про идентификатор пользователя в URL: часть wellness-эндпоинтов (в частности
  /usersummary-service/usersummary/daily/{X}, откуда берётся RHR/шаги/калории) требуют
  в пути внутренний Garmin displayName, а НЕ email логина — с email эта конкретная ручка
  отвечает "403 Forbidden", даже если авторизация в полном порядке (другие эндпоинты,
  например dailySleepData, email почему-то принимают, отчего проблема не сразу заметна —
  сон/HRV/Body Battery/стресс/readiness будут выгружаться нормально, а именно rhr в
  таблице wellness будет молча пустым). resolve_display_name() решает это, запрашивая
  /userprofile-service/socialProfile и беря оттуда displayName, с откатом на email, если
  профиль недоступен. Если после обновления rhr всё ещё пустой — проверь через
  --dump-wellness-raw ДАТА (см. ниже), какой displayName резолвится и что отвечает
  usersummary-daily именно с ним.

Дополнительно (по умолчанию, если не указан --no-cross-training) выгружаются вело/лыжи/
плавание/силовые — но ТОЛЬКО суммарная нагрузка (дата/длительность/дистанция/пульс), без
лапов и без беговой классификации: калькулятор планов беговой, поэтому кросс-тренировки
не должны попадать в беговые оси (vo2/threshold/endurance), но их нагрузка нужна для
ACWR и фита systemic-утомления по HRV/RHR — иначе тяжёлая велотренировка выглядит как
необъяснимый провал HRV. Список видов — --cross-sports (по умолчанию все четыре).

Также (если не указан --no-lactate-threshold) выгружается история ПАНО (лактатного
порога), которую Garmin сам считает по алгоритму Firstbeat из фактических тренировок —
избавляет от ручного ввода порога в garmin_calibration_fit.py и, в отличие от одного
статичного числа, учитывает изменение порога во времени по мере роста/потери формы.
Точный путь эндпоинта не задокументирован Garmin — пробуются несколько кандидатов,
--dump-wellness-raw ДАТА поможет проверить/поправить на реальном аккаунте, если формат
ответа отличается.

Результат — файл SQLite (по умолчанию garmin_running.db) с таблицами:
  activities(activity_id PK, date, start_time, name, sport, duration_s, distance_m,
             avg_hr, max_hr, avg_pace_s_per_km, lap_source, type_guess, type_reason,
             elevation_gain_m, elevation_loss_m, avg_temperature_c, avg_cadence_spm,
             avg_stride_length_m, calories, aerobic_training_effect,
             anaerobic_training_effect, manual_activity, elevation_corrected,
             avg_grade_adjusted_pace_s_per_km, exported_at)
    Последние 10 колонок (elevation_gain_m .. elevation_corrected) — конфаунды EF, см.
    п.14 выше; для БД, созданных до этой версии скрипта, колонки добавляются
    автоматически при первом запуске (ensure_schema_migrations, ALTER TABLE ADD COLUMN).
    avg_grade_adjusted_pace_s_per_km (см. п.15 ниже) — темп с поправкой на уклон
    (directGradeAdjustedSpeed), заполняется ТОЛЬКО для уличных тренировок (не дорожка/
    помещение/виртуальный бег) со значимым набором высоты (>= --grade-adjusted-min-
    elevation-m, по умолчанию 30 м) — для остальных тренировок остаётся NULL, это не
    ошибка/пропуск данных, а сознательный фильтр (см. fetch_grade_adjusted_pace).
    В отличие от wellness (который по умолчанию пропускает уже выгруженные дни),
    activities всегда перезаписывается (INSERT OR REPLACE) для каждой активности в
    заданном диапазоне дат — поэтому чтобы заполнить эти колонки для СТАРЫХ тренировок,
    достаточно просто перезапустить обычный экспорт за нужный период (--start-date/
    --end-date или --days), Garmin запрашивать заново не обязательно из кеша, но новый
    запрос всё равно уйдёт по каждой активности в диапазоне.
  intervals(activity_id, idx, lap_type, duration_s, distance_m, avg_hr, max_hr,
            avg_pace_s_per_km, PRIMARY KEY(activity_id, idx))
  wellness(date PK, sleep_score, sleep_duration_s, sleep_deep_s, sleep_light_s,
           sleep_rem_s, sleep_awake_s, sleep_avg_resp, hrv_last_night_avg, hrv_weekly_avg,
           hrv_status, rhr, body_battery_min/max/charged/drained, stress_avg, stress_max,
           training_readiness_score, training_readiness_level, steps, active_calories,
           exported_at)
  cross_activities(activity_id PK, date, start_time, name, sport, duration_s, distance_m,
                    avg_hr, max_hr, exported_at)
  lactate_threshold(date PK, threshold_hr, threshold_pace_s_per_km, source, exported_at)

Повторные запуски делают upsert по activity_id/date (INSERT OR REPLACE) — можно гонять
скрипт периодически с новым диапазоном дат, старые данные не задублируются и не
потеряются; дни, для которых wellness уже есть в БД, по умолчанию пропускаются
(--force-refresh-wellness — перезаписать). Для просмотра в Excel таблицы можно выгрузить
в CSV: `sqlite3 garmin_running.db -csv -header "select * from intervals" > intervals.csv`
(или флагом --export-csv, см. ниже; --in/--out-format конвертируют только activities+
intervals, wellness/cross_activities/lactate_threshold туда не входят).

АВТОРИЗАЦИЯ
  Скрипт лежит рядом с plan_export_garmin.py (старое имя — garmin_plan_import.py, оба
  варианта поддерживаются) и переиспользует его логику входа (тот же токен в
  ~/.garth/<логин>, тот же обход блокировки через User-Agent).
  Если сохранена ровно одна учётка Garmin — подхватится сама.

  pip install garth==0.6.3

ЗАПУСК
  python garmin_activities_export.py --start-date 2026-01-01 --end-date 2026-08-11
  python garmin_activities_export.py --days 180 --account you@mail.com
  python garmin_activities_export.py --days 90 --db calib_2026h1.db
  python garmin_activities_export.py --days 30 --dump-raw 12345678901   # сырой JSON одной активности (отладка схемы)
  python garmin_activities_export.py --days 30 --dump-activity-fields 12345678901   # отладка конфаундов EF (п.14): где реально лежат elevation/temperature/cadence на твоём аккаунте
  python garmin_activities_export.py --dump-activity-details 12345678901   # поиск ключа темпа с учётом уклона (grade-adjusted pace), см. диалог 2026-08-17; бери ID реальной уличной тренировки с заметным набором высоты
  python garmin_activities_export.py --days 90 --grade-adjusted-min-elevation-m 50  # тот же экспорт, но GAP считается только от 50 м набора (по умолчанию 30)
  python garmin_activities_export.py --days 90 --no-grade-adjusted-pace             # без GAP вообще (быстрее — эндпоинт /details поточный, по секундам)
  python garmin_activities_export.py --db garmin_running.db --export-csv    # выгрузить обе таблицы БД в CSV рядом и выйти

  # Конвертация форматов туда-обратно, без обращения к Garmin:
  python garmin_activities_export.py --in old_export.json --out-format sqlite --out garmin_running.db
  python garmin_activities_export.py --in garmin_running.db --out-format json --out backup.json
  python garmin_activities_export.py --in garmin_running_activities.csv --out-format sqlite   # найдёт _intervals.csv рядом

  # Wellness (сон/HRV/RHR/Body Battery/стресс/training readiness):
  python garmin_activities_export.py --days 365 --no-wellness              # только тренировки, без wellness
  python garmin_activities_export.py --wellness-only --days 365            # только wellness, без активностей
  python garmin_activities_export.py --days 30 --force-refresh-wellness    # перезаписать wellness за последние 30 дней

  # Кросс-тренировки и ПАНО:
  python garmin_activities_export.py --days 365 --no-cross-training              # без вело/лыж/плавания/силовых
  python garmin_activities_export.py --cross-training-only --days 365            # только кросс-тренировки
  python garmin_activities_export.py --cross-sports cycling,swimming --days 365  # только эти два вида
  python garmin_activities_export.py --dump-wellness-raw 2026-08-01              # сырой JSON wellness+ПАНО за день (отладка)

ЭВРИСТИКА ТИПА ТРЕНИРОВКИ (грубая, для предварительной калибровки; --max-hr/--rest-hr
уточняют зоны по Карвонену, иначе используются абсолютные пороги пульса по умолчанию):
  interval   — много (>=4) контрастных по темпу/пульсу интервалов, среди которых есть
               короткие "быстрые" сегменты и заметно более медленные/низкопульсовые
               "recovery"-сегменты между ними
  threshold  — 1-3 продолжительных (8-40 мин) сегмента на повышенном устойчивом пульсе
               без выраженных recovery-интервалов между ними
  long       — суммарная длительность бега >= порога (по умолчанию 75 мин), пульс
               невысокий/умеренный, темп относительно ровный
  easy       — остальное при невысоком среднем пульсе
  mixed      — не подошло ни под один профиль уверенно — нужен ручной разбор

14. КОНФАУНДЫ EF (elevation/температура/каденс/качество данных) — garmin_calibration_fit.py
    использует Efficiency Factor (EF = темп/пульс) как прокси аэробной формы; в его
    not_calibrated явно отмечено, что EF искажается рельефом, погодой, техникой и
    качеством GPS, и что для честной калибровки нужно как минимум elevation_gain_m/
    elevation_loss_m, температуру, каденс и признак качества данных — раньше их не было
    в БД вообще. Список активностей Garmin (тот же ответ, из которого уже берутся
    duration/distance/avg_hr) обычно уже содержит эти поля без дополнительного запроса
    на каждую активность:
      elevation_gain_m / elevation_loss_m — суммарный набор/сброс высоты за тренировку
          (Garmin: elevationGain/elevationLoss). Позволяет в дальнейшем считать
          grade-adjusted EF вместо сырого (см. TODO в garmin_calibration_fit.py) —
          этот скрипт сам НЕ считает grade-adjusted pace, только выгружает сырые
          ingredients (высота+дистанция+время), чтобы не зашивать в экспортёр
          непроверенную формулу пересчёта.
      avg_temperature_c — среднее из minTemperature/maxTemperature активности; ЕДИНИЦА
          НЕ ЗАДОКУМЕНТИРОВАНА Garmin и зависит от настроек аккаунта (Цельсий/Фаренгейт) —
          если после экспорта числа выглядят как Фаренгейт (обычно > 45 для бега не в
          пустыне), запусти с --temperature-unit f, скрипт сам сконвертирует в Цельсий.
      avg_cadence_spm / avg_stride_length_m — каденс (шаг/мин) и длина шага (Garmin
          обычно отдаёт в мм — конвертируется в метры) — прокси техники/нервно-мышечной
          усталости, который НЕ зависит от пульса (в отличие от EF), полезен как
          дополнительная ось валидации отдельно от HR-based метрик.
      calories, aerobic_training_effect, anaerobic_training_effect — уже готовые оценки
          Garmin/Firstbeat, справочно (не входят пока ни в один фит garmin_calibration_fit.py).
      manual_activity — 1, если тренировка добавлена в Garmin Connect вручную (не с
          часов) — в этом случае elevation/cadence/пульс обычно неполные или нулевые,
          такие активности стоит исключать из EF-калибровки по рельефу отдельно.
      elevation_corrected — 1, если высота скорректирована Garmin по цифровой модели
          рельефа (точнее сырого барометра/GPS) — справочный индикатор качества.
    ВАЖНО (честно): точные ключи полей в JSON списка активностей Garmin нигде официально
    не задокументированы (неофициальный API, как и wellness-эндпоинты выше) — извлечение
    сделано best-effort в _extract_ef_confounds(), поле отсутствует -> None, ничего не
    падает. Проверяй реальные значения через --dump-raw ACTIVITY_ID на своём аккаунте;
    если какая-то колонка стабильно пустая там, где в самом Garmin Connect (веб/приложение)
    для этой активности данные видны — почти наверняка Garmin использует другое имя поля,
    поправь список кандидатов в _extract_ef_confounds().

15. GRADE-ADJUSTED PACE (темп с поправкой на уклон) — ещё один способ снять с EF конфаунд
    рельефа (см. п.14): в отличие от elevation_gain_m/elevation_loss_m (сырые ingredients,
    из которых grade-adjusted EF никто пока не считает — см. TODO в garmin_calibration_fit.py),
    avg_grade_adjusted_pace_s_per_km — уже готовая метрика Garmin/Firstbeat, посчитанная
    по каждой секунде тренировки с учётом текущего уклона в этой точке трассы, а не только
    по суммарному набору/сбросу за всю тренировку. Источник — ключ directGradeAdjustedSpeed
    (м/с) в поточном эндпоинте /activity-service/activity/{id}/details (см.
    fetch_grade_adjusted_pace() и диагностику --dump-activity-details, диалог 2026-08-17);
    этот эндпоинт не используется остальным экспортом (он про секундные точки, а не про
    сводку активности/лапов) и ощутимо тяжелее обычных запросов, поэтому запрашивается
    ИЗБИРАТЕЛЬНО: только для уличных тренировок (OUTDOOR_RUN_TYPE_KEYS — исключены
    treadmill_running/indoor_running/virtual_run, где либо нет реального рельефа, либо
    уклон механический) со значимым набором высоты (elevation_gain_m >=
    --grade-adjusted-min-elevation-m, по умолчанию 30 м) — --no-grade-adjusted-pace
    отключает совсем. Для всех остальных активностей колонка остаётся NULL — это
    сознательный фильтр, не ошибка/недостающие данные.
"""

import os, sys, json, csv, sqlite3, argparse, datetime, statistics, time, importlib.util

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------
# Переиспользуем авторизацию из garmin_plan_import.py (тот же токен/UA-обход). Файл в разное
# время назывался по-разному (garmin_plan_import.py -> plan_export_garmin.py, см. диалог
# 2026-08-17) — пробуем оба имени по очереди, чтобы переименование модуля с логином не ломало
# этот скрипт молча.
_PLAN_IMPORT_CANDIDATES = ["plan_export_garmin.py", "garmin_plan_import.py"]


def _load_plan_import_module():
    for name in _PLAN_IMPORT_CANDIDATES:
        path = os.path.join(HERE, name)
        if os.path.isfile(path):
            spec = importlib.util.spec_from_file_location("garmin_plan_import", path)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            return mod
    tried = ", ".join(_PLAN_IMPORT_CANDIDATES)
    sys.exit(f"Не найден ни один из [{tried}] рядом со скриптом ({HERE}) — нужен для входа в Garmin.")

def connect(*args, **kwargs):
    """Ленивая обёртка: garmin_plan_import.py (и garth) нужны только когда реально тянем
    Garmin — режим конвертации (--in) должен работать и без них."""
    return _load_plan_import_module().connect(*args, **kwargs)

# ---------------------------------------------------------------------------
# Типы бега (по sportType.sportTypeKey/typeKey Garmin)
# ---------------------------------------------------------------------------
RUN_TYPE_KEYS = {
    "running", "track_running", "trail_running", "treadmill_running",
    "street_running", "indoor_running", "virtual_run", "obstacle_run",
    "ultra_run",
}

# Кросс-тренировки: сюда пойдёт только суммарная (не по осям) нагрузка — калькулятор
# планов беговой, поэтому вело/лыжи/плавание не должны попадать в беговые оси vo2/
# threshold/endurance, но должны учитываться в systemic-утомлении (ACWR, фит по HRV/RHR),
# иначе тяжёлая велотренировка будет выглядеть как "необъяснимый" провал HRV.
CROSS_TYPE_GROUPS = {
    "cycling": {"cycling", "road_biking", "indoor_cycling", "mountain_biking", "gravel_cycling",
                "virtual_ride", "cyclocross", "track_cycling", "recumbent_cycling", "e_bike_fitness"},
    "skiing": {"resort_skiing_snowboarding_ws", "skate_skiing_ws", "classic_skiing_ws",
               "backcountry_skiing_ws", "cross_country_skiing_ws", "resort_skiing",
               "cross_country_skiing", "backcountry_skiing", "skate_skiing"},
    "swimming": {"lap_swimming", "open_water_swimming"},
    "strength_training": {"strength_training"},
}
CROSS_TYPE_KEY_TO_GROUP = {k: g for g, ks in CROSS_TYPE_GROUPS.items() for k in ks}

# "Уличные" беговые типы (реальный набор высоты/уклон существуют физически) — из
# RUN_TYPE_KEYS исключены treadmill_running (дорожка — уклон механический/фиксированный,
# набор высоты по GPS не имеет смысла) и indoor_running/virtual_run (без реального рельефа).
# Используется, чтобы не тратить лишний запрос на grade-adjusted pace (fetch_grade_adjusted_pace)
# там, где он физически не может быть информативным.
OUTDOOR_RUN_TYPE_KEYS = RUN_TYPE_KEYS - {"treadmill_running", "indoor_running", "virtual_run"}

# "активные" типы лапов у структурированных тренировок Garmin
LAP_ACTIVE_TYPES = {"INTERVAL_ACTIVE", "ACTIVE", "INTERVAL", "REPEAT", "WORK"}
LAP_REST_TYPES = {"INTERVAL_REST", "RECOVERY", "REST", "RECOVERY_ACTIVE"}
LAP_EDGE_TYPES = {"WARMUP", "COOLDOWN"}


def fetch_activities(garth, start_date, end_date, limit_batch=100, max_total=2000, activity_type="running"):
    """Список активностей за период (постранично). activity_type=None — без фильтра
    (все виды спорта, нужно для выгрузки кросс-тренировок)."""
    out = []
    start = 0
    while True:
        params = f"?limit={limit_batch}&start={start}&startDate={start_date}&endDate={end_date}"
        if activity_type:
            params += f"&activityType={activity_type}"
        batch = garth.connectapi(f"/activitylist-service/activities/search/activities{params}")
        if not batch:
            break
        out.extend(batch)
        if len(batch) < limit_batch or len(out) >= max_total:
            break
        start += limit_batch
    return out


# ---------------------------------------------------------------------------
# Wellness-метрики (сон/HRV/RHR/Body Battery/стресс/training readiness) — источники
# восстановления/системного утомления, которых нет в самих тренировках. Эндпоинты
# неофициальные (те же, что использует python-garminconnect/garth) — Garmin может их
# поменять без предупреждения, поэтому каждый вызов обёрнут в try/except и падение
# одной метрики не должно ронять весь день/экспорт.
# ---------------------------------------------------------------------------

def _safe_get(garth, path):
    try:
        return garth.connectapi(path)
    except Exception:
        return None


def resolve_display_name(garth):
    """Внутренний Garmin 'displayName' (НЕ email логина!) — часть wellness-эндпоинтов,
    которым в пути URL нужен идентификатор пользователя, требуют именно его; email
    (garth.client.username) для некоторых из них Garmin почему-то принимает (dailySleepData
    работал и с email), а для других отвечает 403 Forbidden (usersummary-service/
    usersummary/daily — именно так и проявилось: 403 Client Error: Forbidden for url:
    https://connectapi.garmin.com/usersummary-service/usersummary/daily/<email>?...).
    Правильный источник — /userprofile-service/socialProfile, поле displayName (тот же
    подход, что в python-garminconnect). Если профиль недоступен — откатываемся на email
    (для эндпоинтов, которые его всё же принимают), но тогда usersummary daily (RHR/шаги/
    калории) скорее всего продолжит падать 403 — это не баг этой функции, а исчерпанный
    fallback."""
    try:
        prof = garth.connectapi("/userprofile-service/socialProfile")
        display_name = (prof or {}).get("displayName")
        if display_name:
            return display_name
    except Exception:
        pass
    try:
        return garth.client.username
    except Exception:
        return None


def fetch_sleep_day(garth, username, date_str):
    """Помимо базовых стадий сна — независимые физиологические сигналы, которые НЕ являются
    производными Garmin-метриками (в отличие от Training Readiness/Body Battery, см. докстринг
    п.8а): SpO2 и стресс во время сна, отклонение температуры кожи (avgSkinTempDeviationC —
    вообще отдельный сенсор, раньше нигде не читался), ЧСС покоя именно по данным сна (третий
    источник RHR наряду с daily_summary.restingHeartRate/lastSevenDaysAvgRestingHeartRate — три
    источника могут расходиться, при желании их можно сравнивать), и categorical-поле
    sleepNeed.trainingFeedback (готовая оценка Garmin вида HIGH_ACWR_AND_CHRONIC — справочно,
    это уже производная метрика, аналогично Training Readiness не должна попадать в независимый
    aggregate systemic_fatigue)."""
    data = _safe_get(garth, f"/wellness-service/wellness/dailySleepData/{username}?date={date_str}&nonSleepBufferMinutes=60")
    dto = (data or {}).get("dailySleepDTO") or {}
    if not dto or not dto.get("sleepTimeSeconds"):
        return {}
    scores = dto.get("sleepScores") or {}
    overall = scores.get("overall") if isinstance(scores.get("overall"), dict) else None
    sleep_need = dto.get("sleepNeed") or {}
    return {
        "sleep_score": overall.get("value") if overall else None,
        "sleep_duration_s": dto.get("sleepTimeSeconds"),
        "sleep_deep_s": dto.get("deepSleepSeconds"),
        "sleep_light_s": dto.get("lightSleepSeconds"),
        "sleep_rem_s": dto.get("remSleepSeconds"),
        "sleep_awake_s": dto.get("awakeSleepSeconds"),
        "sleep_avg_resp": data.get("avgSleepRespirationValue") or dto.get("averageRespirationValue"),
        "avg_sleep_stress": dto.get("avgSleepStress"),
        "sleep_spo2_avg": dto.get("averageSpO2Value"),
        "sleep_spo2_min": dto.get("lowestSpO2Value"),
        "sleep_rhr": (data or {}).get("restingHeartRate"),
        "skin_temp_deviation_c": (data or {}).get("avgSkinTempDeviationC"),
        "sleep_training_feedback": sleep_need.get("trainingFeedback"),
    }


def fetch_hrv_day(garth, date_str):
    data = _safe_get(garth, f"/hrv-service/hrv/{date_str}")
    summary = (data or {}).get("hrvSummary") or {}
    if not summary:
        return {}
    return {
        "hrv_last_night_avg": summary.get("lastNightAvg"),
        "hrv_weekly_avg": summary.get("weeklyAvg"),
        "hrv_status": summary.get("status"),
    }


def fetch_daily_summary_day(garth, username, date_str):
    """RHR + шаги/калории (справочно, не встраиваются в фит tau — см. обсуждение),
    плюс разбивка дневного стресса на день/ночь и по уровням (rest/activity/uncategorized,
    low/medium/high) и floorsAscended (NEAT-прокси) — из одного и того же daily summary,
    не отдельный запрос. Разбивка стресса даёт больше сигнала, чем один stress_avg: по
    ночной части можно попробовать искать корреляции с восстановлением, по дневной — с
    накопленной нагрузкой (см. анализ сырых полей)."""
    data = _safe_get(garth, f"/usersummary-service/usersummary/daily/{username}?calendarDate={date_str}")
    if not data:
        return {}
    return {
        "rhr": data.get("restingHeartRate"),
        "steps": data.get("totalSteps"),
        "active_calories": data.get("activeKilocalories"),
        "floors_ascended": data.get("floorsAscended"),
        "stress_rest_s": data.get("restStressDuration"),
        "stress_activity_s": data.get("activityStressDuration"),
        "stress_uncategorized_s": data.get("uncategorizedStressDuration"),
        "stress_low_s": data.get("lowStressDuration"),
        "stress_medium_s": data.get("mediumStressDuration"),
        "stress_high_s": data.get("highStressDuration"),
    }


def fetch_lactate_threshold_range(garth, start_date, end_date):
    """История ПАНО (лактатного порога), которую Garmin сам считает по алгоритму
    Firstbeat из фактических тренировок — если задана, используется вместо ручного
    --threshold-hr в garmin_calibration_fit.py (и, в отличие от одного числа на весь
    период, учитывает изменение порога во времени).

    ИСПРАВЛЕНО: старые кандидаты /metrics-service/metrics/lactatethreshold(...) НИКОГДА не
    отвечали данными на реальном аккаунте (--dump-wellness-raw стабильно показывал
    lactate_threshold_v1/v2 = None) — это не "нет данных за период", а неверный путь
    эндпоинта. Правильные пути (подтверждены по исходникам python-garminconnect,
    get_lactate_threshold(), см. garminconnect/__init__.py на GitHub) — biometric-service,
    ОТДЕЛЬНО heart rate и speed как временные ряды (aggregation=daily,
    aggregationStrategy=LATEST), объединяются по дате ниже. Если и они пустые (например
    Garmin ни разу не считал ПАНО для этого аккаунта/спорта) — запасной вариант:
    latestLactateThreshold (только ПОСЛЕДНЕЕ значение, не ряд по датам, лучше чем ничего).
    Если всё равно пусто — таблица останется пустой и калибровка откатится на TRIMP/ручной
    --threshold-hr (см. --dump-wellness-raw, чтобы посмотреть сырой ответ этих эндпоинтов
    на своём аккаунте, если понадобится поправить парсинг под другой формат ответа).

    ИСПРАВЛЕНО-2 (диагностика garmin_pano_diagnose.py, 2026-08-12, аккаунт с ~2-летней
    историей): /range/{start}/{end} отдаёт 400 Bad Request, если ширина диапазона больше
    ~1 года (сам путь и параметры верные — при --days 365 всё работало, при --days 750
    падало 400 на КАЖДОМ варианте sport/aggregation). Раньше 400 просто гасился в
    _safe_get() -> None -> пустой результат по всему периоду, хотя часть истории внутри
    более коротких кусков диапазона реально была доступна. Теперь бьём запрошенный
    период на кусочки по <=365 дней и объединяем результат по каждому кусочку отдельно
    (см. _year_chunks ниже) — так же, как если бы calibration_fit гонял --days 365
    несколько раз подряд с разными --start-date/--end-date."""

    def _chunks(start_date, end_date, max_days=365):
        d0 = datetime.date.fromisoformat(start_date)
        d1 = datetime.date.fromisoformat(end_date)
        cur = d0
        step = datetime.timedelta(days=max_days)
        while cur <= d1:
            chunk_end = min(cur + step, d1)
            yield cur.isoformat(), chunk_end.isoformat()
            cur = chunk_end + datetime.timedelta(days=1)

    def _fetch_range(metric_path):
        merged = []
        for c_start, c_end in _chunks(start_date, end_date):
            path = (f"/biometric-service/stats/{metric_path}/range/{c_start}/{c_end}"
                    f"?sport=RUNNING&aggregation=daily&aggregationStrategy=LATEST")
            data = _safe_get(garth, path)
            if isinstance(data, list):
                merged.extend(data)
            elif isinstance(data, dict):
                merged.append(data)
        return merged

    hr_data = _fetch_range("lactateThresholdHeartRate")
    speed_data = _fetch_range("lactateThresholdSpeed")

    def _rows(data):
        if not data:
            return []
        if isinstance(data, dict):
            data = data.get("values") or data.get("statsMap") or data.get("data") or [data]
        return data if isinstance(data, list) else []

    def _row_date(row):
        # ИСПРАВЛЕНО (диагностика garmin_pano_diagnose.py на реальном аккаунте,
        # 2026-08-12): реальный ответ biometric-service/stats/.../range отдаёт НЕ
        # calendarDate/date, а {"from": ..., "until": ..., "series": "running",
        # "value": ..., "updatedDate": ...}. Раньше date_str всегда был None,
        # строки молча пропускались, и функция возвращала {} даже когда Garmin
        # реально отдавал год истории ПАНО. Берём "until" (конец периода
        # агрегации — совпадает с "from" при aggregation=daily) с fallback на
        # updatedDate/from/calendarDate/date для устойчивости к другим вариантам
        # ответа (aggregation=weekly и т.п. кандидаты из старого кода).
        return (row.get("until") or row.get("updatedDate") or row.get("from")
                or row.get("calendarDate") or row.get("date"))

    out = {}
    for row in _rows(hr_data):
        if not isinstance(row, dict):
            continue
        date_str = _row_date(row)
        hr = row.get("value") or row.get("lactateThresholdHeartRate") or row.get("heartRate")
        if date_str and hr:
            out.setdefault(date_str, {"threshold_hr": None, "threshold_pace_s_per_km": None})["threshold_hr"] = hr
    for row in _rows(speed_data):
        if not isinstance(row, dict):
            continue
        date_str = _row_date(row)
        speed = row.get("value") or row.get("lactateThresholdSpeed")
        if date_str and speed:
            out.setdefault(date_str, {"threshold_hr": None, "threshold_pace_s_per_km": None})["threshold_pace_s_per_km"] = s_per_km(speed)
    if out:
        for row in out.values():
            row["source"] = "biometric-service/stats/lactateThreshold{HeartRate,Speed}/range"
        return out

    latest = _safe_get(garth, "/biometric-service/biometric/latestLactateThreshold")
    # ИСПРАВЛЕНО-3 (та же диагностика 2026-08-12): реальный ответ — СПИСОК записей
    # (не dict, старая проверка `isinstance(latest, dict)` всегда была False и этот
    # fallback никогда не срабатывал), причём бег/вело/греблю Garmin отдаёт РАЗНЫМИ
    # записями с разными calendarDate, например:
    #   [{"calendarDate": "...", "speed": 0.319, "hearRate": null, "heartRateCycling": null, ...},
    #    {"calendarDate": "...", "speed": null, "hearRate": 183, "heartRateCycling": null, ...}]
    # Поле для бегового пульса называется именно "hearRate" (без опечатки на нашей
    # стороне — так у Garmin), поле для скорости — просто "speed" (не
    # "lactateThresholdSpeed"). heartRateCycling/rowSpeed/heartRateRowing относятся к
    # другим видам спорта и не используются (калькулятор — только бег).
    latest_rows = latest if isinstance(latest, list) else ([latest] if isinstance(latest, dict) else [])
    hr = speed = date_str = None
    for row in latest_rows:
        if not isinstance(row, dict):
            continue
        row_date = row.get("calendarDate")
        row_hr = row.get("hearRate") or row.get("heartRate") or _deep_find_key(row, "lactateThresholdHeartRate")
        row_speed = row.get("speed") or _deep_find_key(row, "lactateThresholdSpeed")
        if row_hr and (hr is None or (row_date or "") > (date_str or "")):
            hr = row_hr
            date_str = row_date or date_str
        if row_speed and (speed is None or (row_date or "") >= (date_str or "")):
            speed = row_speed
            date_str = date_str or row_date
    date_str = date_str or end_date
    if hr or speed:
        return {date_str: {
            "threshold_hr": hr,
            "threshold_pace_s_per_km": s_per_km(speed) if speed else None,
            "source": "biometric-service/biometric/latestLactateThreshold (только последнее значение, не временной ряд)",
        }}
    return {}


def fetch_stress_day(garth, date_str):
    data = _safe_get(garth, f"/wellness-service/wellness/dailyStress/{date_str}")
    if not data:
        return {}
    return {"stress_avg": data.get("avgStressLevel"), "stress_max": data.get("maxStressLevel")}


def fetch_training_readiness_day(garth, date_str):
    data = _safe_get(garth, f"/metrics-service/metrics/trainingreadiness/{date_str}")
    if not data:
        return {}
    row = data[0] if isinstance(data, list) and data else (data if isinstance(data, dict) else None)
    if not row:
        return {}
    return {"training_readiness_score": row.get("score"), "training_readiness_level": row.get("level")}


def fetch_body_battery_range(garth, start_date, end_date):
    """Body Battery отдаётся диапазоном за раз (в отличие от остальных wellness-метрик) —
    но у Garmin неофициально ограничение на ширину окна, поэтому бьём на кусочки по 28 дней."""
    out = {}
    d0 = datetime.date.fromisoformat(start_date)
    d1 = datetime.date.fromisoformat(end_date)
    cur = d0
    while cur <= d1:
        chunk_end = min(cur + datetime.timedelta(days=27), d1)
        data = _safe_get(garth, f"/wellness-service/wellness/bodyBattery/reports/daily?startDate={cur.isoformat()}&endDate={chunk_end.isoformat()}")
        for row in (data or []):
            d = row.get("date") or row.get("calendarDate")
            if not d:
                continue
            values = [v[1] for v in (row.get("bodyBatteryValuesArray") or []) if isinstance(v, (list, tuple)) and len(v) > 1 and v[1] is not None]
            out[d] = {
                "body_battery_min": min(values) if values else None,
                "body_battery_max": max(values) if values else None,
                "body_battery_charged": row.get("charged"),
                "body_battery_drained": row.get("drained"),
            }
        cur = chunk_end + datetime.timedelta(days=1)
    return out


def fetch_wellness_day(garth, username, date_str, body_battery_by_date):
    w = {}
    w.update(fetch_sleep_day(garth, username, date_str))
    w.update(fetch_hrv_day(garth, date_str))
    w.update(fetch_daily_summary_day(garth, username, date_str))
    w.update(fetch_stress_day(garth, date_str))
    w.update(fetch_training_readiness_day(garth, date_str))
    w.update(body_battery_by_date.get(date_str, {}))
    return w


def is_running(act):
    st = (act.get("activityType") or {})
    key = (st.get("typeKey") or "").lower()
    return key in RUN_TYPE_KEYS


def cross_group(act):
    """Группа кросс-тренировки (cycling/skiing/swimming/strength_training) или None."""
    st = (act.get("activityType") or {})
    key = (st.get("typeKey") or "").lower()
    return CROSS_TYPE_KEY_TO_GROUP.get(key)


def s_per_km(speed_m_s):
    """м/с -> сек/км."""
    if not speed_m_s or speed_m_s <= 0:
        return None
    return round(1000.0 / speed_m_s, 1)


def fmt_pace(sec_per_km):
    if not sec_per_km:
        return None
    m, s = divmod(int(round(sec_per_km)), 60)
    return f"{m}:{s:02d}"


# Конфаунды EF (см. докстринг п.14) — большинство берётся из того же ответа списка
# активностей, из которого уже читаются duration/distance/avg_hr выше, БЕЗ доп. запроса на
# каждую активность. ИСКЛЮЧЕНИЕ (проверено на реальном аккаунте через --dump-activity-fields):
# impactLoad почему-то отдаётся ТОЛЬКО в detail-объекте (/activity-service/activity/{id},
# поле detail.summaryDTO.impactLoad) — в bulk-списке активностей его нет вообще, поэтому при
# конфаунд-экстракции из одного только list-объекта impact_load был 0% на всех активностях
# (см. --no-detail-confounds ниже и export(), который теперь мёржит detail в объект перед
# извлечением конфаундов). Список ключей-кандидатов на поле, а не одно жёстко зашитое имя —
# неофициальный API, Garmin может по-разному называть поле в разных версиях ответа;
# первый непустой кандидат побеждает, если ни один не найден — None (см. докстринг п.14
# про --dump-raw для проверки на реальном аккаунте).
_EF_CONFOUND_FIELD_CANDIDATES = {
    "elevation_gain_m": ["elevationGain", "elevationGainInMeter", "elevationGainMeters"],
    "elevation_loss_m": ["elevationLoss", "elevationLossInMeter", "elevationLossMeters"],
    "min_temperature": ["minTemperature", "minTemp"],
    "max_temperature": ["maxTemperature", "maxTemp"],
    "avg_cadence_spm": ["averageRunningCadenceInStepsPerMinute", "avgRunCadence", "averageBikingCadenceInRevPerMinute"],
    "avg_stride_length_mm": ["avgStrideLength", "averageStrideLength"],
    "calories": ["calories"],
    "aerobic_training_effect": ["aerobicTrainingEffect"],
    "anaerobic_training_effect": ["anaerobicTrainingEffect"],
    "manual_activity": ["manualActivity", "manual"],
    "elevation_corrected": ["elevationCorrected"],
    # Добавлено по итогам разбора реального сырого дампа активности (--dump-activity-fields,
    # см. анализ raw-полей): water_estimated_ml — конфаунд по гидратации, который раньше был
    # только заявлен в докстринге, но не заполнялся; impact_load/difference_body_battery —
    # кандидаты на РЕАЛЬНОЕ измерение для категории orthopedic (сейчас в garmin_calibration_fit.py
    # она только проксируется через endurance-ось EF, см. п.14 докстринга); intensity minutes —
    # официальные ВОЗ-подобные пороги интенсивности от Garmin; hr_time_in_zone_1..5 — почасовое
    # время в зонах пульса для зонального TRIMP вместо TRIMP по среднему пульсу за тренировку.
    "water_estimated_ml": ["waterEstimated"],
    "impact_load": ["impactLoad"],
    "activity_training_load": ["activityTrainingLoad"],
    "difference_body_battery": ["differenceBodyBattery"],
    "moderate_intensity_min": ["moderateIntensityMinutes"],
    "vigorous_intensity_min": ["vigorousIntensityMinutes"],
    "hr_time_in_zone_1": ["hrTimeInZone_1"],
    "hr_time_in_zone_2": ["hrTimeInZone_2"],
    "hr_time_in_zone_3": ["hrTimeInZone_3"],
    "hr_time_in_zone_4": ["hrTimeInZone_4"],
    "hr_time_in_zone_5": ["hrTimeInZone_5"],
}


def _deep_find_key(obj, key_name, _depth=0):
    """Ищет ключ key_name (без учёта регистра) РЕКУРСИВНО по всему дереву obj — не только
    на верхнем уровне. Нужно, т.к. неофициальный API Garmin может отдавать нужные поля
    завёрнутыми во вложенный объект (например summaryDTO) в зависимости от эндпоинта/
    версии приложения; плоский act.get(key) такие случаи молча пропускал бы (см. докстринг
    п.14 и --dump-activity-fields, которым это и обнаруживается). Глубина ограничена, чтобы
    не уйти в бесконечность на случайных циклических/огромных структурах."""
    if _depth > 6:
        return None
    if isinstance(obj, dict):
        key_lower = key_name.lower()
        for k, v in obj.items():
            if isinstance(k, str) and k.lower() == key_lower and not isinstance(v, (dict, list)):
                return v
        for v in obj.values():
            found = _deep_find_key(v, key_name, _depth + 1)
            if found is not None:
                return found
    elif isinstance(obj, list):
        for item in obj[:3]:
            found = _deep_find_key(item, key_name, _depth + 1)
            if found is not None:
                return found
    return None


def _first_present(act, keys):
    for k in keys:
        v = _deep_find_key(act, k)
        if v is not None:
            return v
    return None


def _fahrenheit_to_celsius(f):
    return (f - 32.0) * 5.0 / 9.0


def _extract_ef_confounds(act, temperature_unit="c"):
    """Возвращает dict с сырыми конфаундами EF (см. докстринг п.14) для одной активности
    из ответа списка активностей Garmin. Best-effort — отсутствующее поле даёт None,
    ничего не падает. temperature_unit — "c"/"f", см. --temperature-unit (Garmin не
    документирует единицу температуры в ответе, она зависит от настроек аккаунта)."""
    raw = {k: _first_present(act, cands) for k, cands in _EF_CONFOUND_FIELD_CANDIDATES.items()}

    elevation_gain_m = round(raw["elevation_gain_m"], 1) if raw["elevation_gain_m"] is not None else None
    elevation_loss_m = round(raw["elevation_loss_m"], 1) if raw["elevation_loss_m"] is not None else None

    temps = [t for t in (raw["min_temperature"], raw["max_temperature"]) if t is not None]
    avg_temperature_c = None
    if temps:
        avg_temperature_raw = statistics.mean(temps)
        avg_temperature_c = round(_fahrenheit_to_celsius(avg_temperature_raw) if temperature_unit == "f"
                                    else avg_temperature_raw, 1)

    avg_cadence_spm = round(raw["avg_cadence_spm"], 1) if raw["avg_cadence_spm"] is not None else None
    # Garmin обычно отдаёт длину шага в мм — если число похоже на метры (< 5), не делим повторно.
    stride_raw = raw["avg_stride_length_mm"]
    avg_stride_length_m = None
    if stride_raw is not None:
        avg_stride_length_m = round(stride_raw / 1000.0, 3) if stride_raw > 5 else round(float(stride_raw), 3)

    calories = round(raw["calories"]) if raw["calories"] is not None else None
    aerobic_training_effect = raw["aerobic_training_effect"]
    anaerobic_training_effect = raw["anaerobic_training_effect"]
    manual_activity = 1 if raw["manual_activity"] else (0 if raw["manual_activity"] is not None else None)
    elevation_corrected = 1 if raw["elevation_corrected"] else (0 if raw["elevation_corrected"] is not None else None)

    water_estimated_ml = raw["water_estimated_ml"]
    impact_load = raw["impact_load"]
    activity_training_load = raw["activity_training_load"]
    difference_body_battery = raw["difference_body_battery"]
    moderate_intensity_min = raw["moderate_intensity_min"]
    vigorous_intensity_min = raw["vigorous_intensity_min"]
    hr_zone_times = {f"hr_time_in_zone_{i}": raw[f"hr_time_in_zone_{i}"] for i in range(1, 6)}

    return {
        "elevation_gain_m": elevation_gain_m,
        "elevation_loss_m": elevation_loss_m,
        "avg_temperature_c": avg_temperature_c,
        "avg_cadence_spm": avg_cadence_spm,
        "avg_stride_length_m": avg_stride_length_m,
        "calories": calories,
        "aerobic_training_effect": aerobic_training_effect,
        "anaerobic_training_effect": anaerobic_training_effect,
        "manual_activity": manual_activity,
        "elevation_corrected": elevation_corrected,
        "water_estimated_ml": water_estimated_ml,
        "impact_load": impact_load,
        "activity_training_load": activity_training_load,
        "difference_body_battery": difference_body_battery,
        "moderate_intensity_min": moderate_intensity_min,
        "vigorous_intensity_min": vigorous_intensity_min,
        **hr_zone_times,
    }


EF_CONFOUND_KEYS = ["elevation_gain_m", "elevation_loss_m", "avg_temperature_c", "avg_cadence_spm",
                     "avg_stride_length_m", "calories", "aerobic_training_effect",
                     "anaerobic_training_effect", "manual_activity", "elevation_corrected",
                     "water_estimated_ml", "impact_load", "activity_training_load",
                     "difference_body_battery", "moderate_intensity_min", "vigorous_intensity_min",
                     "hr_time_in_zone_1", "hr_time_in_zone_2", "hr_time_in_zone_3",
                     "hr_time_in_zone_4", "hr_time_in_zone_5"]


def fetch_laps(garth, activity_id):
    """Пытаемся получить типизированные лапы (со структурой WARMUP/INTERVAL_ACTIVE/...),
    если тренировка была структурированной; иначе — обычные авто/ручные лапы.
    Возвращает (laps, source) где source in {"typed","plain","none"}.
    """
    try:
        typed = garth.connectapi(f"/activity-service/activity/{activity_id}/typedsplits")
        laps = (typed or {}).get("lapDTOs") if isinstance(typed, dict) else typed
        if laps:
            return laps, "typed"
    except Exception:
        pass
    try:
        plain = garth.connectapi(f"/activity-service/activity/{activity_id}/splits")
        laps = (plain or {}).get("lapDTOs") if isinstance(plain, dict) else plain
        if laps:
            return laps, "plain"
    except Exception:
        pass
    return [], "none"


def lap_type_key(lap):
    """ИСПРАВЛЕНО (2026-08-12): реальный ответ /activity-service/activity/{id}/splits
    ("plain"-источник, который фактически используется почти всегда — см. fetch_laps,
    "typed"-эндпоинт /typedsplits у Garmin в проверенных активностях не отдавал лапы вовсе)
    не содержит поля "type" — это была гипотеза под возможный формат typedsplits, которая
    никогда не подтверждалась на реальных данных. Из-за этого lap_type_key всегда возвращал
    None, и вся intervals-таблица (4463+ строк) экспортировалась с lap_type=NULL — рабочие
    отрезки/отдых/разминку/заминку невозможно было отличить программно, только эвристикой
    по постфактум дистанции/темпу (см. анализ вне этого скрипта, 2026-08-12).
    Реальное поле — "intensityType" (значения ACTIVE/REST/WARMUP/COOLDOWN, проверено на
    сырых лапах, см. activity_*_fields_raw.json). Оставляем проверку "type" первой (на
    случай, если typedsplits когда-то реально заработает и вернёт другую форму), но с
    фолбэком на intensityType, который РЕАЛЬНО приходит."""
    t = lap.get("type")
    if isinstance(t, dict):
        t = t.get("typeKey") or t.get("key")
    if isinstance(t, str) and t:
        return t.upper()
    intensity = lap.get("intensityType")
    if isinstance(intensity, str) and intensity:
        return intensity.upper()
    return None


def normalize_lap(lap, idx):
    dur = lap.get("duration") or lap.get("movingDuration") or lap.get("elapsedDuration")
    dist = lap.get("distance")
    avg_hr = lap.get("averageHR") or lap.get("avgHr")
    max_hr = lap.get("maxHR") or lap.get("maxHr")
    avg_speed = lap.get("averageSpeed") or lap.get("avgSpeed")
    pace = s_per_km(avg_speed) if avg_speed else (s_per_km(dist / dur) if dist and dur else None)
    # Беговая динамика по кругу (см. анализ raw-полей, --dump-activity-fields) — нужна не сама
    # по себе, а как сырьё для внутритренировочного дрейфа (compute_lap_drift): рост GCT/verticalRatio
    # и падение каденса к концу тренировки при том же темпе/пульсе — прямой сигнал локального
    # нервно-мышечного утомления ВНУТРИ одной тренировки, а не только между тренировками.
    cadence = lap.get("averageRunCadence")
    gct_ms = lap.get("groundContactTime")
    vert_osc_mm = lap.get("verticalOscillation")
    vert_ratio = lap.get("verticalRatio")
    stride_mm = lap.get("strideLength")
    avg_resp = lap.get("avgRespirationRate")
    compliance = lap.get("directWorkoutComplianceScore")
    return {
        "idx": idx,
        "lap_type": lap_type_key(lap),
        "duration_s": round(dur, 1) if dur else None,
        "distance_m": round(dist, 1) if dist else None,
        "avg_hr": round(avg_hr) if avg_hr else None,
        "max_hr": round(max_hr) if max_hr else None,
        "avg_pace_s_per_km": pace,
        "avg_pace": fmt_pace(pace),
        "avg_cadence_spm": round(cadence, 1) if cadence else None,
        "ground_contact_time_ms": round(gct_ms, 1) if gct_ms else None,
        "vertical_oscillation_mm": round(vert_osc_mm, 1) if vert_osc_mm else None,
        "vertical_ratio": round(vert_ratio, 2) if vert_ratio else None,
        "stride_length_mm": round(stride_mm, 1) if stride_mm else None,
        "avg_respiration_rate": round(avg_resp, 1) if avg_resp else None,
        "workout_compliance_score": compliance,
    }


def compute_lap_drift(laps):
    """Внутритренировочный дрейф беговой динамики (см. анализ raw-полей, п.3 предложенных
    направлений): сравнивает первую и последнюю треть 'рабочих' лапов (с ненулевой
    дистанцией/длительностью и известным каденсом) по каденсу/GCT/вертикальным колебаниям.
    Рост GCT/verticalRatio и падение каденса к концу — сигнал локального нервно-мышечного
    утомления внутри тренировки, независимый от EF (EF считается по средним за всю
    тренировку и такой дрейф не видит). Возвращает None, если лапов слишком мало (< 6) или
    беговая динамика не пришла (старые Garmin-часы её не считают) — тогда фит просто не
    получит этот сигнал, это не ошибка."""
    active = [l for l in laps if l.get("duration_s") and l["duration_s"] > 0]
    with_dynamics = [l for l in active if l.get("avg_cadence_spm") is not None]
    if len(with_dynamics) < 6:
        return None
    third = max(2, len(with_dynamics) // 3)
    first = with_dynamics[:third]
    last = with_dynamics[-third:]

    def _pct_change(field):
        a = [l[field] for l in first if l.get(field) is not None]
        b = [l[field] for l in last if l.get(field) is not None]
        if not a or not b:
            return None
        avg_a, avg_b = statistics.mean(a), statistics.mean(b)
        if not avg_a:
            return None
        return round((avg_b - avg_a) / avg_a * 100.0, 1)

    return {
        "cadence_drift_pct": _pct_change("avg_cadence_spm"),
        "gct_drift_pct": _pct_change("ground_contact_time_ms"),
        "vertical_osc_drift_pct": _pct_change("vertical_oscillation_mm"),
    }


LAP_DRIFT_KEYS = ["cadence_drift_pct", "gct_drift_pct", "vertical_osc_drift_pct"]


def classify(total_duration_s, laps, long_threshold_s=75 * 60, hr_zones=None):
    """Эвристика типа тренировки. hr_zones — опц. dict {z2_hi, z4_lo, ...}, посчитанный
    estimate_hr_zones() (та же методика Карвонена + ПАНО, что и build_zones() в
    build_report.py, раздел 3 отчёта 'Пульсовые зоны и актуальный темп') из уже накопленных
    в БД данных Гармина (wellness.rhr, max_hr/avg_hr тренировок, lactate_threshold) — без
    ручного ввода. None, если в БД ещё нет истории ПАНО (см. estimate_hr_zones).

    ВАЖНО (см. диалог 2026-08-20): раньше порядок был typed-лапы -> CV темпа (interval) ->
    HR-threshold -> длительность (long). Из-за этого длинная тренировка (по факту >=75 мин)
    с ускорением/прогрессией/пикапом в середине (пульс и темп заметно меняются между лапами)
    ошибочно ловилась правилом #2 (CV темпа) и целиком помечалась как 'interval' — реальный
    пример: тренировка 150 мин с прогрессией темпа/пульса в середине (пульс поднимался почти
    до ПАНО), классифицированная как interval, из-за чего пропадала и из 'long', и из подсчёта
    марафонских вставок (marathon_time_per_activity ищет их только в long/easy). Длительность
    >= long_threshold_s теперь проверяется РАНЬШЕ эвристики по разбросу темпа: 75+ минут — это
    длинная тренировка, даже если внутри нее есть выраженный быстрый участок (см. раздел 10
    отчёта, где марафонский темп внутри long и так учитывается отдельно). Правило #1
    (типизированные Garmin work/rest лапы) по-прежнему проверяется первым и не переопределяется
    длительностью — если часы сами разметили интервальную структуру, это надёжнее эвристики.
    """
    active = [l for l in laps if l["duration_s"] and l["duration_s"] > 0]
    if not active:
        return "unknown", "нет данных по лапам"

    paces = [l["avg_pace_s_per_km"] for l in active if l["avg_pace_s_per_km"]]
    hrs = [l["avg_hr"] for l in active if l["avg_hr"]]

    typed_active = [l for l in active if l["lap_type"] in LAP_ACTIVE_TYPES]
    typed_rest = [l for l in active if l["lap_type"] in LAP_REST_TYPES]

    # 1) структурированная тренировка — Garmin сам разметил work/rest лапы.
    #
    # ВАЖНО (см. диалог 2026-08-20, тренировка 'Порог 2x20'' от 2026-07-09, id 23536204499):
    # Гармин помимо work/rest дополнительно бьёт один непрерывный work-сегмент автолапами ПО
    # КИЛОМЕТРУ — тот забег физически состоял из двух непрерывных 20-минутных пороговых блоков,
    # но каждый блок распался на ~5 автолапов ACTIVE по ~4 мин (без единого rest-лапа ВНУТРИ
    # блока, rest стоит только МЕЖДУ блоками). Если считать среднюю длительность каждого
    # ОТДЕЛЬНОГО typed_active-лапа (как раньше — 4 мин), это ниже порога 8 мин, и тренировка
    # ошибочно уходит в 'interval', хотя по факту это 'threshold' (2 длинных непрерывных
    # усилия). Поэтому здесь сначала СКЛЕИВАЮТСЯ подряд идущие typed_active-лапы (без
    # typed_rest-лапа между ними — то есть без разрыва на отдых) в непрерывные рабочие БЛОКИ,
    # и порог 8 минут сравнивается со СРЕДНЕЙ ДЛИТЕЛЬНОСТЬЮ БЛОКА, а не отдельного лапа.
    if len(typed_active) >= 2 and len(typed_rest) >= 1:
        work_blocks = []
        block_dur = 0.0
        for l in active:
            if l["lap_type"] in LAP_ACTIVE_TYPES:
                block_dur += l["duration_s"]
            else:
                if block_dur:
                    work_blocks.append(block_dur)
                block_dur = 0.0
        if block_dur:
            work_blocks.append(block_dur)
        avg_work_dur = statistics.mean(work_blocks) if work_blocks else statistics.mean(l["duration_s"] for l in typed_active)
        block_note = (
            f"{len(work_blocks)} непрерывных work-блоков (склеены автолапы без rest внутри блока)"
            if len(work_blocks) != len(typed_active) else f"{len(typed_active)} work"
        )
        if avg_work_dur <= 8 * 60:
            return "interval", f"типизированные лапы Garmin: {block_note} / {len(typed_rest)} rest, ср. work {avg_work_dur:.0f}с"
        else:
            return "threshold", f"типизированные лапы Garmin: {block_note} (ср. {avg_work_dur:.0f}с) с rest между ними"

    # 2) длительная — проверяется РАНЬШЕ эвристики по разбросу темпа (см. докстринг выше):
    #    75+ минут суммарно — это длинная тренировка, даже если внутри неё есть быстрый участок.
    if total_duration_s >= long_threshold_s:
        return "long", f"суммарная длительность {total_duration_s/60:.0f} мин >= порога {long_threshold_s/60:.0f} мин"

    # 3) нет типизации, короче порога 'long' — смотрим на разброс темпа между лапами (только
    #    если лапов много, т.е. похоже на ручные/авто-лапы вокруг структурированной тренировки,
    #    а не 1км-авто на easy)
    if len(active) >= 4 and len(paces) >= 4:
        cv_pace = statistics.pstdev(paces) / statistics.mean(paces)
        fastest = min(paces)
        slowest = max(paces)
        if cv_pace > 0.12 and slowest / fastest > 1.25:
            # есть явные быстрые и явные медленные (recovery) лапы
            fast_laps = [l for l in active if l["avg_pace_s_per_km"] and l["avg_pace_s_per_km"] <= fastest * 1.08]
            if len(fast_laps) >= 3 and statistics.mean(l["duration_s"] for l in fast_laps) <= 8 * 60:
                # ГЕЙТ ПО ПУЛЬСУ (см. диалог 2026-08-20): "быстрые" по темпу лапы должны быть
                # выполнены с реальным повышенным усилием (>= низа зоны Z4, hr_zones["z4_lo"],
                # см. build_zones/раздел 3 отчёта) — иначе это может быть просто уклон/сброс
                # усталости/погрешность GPS-темпа на лёгкой тренировке, а не осознанный
                # интервал. Если пульса на быстрых лапах нет вообще — не блокируем (старое
                # поведение, лучше так, чем совсем терять сигнал).
                fast_hrs = [l["avg_hr"] for l in fast_laps if l.get("avg_hr")]
                hr_note = ""
                hr_ok = True
                if fast_hrs and hr_zones:
                    mean_fast_hr = statistics.mean(fast_hrs)
                    hr_ok = mean_fast_hr >= hr_zones["z4_lo"]
                    hr_note = f", пульс быстрых лапов {mean_fast_hr:.0f} (порог интервала {hr_zones['z4_lo']})"
                if hr_ok:
                    return "interval", f"CV темпа {cv_pace:.2f}, {len(fast_laps)} быстрых интервалов среди {len(active)} лапов{hr_note}"

    # 4) устойчивый повышенный пульс без выраженного разброса, средняя длительность
    if hrs:
        avg_hr_all = statistics.mean(hrs)
        hi = hr_zones["z4_lo"] if hr_zones else None
        if hi and avg_hr_all >= hi and 12 * 60 <= total_duration_s <= 55 * 60:
            return "threshold", f"устойчивый пульс {avg_hr_all:.0f} >= порога зоны {hi}, длительность {total_duration_s/60:.0f} мин"

    # 5) легкая по умолчанию (если пульс невысокий) или mixed, если пульс высокий но не подошло выше
    if hrs:
        avg_hr_all = statistics.mean(hrs)
        lo = hr_zones["z2_hi"] if hr_zones else None
        if lo is None or avg_hr_all <= lo:
            return "easy", f"средний пульс {avg_hr_all:.0f}, длительность {total_duration_s/60:.0f} мин"
        return "mixed", f"не подошло ни под один профиль уверенно (ср.пульс {avg_hr_all:.0f}, {total_duration_s/60:.0f} мин) — разобрать вручную"

    return "easy", f"длительность {total_duration_s/60:.0f} мин, пульс неизвестен"


SCHEMA = """
CREATE TABLE IF NOT EXISTS activities (
    activity_id        INTEGER PRIMARY KEY,
    date                TEXT,
    start_time          TEXT,
    name                TEXT,
    sport               TEXT,
    duration_s          REAL,
    distance_m          REAL,
    avg_hr              INTEGER,
    max_hr              INTEGER,
    avg_pace_s_per_km   REAL,
    lap_source          TEXT,
    type_guess          TEXT,
    type_reason         TEXT,
    elevation_gain_m           REAL,
    elevation_loss_m           REAL,
    avg_temperature_c          REAL,
    avg_cadence_spm            REAL,
    avg_stride_length_m        REAL,
    calories                   REAL,
    aerobic_training_effect    REAL,
    anaerobic_training_effect  REAL,
    manual_activity            INTEGER,
    elevation_corrected        INTEGER,
    water_estimated_ml         REAL,
    impact_load                REAL,
    activity_training_load     REAL,
    difference_body_battery    INTEGER,
    moderate_intensity_min     REAL,
    vigorous_intensity_min     REAL,
    hr_time_in_zone_1          REAL,
    hr_time_in_zone_2          REAL,
    hr_time_in_zone_3          REAL,
    hr_time_in_zone_4          REAL,
    hr_time_in_zone_5          REAL,
    cadence_drift_pct          REAL,
    gct_drift_pct              REAL,
    vertical_osc_drift_pct     REAL,
    avg_grade_adjusted_pace_s_per_km REAL,
    exported_at         TEXT
);
CREATE TABLE IF NOT EXISTS intervals (
    activity_id         INTEGER,
    idx                 INTEGER,
    lap_type            TEXT,
    duration_s          REAL,
    distance_m          REAL,
    avg_hr              INTEGER,
    max_hr              INTEGER,
    avg_pace_s_per_km   REAL,
    avg_cadence_spm            REAL,
    ground_contact_time_ms     REAL,
    vertical_oscillation_mm    REAL,
    vertical_ratio             REAL,
    stride_length_mm           REAL,
    avg_respiration_rate       REAL,
    workout_compliance_score   INTEGER,
    avg_grade_adjusted_pace_s_per_km REAL,
    PRIMARY KEY (activity_id, idx),
    FOREIGN KEY (activity_id) REFERENCES activities(activity_id)
);
CREATE INDEX IF NOT EXISTS idx_activities_date ON activities(date);
CREATE INDEX IF NOT EXISTS idx_intervals_activity ON intervals(activity_id);
CREATE TABLE IF NOT EXISTS wellness (
    date                        TEXT PRIMARY KEY,
    sleep_score                 INTEGER,
    sleep_duration_s            REAL,
    sleep_deep_s                REAL,
    sleep_light_s               REAL,
    sleep_rem_s                 REAL,
    sleep_awake_s               REAL,
    sleep_avg_resp              REAL,
    hrv_last_night_avg          REAL,
    hrv_weekly_avg              REAL,
    hrv_status                  TEXT,
    rhr                         INTEGER,
    body_battery_min            INTEGER,
    body_battery_max            INTEGER,
    body_battery_charged        INTEGER,
    body_battery_drained        INTEGER,
    stress_avg                  INTEGER,
    stress_max                  INTEGER,
    training_readiness_score    INTEGER,
    training_readiness_level    TEXT,
    steps                       INTEGER,
    active_calories             INTEGER,
    avg_sleep_stress            REAL,
    sleep_spo2_avg              REAL,
    sleep_spo2_min              INTEGER,
    sleep_rhr                   INTEGER,
    skin_temp_deviation_c       REAL,
    sleep_training_feedback     TEXT,
    floors_ascended             REAL,
    stress_rest_s               REAL,
    stress_activity_s           REAL,
    stress_uncategorized_s      REAL,
    stress_low_s                REAL,
    stress_medium_s             REAL,
    stress_high_s               REAL,
    exported_at                 TEXT
);
CREATE TABLE IF NOT EXISTS cross_activities (
    activity_id         INTEGER PRIMARY KEY,
    date                TEXT,
    start_time          TEXT,
    name                TEXT,
    sport               TEXT,
    duration_s          REAL,
    distance_m          REAL,
    avg_hr              INTEGER,
    max_hr              INTEGER,
    exported_at         TEXT
);
CREATE INDEX IF NOT EXISTS idx_cross_activities_date ON cross_activities(date);
CREATE TABLE IF NOT EXISTS lactate_threshold (
    date                        TEXT PRIMARY KEY,
    threshold_hr                INTEGER,
    threshold_pace_s_per_km     REAL,
    source                      TEXT,
    exported_at                 TEXT
);
"""


# Колонки, добавленные позже исходной схемы (см. докстринг п.14) — для БД, созданных
# старой версией скрипта, CREATE TABLE IF NOT EXISTS их не добавит (таблица уже
# существует), поэтому докатываем ALTER TABLE ADD COLUMN отдельно (миграция).
ACTIVITY_MIGRATION_COLUMNS = [
    ("elevation_gain_m", "REAL"), ("elevation_loss_m", "REAL"), ("avg_temperature_c", "REAL"),
    ("avg_cadence_spm", "REAL"), ("avg_stride_length_m", "REAL"), ("calories", "REAL"),
    ("aerobic_training_effect", "REAL"), ("anaerobic_training_effect", "REAL"),
    ("manual_activity", "INTEGER"), ("elevation_corrected", "INTEGER"),
    ("water_estimated_ml", "REAL"), ("impact_load", "REAL"), ("activity_training_load", "REAL"),
    ("difference_body_battery", "INTEGER"), ("moderate_intensity_min", "REAL"),
    ("vigorous_intensity_min", "REAL"), ("hr_time_in_zone_1", "REAL"), ("hr_time_in_zone_2", "REAL"),
    ("hr_time_in_zone_3", "REAL"), ("hr_time_in_zone_4", "REAL"), ("hr_time_in_zone_5", "REAL"),
    ("cadence_drift_pct", "REAL"), ("gct_drift_pct", "REAL"), ("vertical_osc_drift_pct", "REAL"),
    ("avg_grade_adjusted_pace_s_per_km", "REAL"),
]

INTERVAL_MIGRATION_COLUMNS = [
    ("avg_cadence_spm", "REAL"), ("ground_contact_time_ms", "REAL"), ("vertical_oscillation_mm", "REAL"),
    ("vertical_ratio", "REAL"), ("stride_length_mm", "REAL"), ("avg_respiration_rate", "REAL"),
    ("workout_compliance_score", "INTEGER"), ("avg_grade_adjusted_pace_s_per_km", "REAL"),
]

WELLNESS_MIGRATION_COLUMNS = [
    ("avg_sleep_stress", "REAL"), ("sleep_spo2_avg", "REAL"), ("sleep_spo2_min", "INTEGER"),
    ("sleep_rhr", "INTEGER"), ("skin_temp_deviation_c", "REAL"), ("sleep_training_feedback", "TEXT"),
    ("floors_ascended", "REAL"), ("stress_rest_s", "REAL"), ("stress_activity_s", "REAL"),
    ("stress_uncategorized_s", "REAL"), ("stress_low_s", "REAL"), ("stress_medium_s", "REAL"),
    ("stress_high_s", "REAL"),
]


def _migrate_table_columns(con, table, columns):
    try:
        existing = {row[1] for row in con.execute(f"PRAGMA table_info({table})").fetchall()}
    except sqlite3.OperationalError:
        return  # таблицы ещё нет — CREATE TABLE IF NOT EXISTS в SCHEMA создаст её сразу с новыми колонками
    for col, coltype in columns:
        if col not in existing:
            con.execute(f"ALTER TABLE {table} ADD COLUMN {col} {coltype}")


def ensure_schema_migrations(con):
    _migrate_table_columns(con, "activities", ACTIVITY_MIGRATION_COLUMNS)
    _migrate_table_columns(con, "intervals", INTERVAL_MIGRATION_COLUMNS)
    _migrate_table_columns(con, "wellness", WELLNESS_MIGRATION_COLUMNS)


def open_db(path):
    con = sqlite3.connect(path)
    con.executescript(SCHEMA)
    ensure_schema_migrations(con)
    return con


_ACTIVITY_EXTRA_KEYS = EF_CONFOUND_KEYS + LAP_DRIFT_KEYS + ["avg_grade_adjusted_pace_s_per_km"]
_INTERVAL_EXTRA_KEYS = ["avg_cadence_spm", "ground_contact_time_ms", "vertical_oscillation_mm",
                        "vertical_ratio", "stride_length_mm", "avg_respiration_rate",
                        "workout_compliance_score", "avg_grade_adjusted_pace_s_per_km"]


def upsert_activity(con, a, exported_at):
    con.execute(f"""
        INSERT OR REPLACE INTO activities
        (activity_id, date, start_time, name, sport, duration_s, distance_m,
         avg_hr, max_hr, avg_pace_s_per_km, lap_source, type_guess, type_reason,
         {", ".join(_ACTIVITY_EXTRA_KEYS)}, exported_at)
        VALUES ({", ".join(["?"] * (14 + len(_ACTIVITY_EXTRA_KEYS)))})
    """, (a["activity_id"], a["date"], a["start_time"], a["name"], a["sport"],
          a["duration_s"], a["distance_m"], a["avg_hr"], a["max_hr"], a["avg_pace_s_per_km"],
          a["lap_source"], a["type_guess"], a["type_reason"],
          *[a.get(k) for k in _ACTIVITY_EXTRA_KEYS], exported_at))
    con.execute("DELETE FROM intervals WHERE activity_id=?", (a["activity_id"],))
    for lap in a["intervals"]:
        con.execute(f"""
            INSERT OR REPLACE INTO intervals
            (activity_id, idx, lap_type, duration_s, distance_m, avg_hr, max_hr, avg_pace_s_per_km,
             {", ".join(_INTERVAL_EXTRA_KEYS)})
            VALUES ({", ".join(["?"] * (8 + len(_INTERVAL_EXTRA_KEYS)))})
        """, (a["activity_id"], lap["idx"], lap["lap_type"], lap["duration_s"], lap["distance_m"],
              lap["avg_hr"], lap["max_hr"], lap["avg_pace_s_per_km"],
              *[lap.get(k) for k in _INTERVAL_EXTRA_KEYS]))


WELLNESS_COLUMNS = [
    "sleep_score", "sleep_duration_s", "sleep_deep_s", "sleep_light_s", "sleep_rem_s", "sleep_awake_s",
    "sleep_avg_resp", "hrv_last_night_avg", "hrv_weekly_avg", "hrv_status", "rhr",
    "body_battery_min", "body_battery_max", "body_battery_charged", "body_battery_drained",
    "stress_avg", "stress_max", "training_readiness_score", "training_readiness_level",
    "steps", "active_calories",
] + [c for c, _ in WELLNESS_MIGRATION_COLUMNS]


def upsert_wellness(con, date_str, w, exported_at):
    cols = ", ".join(WELLNESS_COLUMNS)
    placeholders = ", ".join(["?"] * len(WELLNESS_COLUMNS))
    con.execute(f"""
        INSERT OR REPLACE INTO wellness (date, {cols}, exported_at)
        VALUES (?, {placeholders}, ?)
    """, (date_str, *[w.get(c) for c in WELLNESS_COLUMNS], exported_at))


def upsert_cross_activity(con, a, exported_at):
    con.execute("""
        INSERT OR REPLACE INTO cross_activities
        (activity_id, date, start_time, name, sport, duration_s, distance_m, avg_hr, max_hr, exported_at)
        VALUES (?,?,?,?,?,?,?,?,?,?)
    """, (a["activity_id"], a["date"], a["start_time"], a["name"], a["sport"],
          a["duration_s"], a["distance_m"], a["avg_hr"], a["max_hr"], exported_at))


def upsert_lactate_threshold(con, date_str, threshold_hr, threshold_pace_s_per_km, source, exported_at):
    con.execute("""
        INSERT OR REPLACE INTO lactate_threshold (date, threshold_hr, threshold_pace_s_per_km, source, exported_at)
        VALUES (?,?,?,?,?)
    """, (date_str, threshold_hr, threshold_pace_s_per_km, source, exported_at))


def export_csv_from_db(db_path):
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    base = os.path.splitext(db_path)[0]
    for table in ("activities", "intervals", "wellness", "cross_activities", "lactate_threshold"):
        try:
            rows = con.execute(f"SELECT * FROM {table} ORDER BY 1").fetchall()
        except sqlite3.OperationalError:
            print(f"{table}: таблицы нет в этой БД (старый экспорт без --wellness/кросс-тренировок) — пропущено")
            continue
        out_path = f"{base}_{table}.csv"
        with open(out_path, "w", encoding="utf-8", newline="") as f:
            w = csv.writer(f)
            if rows:
                w.writerow(rows[0].keys())
                for r in rows:
                    w.writerow(list(r))
        print(f"{table}: {len(rows)} строк -> {out_path}")
    con.close()


# ---------------------------------------------------------------------------
# Конвертация между форматами (--in / --out-format), без обращения к Garmin.
# Общая in-memory структура — список dict вида "results" (как в export()):
#   {activity_id, date, start_time, name, sport, duration_s, distance_m, avg_hr,
#    max_hr, avg_pace_s_per_km, avg_pace, lap_source, type_guess, type_reason,
#    intervals: [{idx, lap_type, duration_s, distance_m, avg_hr, max_hr,
#                 avg_pace_s_per_km, avg_pace}, ...]}
# ---------------------------------------------------------------------------
INTERVAL_KEYS = ["idx", "lap_type", "duration_s", "distance_m", "avg_hr", "max_hr", "avg_pace_s_per_km",
                  "avg_cadence_spm", "ground_contact_time_ms", "vertical_oscillation_mm",
                  "vertical_ratio", "stride_length_mm", "avg_respiration_rate", "workout_compliance_score"]
ACTIVITY_KEYS = ["activity_id", "date", "start_time", "name", "sport", "duration_s", "distance_m",
                  "avg_hr", "max_hr", "avg_pace_s_per_km", "lap_source", "type_guess", "type_reason"] \
                 + EF_CONFOUND_KEYS + LAP_DRIFT_KEYS


def detect_format(path):
    ext = os.path.splitext(path)[1].lower()
    if ext == ".json":
        return "json"
    if ext in (".db", ".sqlite", ".sqlite3"):
        return "sqlite"
    if ext == ".csv":
        return "csv"
    sys.exit(f"Не понял формат файла по расширению: {path} (ожидается .json / .db|.sqlite|.sqlite3 / .csv)")


def _coerce_num(v):
    if v in (None, ""):
        return None
    try:
        f = float(v)
        return int(f) if f.is_integer() else f
    except (TypeError, ValueError):
        return v


def results_from_sqlite(db_path):
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    acts = con.execute("SELECT * FROM activities ORDER BY date").fetchall()
    results = []
    for a in acts:
        a = dict(a)
        laps = con.execute("SELECT * FROM intervals WHERE activity_id=? ORDER BY idx", (a["activity_id"],)).fetchall()
        a["intervals"] = [dict(l) for l in laps]
        for l in a["intervals"]:
            l["avg_pace"] = fmt_pace(l.get("avg_pace_s_per_km"))
        a["avg_pace"] = fmt_pace(a.get("avg_pace_s_per_km"))
        a.pop("exported_at", None)
        results.append(a)
    con.close()
    return results


def results_from_json(path):
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    results = data.get("activities", data if isinstance(data, list) else [])
    for a in results:
        a.setdefault("intervals", [])
        for l in a["intervals"]:
            l.setdefault("avg_pace", fmt_pace(l.get("avg_pace_s_per_km")))
        a.setdefault("avg_pace", fmt_pace(a.get("avg_pace_s_per_km")))
    return results


def results_from_csv(path):
    """path — файл активностей (обычно <base>_activities.csv, как пишет --export-csv);
    рядом ожидается <base>_intervals.csv (та же основа имени)."""
    if path.endswith("_activities.csv"):
        base = path[: -len("_activities.csv")]
    else:
        base = os.path.splitext(path)[0]
        print(f"Предупреждение: {path} не заканчивается на _activities.csv — ищу пару по основе имени {base}*")
    act_path = f"{base}_activities.csv" if os.path.isfile(f"{base}_activities.csv") else path
    int_path = f"{base}_intervals.csv"
    if not os.path.isfile(int_path):
        sys.exit(f"Не найден файл интервалов рядом: {int_path} (нужна пара <base>_activities.csv + <base>_intervals.csv)")

    by_id = {}
    with open(act_path, "r", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            a = {k: _coerce_num(row.get(k)) for k in ACTIVITY_KEYS}
            a["activity_id"] = int(row["activity_id"])
            a["intervals"] = []
            a["avg_pace"] = fmt_pace(a.get("avg_pace_s_per_km"))
            by_id[a["activity_id"]] = a
    with open(int_path, "r", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            aid = int(row["activity_id"])
            if aid not in by_id or row.get("idx") in (None, ""):
                continue
            lap = {k: _coerce_num(row.get(k)) for k in INTERVAL_KEYS}
            lap["avg_pace"] = fmt_pace(lap.get("avg_pace_s_per_km"))
            by_id[aid]["intervals"].append(lap)
    return sorted(by_id.values(), key=lambda a: a.get("date") or "")


def load_results(path):
    fmt = detect_format(path)
    if fmt == "json":
        return results_from_json(path)
    if fmt == "sqlite":
        return results_from_sqlite(path)
    return results_from_csv(path)


def write_results_to_sqlite(results, db_path):
    exported_at = datetime.datetime.now().replace(microsecond=0).isoformat()
    con = open_db(db_path)
    with con:
        for a in results:
            upsert_activity(con, a, exported_at)
    con.close()
    print(f"Записано в {db_path}: {len(results)} тренировок")


def write_results_to_json(results, path):
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"meta": {"count": len(results)}, "activities": results}, f, ensure_ascii=False, indent=2)
    print(f"Записано в {path}: {len(results)} тренировок")


def write_results_to_csv(results, out_base):
    act_path = f"{out_base}_activities.csv"
    int_path = f"{out_base}_intervals.csv"
    with open(act_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(ACTIVITY_KEYS)
        for a in results:
            w.writerow([a.get(k) for k in ACTIVITY_KEYS])
    with open(int_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["activity_id"] + INTERVAL_KEYS)
        for a in results:
            for lap in a["intervals"]:
                w.writerow([a["activity_id"]] + [lap.get(k) for k in INTERVAL_KEYS])
    print(f"Записано: {act_path}, {int_path}")


def convert(in_path, out_format, out_path):
    results = load_results(in_path)
    print(f"Прочитано из {in_path}: {len(results)} тренировок ({detect_format(in_path)})")
    if out_format == "sqlite":
        write_results_to_sqlite(results, out_path)
    elif out_format == "json":
        write_results_to_json(results, out_path)
    elif out_format == "csv":
        base = out_path[:-4] if out_path.lower().endswith(".csv") else out_path
        write_results_to_csv(results, base)
    else:
        sys.exit(f"Неизвестный --out-format: {out_format}")



def _read_lt_from_db(db_path):
    con = sqlite3.connect(db_path)
    try:
        rows = con.execute(
            "SELECT date, threshold_hr FROM lactate_threshold WHERE threshold_hr IS NOT NULL ORDER BY date"
        ).fetchall()
    except sqlite3.OperationalError:
        rows = []
    con.close()
    return rows


def _read_wellness_rhr_from_db(db_path):
    con = sqlite3.connect(db_path)
    try:
        rows = con.execute(
            "SELECT date, rhr FROM wellness WHERE rhr IS NOT NULL ORDER BY date"
        ).fetchall()
    except sqlite3.OperationalError:
        rows = []
    con.close()
    return rows


def _read_hr_pairs_from_db(db_path):
    con = sqlite3.connect(db_path)
    try:
        rows = con.execute(
            "SELECT max_hr, avg_hr FROM activities WHERE max_hr IS NOT NULL AND avg_hr IS NOT NULL"
        ).fetchall()
    except sqlite3.OperationalError:
        rows = []
    con.close()
    return rows


def _pano_from_db(db_path, recent_days=120):
    """Порт garmin_pano_estimate() из build_report.py (см. отчёт, раздел 1 'Пульс ПАНО') —
    среднее threshold_hr за последние recent_days дней от последней записи lactate_threshold
    в уже накопленной локальной БД. Возвращает None, если истории ПАНО в БД ещё нет
    (например, самый первый экспорт с чистой БД)."""
    rows = _read_lt_from_db(db_path)
    if not rows:
        return None
    dates = [datetime.date.fromisoformat(str(d)[:10]) for d, _ in rows]
    last_date = max(dates)
    cutoff = last_date - datetime.timedelta(days=recent_days)
    recent = [hr for (d, hr), dt in zip(rows, dates) if dt >= cutoff]
    if not recent:
        recent = [hr for _, hr in rows]
    return round(statistics.mean(recent))


def _rest_hr_from_db(db_path, recent_days=90, fallback_rhr=None):
    """Порт estimate_resting_hr() из build_report.py — среднее wellness.rhr за последние
    recent_days дней от последней записи."""
    rows = _read_wellness_rhr_from_db(db_path)
    if not rows:
        return fallback_rhr if fallback_rhr else 50
    dates = [datetime.date.fromisoformat(str(d)[:10]) for d, _ in rows]
    last_date = max(dates)
    cutoff = last_date - datetime.timedelta(days=recent_days)
    recent = [rhr for (d, rhr), dt in zip(rows, dates) if dt >= cutoff]
    if not recent:
        recent = [rhr for _, rhr in rows]
    return statistics.mean(recent)


def _max_hr_from_db(db_path, fallback_max_hr=None, abs_ceiling=215, max_spread=60):
    """Порт estimate_max_hr() из build_report.py — max(max_hr) только по тренировкам без
    похожего на выброс разрыва max_hr-avg_hr (защита от одиночных скачков оптического
    пульсометра, см. докстринг оригинала в build_report.py)."""
    rows = _read_hr_pairs_from_db(db_path)
    clean = [mh for mh, ah in rows if mh <= abs_ceiling and (mh - ah) <= max_spread]
    if clean:
        return round(max(clean))
    if fallback_max_hr:
        return round(fallback_max_hr)
    return 195


def build_zones_for_classifier(rhr, pano, max_hr, z2_hrr=0.60, z3_hrr=0.70):
    """Порт build_zones() из build_report.py (см. отчёт, раздел 3 'Пульсовые зоны и актуальный
    темп') — та же методика Карвонена (%HRR) + ПАНО из истории Гармина как верх Z4, чтобы
    классификатор тренировок здесь использовал ТЕ ЖЕ пульсовые зоны, что и отчёт. Возвращает
    только то, что нужно classify(): z2_hi (верх лёгкой/аэробной зоны) и z4_lo (низ порогового,
    то есть настоящего 'рабочего' усилия)."""
    hrr = max_hr - rhr
    z2_lo = round(rhr + z2_hrr * hrr)
    z3_lo_hrr = round(rhr + z3_hrr * hrr)
    z4_hi = pano
    z2_hi = z3_lo_hrr - 1
    z3_lo = z2_hi + 1
    z4_lo = round((z3_lo + z4_hi) / 2)
    return {"z2_hi": z2_hi, "z4_lo": z4_lo, "pano": pano, "rhr": round(rhr, 1), "max_hr": max_hr}


def estimate_hr_zones(db_path):
    """Оценивает пороги пульса для classify() из уже накопленной локальной БД (та же методика,
    что и build_zones() в build_report.py, раздел 3 отчёта): rest_hr — из wellness.rhr
    (реальные данные Гармина, см. _rest_hr_from_db), max_hr — устойчивая оценка по фактическим
    max_hr/avg_hr тренировок (см. _max_hr_from_db), ПАНО — из истории lactate_threshold
    (Гармин/Firstbeat, см. _pano_from_db). Никаких ручных вводов не требуется — все три
    величины берутся из данных, которые Гармин уже прислал. Возвращает None, если в БД ещё
    нет истории ПАНО (lactate_threshold) — например, самый первый экспорт с чистой БД; тогда
    threshold/easy-классификация по абсолютному пульсу временно пропускается (см. export())."""
    if not db_path or not os.path.isfile(db_path):
        return None
    pano = _pano_from_db(db_path)
    if pano is None:
        return None
    rhr = _rest_hr_from_db(db_path)
    max_hr = _max_hr_from_db(db_path)
    return build_zones_for_classifier(rhr, pano, max_hr)


def reclassify_activities(db_path, long_threshold_s=75 * 60, activity_ids=None, verbose=True):
    """Второй проход: пересчитывает type_guess/type_reason для уже сохранённых в БД активностей
    (см. диалог 2026-08-20: 'надо делать в два прохода. Сначала экспортируем, потом считаем
    поля'). Раньше classify() вызывался ПРЯМО во время выгрузки каждой активности из Garmin API,
    используя hr_zones, посчитанные по БД ДО того, как в неё попали данные текущего запуска
    (включая свежую историю ПАНО/wellness.rhr, которые сама же выгрузка и добавляет чуть позже
    в export_wellness()) — то есть классификация первой партии активностей в каждом запуске
    всегда была немного 'вслепую'. Теперь это отдельный шаг, который:
      1) не делает ни одного обращения к Garmin API — только читает уже сохранённые activities/
         intervals из локальной БД (db_path);
      2) считает hr_zones (estimate_hr_zones) по ПОЛНОСТЬЮ обновлённой БД — то есть уже видит
         и только что выгруженные активности, и свежую историю ПАНО/RHR из wellness;
      3) может быть запущен отдельно (--reclassify-only) в любой момент — например, сразу после
         правки логики classify() (как это уже случалось, см. диалог 2026-08-20 про 150-минутную
         тренировку) — чтобы пересчитать типы по ВСЕЙ истории без повторной выгрузки из Гармина.
    activity_ids — опц. список ID, чтобы пересчитать только часть базы (иначе — вся activities).
    """
    hr_zones = estimate_hr_zones(db_path)
    if hr_zones:
        print(
            f"Пороги пульса для классификатора (те же зоны, что в отчёте build_report.py, "
            f"раздел 3: rhr={hr_zones['rhr']}, max_hr={hr_zones['max_hr']}, ПАНО={hr_zones['pano']}): "
            f"z2_hi={hr_zones['z2_hi']}, z4_lo={hr_zones['z4_lo']}"
        )
    else:
        print("Пороги пульса не посчитаны (в БД ещё нет истории ПАНО lactate_threshold) — "
              "классификация threshold/easy по абсолютному пульсу будет пропущена, "
              "но interval/long/mixed определятся и без них.")

    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    q = "SELECT activity_id, date, name, duration_s, type_guess FROM activities"
    params = ()
    if activity_ids:
        placeholders = ",".join("?" * len(activity_ids))
        q += f" WHERE activity_id IN ({placeholders})"
        params = tuple(activity_ids)
    q += " ORDER BY date"
    acts = con.execute(q, params).fetchall()

    n_changed = 0
    for i, act in enumerate(acts, 1):
        aid = act["activity_id"]
        lap_rows = con.execute(
            "SELECT duration_s, avg_pace_s_per_km, avg_hr, lap_type FROM intervals "
            "WHERE activity_id=? ORDER BY idx",
            (aid,),
        ).fetchall()
        laps = [
            {"duration_s": r["duration_s"], "avg_pace_s_per_km": r["avg_pace_s_per_km"],
             "avg_hr": r["avg_hr"], "lap_type": r["lap_type"] or "ACTIVE"}
            for r in lap_rows
        ]
        type_guess, reason = classify(act["duration_s"] or 0, laps, long_threshold_s=long_threshold_s, hr_zones=hr_zones)
        if act["type_guess"] != type_guess:
            n_changed += 1
        con.execute(
            "UPDATE activities SET type_guess=?, type_reason=? WHERE activity_id=?",
            (type_guess, reason, aid),
        )
        if verbose:
            print(f"[{i}/{len(acts)}] {act['date']} {(act['name'] or '')[:40]:40s} -> {type_guess:10s} ({len(laps)} лапов)")
    con.commit()
    con.close()
    print(f"Пересчитано типов: {len(acts)}, изменилось: {n_changed}")
    return len(acts), n_changed


def export(args):
    garth = connect(args.account, force_login=args.force_login)

    end_date = args.end_date or datetime.date.today().isoformat()
    if args.start_date:
        start_date = args.start_date
    else:
        start_date = (datetime.date.today() - datetime.timedelta(days=args.days)).isoformat()

    # Два прохода (см. диалог 2026-08-20): здесь (проход 1) только выгружаем сырые данные из
    # Garmin и сохраняем их в БД, БЕЗ классификации type_guess — она требует пульсовых зон
    # (rest_hr/max_hr/ПАНО), а зоны надёжнее считать по УЖЕ ПОЛНОСТЬЮ обновлённой локальной БД
    # (включая только что выгруженную wellness/lactate_threshold этого же запуска), а не по
    # тому, что было в базе ДО текущей выгрузки. Проход 2 — reclassify_activities() в конце
    # export(), после export_wellness()/export_cross_training().
    print(f"Период: {start_date} .. {end_date}")
    acts = fetch_activities(garth, start_date, end_date)
    acts = [a for a in acts if is_running(a)]
    print(f"Найдено беговых активностей: {len(acts)}")

    if args.dump_raw:
        raw = garth.connectapi(f"/activity-service/activity/{args.dump_raw}")
        raw_laps, src = fetch_laps(garth, args.dump_raw)
        with open(f"activity_{args.dump_raw}_raw.json", "w", encoding="utf-8") as f:
            json.dump({"summary": raw, "laps_source": src, "laps": raw_laps}, f, ensure_ascii=False, indent=2)
        print(f"Сырой JSON сохранён в activity_{args.dump_raw}_raw.json (laps_source={src})")
        return

    results = []
    for i, act in enumerate(acts, 1):
        aid = act.get("activityId")
        name = act.get("activityName") or ""
        start_local = act.get("startTimeLocal") or ""
        date = start_local.split(" ")[0] if start_local else None
        duration = act.get("duration")
        distance = act.get("distance")
        avg_hr = act.get("averageHR")
        max_hr = act.get("maxHR")
        avg_speed = act.get("averageSpeed")
        avg_pace = s_per_km(avg_speed) if avg_speed else (s_per_km(distance / duration) if distance and duration else None)

        laps_raw, lap_source = fetch_laps(garth, aid)
        laps = [normalize_lap(l, idx + 1) for idx, l in enumerate(laps_raw)]

        # type_guess/type_reason считаются ПОЗЖЕ, отдельным проходом (reclassify_activities(),
        # см. конец export()) — не здесь, см. докстринг reclassify_activities().
        type_guess, reason = None, "ожидает пересчёта (проход 2, см. reclassify_activities)"
        # impactLoad (и на будущее — любой другой конфаунд, который Garmin решит переносить между
        # ответами) реально приходит только в detail-объекте, не в bulk-списке (см. докстринг
        # _EF_CONFOUND_FIELD_CANDIDATES) — по умолчанию делаем доп. запрос на активность, чтобы его
        # получить; --no-detail-confounds отключает (быстрее, но impact_load/orthopedic-фит не заполнятся).
        confound_source = act
        if not args.no_detail_confounds:
            detail = _safe_get(garth, f"/activity-service/activity/{aid}")
            if detail:
                confound_source = {**act, "_activity_detail": detail}
        confounds = _extract_ef_confounds(confound_source, temperature_unit=args.temperature_unit)
        drift = compute_lap_drift(laps) or {"cadence_drift_pct": None, "gct_drift_pct": None, "vertical_osc_drift_pct": None}

        # Grade-adjusted pace (directGradeAdjustedSpeed) — и в среднем за тренировку, и ПО КАЖДОМУ
        # ЛАПУ (см. fetch_grade_adjusted_pace_by_lap) — только для уличных тренировок со значимым
        # набором высоты (--grade-adjusted-min-elevation-m): для ровных/беговой дорожки/помещения
        # метрика неинформативна или отсутствует, а сам запрос к /details — лишний (медленный,
        # поточные данные по секундам).
        sport_key = ((act.get("activityType") or {}).get("typeKey") or "").lower()
        elevation_gain_m = confounds.get("elevation_gain_m")
        avg_gap_s_per_km = None
        if (not args.no_grade_adjusted_pace and sport_key in OUTDOOR_RUN_TYPE_KEYS
                and elevation_gain_m is not None and elevation_gain_m >= args.grade_adjusted_min_elevation_m):
            avg_gap_s_per_km, lap_gap_by_idx = fetch_grade_adjusted_pace_by_lap(
                garth, aid, laps, max_chart_size=args.max_chart_size)
            for lap in laps:
                lap["avg_grade_adjusted_pace_s_per_km"] = lap_gap_by_idx.get(lap["idx"])
        else:
            for lap in laps:
                lap["avg_grade_adjusted_pace_s_per_km"] = None

        results.append({
            "activity_id": aid,
            "date": date,
            "start_time": start_local,
            "name": name,
            "sport": (act.get("activityType") or {}).get("typeKey"),
            "duration_s": round(duration, 1) if duration else None,
            "distance_m": round(distance, 1) if distance else None,
            "avg_hr": round(avg_hr) if avg_hr else None,
            "max_hr": round(max_hr) if max_hr else None,
            "avg_pace_s_per_km": avg_pace,
            "avg_pace": fmt_pace(avg_pace),
            "lap_source": lap_source,
            "type_guess": type_guess,
            "type_reason": reason,
            "intervals": laps,
            **confounds,
            **drift,
            "avg_grade_adjusted_pace_s_per_km": avg_gap_s_per_km,
        })
        gap_note = f", GAP {fmt_pace(avg_gap_s_per_km)}/км" if avg_gap_s_per_km else ""
        print(f"[{i}/{len(acts)}] {date} {name[:40]:40s} -> выгружено ({lap_source}, {len(laps)} лапов{gap_note})")

    exported_at = start_local_now = datetime.datetime.now().replace(microsecond=0).isoformat()
    con = open_db(args.db)
    with con:
        for a in results:
            upsert_activity(con, a, exported_at)
    con.close()

    total = con_count = None
    con2 = sqlite3.connect(args.db)
    total = con2.execute("SELECT COUNT(*) FROM activities").fetchone()[0]
    con2.close()

    print(f"\nГотово: {args.db} (записано/обновлено в этом запуске: {len(results)}; всего в базе: {total})")

    if not args.no_wellness:
        export_wellness(garth, args, start_date, end_date)

    if not args.no_cross_training:
        export_cross_training(garth, args, start_date, end_date)

    # Проход 2 (см. диалог 2026-08-20): классификация — теперь, когда БД уже содержит и только
    # что выгруженные активности, и свежую wellness/lactate_threshold этого же запуска.
    print()
    reclassify_activities(args.db, long_threshold_s=args.long_threshold * 60)

    if args.export_csv_after:
        export_csv_from_db(args.db)


def export_wellness(garth, args, start_date, end_date):
    """Сон/HRV/RHR/Body Battery/стресс/training readiness за период — по дню
    (кроме Body Battery, тот отдаётся диапазоном). Неофициальные эндпоинты, каждый
    вызов best-effort — если Garmin не отдал какую-то метрику за день, просто None."""
    username = resolve_display_name(garth)
    if not username:
        print("Не удалось определить пользователя для wellness-эндпоинтов (ни displayName, ни email) — "
              "пропускаю сон/HRV/RHR/стресс.")
        return

    print(f"\nWellness ({start_date} .. {end_date}):")
    print("  Body Battery (диапазоном)...")
    bb_by_date = fetch_body_battery_range(garth, start_date, end_date)

    d0 = datetime.date.fromisoformat(start_date)
    d1 = datetime.date.fromisoformat(end_date)
    dates = [(d0 + datetime.timedelta(days=i)).isoformat() for i in range((d1 - d0).days + 1)]

    con = open_db(args.db)
    exported_at = datetime.datetime.now().replace(microsecond=0).isoformat()
    n_written = 0
    with con:
        for i, date_str in enumerate(dates, 1):
            if not args.force_refresh_wellness:
                existing = con.execute("SELECT 1 FROM wellness WHERE date=?", (date_str,)).fetchone()
                if existing:
                    continue
            w = fetch_wellness_day(garth, username, date_str, bb_by_date)
            if w:
                upsert_wellness(con, date_str, w, exported_at)
                n_written += 1
            if i % 30 == 0 or i == len(dates):
                print(f"  [{i}/{len(dates)}] дней обработано, записано новых: {n_written}")
            if args.wellness_delay_ms:
                time.sleep(args.wellness_delay_ms / 1000.0)

    print(f"Wellness готово: записано/обновлено {n_written} дней из {len(dates)}.")

    if not getattr(args, "no_lactate_threshold", False):
        print("  ПАНО (лактатный порог, история от Garmin)...")
        lt_by_date = fetch_lactate_threshold_range(garth, start_date, end_date)
        if lt_by_date:
            with con:
                for date_str, lt in lt_by_date.items():
                    upsert_lactate_threshold(con, date_str, lt.get("threshold_hr"), lt.get("threshold_pace_s_per_km"),
                                              lt.get("source"), exported_at)
            print(f"  ПАНО: записано {len(lt_by_date)} точек истории.")
        else:
            print("  ПАНО: Garmin не отдал историю по известным эндпоинтам — используй --dump-wellness-raw "
                  "для диагностики на реальном аккаунте, либо укажи --threshold-hr вручную в garmin_calibration_fit.py.")
    con.close()


def export_cross_training(garth, args, start_date, end_date):
    """Вело/лыжи/плавание/силовые — только суммарная нагрузка (см. cross_group())."""
    groups = set((args.cross_sports or "cycling,skiing,swimming,strength_training").split(","))
    print(f"\nКросс-тренировки ({', '.join(sorted(groups))}, {start_date} .. {end_date}):")
    acts = fetch_activities(garth, start_date, end_date, activity_type=None)
    matched = []
    for act in acts:
        g = cross_group(act)
        if g and g in groups:
            matched.append((g, act))
    print(f"Найдено кросс-тренировок: {len(matched)}")

    exported_at = datetime.datetime.now().replace(microsecond=0).isoformat()
    con = open_db(args.db)
    with con:
        for g, act in matched:
            start_local = act.get("startTimeLocal") or ""
            duration = act.get("duration")
            distance = act.get("distance")
            row = {
                "activity_id": act.get("activityId"),
                "date": start_local.split(" ")[0] if start_local else None,
                "start_time": start_local,
                "name": act.get("activityName") or "",
                "sport": g,
                "duration_s": round(duration, 1) if duration else None,
                "distance_m": round(distance, 1) if distance else None,
                "avg_hr": round(act.get("averageHR")) if act.get("averageHR") else None,
                "max_hr": round(act.get("maxHR")) if act.get("maxHR") else None,
            }
            upsert_cross_activity(con, row, exported_at)
    con.close()
    print(f"Кросс-тренировки готово: записано/обновлено {len(matched)}.")


# ---------------------------------------------------------------------------
# --dump-activity-fields — отладка "откуда брать конфаунды EF" (см. докстринг п.14).
# _extract_ef_confounds() читает поля из ОБЪЕКТА СПИСКА активностей (то, что возвращает
# fetch_activities/activitylist-service — именно его export() передаёт как `act`), а НЕ
# из объекта, который выгружает --dump-raw (тот — /activity-service/activity/{id}, другой
# эндпоинт с другой структурой). Поэтому чтобы понять, под какими именами Garmin реально
# отдаёт elevationGain/temperature/cadence и т.п. НА ТВОЁМ аккаунте, нужно посмотреть
# именно list-объект — эта команда его находит и печатает.
# ---------------------------------------------------------------------------
CONFOUND_KEYWORDS = [
    "elev", "temp", "cadence", "stride", "calor", "trainingeffect", "training_effect",
    "manual", "correct", "grade", "gps", "accuracy", "hydrat", "vo2", "speed",
]


def _walk_json(obj, path=""):
    """Генератор (path, value) по всем листовым (не dict/list) значениям вложенного
    JSON — используется, чтобы найти поля-кандидаты по подстроке в имени ключа, не
    зная заранее точную структуру ответа Garmin."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from _walk_json(v, f"{path}.{k}" if path else str(k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj[:3]):  # первые 3 элемента списка достаточно для образца структуры
            yield from _walk_json(v, f"{path}[{i}]")
    else:
        yield path, obj


def _print_confound_candidates(label, obj, keywords=None):
    keywords = keywords if keywords is not None else CONFOUND_KEYWORDS
    print(f"\n  Поля-кандидаты в {label} (по подстроке в имени: {', '.join(keywords)}):")
    found = False
    for path, value in _walk_json(obj):
        key_lower = path.rsplit(".", 1)[-1].lower()
        if any(kw in key_lower for kw in keywords):
            print(f"    {path} = {value!r}")
            found = True
    if not found:
        print("    (ничего не нашлось по этим ключевым словам — либо Garmin называет поле иначе, "
              "либо в этом объекте таких данных вообще нет; смотри сырой JSON целиком)")


def dump_activity_fields(garth, activity_id, start_date, end_date):
    """Находит и печатает/сохраняет ОБА представления одной активности:
      1) list_entry — объект из fetch_activities (activitylist-service) — ИМЕННО ОН
         передаётся в _extract_ef_confounds() при обычном экспорте;
      2) detail — объект из /activity-service/activity/{id} (то же, что --dump-raw).
    Плюс лапы (fetch_laps) и подсветку полей-кандидатов по ключевым словам в обоих.
    Если list_entry не нашёлся в заданном диапазоне дат — сузь/расширь --start-date/
    --end-date (по умолчанию берётся текущий --days/--start-date/--end-date, как и для
    обычного экспорта) так, чтобы дата активности попадала в диапазон."""
    activity_id = int(activity_id)
    print(f"Ищу активность {activity_id} в списке активностей за {start_date}..{end_date} "
          f"(activityType=None, т.е. среди ВСЕХ видов спорта)...")
    acts = fetch_activities(garth, start_date, end_date, activity_type=None)
    list_entry = next((a for a in acts if a.get("activityId") == activity_id), None)
    if list_entry is None:
        print(f"  НЕ НАЙДЕНА в списке за {start_date}..{end_date} (проверено {len(acts)} активностей) — "
              f"расширь диапазон через --start-date/--end-date/--days так, чтобы дата тренировки "
              f"туда попадала, и повтори.")
    else:
        print(f"  Найдена: {list_entry.get('activityName')!r} от {list_entry.get('startTimeLocal')}")

    print(f"\nЗапрашиваю детальный объект (тот же эндпоинт, что --dump-raw)...")
    detail = garth.connectapi(f"/activity-service/activity/{activity_id}")
    laps_raw, lap_source = fetch_laps(garth, activity_id)

    out_file = f"activity_{activity_id}_fields_raw.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump({"list_entry": list_entry, "detail": detail, "laps_source": lap_source,
                    "laps": laps_raw}, f, ensure_ascii=False, indent=2)
    print(f"Полный сырой JSON (list_entry + detail + laps) сохранён в {out_file} — открой его целиком, "
          f"если подсветка ниже не нашла нужное поле.")

    if list_entry is not None:
        _print_confound_candidates("list_entry (используется в _extract_ef_confounds)", list_entry)
        print("\n  То, что ТЕКУЩИЙ _extract_ef_confounds() реально извлёк бы из этого list_entry:")
        for k, v in _extract_ef_confounds(list_entry).items():
            print(f"    {k:28s} = {v!r}")
    _print_confound_candidates("detail (/activity-service/activity/{id}, как --dump-raw)", detail)

    print("\nЕсли нужное поле нашлось в list_entry под ДРУГИМ именем, чем в "
          "_EF_CONFOUND_FIELD_CANDIDATES (см. garmin_activities_export.py) — допиши это имя в "
          "список кандидатов нужного ключа и перезапусти обычный экспорт за этот период.")


# ---------------------------------------------------------------------------
# --dump-activity-details — поиск ключа темпа с учётом уклона (grade-adjusted pace/speed),
# см. диалог 2026-08-17. В отличие от --dump-activity-fields (который смотрит list_entry и
# /activity-service/activity/{id} — это СВОДКА активности и СВОДКА по лапам), grade-adjusted
# pace Garmin отдаёт только в ПОТОКОВЫХ данных по секундам — эндпоинт
# /activity-service/activity/{id}/details, который обычный экспорт (export()) и
# dump_activity_fields() вообще не запрашивают. Ответ этого эндпоинта устроен иначе, чем
# остальные объекты Garmin: не плоский JSON с именованными полями, а
#   metricDescriptors: [{"key": "directSpeed", "metricsIndex": 3, ...}, ...]  — описание КОЛОНОК
#   activityDetailMetrics: [{"metrics": [v0, v1, v2, ...]}, ...]              — САМИ ТОЧКИ,
#     где значение под индексом metricsIndex каждого дескриптора соответствует своей колонке
# то есть нужное значение — это не значение по имени ключа, а позиция в массиве metrics,
# указанная в metricDescriptors. Отдельно проверять по одной активности необходимо, потому что
# набор ключей (и их порядок) может отличаться по типам тренировок/устройствам — точное имя
# ("directGradeAdjustedSpeed" — самое вероятное по практике других интеграций с Garmin Connect,
# но НЕ подтверждено на этом аккаунте) и его metricsIndex нужно увидеть в реальном ответе.
CONFOUND_KEYWORDS_DETAILS = ["grade", "adjust", "speed", "elev", "pace"]

# Подтверждено на реальном аккаунте через --dump-activity-details (диалог 2026-08-17):
# ключ в metricDescriptors — "directGradeAdjustedSpeed" (м/с). Второй кандидат оставлен
# на случай другого устройства/версии приложения Garmin, где ключ мог бы называться
# иначе (неофициальный API — гарантий стабильности имени нет).
GRADE_ADJUSTED_SPEED_KEY_CANDIDATES = ["directGradeAdjustedSpeed", "gradeAdjustedSpeed"]


GRADE_ADJUSTED_DISTANCE_KEY_CANDIDATES = ["sumDistance"]


def fetch_grade_adjusted_pace_by_lap(garth, activity_id, laps, max_chart_size=2000):
    """Темп с поправкой на уклон (grade-adjusted pace, GAP) — и за тренировку целиком, и
    ПО КАЖДОМУ ЛАПУ отдельно. В отличие от avg_pace_s_per_km (сырой темп по GPS-дистанции),
    GAP компенсирует набор/сброс высоты — на этом основана попытка снять с EF (см. п.14
    докстринга) конфаунд рельефа: сырой темп на холмистой трассе занижает EF относительно
    ровной трассы при той же физиологической нагрузке. Средний по всей тренировке GAP
    смешивает подъёмы/спуски/равнину в одно число — если рельеф неравномерный (набор в
    начале, ровный участок в конце и т.п.), полапная разбивка нужна, чтобы увидеть GAP
    именно там, где физически был подъём, а не размазанным по всей дистанции.

    Метрика приходит ТОЛЬКО из поточного эндпоинта /activity-service/activity/{id}/details
    (см. dump_activity_details() выше и диалог 2026-08-17) — не из списка активностей и не
    из /activity-service/activity/{id} (обычный detail-объект). Формат ответа: список
    metricDescriptors описывает колонки (key -> metricsIndex), activityDetailMetrics —
    сами точки по секундам/интервалам.

    Разбивка по лапам сделана через КУМУЛЯТИВНУЮ ДИСТАНЦИЮ (sumDistance каждой точки
    потока сравнивается с накопленной суммой distance_m лапов из normalize_lap), а не
    через время/timestamp: у лапов нет проверенного на реальном аккаунте поля начала/конца
    в том же формате эпохи, что directTimestamp потока, а distance_m у лапов есть всегда
    (и sumDistance у потока — тоже, тот же физический счётчик GPS-дистанции), поэтому
    точки надёжнее сопоставлять по общей для обоих источников величине. Если у какого-то
    лапа distance_m отсутствует — для него GAP не считается (пропускается), кумулятивная
    сумма для последующих лапов при этом не сдвигается специально — просто такой лап
    выпадает из разбивки, остальные считаются корректно.

    Best-effort: если эндпоинт недоступен, нужного дескриптора нет (старые часы/короткая
    активность без потока) или все точки пустые — возвращает (None, {}), не падает.
    Вызывается только для уличных тренировок со значимым набором высоты (см. export(),
    --grade-adjusted-min-elevation-m/--no-grade-adjusted-pace) — не тратим лишний запрос
    на каждую активность. Возвращает (overall_s_per_km, {lap_idx: s_per_km})."""
    try:
        path = f"/activity-service/activity/{activity_id}/details?maxChartSize={max_chart_size}&maxPolylineSize={max_chart_size}"
        details = garth.connectapi(path)
    except Exception:
        return None, {}
    if not isinstance(details, dict):
        return None, {}
    descriptors = details.get("metricDescriptors") or []

    def _find_idx(candidates):
        for key in candidates:
            for d in descriptors:
                if (d.get("key") or "") == key:
                    return d.get("metricsIndex")
        return None

    idx_gap = _find_idx(GRADE_ADJUSTED_SPEED_KEY_CANDIDATES)
    if idx_gap is None:
        return None, {}
    idx_dist = _find_idx(GRADE_ADJUSTED_DISTANCE_KEY_CANDIDATES)

    points = details.get("activityDetailMetrics") or []
    all_values = []
    dist_gap_points = []  # (кумулятивная дистанция потока, м; GAP, м/с)
    for p in points:
        metrics = p.get("metrics") or []
        if idx_gap >= len(metrics):
            continue
        gap_v = metrics[idx_gap]
        if gap_v is None:
            continue
        all_values.append(gap_v)
        if idx_dist is not None and idx_dist < len(metrics) and metrics[idx_dist] is not None:
            dist_gap_points.append((metrics[idx_dist], gap_v))

    overall = s_per_km(statistics.mean(all_values)) if all_values else None

    per_lap = {}
    if dist_gap_points:
        dist_gap_points.sort(key=lambda x: x[0])
        cum = 0.0
        for lap in laps:
            dist = lap.get("distance_m")
            if not dist:
                continue
            start, end = cum, cum + dist
            cum = end
            bucket = [g for d, g in dist_gap_points if start <= d <= end]
            if bucket:
                per_lap[lap["idx"]] = s_per_km(statistics.mean(bucket))

    return overall, per_lap


def dump_activity_details(garth, activity_id, max_chart_size=4000):
    """Запрашивает /activity-service/activity/{id}/details (поточные данные по секундам —
    ДРУГОЙ эндпоинт, чем --dump-raw/--dump-activity-fields, которые смотрят только сводку
    активности и сводку по лапам) и ищет в metricDescriptors ключи, похожие на "темп/скорость с
    учётом уклона" (grade-adjusted pace/speed).

    max_chart_size — Garmin по этому эндпоинту иногда прорежает точки, если не попросить явно
    побольше (по умолчанию просим с запасом; если тренировка длиннее — увеличь --max-chart-size).

    Сохраняет полный сырой JSON в activity_ID_details_raw.json (он может быть большим — тысячи
    точек по секундам) и печатает:
      1) все metricDescriptors целиком (это компактный список — по нему сразу видно точное имя
         ключа на твоём аккаунте, даже если оно не попадёт в подсветку по ключевым словам ниже);
      2) отдельно подсвеченные кандидаты по ключевым словам (grade/adjust/speed/elev/pace);
      3) если найден правдоподобный кандидат — по 10 первых ненулевых значений этой колонки из
         activityDetailMetrics, чтобы визуально убедиться, что это не пустая/нулевая колонка.
    """
    activity_id = int(activity_id)
    path = f"/activity-service/activity/{activity_id}/details?maxChartSize={max_chart_size}&maxPolylineSize={max_chart_size}"
    print(f"Запрашиваю {path} ...")
    details = garth.connectapi(path)

    out_file = f"activity_{activity_id}_details_raw.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(details, f, ensure_ascii=False, indent=2)
    print(f"Полный сырой JSON сохранён в {out_file} (может быть большим — это поточные данные по секундам).")

    if not isinstance(details, dict):
        print(f"  Неожиданный тип ответа: {type(details)} — открой {out_file} и посмотри вручную.")
        return

    descriptors = details.get("metricDescriptors") or []
    print(f"\nВсего metricDescriptors: {len(descriptors)}")
    if not descriptors:
        print("  Пусто — либо у активности нет поточных данных (короткая/помеченная вручную/без GPS), "
              "либо Garmin вернул ответ другой формы — смотри сырой JSON целиком.")
        return

    print("\n  Все metricDescriptors (ключ -> metricsIndex):")
    for d in descriptors:
        print(f"    {d.get('key')!r:45s} metricsIndex={d.get('metricsIndex')}")

    candidates = [d for d in descriptors if any(kw in str(d.get("key", "")).lower() for kw in CONFOUND_KEYWORDS_DETAILS)]
    print(f"\n  Кандидаты по ключевым словам ({', '.join(CONFOUND_KEYWORDS_DETAILS)}):")
    if not candidates:
        print("    (ничего не нашлось — либо Garmin называет метрику совсем иначе, смотри полный "
              "список metricDescriptors выше целиком, либо на этом устройстве/типе активности "
              "grade-adjusted pace вообще не считается)")
    else:
        for d in candidates:
            print(f"    {d.get('key')!r} (metricsIndex={d.get('metricsIndex')})")

        points = details.get("activityDetailMetrics") or []
        print(f"\n  Точек в activityDetailMetrics: {len(points)}")
        for d in candidates:
            idx = d.get("metricsIndex")
            if idx is None:
                continue
            sample = []
            for p in points:
                metrics = p.get("metrics") or []
                if idx < len(metrics) and metrics[idx] is not None:
                    sample.append(metrics[idx])
                if len(sample) >= 10:
                    break
            print(f"    {d.get('key')!r}: первые ненулевые значения (сырые, ед. измерения см. в "
                  f"metricDescriptors[...].unit в JSON) = {sample!r}")
            if not sample:
                print(f"      (все значения этой колонки пустые/None в первых точках — возможно, "
                      f"метрика посчитана не для всей тренировки, или это не тот ключ)")

    print("\nЕсли нашёлся правдоподобный ключ (например, что-то вроде directGradeAdjustedSpeed) — "
          "скажи мне его точное имя и metricsIndex, и я допишу в export() агрегацию этой метрики "
          "по границам лап (среднее между началом и концом каждой лапы) в новую колонку intervals.")


# ---------------------------------------------------------------------------
# --dump-wellness-fields — отладка показателей здоровья/восстановления (сон/HRV/RHR/
# Body Battery/стресс/training readiness/ПАНО), см. докстринг про wellness выше и
# export_wellness()/fetch_wellness_day(). Та же идея, что и dump_activity_fields():
# запросить ВСЕ сырые ответы по одной дате, подсветить поля-кандидаты по ключевым
# словам и сравнить с тем, что реально извлекает текущий парсинг — чтобы было видно,
# если Garmin переименовал/убрал поле или если в сыром ответе есть метрика, которую
# скрипт пока не читает (например SpO2/пульс во сне/VDOT — Garmin их отдаёт, но
# fetch_wellness_day() сейчас не берёт).
# ---------------------------------------------------------------------------
WELLNESS_FIELD_KEYWORDS = [
    "sleep", "hrv", "restingheartrate", "rhr", "battery", "stress", "readiness",
    "respirat", "spo2", "pulseox", "vo2", "score", "status", "level", "steps",
    "calor", "lactate", "threshold",
]


def dump_wellness_fields(garth, date_str, no_lactate_threshold=False):
    """Запрашивает и сохраняет ВСЕ сырые wellness-ответы Garmin за одну дату (то же
    множество эндпоинтов, что fetch_wellness_day() использует при обычном экспорте,
    плюс Body Battery, которого в старом --dump-wellness-raw не было — он отдаётся
    диапазоном, а не по дню, см. fetch_body_battery_range) и подсвечивает поля-
    кандидаты по ключевым словам, плюс печатает, что реально извлёк бы текущий
    fetch_wellness_day() из этого дня — для сравнения "что есть в сыром JSON" vs
    "что уже парсится". Используй, если после обычного экспорта нужная wellness-
    колонка пустая/подозрительная, или чтобы найти метрику, которой ещё нет в схеме."""
    username = resolve_display_name(garth)
    if not username:
        sys.exit("Не удалось определить пользователя для wellness-эндпоинтов (ни displayName, ни email).")

    print(f"Запрашиваю wellness-эндпоинты за {date_str} (displayName/логин: {username})...")
    dump = {
        "sleep": garth.connectapi(f"/wellness-service/wellness/dailySleepData/{username}?date={date_str}&nonSleepBufferMinutes=60"),
        "hrv": garth.connectapi(f"/hrv-service/hrv/{date_str}"),
        "daily_summary": garth.connectapi(f"/usersummary-service/usersummary/daily/{username}?calendarDate={date_str}"),
        "stress": garth.connectapi(f"/wellness-service/wellness/dailyStress/{date_str}"),
        "training_readiness": garth.connectapi(f"/metrics-service/metrics/trainingreadiness/{date_str}"),
        "body_battery": _safe_get(garth, f"/wellness-service/wellness/bodyBattery/reports/daily?startDate={date_str}&endDate={date_str}"),
    }
    if not no_lactate_threshold:
        # v1/v2 — старые кандидаты /metrics-service/... (на реальном аккаунте всегда None,
        # см. докстринг fetch_lactate_threshold_range) — оставлены в дампе для сравнения "было/стало".
        dump["lactate_threshold_v1_metrics_service"] = _safe_get(garth, f"/metrics-service/metrics/lactatethreshold?startDate={date_str}&endDate={date_str}")
        dump["lactate_threshold_v2_metrics_service"] = _safe_get(garth, f"/metrics-service/metrics/latest/lactatethreshold?startDate={date_str}&endDate={date_str}")
        # v3/v4/v5 — рабочие эндпоинты biometric-service (см. fetch_lactate_threshold_range)
        dump["lactate_threshold_v3_hr_range"] = _safe_get(garth, f"/biometric-service/stats/lactateThresholdHeartRate/range/{date_str}/{date_str}?sport=RUNNING&aggregation=daily&aggregationStrategy=LATEST")
        dump["lactate_threshold_v4_speed_range"] = _safe_get(garth, f"/biometric-service/stats/lactateThresholdSpeed/range/{date_str}/{date_str}?sport=RUNNING&aggregation=daily&aggregationStrategy=LATEST")
        dump["lactate_threshold_v5_latest"] = _safe_get(garth, "/biometric-service/biometric/latestLactateThreshold")

    out_file = f"wellness_{date_str}_fields_raw.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(dump, f, ensure_ascii=False, indent=2)
    print(f"Полный сырой JSON всех wellness-эндпоинтов сохранён в {out_file} — открой его целиком, "
          f"если подсветка ниже не нашла нужное поле.")

    for label, obj in dump.items():
        if obj is None:
            print(f"\n  {label}: пусто (Garmin не отдал ничего за эту дату по этому эндпоинту)")
            continue
        _print_confound_candidates(label, obj, keywords=WELLNESS_FIELD_KEYWORDS)

    bb_by_date = fetch_body_battery_range(garth, date_str, date_str)
    parsed = fetch_wellness_day(garth, username, date_str, bb_by_date)
    print("\n  То, что ТЕКУЩИЙ fetch_wellness_day() реально извлёк бы за этот день (см. WELLNESS_COLUMNS):")
    for col in WELLNESS_COLUMNS:
        print(f"    {col:28s} = {parsed.get(col)!r}")

    print("\nЕсли в сыром JSON есть метрика, которую fetch_wellness_day() не берёт (например SpO2, "
          "пульс во время сна, VDOT/race predictor) — допиши её парсинг в соответствующую fetch_*_day() "
          "функцию и в WELLNESS_COLUMNS/SCHEMA, чтобы она попала в БД.")


def main():
    ap = argparse.ArgumentParser(description="Выгрузка беговых тренировок из Garmin Connect для калибровки адаптивной модели.")
    ap.add_argument("--account", help="логин Garmin (email); если не задан — GARMIN_EMAIL / единственная сохранённая учётка")
    ap.add_argument("--force-login", action="store_true", help="не использовать сохранённый токен, войти паролем заново")
    ap.add_argument("--start-date", help="дата начала периода ГГГГ-ММ-ДД (по умолчанию сегодня минус --days)")
    ap.add_argument("--end-date", help="дата конца периода ГГГГ-ММ-ДД (по умолчанию сегодня)")
    ap.add_argument("--days", type=int, default=365, help="если --start-date не задан — сколько дней назад от сегодня выгружать (по умолчанию 365)")
    ap.add_argument("--db", default="garmin_running.db", help="путь к файлу SQLite (создаётся, если не существует; по умолчанию garmin_running.db)")
    ap.add_argument("--long-threshold", type=float, default=75, help="порог длительности (мин) для типа 'long' (по умолчанию 75)")
    ap.add_argument("--temperature-unit", choices=["c", "f"], default="c",
                     help="в какой единице Garmin отдаёт minTemperature/maxTemperature на твоём аккаунте — "
                          "c (Цельсий, по умолчанию) или f (Фаренгейт, тогда сконвертируется в Цельсий в "
                          "колонке avg_temperature_c). Garmin не документирует единицу в ответе API, "
                          "проверь через --dump-raw ACTIVITY_ID, если числа выглядят странно (см. докстринг п.14).")
    ap.add_argument("--no-detail-confounds", action="store_true",
                     help="не делать доп. запрос /activity-service/activity/{id} на каждую активность ради "
                          "impact_load (см. докстринг _EF_CONFOUND_FIELD_CANDIDATES) — быстрее, но impact_load "
                          "останется пустым и orthopedic-фит в garmin_calibration_fit.py откатится на прокси")
    ap.add_argument("--no-grade-adjusted-pace", action="store_true",
                     help="не запрашивать /activity-service/activity/{id}/details ради grade-adjusted pace "
                          "(directGradeAdjustedSpeed, см. fetch_grade_adjusted_pace/--dump-activity-details) — "
                          "быстрее (эндпоинт поточный, по секундам), но avg_grade_adjusted_pace_s_per_km "
                          "останется пустым для всех тренировок")
    ap.add_argument("--grade-adjusted-min-elevation-m", type=float, default=30,
                     help="минимальный набор высоты (elevation_gain_m), начиная с которого для уличной "
                          "тренировки (см. OUTDOOR_RUN_TYPE_KEYS — дорожка/помещение/виртуальный бег "
                          "исключены) дополнительно запрашивается grade-adjusted pace (по умолчанию 30 м; "
                          "ниже — рельеф обычно не искажает EF настолько, чтобы платить лишним запросом "
                          "по каждой такой активности)")
    ap.add_argument("--dump-raw", metavar="ACTIVITY_ID", help="только выгрузить сырой JSON одной активности (для отладки схемы Garmin API) и выйти")
    ap.add_argument("--dump-activity-fields", metavar="ACTIVITY_ID",
                     help="отладка конфаундов EF (см. докстринг п.14): найти активность и в списке "
                          "активностей (откуда реально берутся elevation/temperature/cadence при "
                          "экспорте), и в детальном объекте, сохранить оба сырых JSON в "
                          "activity_ID_fields_raw.json и подсветить поля-кандидаты по ключевым словам "
                          "(elevation/temp/cadence/...) — используй, если после обычного экспорта нужные "
                          "колонки пустые, чтобы понять точные имена полей на своём аккаунте; диапазон "
                          "поиска — --start-date/--end-date/--days, как для обычного экспорта")
    ap.add_argument("--dump-activity-details", metavar="ACTIVITY_ID",
                     help="поиск ключа темпа с учётом уклона (grade-adjusted pace/speed, см. диалог "
                          "2026-08-17): запросить /activity-service/activity/{id}/details (поточные "
                          "данные по секундам — ДРУГОЙ эндпоинт, чем --dump-raw/--dump-activity-fields), "
                          "сохранить сырой JSON в activity_ID_details_raw.json и распечатать все "
                          "metricDescriptors + подсветку кандидатов по ключевым словам "
                          "(grade/adjust/speed/elev/pace). Выбери для проверки реальную уличную "
                          "тренировку с заметным набором высоты (elevation_gain_m большой)")
    ap.add_argument("--max-chart-size", type=int, default=4000,
                     help="только для --dump-activity-details: сколько точек запросить у Garmin "
                          "(по умолчанию 4000 — с запасом; увеличь для очень длинных тренировок)")
    ap.add_argument("--reclassify-only", action="store_true",
                     help="не тянуть Garmin — только пересчитать type_guess/type_reason для всей "
                          "уже существующей --db (проход 2, см. reclassify_activities) и выйти. "
                          "Полезно сразу после правки логики classify(), чтобы пересчитать типы "
                          "по всей истории без повторной выгрузки из Гармина.")
    ap.add_argument("--export-csv", action="store_true", help="не тянуть Garmin — выгрузить обе таблицы уже существующей --db в CSV рядом и выйти")
    ap.add_argument("--export-csv-after", action="store_true", help="после обычной выгрузки дополнительно сохранить CSV-снимок БД")
    ap.add_argument("--in", dest="in_path", metavar="PATH",
                     help="конвертация без обращения к Garmin: прочитать тренировки из PATH "
                          "(.json — старый формат экспорта, .db/.sqlite/.sqlite3 — база, "
                          ".csv — файл <base>_activities.csv из --export-csv, рядом должен лежать "
                          "<base>_intervals.csv) и записать в формате --out-format")
    ap.add_argument("--out-format", choices=["sqlite", "json", "csv"], default="sqlite",
                     help="формат записи при конвертации через --in (по умолчанию sqlite)")
    ap.add_argument("--out", metavar="PATH",
                     help="куда записать при конвертации через --in (по умолчанию рядом с --in, "
                          "с расширением/суффиксом под --out-format)")
    ap.add_argument("--no-wellness", action="store_true",
                     help="не выгружать сон/HRV/RHR/Body Battery/стресс/training readiness (по умолчанию выгружаются вместе с тренировками)")
    ap.add_argument("--force-refresh-wellness", action="store_true",
                     help="перезаписать wellness-данные даже за дни, которые уже есть в БД (по умолчанию такие дни пропускаются — быстрее для повторных запусков)")
    ap.add_argument("--wellness-delay-ms", type=int, default=150,
                     help="пауза между запросами wellness-метрик по дням, мс (вежливость к API Garmin; по умолчанию 150)")
    ap.add_argument("--wellness-only", action="store_true",
                     help="не тянуть активности — только wellness-метрики за период и выйти")
    ap.add_argument("--no-cross-training", action="store_true",
                     help="не выгружать вело/лыжи/плавание/силовые (по умолчанию выгружаются вместе с бегом, "
                          "идут только в суммарную нагрузку, не в беговые оси — см. --cross-sports)")
    ap.add_argument("--cross-sports", default="cycling,skiing,swimming,strength_training",
                     help="через запятую какие группы кросс-тренировок выгружать (по умолчанию все четыре)")
    ap.add_argument("--cross-training-only", action="store_true",
                     help="не тянуть бег/wellness — только кросс-тренировки за период и выйти")
    ap.add_argument("--no-lactate-threshold", action="store_true",
                     help="не выгружать историю ПАНО от Garmin (лактатный порог) вместе с wellness")
    ap.add_argument("--dump-wellness-raw", metavar="DATE",
                     help="отладка показателей здоровья/восстановления: выгрузить сырой JSON ВСЕХ "
                          "wellness-эндпоинтов (сон/HRV/RHR/Body Battery/стресс/training readiness/ПАНО) "
                          "за одну дату ГГГГ-ММ-ДД в wellness_ДАТА_fields_raw.json, подсветить поля-"
                          "кандидаты по ключевым словам и сравнить с тем, что реально извлекает текущий "
                          "fetch_wellness_day() — используй, если после экспорта нужная wellness-колонка "
                          "пустая/подозрительная, или чтобы найти метрику, которой ещё нет в схеме — и выйти")
    args = ap.parse_args()
    if args.in_path:
        out_path = args.out
        if not out_path:
            base = os.path.splitext(args.in_path)[0]
            if base.endswith("_activities"):
                base = base[: -len("_activities")]
            out_path = {"sqlite": base + ".db", "json": base + ".json", "csv": base + ".csv"}[args.out_format]
        convert(args.in_path, args.out_format, out_path)
        return
    if args.reclassify_only:
        if not os.path.isfile(args.db):
            sys.exit(f"Файл БД не найден: {args.db}")
        reclassify_activities(args.db, long_threshold_s=args.long_threshold * 60)
        return
    if args.export_csv:
        if not os.path.isfile(args.db):
            sys.exit(f"Файл БД не найден: {args.db}")
        export_csv_from_db(args.db)
        return
    if args.dump_activity_fields:
        garth = connect(args.account, force_login=args.force_login)
        end_date = args.end_date or datetime.date.today().isoformat()
        start_date = args.start_date or (datetime.date.today() - datetime.timedelta(days=args.days)).isoformat()
        dump_activity_fields(garth, args.dump_activity_fields, start_date, end_date)
        return
    if args.dump_activity_details:
        garth = connect(args.account, force_login=args.force_login)
        dump_activity_details(garth, args.dump_activity_details, max_chart_size=args.max_chart_size)
        return
    if args.dump_wellness_raw:
        garth = connect(args.account, force_login=args.force_login)
        dump_wellness_fields(garth, args.dump_wellness_raw, no_lactate_threshold=args.no_lactate_threshold)
        return
    if args.wellness_only:
        garth = connect(args.account, force_login=args.force_login)
        end_date = args.end_date or datetime.date.today().isoformat()
        start_date = args.start_date or (datetime.date.today() - datetime.timedelta(days=args.days)).isoformat()
        export_wellness(garth, args, start_date, end_date)
        return
    if args.cross_training_only:
        garth = connect(args.account, force_login=args.force_login)
        end_date = args.end_date or datetime.date.today().isoformat()
        start_date = args.start_date or (datetime.date.today() - datetime.timedelta(days=args.days)).isoformat()
        export_cross_training(garth, args, start_date, end_date)
        return
    export(args)


if __name__ == "__main__":
    main()
