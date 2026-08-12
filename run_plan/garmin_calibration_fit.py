#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Поиск индивидуальных калибровочных коэффициентов адаптивной модели построения
планов (см. c:\\Users\\ystef\\Claude\\Projects\\Run\\adaptive_model_architecture.md, §3.3, §10)
по факту выполненных тренировок, выгруженных garmin_activities_export.py в SQLite
(garmin_running.db: таблицы activities/intervals).

ЧТО СЧИТАЕТ И ПОЧЕМУ ИМЕННО ТАК
--------------------------------
Модель в architecture.md устроена как классическая двухкомпонентная impulse-response
модель Banister/Morton (fitness-fatigue): каждая тренировка даёт "дозу" (load), которая
одновременно порождает adaptation (растёт и медленно спадает, τ_adapt) и fatigue
(растёт быстрее и спадает быстрее, τ_fatigue); наблюдаемая форма (readiness/performance)
= adaptation − fatigue. Это ровно тот же матаппарат, который в спортивной науке фитуют
по историческому ряду "нагрузка -> результат" методом нелинейного МНК — здесь делается
то же самое, отдельно по каждой оси стимула.

1. LOAD ТРЕНИРОВКИ — два варианта, выбор через наличие --threshold-hr:
   а) TRIMP (Banister, 1991) по резерву пульса (--max-hr/--rest-hr обязательны всегда —
      используется как минимум для запасного варианта и для --plot):
         hrr = (avg_hr - rest_hr) / (max_hr - rest_hr)
         TRIMP = duration_min * hrr * a * exp(b * hrr)      a/b — коэффициенты пола
   б) TSS-подобная формула по ПАНО (--threshold-hr), включается автоматически, если
      --threshold-hr задан — ИСПОЛЬЗУЕТСЯ ПО УМОЛЧАНИЮ ВМЕСТО TRIMP, если задан:
         IF = avg_hr / threshold_hr           (Intensity Factor относительно порога)
         load = duration_min * IF^2
      Почему это точнее лично для вас: %HRR — это линейная шкала между двумя
      произвольными точками (покой/макс), она не знает, где именно у ВАС проходит
      аэробно-анаэробный переход — Banister компенсирует это экспоненциальным весом
      a*exp(b*hrr), но коэффициенты a/b усреднены по выборке лабораторных тестов, а не
      подобраны под вас. IF от ПАНО — это доля от вашего фактического порога, то есть
      нагрузка одной и той же тренировки будет по-разному "весить" в зависимости от
      того, где реально проходит переход между преимущественно аэробным и
      преимущественно анаэробным метаболизмом у ВАС, а не у усреднённого испытуемого.
      Дополнительный плюс: ось threshold в модели и так является отдельной осью
      стимула — при оценке load через ПАНО согласованность между "какая тренировка
      ближе всего к порогу" и "как считается её вклад в нагрузку" выше.
   ПРИОРИТЕТ ИСТОЧНИКА ПОРОГА: --threshold-hr (ручной, если явно задан) > история ПАНО
   от Garmin (таблица lactate_threshold из garmin_activities_export.py — Garmin сам
   считает порог по алгоритму Firstbeat из фактических тренировок, ВРЕМЕННОЙ РЯД, а не
   одно число: учитывает изменение порога по мере роста/потери формы за период выгрузки,
   что точнее константы на весь год) > TRIMP по %HRR, если ни того ни другого нет.
   Отключить историю Garmin принудительно: --no-garmin-threshold.

2. ВЕКТОР СТИМУЛА ПО ОСЯМ — эвристический guess-тип тренировки (interval/threshold/
   long/easy/mixed), который уже проставил export-скрипт, раскладывается по осям
   architecture.md (vo2, threshold, marathon_specific, endurance, aerobic, neuromuscular)
   через таблицу весов STIMULUS_MAP ниже. Это ЭВРИСТИКА, не измерение — веса можно и
   нужно поправить руками под свой план (см. --weights-json).

3. ПРОИЗВОДИТЕЛЬНОСТЬ (наблюдаемая переменная для фита) — Efficiency Factor,
   EF = (дистанция/время) / средний_пульс, на активностях каждой оси-источника
   (easy -> aerobic, long -> endurance, threshold -> threshold, interval -> vo2).
   Это стандартный прокси аэробной формы в беговой практике (Maffetone EF) — растёт,
   когда та же скорость даётся при более низком пульсе (или наоборот).

4. ФИТ τ_adapt/τ_fatigue — для каждой оси с достаточным числом точек EF строится
   ежедневный ряд нагрузки этой оси (load, взвешенный по STIMULUS_MAP), из него
   рекуррентно (экспоненциальное сглаживание, эквивалент свёртки с exp(-t/τ))
   считаются fitness(t)/fatigue(t) для пробных τ, и через scipy.optimize.curve_fit
   подбираются τ_adapt, τ_fatigue, k1, k2, baseline так, чтобы
       EF_pred(t) = baseline + k1*fitness(t) − k2*fatigue(t)
   максимально совпадало с фактическими EF в даты тренировок этой оси.

5. ФИТ recoveryNonlinearity {k,p} (τ = τ_base×(1+k·load^p)) — по парам "качественная
   тренировка (interval/threshold, отдельно long) -> сколько дней прошло до следующей
   тренировки ТОЙ ЖЕ оси": гипотеза в том, что интервал между повторами оси растёт с ростом
   load тренировки степенным образом. Это тоже эвристика (реальный perceived recovery не
   измеряется Garmin), но она напрямую посчитана из ваших данных, а не взята из литературы.

   5a. УЧЁТ КЛАСТЕРОВ НАГРУЗКИ (несколько интенсивных или несколько длинных подряд) —
   единая взвешенная сумма нагрузки не различает, что "два интервальных дня подряд" и
   "интервал + длинная подряд" — разная физиологическая цена: интервалы/порог грузят
   преимущественно нервно-мышечную/ЦНС систему (быстрее восстанавливается, но плохо
   переносит повтор через день-два), длинная/лёгкая — преимущественно опорно-двигательную/
   метаболическую (копится медленнее, держится дольше). Поэтому нагрузка раскладывается ещё
   и по ДВУМ КАТЕГОРИЯМ УТОМЛЕНИЯ (TYPE_CATEGORY/build_daily_category_load, отдельно от осей
   стимула STIMULUS_MAP выше): "neuromuscular" (interval/threshold) и "volume" (long/easy/
   mixed/unknown). Для каждой пары "тренировка -> следующая того же типа" дополнительно
   считается hist = сумма load СВОЕЙ категории утомления за --history-window-days (по
   умолчанию 5) ДО этой тренировки (не включая её саму), и в модель восстановления вносится
   усиливающий множитель:
       load_eff = load × (1 + k_hist × hist/mean(load))
       gap = tau_base × (1 + k × load_eff^p)
   k_hist > 0 значит: та же по своей нагрузке тренировка требует больше восстановления, если
   перед ней уже был кластер нагрузки той же категории утомления — это прямая проверка
   гипотезы "реакция на комплекс нагрузок", а не только на отдельную тренировку. Если пар
   мало (< 10) или фит с доп. параметром не сходится — тихий откат на старую 3-параметрическую
   модель без истории (k_hist=null в отчёте, см. note).

6. СЕЗОННАЯ ПОПРАВКА EF (зима/снег/холод) — тот же темп зимой на морозе/снегу даётся
   при более высоком пульсе, чем летом на асфальте, что искажает EF как прокси формы:
   провал EF в январе может быть погодой, а не реальной потерей фитнеса, и наоборот,
   рост EF весной — потеплением, а не тренировочным эффектом. Перед фитом Banister по
   каждой оси EF пропускается через гармоническую регрессию по дню года (2 гармоники:
   годовой и полугодовой цикл) на log(EF); из EF вычитается сезонная составляющая —
   остаётся отклонение, которое можно объяснить только историей нагрузок, а не сезоном.
   Включается автоматически, если данных хватает на >= ~300 дней охвата (иначе не на
   чем оценить годовой цикл надёжно — тогда используется EF без поправки, это отмечается
   в отчёте флагом seasonal.applied=false). Отключить принудительно: --no-seasonal.

7. ПРОПУСКИ В ТРЕНИРОВКАХ КАК СИГНАЛ ПЕРЕГРУЗКИ — пропуск бега на много дней подряд
   часто означает травму/болезнь, а не запланированный отдых; ретроспективно это повод
   спросить, не было ли непосредственно перед пропуском накопленной перегрузки. Считается
   ACWR (acute:chronic workload ratio, Gabbett 2016) — отношение суммы нагрузки за
   последние 7 дней к среднему за 4 предыдущих недели; ACWR>=1.5 в спортивной науке
   ассоциирован с повышенным риском травмы. Для каждого пропуска длиннее --gap-days
   (по умолчанию 10 дней подряд без бега) отчёт показывает ACWR в момент начала
   пропуска — так видно, предшествовала ли пропуску реальная перегрузка (что прямо
   калибрует открытый вопрос overload_penalty из architecture.md §7/§10) или пропуск
   не связан с нагрузкой (болезнь/поездка/т.п.). ACWR по умолчанию считается один раз по
   суммарной нагрузке (daily_total_load), но такой же ACWR дополнительно считается ОТДЕЛЬНО
   по каждой из двух категорий утомления из п.5a (overload_by_category в отчёте) — суммарный
   ACWR может быть в норме, даже если весь его рост дала одна категория (например неделя из
   трёх интервальных дней подряд при обычном общем объёме); раздельный ACWR это видит, а
   общий — нет.

8. SYSTEMIC FATIGUE ПО WELLNESS-МЕТРИКАМ (HRV/RHR/Body Battery/стресс/training
   readiness из garmin_activities_export.py, таблица wellness) — architecture.md §2/§3.2
   отдельно называет "системную (центральную) усталость" — агрегирующую переменную,
   ограничивающую способность получить качественный стимул независимо от оси; это ровно
   категория `systemic` в FatigueState, у которой (в отличие от осей AdaptationState)
   НЕТ компонента адаптации — только чистое утомление. HRV/RHR измеряются Garmin каждую
   ночь независимо от того, была ли тренировка, поэтому дают намного более плотный ряд,
   чем EF (только в дни тренировок этого типа). Фитится однокомпонентная модель (только
   fatigue, без adaptation):
       metric_pred(t) = baseline − direction*k*fatigue(t, tau)     (direction зависит от
                                                                     метрики, см. WELLNESS_METRICS)
   где fatigue(t,tau) — та же рекуррентная свёртка суммарной (не по осям, по всем
   тренировкам) дневной нагрузки, что и в п.4. HRV/training_readiness убывают с
   нагрузкой (direction=-1 в формуле выше, т.е. вычитаем −(-k)=+k... см. WELLNESS_METRICS
   ниже — знак уже учтён в конфиге), RHR/стресс/Body Battery drained растут. Фитится τ и k
   отдельно по каждой доступной метрике — если несколько метрик дали согласованный τ,
   это сильный сигнал; если разные — сохраняются все, разброс сам по себе информативен.

8a. TRAINING READINESS — НЕ НЕЗАВИСИМАЯ ВАЛИДАЦИЯ. Garmin Training Readiness сам вычисляется
    закрытым алгоритмом Firstbeat ИЗ той же тренировочной нагрузки/HRV/сна/стресса/истории
    тренировок, которую мы пытаемся этим же load объяснить — калибруя "наш load -> Training
    Readiness", мы отчасти воспроизводим алгоритм Garmin, а не измеряем независимую
    физиологическую реакцию (в отличие от HRV/RHR/Body Battery/стресса/сна — это сырые
    сенсорные/производные метрики, которые Garmin не строит из нашей модели нагрузки). Поэтому
    systemic.aggregate (итоговое tau_fatigue_used для категории `systemic`, см. п.13) считается
    ТОЛЬКО по независимым метрикам; Training Readiness фитится и показывается отдельно, как
    training_readiness_check — сверка, насколько её τ согласуется с независимой оценкой, а
    не источник калибровки.

9. КРОСС-ТРЕНИРОВКИ (вело/лыжи/плавание/силовые из garmin_activities_export.py, таблица
   cross_activities) — учитываются ТОЛЬКО в суммарной нагрузке (build_daily_total_load),
   которая питает ACWR (п.7) и systemic-фит (п.8), но НЕ в беговых осях vo2/threshold/
   endurance/aerobic (build_daily_axis_load) — калькулятор планов беговой, велофитнес не
   то же самое, что беговой, смешивать их в одну ось было бы категориальной ошибкой.
   Нагрузка кросс-тренировки считается по TRIMP (%HRR) всегда, даже если для бега
   используется ПАНО — ваш порог лактата специфичен для бега (другая мышечная группа/
   экономичность движения), переносить его на велосипед/лыжи некорректно. Пропуски
   (п.7) теперь считаются по отсутствию ЛЮБОЙ активности (бег ИЛИ кросс), а не только
   бега — иначе неделя на велосипеде вместо бега ошибочно читалась бы как "пропуск,
   возможна травма", хотя нагрузка не пропадала, просто сменился вид спорта.

11. КАЧЕСТВО ФИТА (IDENTIFIED/WEAK/UNIDENTIFIED) И SHRINKAGE К ДЕФОЛТУ — curve_fit ВСЕГДА
    возвращает какие-то числа, даже когда данных недостаточно, чтобы отличить сигнал от шума
    (низкий R², или оптимизатор упёрся в границу bounds, что часто значит "внутреннего
    оптимума нет вообще"). Раньше такие числа шли в отчёт наравне с надёжными — например
    aerobic (R²=0.002, упёрлось в границу) выглядел в JSON так же авторитетно, как
    endurance (R²=0.10). Теперь каждый фит (оси, systemic-метрики) классифицируется:
        IDENTIFIED:   R² >= 0.20, фит НЕ упёрся в границу диапазона, n точек достаточно
        WEAK:         R² в [0.05, 0.20) и не упёрся в границу — сигнал есть, но слабый
        UNIDENTIFIED: R² < 0.05 ИЛИ упёрся в границу — фиту нельзя доверять вообще
    (см. classify_fit_quality). Для IDENTIFIED в модель идёт калиброванное значение как есть
    (tau_*_used = tau_*_days). Для WEAK — shrinkage к дефолту:
        tau_used = w * tau_calibrated + (1-w) * tau_default,   w растёт с R² (0.15..0.85)
    (см. shrinkage_weight/apply_shrinkage). Для UNIDENTIFIED — tau_used = tau_default,
    калиброванное число вообще не должно менять модель. Дефолты — AXIS_TAU_DEFAULTS (для осей)
    и SYSTEMIC_TAU_DEFAULT (для systemic-метрик).

12. OBSERVED VS ESTIMATED OPTIMAL SPACING — quality_session_spacing (п.5, переименовано в
    докстринге в observed_spacing) — это статистика ФАКТИЧЕСКОГО расписания атлета (сколько
    дней он сам ставил между качественными тренировками одного типа), т.е. поведение атлета
    и его тренера/плана, а не измерение физиологии восстановления. Отдельно строится
    estimated_optimal_spacing — то же самое gap = tau_base*(1+k*load^p) из
    recovery_nonlinearity, но взятое при типичном (медианном) load тренировок этого типа —
    оценка МИНИМАЛЬНО НЕОБХОДИМОГО интервала по adaptive response model. Только
    estimated_optimal_spacing должен участвовать в построении плана.

13. FATIGUE_CATEGORY_MAPPING — architecture.md держит tauFatigue ПО КАТЕГОРИИ УТОМЛЕНИЯ
    (systemic/muscular/neuromuscular/orthopedic), тогда как фит по осям (п.4) даёт tau_fatigue
    ПО ОСИ СТИМУЛА (aerobic/threshold/endurance/vo2) — это два разных разбиения, которые
    предыдущая версия скрипта не сопоставляла явно, из-за чего было легко подставить
    "не ту" tau_fatigue не в ту категорию (или подставить threshold/aerobic tau_fatigue
    туда, где для них вообще нет отдельной категории). Явное сопоставление:
        vo2       -> neuromuscular  (интервалы грузят преимущественно нервно-мышечную/ЦНС)
        endurance -> orthopedic     (длинные грузят преимущественно опорно-двигательную систему)
        systemic  -> systemic_fatigue.aggregate (см. п.8а, только независимые wellness-метрики)
        muscular  -> не измеряется этим скриптом вообще (нет прямого прокси)
    threshold/aerobic tau_fatigue остаются в блоке axes для диагностики фита EF, но НЕ
    подставляются ни в одну из 4 категорий модели.

ЧТО НЕ КАЛИБРУЕТСЯ (честно, см. architecture.md §10)
  - capacity(exp, age) / experienceCapacity / ageAdjustment — нет размеченных данных
    "опыт/возраст -> базовая форма", это внешний вход, не выводится из истории тренировок.
  - веса КАТЕГОРИЙ утомления в weightedFatiguePenalty — Garmin даёт хороший прокси именно
    для systemic (см. п.8), но НЕ различает muscular/neuromuscular/orthopedic между собой
    (нет данных о локализации утомления — крепатура/суставы/ЦНС отдельно не измеряются),
    поэтому веса ЭТИХ ТРЁХ категорий (в отличие от systemic) всё ещё не оцениваются.
  - marathon_specific/strength оси — либо мало прямых измерений (marathon_specific
    почти совпадает с threshold/long по имеющимся данным), либо нет измерения
    производительности вообще (strength) — в отчёте помечаются как "нет данных".

ЗАВИСИМОСТИ
  pip install numpy scipy

ЗАПУСК
  python garmin_calibration_fit.py --db garmin_running.db --max-hr 190 --rest-hr 50 --sex m
  python garmin_calibration_fit.py --db garmin_running.db --max-hr 190 --rest-hr 50 --threshold-hr 172 --sex m
  python garmin_calibration_fit.py --db garmin_running.db --max-hr 190 --rest-hr 50 --sex m --plot
  python garmin_calibration_fit.py --db garmin_running.db --max-hr 190 --rest-hr 50 --sex m --weights-json my_weights.json
  python garmin_calibration_fit.py --db garmin_running.db --max-hr 190 --rest-hr 50 --sex m --gap-days 14 --acwr-threshold 1.5
  python garmin_calibration_fit.py --db garmin_running.db --max-hr 190 --rest-hr 50 --sex m --no-wellness   # без HRV/RHR/... даже если таблица есть

Результат: calibration_profile.json (структура — см. build_report()) + человекочитаемая
сводка в консоль. При --plot дополнительно PNG-графики fitness/fatigue/EF по каждой
откалиброванной оси (calibration_plot_<axis>.png) — если установлен matplotlib.
"""

import os, sys, json, sqlite3, argparse, datetime, statistics, math

try:
    import numpy as np
except ImportError:
    sys.exit("Нужен numpy: pip install numpy scipy")

try:
    from scipy.optimize import curve_fit
    from scipy import stats as scipy_stats
except ImportError:
    sys.exit("Нужен scipy: pip install numpy scipy")

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

AXES = ["vo2", "threshold", "marathon_specific", "endurance", "aerobic", "strength", "neuromuscular"]

# Разложение эвристического type_guess (из export-скрипта) на вектор стимула по осям
# architecture.md §2/§3.1. Сумма весов по строке не обязана быть 1 — это относительный
# вклад в load каждой оси, не проценты. Правьте под свою методику через --weights-json
# (файл с тем же форматом целиком заменит таблицу ниже).
DEFAULT_STIMULUS_MAP = {
    "interval":  {"vo2": 0.55, "neuromuscular": 0.30, "threshold": 0.10, "aerobic": 0.05},
    "threshold": {"threshold": 0.65, "marathon_specific": 0.15, "aerobic": 0.15, "vo2": 0.05},
    "long":      {"endurance": 0.45, "marathon_specific": 0.30, "aerobic": 0.25},
    "easy":      {"aerobic": 0.85, "endurance": 0.15},
    "mixed":     {"aerobic": 0.50, "endurance": 0.25, "threshold": 0.25},
    "unknown":   {"aerobic": 0.50, "endurance": 0.50},
}

# Ось -> какой type_guess считается "измерением производительности" этой оси (EF-прокси),
# и разумные границы для τ_adapt/τ_fatigue при фите (дни). Диапазоны — ориентировочные
# рамки, взятые из architecture.md §5 (vo2~72ч, threshold~96ч, long~120ч) с запасом,
# не жёсткая истина — если фит упирается в границу, стоит её расширить и перезапустить.
AXIS_SOURCE = {
    "aerobic":   {"types": ["easy"],               "tau_adapt_bounds": (15, 60), "tau_fatigue_bounds": (2, 15)},
    "endurance": {"types": ["long"],                "tau_adapt_bounds": (25, 90), "tau_fatigue_bounds": (4, 20)},
    "threshold": {"types": ["threshold"],           "tau_adapt_bounds": (15, 60), "tau_fatigue_bounds": (3, 15)},
    "vo2":       {"types": ["interval"],            "tau_adapt_bounds": (10, 45), "tau_fatigue_bounds": (2, 10)},
}

MIN_POINTS = 12       # минимум EF-измерений оси для попытки фита
MIN_SPAN_DAYS = 40    # минимум охвата по датам для попытки фита

# ДЕФОЛТЫ τ (дни) на случай UNIDENTIFIED/WEAK фита (см. classify_fit_quality/shrink_tau ниже,
# докстринг п.11) — берутся как середина bounds для tau_adapt (архитектура не даёт для него
# конкретных чисел) и как значения из architecture.md §5 (vo2~72ч=3д, threshold~96ч=4д,
# long~120ч=5д) для tau_fatigue; aerobic — консервативная оценка по аналогии (быстрее всех
# восстанавливается, лёгкая нагрузка).
AXIS_TAU_DEFAULTS = {
    "aerobic":   {"tau_adapt": 35.0, "tau_fatigue": 2.0},
    "endurance": {"tau_adapt": 55.0, "tau_fatigue": 5.0},
    "threshold": {"tau_adapt": 35.0, "tau_fatigue": 4.0},
    "vo2":       {"tau_adapt": 25.0, "tau_fatigue": 3.0},
}

# Порог качества фита (см. докстринг п.11 "IDENTIFIED/WEAK/UNIDENTIFIED"). r2 ниже 0.05 или
# упор в границу диапазона (hit_bound) — фит статистически неотличим от шума / артефакт
# ограничения bounds, доверять числу нельзя вообще. r2 в [0.05, 0.20) без упора в границу —
# сигнал есть, но слабый, нужен shrinkage к дефолту. r2 >= 0.20 без упора в границу и с
# достаточным n — фит можно подставлять в модель как есть.
FIT_R2_UNIDENTIFIED = 0.05
FIT_R2_IDENTIFIED = 0.20
FIT_N_IDENTIFIED = 20  # "n достаточен" для IDENTIFIED — строже, чем минимум для попытки фита


def classify_fit_quality(r2, hit_bound, n_points, r2_unidentified=FIT_R2_UNIDENTIFIED,
                           r2_identified=FIT_R2_IDENTIFIED, n_identified=FIT_N_IDENTIFIED):
    """IDENTIFIED / WEAK / UNIDENTIFIED — см. докстринг п.11. hit_bound или r2 < r2_unidentified
    -> UNIDENTIFIED безусловно (фиту нельзя доверять вообще, даже частично): упор в границу
    значит, что curve_fit не нашёл внутренний оптимум и число — артефакт диапазона, а не оценка."""
    if r2 is None or (isinstance(r2, float) and np.isnan(r2)):
        return "UNIDENTIFIED"
    if hit_bound or r2 < r2_unidentified:
        return "UNIDENTIFIED"
    if r2 >= r2_identified and n_points >= n_identified:
        return "IDENTIFIED"
    return "WEAK"


def shrinkage_weight(status, r2):
    """w для tau_used = w*tau_calibrated + (1-w)*tau_default (см. докстринг п.11).
    IDENTIFIED -> w=1 (доверяем фиту полностью), UNIDENTIFIED -> w=0 (фит вообще не должен
    менять модель), WEAK -> w растёт с r2 от 0.15 до 0.85 в диапазоне [r2_unidentified, r2_identified)
    — чем ближе к порогу IDENTIFIED, тем больше веса калиброванному значению."""
    if status == "IDENTIFIED":
        return 1.0
    if status == "UNIDENTIFIED":
        return 0.0
    span = FIT_R2_IDENTIFIED - FIT_R2_UNIDENTIFIED
    frac = (r2 - FIT_R2_UNIDENTIFIED) / span if span > 0 else 0.5
    return round(0.15 + 0.70 * max(0.0, min(1.0, frac)), 3)


def apply_shrinkage(tau_calibrated, tau_default, status, r2):
    w = shrinkage_weight(status, r2)
    tau_used = w * tau_calibrated + (1 - w) * tau_default
    return round(float(tau_used), 2), w

# Раскладка type_guess по КАТЕГОРИЯМ УТОМЛЕНИЯ (см. докстринг п.5a) — НЕ то же самое, что оси
# стимула STIMULUS_MAP выше: тут каждая тренировка целиком относится к одной категории (не
# размазывается по осям), цель — отличить "нервно-мышечный/ЦНС" кластер нагрузки от
# "объёмного/опорно-двигательного", а не спрогнозировать конкретную ось производительности.
TYPE_CATEGORY = {
    "interval":  "neuromuscular",
    "threshold": "neuromuscular",
    "long":      "volume",
    "easy":      "volume",
    "mixed":     "volume",
    "unknown":   "volume",
}
HISTORY_WINDOW_DAYS_DEFAULT = 5  # окно "недавней истории" перед тренировкой для п.5a (дни)


def trimp(duration_min, avg_hr, rest_hr, max_hr, sex):
    if not duration_min or not avg_hr or not max_hr or not rest_hr or max_hr <= rest_hr:
        return None
    hrr = (avg_hr - rest_hr) / (max_hr - rest_hr)
    hrr = max(0.0, min(1.2, hrr))  # лёгкий допуск на погрешность пульса, без обрезания в 0 наглухо
    a, b = (0.64, 1.92) if sex == "m" else (0.86, 1.67)
    return duration_min * hrr * a * np.exp(b * hrr)


def tss_like(duration_min, avg_hr, threshold_hr):
    """Load по ПАНО (Intensity Factor относительно порогового пульса, как rTSS): точнее
    TRIMP по %HRR лично для атлета, если ПАНО измерен (см. докстринг п.1)."""
    if not duration_min or not avg_hr or not threshold_hr:
        return None
    intensity_factor = avg_hr / threshold_hr
    return duration_min * intensity_factor ** 2


def training_load(duration_min, avg_hr, rest_hr, max_hr, sex, threshold_hr=None):
    """Единая точка расчёта load: TSS-подобная формула по ПАНО, если --threshold-hr
    задан (точнее лично для атлета), иначе TRIMP по резерву пульса (запасной вариант)."""
    if threshold_hr:
        v = tss_like(duration_min, avg_hr, threshold_hr)
        if v is not None:
            return v
    return trimp(duration_min, avg_hr, rest_hr, max_hr, sex)


def ef(distance_m, duration_s, avg_hr):
    if not distance_m or not duration_s or not avg_hr:
        return None
    return (distance_m / (duration_s / 60.0)) / avg_hr  # метров в минуту на удар пульса


# Приблизительная grade-adjusted поправка EF (см. докстринг п.6б и анализ raw-полей, направление 4):
# честно НЕ Minetti-модель (полином по градиенту рельефа по каждому отрезку трассы) — тот уровень
# точности требует поminutного профиля высоты, которого в экспорте нет (только суммарные elevation_gain_m/
# elevation_loss_m за тренировку). Вместо этого — грубая, но стандартная в беговой практике эвристика
# "эквивалентная дистанция": METERS_UP/METERS_DOWN — во сколько горизонтальных метров обходится 1 метр
# набора/сброса высоты (типичный диапазон в литературе по GAP ~ 7-12 для набора; для сброса эффект
# разнонаправленный — до определённого угла спуск немного ДЕШЕВЛЕ горизонтали, после — снова дороже из-за
# эксцентрической нагрузки на мышцы, поэтому берём консервативно небольшой множитель, а не отрицательный).
GRADE_ADJUST_METERS_UP = 10.0
GRADE_ADJUST_METERS_DOWN = 3.0


def grade_adjusted_distance_m(distance_m, elevation_gain_m, elevation_loss_m, is_treadmill):
    """Возвращает distance_m с грубой grade-adjusted поправкой (см. константы выше), либо
    исходный distance_m без изменений, если рельефа нет или тренировка на треадмиле.
    ТРЕДМИЛ ИСКЛЮЧЁН ПРИНЦИПИАЛЬНО: у Garmin для treadmill_running elevation-поля либо
    отсутствуют, либо содержат мусорные sentinel-значения (например avgElevation=-500 —
    см. анализ raw-полей), плюс на треадмиле физического набора высоты попросту нет (лента
    горизонтальна даже при выставленном % наклона, который Garmin как elevation_gain не пишет) —
    применение поправки к такой активности исказило бы EF в противоположную от истины сторону."""
    if is_treadmill or not distance_m:
        return distance_m
    gain = elevation_gain_m or 0.0
    loss = elevation_loss_m or 0.0
    return distance_m + gain * GRADE_ADJUST_METERS_UP + loss * GRADE_ADJUST_METERS_DOWN


def ef_grade_adjusted(distance_m, duration_s, avg_hr, elevation_gain_m, elevation_loss_m, is_treadmill):
    """Как ef(), но по grade-adjusted дистанции (см. grade_adjusted_distance_m) — снижает
    искажение EF рельефом (см. докстринг п.6: сезонная поправка убирает температуру/сезон,
    эта поправка убирает рельеф; обе можно применять одновременно, они не пересекаются)."""
    adj_distance = grade_adjusted_distance_m(distance_m, elevation_gain_m, elevation_loss_m, is_treadmill)
    return ef(adj_distance, duration_s, avg_hr)


def load_activities(db_path):
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    cols = {row[1] for row in con.execute("PRAGMA table_info(activities)").fetchall()}
    extra = [c for c in ("elevation_gain_m", "elevation_loss_m", "sport", "impact_load",
                          "difference_body_battery", "manual_activity", "elevation_corrected")
             if c in cols]
    select_cols = "activity_id, date, duration_s, distance_m, avg_hr, max_hr, type_guess" + \
                  ("".join(f", {c}" for c in extra))
    rows = con.execute(f"""
        SELECT {select_cols}
        FROM activities
        WHERE date IS NOT NULL
        ORDER BY date
    """).fetchall()
    con.close()
    return [dict(r) for r in rows]


def load_wellness(db_path):
    """Возвращает {} если таблицы wellness нет (старая БД без --wellness) — вызывающий
    код должен на это отреагировать пропуском systemic-фита, а не падением."""
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    try:
        rows = con.execute("SELECT * FROM wellness WHERE date IS NOT NULL ORDER BY date").fetchall()
    except sqlite3.OperationalError:
        con.close()
        return {}
    con.close()
    return {r["date"]: dict(r) for r in rows}


def load_cross_activities(db_path):
    """Вело/лыжи/плавание/силовые — только для суммарной нагрузки (ACWR, systemic-фит),
    НЕ для беговых осей (см. докстринг п.9). {} если таблицы нет (старая БД)."""
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    try:
        rows = con.execute("""
            SELECT activity_id, date, sport, duration_s, avg_hr, max_hr
            FROM cross_activities WHERE date IS NOT NULL ORDER BY date
        """).fetchall()
    except sqlite3.OperationalError:
        con.close()
        return []
    con.close()
    return [dict(r) for r in rows]


def load_lactate_threshold(db_path):
    """История ПАНО от Garmin (см. докстринг п.1) — сортированный список (date, threshold_hr).
    [] если таблицы нет или в ней нет ни одной строки с threshold_hr."""
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    try:
        rows = con.execute("""
            SELECT date, threshold_hr FROM lactate_threshold
            WHERE threshold_hr IS NOT NULL ORDER BY date
        """).fetchall()
    except sqlite3.OperationalError:
        con.close()
        return []
    con.close()
    return [(datetime.date.fromisoformat(r["date"]), r["threshold_hr"]) for r in rows]


def make_threshold_resolver(threshold_hr_const, lt_series):
    """Возвращает f(date_obj) -> threshold_hr | None.
    Приоритет: явный --threshold-hr (ручной override) > история ПАНО от Garmin
    (forward-fill: последнее известное значение НА ЭТУ ДАТУ ИЛИ РАНЬШЕ; для дат до
    первого измерения — берём самое первое известное, лучше, чем ничего) > None (тогда
    training_load откатится на TRIMP по %HRR)."""
    if threshold_hr_const:
        return lambda d: threshold_hr_const
    if not lt_series:
        return lambda d: None

    dates = [d for d, _ in lt_series]
    values = [v for _, v in lt_series]

    def resolve(d):
        idx = None
        for i, dd in enumerate(dates):
            if dd <= d:
                idx = i
            else:
                break
        if idx is not None:
            return values[idx]
        return values[0]  # до первого измерения — берём самое раннее известное

    return resolve


# Метрика -> (колонка в wellness, sign): sign=+1 если метрика РАСТЁТ с усталостью (RHR,
# стресс, Body Battery drained), sign=-1 если УБЫВАЕТ (HRV, training readiness — выше
# при лучшем восстановлении). Модель для всех единая:
#     metric_pred(t) = baseline + sign * k * fatigue(t, tau)      k >= 0, fatigue >= 0
# т.е. при sign=+1 метрика растёт с усталостью, при sign=-1 — падает. tau_bounds — рамки
# для фита (дни); systemic-утомление в architecture.md не привязано к конкретной оси,
# поэтому используется суммарная (не по осям) дневная нагрузка daily_total_load.
# independent=False у training_readiness_score — см. докстринг п.8а: Garmin Training Readiness
# сам построен на нашей же тренировочной нагрузке/восстановлении/HRV/сне/стрессе через
# закрытый алгоритм Firstbeat, поэтому калибровка "нагрузка -> Training Readiness" не является
# независимой валидацией модели (мы бы отчасти воспроизводили Garmin, а не физиологию). Метрика
# оставлена как ДОПОЛНИТЕЛЬНАЯ сверка (см. training_readiness_check в build_systemic_report),
# но НЕ участвует в systemic.aggregate — итоговом tau_used для systemic-категории.
WELLNESS_METRICS = {
    "hrv_last_night_avg":       {"sign": -1, "tau_bounds": (1, 15), "label": "HRV (за ночь)", "independent": True},
    "rhr":                      {"sign": +1, "tau_bounds": (1, 15), "label": "Пульс покоя", "independent": True},
    "body_battery_drained":     {"sign": +1, "tau_bounds": (1, 10), "label": "Body Battery, потрачено", "independent": True},
    "stress_avg":                {"sign": +1, "tau_bounds": (1, 10), "label": "Стресс, средний", "independent": True},
    "training_readiness_score": {"sign": -1, "tau_bounds": (1, 15), "label": "Training Readiness", "independent": False},
    "sleep_score":               {"sign": -1, "tau_bounds": (1, 10), "label": "Скор сна", "independent": True},
    # Добавлено по итогам анализа raw-полей (--dump-wellness-raw) — все три сняты сенсорами
    # ПОМИМО тренировочной нагрузки (не производные "чёрные ящики" Garmin вроде Training
    # Readiness, см. докстринг п.8а), поэтому independent=True:
    #  - skin_temp_deviation_c: отклонение температуры кожи ночью — растёт при накопленном
    #    системном стрессе/недовосстановлении, отдельный термодатчик, раньше нигде не читался;
    #  - sleep_spo2_avg: средний SpO2 во сне — падение может отражать ухудшение восстановления
    #    (дыхание/качество сна), сенсор пульсоксиметрии;
    #  - avg_sleep_stress: стресс именно во время сна (в отличие от stress_avg — за весь день,
    #    смешивает дневную активность и ночное восстановление) — более чистый сигнал именно
    #    восстановления, а не дневной нагрузки/эмоций.
    "skin_temp_deviation_c":    {"sign": +1, "tau_bounds": (1, 10), "label": "Отклонение темп. кожи (ночь)", "independent": True},
    "sleep_spo2_avg":           {"sign": -1, "tau_bounds": (1, 10), "label": "SpO2 (сон, средний)", "independent": True},
    "avg_sleep_stress":         {"sign": +1, "tau_bounds": (1, 10), "label": "Стресс во время сна", "independent": True},
}

WELLNESS_MIN_POINTS = 20  # HRV/RHR и т.п. измеряются каждый день, порог можно держать строже, чем для EF
SYSTEMIC_TAU_DEFAULT = 3.0  # дефолт τ_fatigue (дни) для systemic, если независимые метрики не дали
                             # IDENTIFIED/WEAK фита — грубая оценка по типичному времени
                             # восстановления HRV/RHR после тренировочной нагрузки.


def fit_systemic_fatigue(daily_total_load, sample_idx, sample_values, sign, tau_bounds):
    """metric_pred(t) = base + sign*k*fatigue(t,tau), k>=0 — однокомпонентный (только
    fatigue, без adaptation) фит, см. п.8 докстринга: у категории systemic нет пары
    в AdaptationState, в отличие от осей стимула."""

    def model(idx, tau, k, base):
        fat_arr = recursive_convolution(daily_total_load, tau)
        idx = idx.astype(int)
        return base + sign * k * fat_arr[idx]

    base0 = float(np.mean(sample_values))
    scale0 = max(float(np.std(sample_values)), 1e-6)
    p0 = [statistics.mean(tau_bounds), scale0 / max(float(np.mean(daily_total_load)) * 5, 1e-6), base0]
    lower = [tau_bounds[0], 0.0, base0 - 5 * scale0]
    upper = [tau_bounds[1], 10.0, base0 + 5 * scale0]

    try:
        popt, _ = curve_fit(model, sample_idx.astype(float), sample_values, p0=p0,
                             bounds=(lower, upper), maxfev=20000)
    except Exception as e:
        return {"ok": False, "error": str(e)}

    pred = model(sample_idx, *popt)
    ss_res = float(np.sum((sample_values - pred) ** 2))
    ss_tot = float(np.sum((sample_values - np.mean(sample_values)) ** 2))
    r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float("nan")
    tau, k, base = popt
    return {
        "ok": True,
        "tau_fatigue_days": round(float(tau), 1),
        "k": round(float(k), 6),
        "baseline": round(float(base), 3),
        "r2": round(r2, 3),
        "n_points": int(len(sample_values)),
        "hit_bound": bool(abs(tau - tau_bounds[0]) < 1e-3 or abs(tau - tau_bounds[1]) < 1e-3),
    }


def build_systemic_report(wellness, daily_total_load, day0, n_days):
    """Помимо фита по каждой метрике отдельно — считает systemic.aggregate: единое tau_used
    для категории systemic из architecture.md, собранное ТОЛЬКО из независимых метрик
    (HRV/RHR/Body Battery/стресс/сон, см. докстринг п.8а), с shrinkage к SYSTEMIC_TAU_DEFAULT
    для WEAK и полным игнором UNIDENTIFIED. Training Readiness фитится и показывается отдельно
    (training_readiness_check) как СВЕРКА — насколько её τ согласуется с независимой оценкой,
    но НЕ входит в aggregate, т.к. сама Readiness уже посчитана Garmin из нашей же нагрузки."""
    if not wellness:
        return {"ok": False, "reason": "таблицы wellness нет в БД или она пустая — перезапусти "
                                         "garmin_activities_export.py без --no-wellness"}
    metrics_report = {}
    for col, cfg in WELLNESS_METRICS.items():
        samples = []
        for date_str, w in wellness.items():
            v = w.get(col)
            if v is None:
                continue
            idx = (datetime.date.fromisoformat(date_str) - day0).days
            if 0 <= idx < n_days:
                samples.append((idx, float(v)))
        if len(samples) < WELLNESS_MIN_POINTS:
            metrics_report[col] = {"ok": False, "reason": f"мало точек ({len(samples)}, нужно >= {WELLNESS_MIN_POINTS})",
                                    "label": cfg["label"], "independent": cfg["independent"]}
            continue
        idxs = np.array([s[0] for s in samples])
        vals = np.array([s[1] for s in samples])
        fit = fit_systemic_fatigue(daily_total_load, idxs, vals, cfg["sign"], cfg["tau_bounds"])
        fit["label"] = cfg["label"]
        fit["independent"] = cfg["independent"]
        if fit.get("ok"):
            status = classify_fit_quality(fit["r2"], fit["hit_bound"], fit["n_points"])
            tau_used, w = apply_shrinkage(fit["tau_fatigue_days"], SYSTEMIC_TAU_DEFAULT, status, fit["r2"])
            fit["fit_quality"] = status
            fit["tau_fatigue_used"] = tau_used
            fit["shrinkage_weight"] = w
        metrics_report[col] = fit

    independent_ok = [m for col, m in metrics_report.items()
                       if m.get("independent") and m.get("ok") and m.get("fit_quality") != "UNIDENTIFIED"]
    if independent_ok:
        weights = [max(m["shrinkage_weight"], 0.05) * m["n_points"] for m in independent_ok]
        taus = [m["tau_fatigue_used"] for m in independent_ok]
        tau_agg = float(np.average(taus, weights=weights))
        aggregate = {
            "ok": True,
            "tau_fatigue_used": round(tau_agg, 2),
            "n_metrics_used": len(independent_ok),
            "metrics_used": [col for col, m in metrics_report.items()
                              if m.get("independent") and m.get("ok") and m.get("fit_quality") != "UNIDENTIFIED"],
            "note": "средневзвешенное tau_fatigue_used (вес = n_points * shrinkage_weight) по НЕЗАВИСИМЫМ "
                    "метрикам (без Training Readiness, см. докстринг п.8а) — это и есть tau, который "
                    "должен идти в tauFatigue['systemic'] модели.",
        }
    else:
        aggregate = {
            "ok": False,
            "tau_fatigue_used": SYSTEMIC_TAU_DEFAULT,
            "reason": "ни одна независимая метрика (HRV/RHR/Body Battery/стресс/сон) не дала "
                      "IDENTIFIED/WEAK фита — используется дефолт, модель не меняем на основании шума.",
        }

    tr = metrics_report.get("training_readiness_score")
    if tr and tr.get("ok"):
        tr_status = tr.get("fit_quality")
        agreement = None
        if aggregate.get("ok") and tr_status != "UNIDENTIFIED":
            agreement = round(abs(tr["tau_fatigue_days"] - aggregate["tau_fatigue_used"]), 2)
        training_readiness_check = {
            "tau_fatigue_days": tr["tau_fatigue_days"],
            "fit_quality": tr_status,
            "r2": tr["r2"],
            "diff_vs_independent_aggregate_days": agreement,
            "note": "СПРАВОЧНО, не источник калибровки (Training Readiness сам построен на нагрузке/"
                    "HRV/сне/стрессе алгоритмом Garmin — см. докстринг п.8а). Большое расхождение с "
                    "aggregate — не обязательно ошибка независимых метрик, скорее сигнал, что алгоритм "
                    "Garmin взвешивает вход иначе, чем наша модель.",
        }
    else:
        training_readiness_check = {"ok": False, "reason": (tr or {}).get("reason", "нет данных")}

    return {
        "ok": True,
        "metrics": metrics_report,
        "aggregate": aggregate,
        "training_readiness_check": training_readiness_check,
        "n_wellness_days": len(wellness),
    }


def build_daily_axis_load(acts, stimulus_map, rest_hr, max_hr, sex, day0, n_days, threshold_resolver=None):
    """daily_load[axis] = numpy-массив длиной n_days, day0 = самая ранняя дата (date object).
    threshold_resolver(date_obj)->hr|None — см. make_threshold_resolver(); только для БЕГА,
    т.к. беговые оси — это специфика беговых тренировок (см. докстринг п.9)."""
    daily = {ax: np.zeros(n_days) for ax in AXES}
    for a in acts:
        d = datetime.date.fromisoformat(a["date"])
        idx = (d - day0).days
        if idx < 0 or idx >= n_days:
            continue
        th = threshold_resolver(d) if threshold_resolver else None
        tr = training_load((a["duration_s"] or 0) / 60.0, a["avg_hr"], rest_hr, max_hr, sex, th)
        if tr is None:
            continue
        weights = stimulus_map.get(a["type_guess"], stimulus_map["unknown"])
        for axis, w in weights.items():
            daily[axis][idx] += tr * w
    return daily


def build_daily_total_load(acts, cross_acts, rest_hr, max_hr, sex, day0, n_days, threshold_resolver=None):
    """Суммарный load по всем тренировкам за день (бег + кросс-тренировки), без разложения
    по осям — используется для ACWR (п.7) и systemic-фита (п.8): реальная суммарная
    нагрузка на организм, а не её проекция на беговую ось. Кросс-тренировки (вело/лыжи/
    плавание/силовые) считаются по TRIMP (%HRR) — ПАНО специфично для бега, не переносим
    его на другие виды спорта (см. докстринг п.9)."""
    daily = np.zeros(n_days)
    for a in acts:
        d = datetime.date.fromisoformat(a["date"])
        idx = (d - day0).days
        if idx < 0 or idx >= n_days:
            continue
        th = threshold_resolver(d) if threshold_resolver else None
        tr = training_load((a["duration_s"] or 0) / 60.0, a["avg_hr"], rest_hr, max_hr, sex, th)
        if tr is not None:
            daily[idx] += tr
    for a in cross_acts:
        d = datetime.date.fromisoformat(a["date"])
        idx = (d - day0).days
        if idx < 0 or idx >= n_days:
            continue
        tr = trimp((a["duration_s"] or 0) / 60.0, a["avg_hr"], rest_hr, max_hr, sex)
        if tr is not None:
            daily[idx] += tr
    return daily


def build_daily_category_load(acts, rest_hr, max_hr, sex, day0, n_days, threshold_resolver=None,
                                category_map=None):
    """daily_category[cat] = numpy-массив длиной n_days, см. докстринг п.5a. В отличие от
    build_daily_axis_load, здесь каждая тренировка целиком относится к ОДНОЙ категории
    утомления (по своему type_guess через TYPE_CATEGORY), а не размазывается по осям
    пропорционально весам — сумма всех категорий за день равна вкладу бега в
    daily_total_load (без кросс-тренировок, они не классифицированы по этим категориям,
    см. докстринг п.9 — велофитнес не создаёт беговой нервно-мышечный/объёмный паттерн)."""
    cmap = category_map or TYPE_CATEGORY
    categories = sorted(set(cmap.values()))
    daily = {c: np.zeros(n_days) for c in categories}
    for a in acts:
        d = datetime.date.fromisoformat(a["date"])
        idx = (d - day0).days
        if idx < 0 or idx >= n_days:
            continue
        th = threshold_resolver(d) if threshold_resolver else None
        tr = training_load((a["duration_s"] or 0) / 60.0, a["avg_hr"], rest_hr, max_hr, sex, th)
        if tr is None:
            continue
        cat = cmap.get(a["type_guess"], "volume")
        daily[cat][idx] += tr
    return daily


def build_daily_impact_load(acts, day0, n_days):
    """Дневной ряд impactLoad (см. EF_CONFOUND_KEYS в garmin_activities_export.py, п.14 докстринга
    экспортёра) — МЕХАНИЧЕСКАЯ ударная нагрузка на опорно-двигательный аппарат, посчитанная Garmin
    отдельно от HR-based TRIMP/IF^2 (daily_total_load/daily_load). Используется как альтернативный
    load-драйвер для категории orthopedic (см. build_orthopedic_fit, направление 2 анализа
    raw-полей) — вместо старого прокси через endurance-ось, где load — это всё та же
    HR-based нагрузка, физически не про удар о поверхность."""
    out = np.zeros(n_days)
    for a in acts:
        v = a.get("impact_load")
        if v is None:
            continue
        idx = (datetime.date.fromisoformat(a["date"]) - day0).days
        if 0 <= idx < n_days:
            out[idx] += float(v)
    return out


def build_orthopedic_fit(acts, daily_impact_load, day0, seasonal=True, grade_adjust=True):
    """Прямая калибровка tau_adapt/tau_fatigue категории orthopedic по impactLoad вместо
    старого прокси через endurance-ось (см. докстринг п.13 и fatigue_category_mapping ниже).
    Наблюдаемая переменная та же, что у endurance-оси — EF по 'long'-тренировкам (тот же
    AXIS_SOURCE['endurance']), отличается только load-driver фита: impactLoad (механический,
    напрямую релевантен категории 'опорно-двигательная') вместо HR-based load. Если impactLoad
    ни разу не пришёл (старые часы/приложение Garmin его не считают — см. --dump-activity-fields)
    или точек EF мало — возвращает ok=False, и вызывающий код (fatigue_category_mapping)
    откатывается на старый endurance-прокси, поведение не меняется молча."""
    cfg = AXIS_SOURCE["endurance"]
    src_acts = [a for a in acts if a["type_guess"] in cfg["types"]]
    samples = []
    for a in src_acts:
        e = _ef_of(a, grade_adjust)
        if e is None:
            continue
        idx = (datetime.date.fromisoformat(a["date"]) - day0).days
        samples.append((idx, e))
    if len(samples) < MIN_POINTS:
        return {"ok": False, "reason": f"недостаточно точек EF по long ({len(samples)}, нужно >= {MIN_POINTS})"}
    if not np.any(daily_impact_load > 0):
        return {"ok": False, "reason": "impactLoad ни разу не пришёл ни на одной активности в БД — "
                                        "экспортёр получил None на всех activities (проверить "
                                        "--dump-activity-fields на свежей тренировке)"}
    idxs = np.array([s[0] for s in samples])
    efs_raw = np.array([s[1] for s in samples])
    if seasonal:
        efs, seasonal_info = seasonal_detrend(idxs, efs_raw, day0)
    else:
        efs, seasonal_info = efs_raw, {"applied": False, "reason": "отключено флагом --no-seasonal"}
    fit = fit_axis_model(daily_impact_load, idxs, efs, cfg["tau_adapt_bounds"], cfg["tau_fatigue_bounds"])
    fit["seasonal"] = seasonal_info
    fit["load_driver"] = "impact_load (механическая нагрузка от Garmin, НЕ HR-based TRIMP/IF^2)"
    if fit.get("ok"):
        status = classify_fit_quality(fit["r2"], fit["hit_bound"], fit["n_points"])
        defaults = AXIS_TAU_DEFAULTS["endurance"]  # тот же дефолт, что у старого прокси — та же физическая категория
        tau_adapt_used, w = apply_shrinkage(fit["tau_adapt_days"], defaults["tau_adapt"], status, fit["r2"])
        tau_fatigue_used, _ = apply_shrinkage(fit["tau_fatigue_days"], defaults["tau_fatigue"], status, fit["r2"])
        fit["fit_quality"] = status
        fit["tau_adapt_used"] = tau_adapt_used
        fit["tau_fatigue_used"] = tau_fatigue_used
        fit["shrinkage_weight"] = w
    return fit


def orthopedic_body_battery_diagnostic(acts):
    """Справочная сверка (не фит): средний differenceBodyBattery (см. EF_CONFOUND_KEYS) по
    каждому type_guess — ожидание физиологически: 'long' и 'threshold' должны давать
    наибольшее ПО МОДУЛЮ отрицательное значение (сильнее всего тратят Body Battery), что
    было бы независимым подтверждением, что impactLoad/EF по long действительно ловят
    орthopedic-релевантную нагрузку, а не шум. Не участвует в фите — только для отчёта."""
    by_type = {}
    for a in acts:
        v = a.get("difference_body_battery")
        if v is None:
            continue
        by_type.setdefault(a["type_guess"], []).append(float(v))
    return {t: {"avg_difference_body_battery": round(statistics.mean(vs), 1), "n": len(vs)}
            for t, vs in by_type.items() if vs}


def compute_acwr_series(daily_load, acute_days=7, chronic_days=28):
    """ACWR (Gabbett 2016) как временной ряд — вынесено в отдельную функцию, чтобы считать
    его как по суммарной нагрузке (см. analyze_gaps_and_overload), так и отдельно по каждой
    категории утомления (см. analyze_category_acwr, докстринг п.7)."""
    n_days = len(daily_load)
    weeks_in_chronic = chronic_days / acute_days
    acwr = np.full(n_days, np.nan)
    for t in range(chronic_days - 1, n_days):
        acute = daily_load[t - acute_days + 1: t + 1].sum()
        chronic_weekly_avg = daily_load[t - chronic_days + 1: t + 1].sum() / weeks_in_chronic
        if chronic_weekly_avg > 1e-9:
            acwr[t] = acute / chronic_weekly_avg
    return acwr


def analyze_category_acwr(daily_category_load, acwr_threshold=1.5, acute_days=7, chronic_days=28):
    """ACWR отдельно по каждой категории утомления (neuromuscular/volume, см. п.5a/п.7):
    суммарный ACWR может быть в норме, даже если весь рост дала одна категория (например
    несколько интервальных дней подряд при обычном общем объёме недели) — раздельный ACWR
    это видит, общий — нет."""
    report = {}
    for cat, arr in daily_category_load.items():
        acwr = compute_acwr_series(arr, acute_days, chronic_days)
        valid = acwr[~np.isnan(acwr)]
        if len(valid) == 0:
            report[cat] = {"ok": False, "reason": "недостаточно охвата для ACWR по этой категории"}
            continue
        report[cat] = {
            "ok": True,
            "median_acwr": round(float(np.median(valid)), 2),
            "pct_days_elevated": round(float(np.mean(valid >= acwr_threshold) * 100), 1),
            "max_acwr": round(float(np.max(valid)), 2),
        }
    return report


DOY_YEAR_REF = 2001  # невисокосный год-эталон для перевода day-of-year обратно в дату (для отчёта)


def doy_to_md(doy):
    d = datetime.date(DOY_YEAR_REF, 1, 1) + datetime.timedelta(days=int(doy) - 1)
    return d.strftime("%d.%m")


def seasonal_detrend(idxs, values, day0, min_span_days=300, min_points=20):
    """Гармоническая регрессия log(value) по дню года (годовой + полугодовой цикл),
    возвращает (adjusted_values, info). Если данных мало для надёжной оценки годового
    цикла — возвращает значения без изменений и info.applied=False (см. п.6 докстринга)."""
    dates = [day0 + datetime.timedelta(days=int(i)) for i in idxs]
    span = (max(dates) - min(dates)).days
    if span < min_span_days or len(values) < min_points:
        return values, {"applied": False, "reason": f"недостаточно охвата для годового цикла "
                                                       f"(span={span}д, n={len(values)}, нужно >= {min_span_days}д и >= {min_points} точек)"}
    doy = np.array([d.timetuple().tm_yday for d in dates], dtype=float)
    w = 2 * np.pi * doy / 365.25
    X = np.column_stack([np.ones_like(w), np.sin(w), np.cos(w), np.sin(2 * w), np.cos(2 * w)])
    logv = np.log(np.asarray(values, dtype=float))
    coef, *_ = np.linalg.lstsq(X, logv, rcond=None)
    seasonal_log = X @ coef - coef[0]
    adjusted = np.asarray(values, dtype=float) / np.exp(seasonal_log)

    doy_grid = np.arange(1, 367, dtype=float)
    wg = 2 * np.pi * doy_grid / 365.25
    Xg = np.column_stack([np.ones_like(wg), np.sin(wg), np.cos(wg), np.sin(2 * wg), np.cos(2 * wg)])
    seasonal_curve = Xg @ coef - coef[0]
    peak_doy = doy_grid[int(np.argmax(seasonal_curve))]
    trough_doy = doy_grid[int(np.argmin(seasonal_curve))]
    drop_pct = round(float((np.exp(seasonal_curve.max()) - np.exp(seasonal_curve.min())) / np.exp(seasonal_curve.max()) * 100), 1)
    return adjusted, {
        "applied": True,
        "peak_around": doy_to_md(peak_doy),
        "trough_around": doy_to_md(trough_doy),
        "drop_peak_to_trough_pct": drop_pct,
        "note": f"EF в среднем ниже своего годового пика на ~{drop_pct}% в районе {doy_to_md(trough_doy)} "
                f"(вероятно погода/покрытие, не потеря формы) — фит tau_adapt/tau_fatigue ниже использует "
                f"EF ПОСЛЕ вычитания этого сезонного эффекта.",
    }


def recursive_convolution(load_arr, tau):
    """fitness/fatigue(t) = сумма load(t_i)*exp(-(t-t_i)/tau) по всем t_i<=t,
    считается рекуррентно: s[t] = s[t-1]*exp(-1/tau) + load[t] (шаг сетки = 1 день)."""
    decay = np.exp(-1.0 / tau)
    out = np.empty_like(load_arr, dtype=float)
    s = 0.0
    for i, v in enumerate(load_arr):
        s = s * decay + v
        out[i] = s
    return out


def fit_axis_model(load_arr, sample_idx, sample_ef, tau_adapt_bounds, tau_fatigue_bounds):
    """Фит EF_pred(t) = base + k1*fitness(t,tau1) - k2*fatigue(t,tau2) по МНК.
    Возвращает dict с параметрами и R^2, либо None если не сошлось."""

    def model(idx, tau1, tau2, k1, k2, base):
        fit_arr = recursive_convolution(load_arr, tau1)
        fat_arr = recursive_convolution(load_arr, tau2)
        idx = idx.astype(int)
        return base + k1 * fit_arr[idx] - k2 * fat_arr[idx]

    ef_mean = float(np.mean(sample_ef))
    ef_scale = max(float(np.std(sample_ef)), 1e-6)
    p0 = [
        statistics.mean(tau_adapt_bounds),
        statistics.mean(tau_fatigue_bounds),
        0.001, 0.002, ef_mean,
    ]
    lower = [tau_adapt_bounds[0], tau_fatigue_bounds[0], -1, -1, ef_mean - 5 * ef_scale]
    upper = [tau_adapt_bounds[1], tau_fatigue_bounds[1],  1,  1, ef_mean + 5 * ef_scale]

    try:
        popt, _ = curve_fit(model, sample_idx.astype(float), sample_ef, p0=p0,
                             bounds=(lower, upper), maxfev=20000)
    except Exception as e:
        return {"ok": False, "error": str(e)}

    pred = model(sample_idx, *popt)
    ss_res = float(np.sum((sample_ef - pred) ** 2))
    ss_tot = float(np.sum((sample_ef - np.mean(sample_ef)) ** 2))
    r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float("nan")

    tau1, tau2, k1, k2, base = popt
    return {
        "ok": True,
        "tau_adapt_days": round(float(tau1), 1),
        "tau_fatigue_days": round(float(tau2), 1),
        "k1": round(float(k1), 6),
        "k2": round(float(k2), 6),
        "baseline_ef": round(float(base), 4),
        "r2": round(r2, 3),
        "n_points": int(len(sample_ef)),
        "hit_bound": bool(
            abs(tau1 - tau_adapt_bounds[0]) < 1e-3 or abs(tau1 - tau_adapt_bounds[1]) < 1e-3 or
            abs(tau2 - tau_fatigue_bounds[0]) < 1e-3 or abs(tau2 - tau_fatigue_bounds[1]) < 1e-3
        ),
    }


def quality_session_gaps(acts, type_guess):
    dates = sorted(datetime.date.fromisoformat(a["date"]) for a in acts if a["type_guess"] == type_guess)
    dates = sorted(set(dates))
    if len(dates) < 2:
        return []
    return [(dates[i + 1] - dates[i]).days for i in range(len(dates) - 1)]


def fit_recovery_nonlinearity(acts, stimulus_map, rest_hr, max_hr, sex, types, threshold_resolver=None,
                                history_load=None, day0=None, history_window_days=HISTORY_WINDOW_DAYS_DEFAULT):
    """Пары (load тренировки, дни_до_следующей_тренировки_того_же_типа) -> gap = tau_base*(1+k*load^p).

    Если передан history_load (дневной ряд нагрузки РЕЛЕВАНТНОЙ КАТЕГОРИИ УТОМЛЕНИЯ, см.
    build_daily_category_load) и day0 — см. докстринг п.5a: для каждой пары дополнительно
    считается hist = сумма history_load за history_window_days ДО тренировки i (не включая
    её саму), и модель усложняется до
        load_eff = load × (1 + k_hist × hist/mean(load))
        gap = tau_base × (1 + k × load_eff^p)
    т.е. цена одной и той же тренировки растёт, если перед ней уже был кластер нагрузки той
    же категории (несколько интенсивных/несколько длинных подряд), а не только от load самой
    этой тренировки. При недостатке пар (< 10) или несходимости — тихий откат на старую
    3-параметрическую модель без истории (k_hist=None в результате)."""
    pts = []
    for t in types:
        same = sorted([a for a in acts if a["type_guess"] == t], key=lambda a: a["date"])
        for i in range(len(same) - 1):
            d1 = datetime.date.fromisoformat(same[i]["date"])
            d2 = datetime.date.fromisoformat(same[i + 1]["date"])
            gap = (d2 - d1).days
            if gap <= 0 or gap > 30:
                continue
            th = threshold_resolver(d1) if threshold_resolver else None
            tr = training_load((same[i]["duration_s"] or 0) / 60.0, same[i]["avg_hr"], rest_hr, max_hr, sex, th)
            if tr is None:
                continue
            hist = None
            if history_load is not None and day0 is not None:
                idx1 = (d1 - day0).days
                if 0 <= idx1 <= len(history_load):
                    lo = max(0, idx1 - history_window_days)
                    hist = float(history_load[lo:idx1].sum())  # окно ДО тренировки i, саму i не включает
            pts.append((tr, gap, hist))
    if len(pts) < 8:
        return {"ok": False, "reason": f"мало пар (нужно >=8, есть {len(pts)})", "n_pairs": len(pts)}

    loads = np.array([p[0] for p in pts])
    gaps = np.array([p[1] for p in pts], dtype=float)
    have_hist = history_load is not None and all(p[2] is not None for p in pts) and len(pts) >= 10

    if have_hist:
        hist_arr = np.array([p[2] for p in pts])
        load_scale = max(float(np.mean(loads)), 1e-6)
        hist_norm = hist_arr / load_scale

        def model_hist(load_and_hist, tau_base, k, p, k_hist):
            load, hn = load_and_hist
            load_eff = load * (1 + k_hist * hn)
            return tau_base * (1 + k * np.power(load_eff, p))

        try:
            popt, _ = curve_fit(model_hist, (loads, hist_norm), gaps, p0=[2.0, 0.05, 1.5, 0.2],
                                 bounds=([0.5, 0.0, 0.5, 0.0], [15.0, 5.0, 4.0, 3.0]), maxfev=20000)
            pred = model_hist((loads, hist_norm), *popt)
            ss_res = float(np.sum((gaps - pred) ** 2))
            ss_tot = float(np.sum((gaps - np.mean(gaps)) ** 2))
            r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float("nan")
            tau_base, k, p, k_hist = popt
            return {
                "ok": True,
                "types_used": types,
                "tau_base_days": round(float(tau_base), 2),
                "k": round(float(k), 4),
                "p": round(float(p), 2),
                "k_hist": round(float(k_hist), 4),
                "history_window_days": history_window_days,
                "r2": round(r2, 3),
                "n_pairs": len(pts),
                "note": ("k_hist — насколько накопленная за history_window_days ДО тренировки нагрузка "
                          "той же категории утомления усиливает требуемое восстановление (эффект "
                          "'несколько подряд'); k_hist≈0 — в ваших данных эффект кластера не обнаружен, "
                          "это не то же самое, что 'эффекта нет физиологически' — возможно, мало пар "
                          "или разброс load недостаточен, чтобы его отличить от шума."),
            }
        except Exception:
            pass  # тихий откат на модель без истории ниже

    def model_simple(load, tau_base, k, p):
        return tau_base * (1 + k * np.power(load, p))

    try:
        popt, _ = curve_fit(model_simple, loads, gaps, p0=[2.0, 0.05, 1.5],
                             bounds=([0.5, 0.0, 0.5], [15.0, 5.0, 4.0]), maxfev=20000)
    except Exception as e:
        return {"ok": False, "reason": str(e), "n_pairs": len(pts)}

    pred = model_simple(loads, *popt)
    ss_res = float(np.sum((gaps - pred) ** 2))
    ss_tot = float(np.sum((gaps - np.mean(gaps)) ** 2))
    r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float("nan")
    tau_base, k, p = popt
    return {
        "ok": True,
        "types_used": types,
        "tau_base_days": round(float(tau_base), 2),
        "k": round(float(k), 4),
        "p": round(float(p), 2),
        "k_hist": None,
        "r2": round(r2, 3),
        "n_pairs": len(pts),
        "note": ("история накопленной нагрузки не учтена (недостаточно пар с известной историей "
                  "или фит не сошёлся)" if history_load is not None else
                  "история накопленной нагрузки не передавалась (см. build_daily_category_load)."),
    }


def build_metric_series(wellness, day0, n_days, metric):
    """Дневной ряд wellness-метрики длиной n_days, NaN там, где данных нет."""
    arr = np.full(n_days, np.nan)
    for date_str, w in wellness.items():
        v = w.get(metric)
        if v is None:
            continue
        idx = (datetime.date.fromisoformat(date_str) - day0).days
        if 0 <= idx < n_days:
            arr[idx] = float(v)
    return arr


def trailing_median(arr, window):
    """arr[i-window..i-1] медиана, НЕ включая сам день i (без утечки будущего в прошлое).
    NaN, если валидных точек в окне меньше половины окна (минимум 3)."""
    out = np.full(len(arr), np.nan)
    min_needed = max(3, window // 2)
    for i in range(len(arr)):
        seg = arr[max(0, i - window):i]
        seg = seg[~np.isnan(seg)]
        if len(seg) >= min_needed:
            out[i] = np.median(seg)
    return out


def ols_fit(X, y):
    """Обычный МНК с t/p-статистиками вручную (без statsmodels, чтобы не тащить лишнюю
    зависимость в calibration-скрипт) — X должен включать столбец константы первым."""
    n, k = X.shape
    beta, _, _, _ = np.linalg.lstsq(X, y, rcond=None)
    resid = y - X @ beta
    dof = max(n - k, 1)
    ss_res = float(np.sum(resid ** 2))
    ss_tot = float(np.sum((y - np.mean(y)) ** 2))
    sigma2 = ss_res / dof
    try:
        xtx_inv = np.linalg.inv(X.T @ X)
        se = np.sqrt(np.maximum(np.diag(sigma2 * xtx_inv), 0.0))
        with np.errstate(divide="ignore", invalid="ignore"):
            tvals = np.where(se > 0, beta / se, 0.0)
        pvals = 2 * (1 - scipy_stats.t.cdf(np.abs(tvals), dof))
    except np.linalg.LinAlgError:
        se = np.full(k, np.nan); tvals = np.full(k, np.nan); pvals = np.full(k, np.nan)
    r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float("nan")
    return {"beta": beta, "se": se, "t": tvals, "p": pvals, "r2": r2, "n": n, "dof": dof, "ss_res": ss_res}


def fit_recovery_response_readiness(wellness, daily_total_load, day0, n_days,
                                      metric="training_readiness_score",
                                      baseline_window=14, acute_days=7, chronic_days=28,
                                      min_points=40, bp_grid=None):
    """Дозозависимость цены восстановления от LOAD ДНЯ, напрямую по wellness-метрике —
    альтернатива fit_recovery_nonlinearity (которая мерит "дни до следующей тренировки
    того же типа": на 147 парах interval/threshold корреляция load<->gap оказалась ~0.03-
    0.13, статистически неотличима от нуля — потому что gap отражает выбор расписания
    атлета, а не физиологию, см. обсуждение в чате калибровки 2026-08-11).

    Отклик = metric(день+1) напрямую (не "потеря относительно baseline" — baseline подаётся
    отдельным регрессором, чтобы не путать реальный эффект накопленной нагрузки с потолком/
    полом ограниченной шкалы метрики 0-100: попытка через net-разность с hist_load в
    абсолютных цифрах дала коэффициент взаимодействия с контринтуитивным знаком именно по
    этой причине).

    Предикторы:
      baseline        — trailing-медиана метрики самого атлета (14д), контроль "текущего уровня"
      min(load, bp)   — load СЕГОДНЯШНЕГО дня ниже порога bp (ожидаемо ~0, шум)
      max(load-bp, 0) — load выше порога bp (ожидаемо значим — см. обсуждение breakpoint)
      acwr-1          — Gabbett ACWR (acute_days/chronic_days), центрирован на "норма"=0:
                        накопленная за неделю нагрузка относительно хронической, отдельно от
                        load сегодняшнего дня
      (load-bp)+ * (acwr-1) — взаимодействие; проверено, но знак/значимость нестабильны при
                        смене спецификации — не считать основным результатом, только доп. сигнал

    bp ищется грид-сёрчем по перцентилям load среди тренировочных дней (порог/перелом, а не
    гладкая степенная форма по всему диапазону — см. обсуждение п.10 architecture.md)."""
    metric_arr = build_metric_series(wellness, day0, n_days, metric)
    baseline_arr = trailing_median(metric_arr, baseline_window)
    next1_arr = np.full(n_days, np.nan)
    next1_arr[:-1] = metric_arr[1:]

    acute = np.array([np.sum(daily_total_load[max(0, i - acute_days):i]) for i in range(n_days)])
    chronic = np.array([np.sum(daily_total_load[max(0, i - chronic_days):i]) for i in range(n_days)]) \
        / (chronic_days / acute_days)

    load = daily_total_load
    valid = (load > 0) & ~np.isnan(baseline_arr) & ~np.isnan(next1_arr) & (chronic > 0)
    idxs = np.where(valid)[0]
    if len(idxs) < min_points:
        return {"ok": False, "reason": f"мало дней с тренировкой и полными wellness-данными "
                                         f"(нужно >= {min_points}, есть {len(idxs)})", "n_points": int(len(idxs))}

    L = load[idxs]
    B = baseline_arr[idxs]
    Y = next1_arr[idxs]
    acwr_c = acute[idxs] / chronic[idxs] - 1.0

    candidates = bp_grid if bp_grid is not None else np.percentile(L, np.arange(20, 91, 5))
    best = None
    for bp in candidates:
        x1 = np.minimum(L, bp)
        x2 = np.maximum(L - bp, 0)
        inter = x2 * acwr_c
        X = np.column_stack([np.ones_like(L), B, x1, x2, acwr_c, inter])
        try:
            fit = ols_fit(X, Y)
        except np.linalg.LinAlgError:
            continue
        if best is None or fit["ss_res"] < best[1]["ss_res"]:
            best = (bp, fit)
    if best is None:
        return {"ok": False, "reason": "не сошлось ни для одного порога", "n_points": int(len(idxs))}

    bp, fit = best
    names = ["const", "baseline", "load_below_bp", "load_above_bp", "acwr_minus1", "load_above_bp_x_acwr"]
    coefs = {name: {"coef": round(float(b), 5), "p": round(float(p), 4)}
             for name, b, p in zip(names, fit["beta"], fit["p"])}

    # для сравнения "с ACWR / без ACWR" на той же выборке и том же bp
    x1 = np.minimum(L, bp); x2 = np.maximum(L - bp, 0)
    X0 = np.column_stack([np.ones_like(L), B, x1, x2])
    fit0 = ols_fit(X0, Y)

    return {
        "ok": True,
        "metric": metric,
        "n_points": int(len(idxs)),
        "breakpoint_load": round(float(bp), 1),
        "acute_days": acute_days,
        "chronic_days": chronic_days,
        "baseline_window_days": baseline_window,
        "r2": round(fit["r2"], 4),
        "r2_without_acwr": round(fit0["r2"], 4),
        "coefficients": coefs,
        "note": ("load_below_bp обычно не значим (p столбца в coefficients) — ниже порога отклик "
                 "статистически не отличим от шума дней полного отдыха (проверено отдельно вне "
                 "этого скрипта: std изменения метрики в дни без тренировки почти совпадает со std "
                 "ниже порога). load_above_bp — цена нагрузки сегодняшнего дня выше порога. "
                 "acwr_minus1 — эффект накопленной за неделю нагрузки относительно хронической "
                 "(Gabbett ACWR), 0 = норма. load_above_bp_x_acwr — взаимодействие, знак/значимость "
                 "нестабильны при смене спецификации (проверялось) — не основной результат."),
    }


def analyze_gaps_and_overload(acts, cross_acts, daily_total_load, day0, n_days, gap_days=10, acwr_threshold=1.5,
                                acute_days=7, chronic_days=28):
    """ACWR (Gabbett 2016) = сумма load за последние acute_days / (сумма load за
    chronic_days / (chronic_days/acute_days)) — отношение острой к хронической нагрузке.
    Пропуском считается отсутствие ЛЮБОЙ активности (бег ИЛИ кросс-тренировка) — если
    провал в беге заполнен велосипедом, это не пропуск, а замена (см. докстринг п.9).
    Для каждого пропуска длиной >= gap_days подряд смотрим ACWR на дату начала пропуска
    (последний тренировочный день перед ним) — см. п.7 докстринга."""
    acwr = compute_acwr_series(daily_total_load, acute_days, chronic_days)

    train_days = set((datetime.date.fromisoformat(a["date"]) - day0).days for a in acts)
    train_days |= set((datetime.date.fromisoformat(a["date"]) - day0).days for a in cross_acts)
    train_set = train_days

    gaps = []
    i = 0
    while i < n_days:
        if i in train_set:
            i += 1
            continue
        j = i
        while j < n_days and j not in train_set:
            j += 1
        gap_len = j - i
        if gap_len >= gap_days:
            last_train_idx = i - 1
            acwr_before = float(acwr[last_train_idx]) if last_train_idx >= 0 and not np.isnan(acwr[last_train_idx]) else None
            gaps.append({
                "start_date": (day0 + datetime.timedelta(days=i)).isoformat(),
                "end_date": (day0 + datetime.timedelta(days=j - 1)).isoformat(),
                "gap_days": gap_len,
                "acwr_before": round(acwr_before, 2) if acwr_before is not None else None,
                "elevated_acwr": bool(acwr_before is not None and acwr_before >= acwr_threshold),
            })
        i = j

    valid_acwr = acwr[~np.isnan(acwr)]
    return {
        "gap_threshold_days": gap_days,
        "acwr_threshold": acwr_threshold,
        "acute_days": acute_days,
        "chronic_days": chronic_days,
        "median_acwr_overall": round(float(np.median(valid_acwr)), 2) if len(valid_acwr) else None,
        "gaps": gaps,
        "n_gaps": len(gaps),
        "n_gaps_with_elevated_acwr": sum(1 for g in gaps if g["elevated_acwr"]),
    }


def _ef_of(a, grade_adjust):
    """EF одной активности — с grade-adjusted поправкой по умолчанию (см. докстринг п.6б),
    если в записи есть elevation_gain_m/elevation_loss_m (старые БД без миграции EF-конфаундов —
    см. п.14 garmin_activities_export.py — этих ключей не имеют, тогда тихий откат на plain ef())."""
    if grade_adjust and "elevation_gain_m" in a:
        is_treadmill = (a.get("sport") or "") == "treadmill_running"
        return ef_grade_adjusted(a["distance_m"], a["duration_s"], a["avg_hr"],
                                   a.get("elevation_gain_m"), a.get("elevation_loss_m"), is_treadmill)
    return ef(a["distance_m"], a["duration_s"], a["avg_hr"])


def _pearsonr_approx(x, y):
    """Коэффициент корреляции + грубая p-value (нормальное приближение t-статистики,
    без scipy) — только для диагностики величины связи в analyze_volume_ef_response,
    не заменяет formal significance testing."""
    if len(x) < 3 or np.std(x) < 1e-9 or np.std(y) < 1e-9:
        return None, None
    r = float(np.corrcoef(x, y)[0, 1])
    n = len(x)
    denom = max(1e-9, 1 - r ** 2)
    t = r * math.sqrt((n - 2) / denom)
    p_approx = 2 * (1 - 0.5 * (1 + math.erf(abs(t) / math.sqrt(2))))
    return round(r, 3), round(float(p_approx), 4)


def analyze_volume_ef_response(acts, daily_total_load, day0, n_days, grade_adjust=True,
                                ef_types=("easy",), rolling_weeks=4, horizon_weeks=4, n_bins=5):
    """Дозозависимость 'объём бега -> будущее изменение EF' — ОТКАЛИБРОВАННАЯ версия ручного
    анализа из внешнего разбора дашборда (2026-08-12): для каждой недели считается скользящий
    за rolling_weeks недель объём бега (км/нед, ВСЕ беговые тренировки) и сезонно-детрендированная
    EF по типам ef_types (по умолчанию 'easy' — самый частый и наименее зашумлённый workout-
    вариацией тип), затем — изменение EF через horizon_weeks недель вперёд. Недели бьются на
    n_bins квантильных корзин по объёму, и по каждой корзине считается среднее будущее изменение
    EF — так находится (a) объём с лучшим последующим ростом EF, (b) объём, выше которого рост
    в среднем устойчиво сменяется спадом. Параллельно то же самое строится по ACWR (Gabbett,
    из daily_total_load) вместо объёма. Полностью выводится из истории тренировок — ничего не
    переносится руками (см. not_calibrated о том, чего этот блок ПРИНЦИПИАЛЬНО не может дать:
    formal significance с учётом автокорреляции недельных окон)."""
    ef_acts = [a for a in acts if a["type_guess"] in ef_types]
    samples = []
    for a in ef_acts:
        e = _ef_of(a, grade_adjust)
        if e is None:
            continue
        idx = (datetime.date.fromisoformat(a["date"]) - day0).days
        if 0 <= idx < n_days:
            samples.append((idx, e))
    if len(samples) < MIN_POINTS:
        return {"ok": False, "reason": f"недостаточно EF-измерений типа {list(ef_types)} "
                                        f"({len(samples)}, нужно >= {MIN_POINTS})"}
    idxs = np.array([s[0] for s in samples])
    efs_raw = np.array([s[1] for s in samples])
    efs, seasonal_info = seasonal_detrend(idxs, efs_raw, day0)

    daily_run_km = np.zeros(n_days)
    for a in acts:
        if not a.get("distance_m"):
            continue
        idx = (datetime.date.fromisoformat(a["date"]) - day0).days
        if 0 <= idx < n_days:
            daily_run_km[idx] += a["distance_m"] / 1000.0

    acwr_series = compute_acwr_series(daily_total_load)

    n_weeks = n_days // 7
    if n_weeks < 2 * (rolling_weeks + horizon_weeks):
        return {"ok": False, "reason": f"недостаточно недель охвата ({n_weeks}) для окна "
                                        f"{rolling_weeks}+{horizon_weeks} нед."}

    ef_week = np.full(n_weeks, np.nan)
    for w in range(n_weeks):
        mask = (idxs >= w * 7) & (idxs < (w + 1) * 7)
        if mask.any():
            ef_week[w] = float(np.mean(efs[mask]))
    have = ~np.isnan(ef_week)
    if have.sum() < MIN_POINTS:
        return {"ok": False, "reason": "недостаточно недель с EF-измерениями после недельной агрегации"}
    ef_week_interp = np.interp(np.arange(n_weeks), np.flatnonzero(have), ef_week[have])

    vol_roll = np.array([
        daily_run_km[max(0, (w + 1) * 7 - rolling_weeks * 7): (w + 1) * 7].sum() / rolling_weeks
        for w in range(n_weeks)
    ])
    acwr_week = np.array([
        np.nanmean(acwr_series[w * 7: (w + 1) * 7])
        if not np.all(np.isnan(acwr_series[w * 7: (w + 1) * 7])) else np.nan
        for w in range(n_weeks)
    ])

    valid_w = np.arange(n_weeks - horizon_weeks)
    delta_ef = ef_week_interp[valid_w + horizon_weeks] - ef_week_interp[valid_w]
    vol_w = vol_roll[valid_w]
    acwr_w = acwr_week[valid_w]

    def _bin_analysis(x, y, label):
        mask = ~np.isnan(x) & ~np.isnan(y) & (x > 0)
        x, y = x[mask], y[mask]
        if len(x) < n_bins * 3:
            return {"ok": False, "reason": f"недостаточно недель с валидным {label} ({len(x)})"}
        order = np.argsort(x)
        x_sorted, y_sorted = x[order], y[order]
        edges = np.array_split(np.arange(len(x_sorted)), n_bins)
        delta_key = f"mean_delta_ef_next_{horizon_weeks}w"
        bins = []
        for e in edges:
            if len(e) == 0:
                continue
            bins.append({
                f"{label}_range": [round(float(x_sorted[e].min()), 2), round(float(x_sorted[e].max()), 2)],
                f"{label}_mean": round(float(x_sorted[e].mean()), 2),
                delta_key: round(float(y_sorted[e].mean()), 4),
                "n_weeks": int(len(e)),
            })
        best = max(bins, key=lambda b: b[delta_key])
        decline_threshold = None
        for i in range(len(bins) - 1, -1, -1):
            if bins[i][delta_key] < 0 and all(b[delta_key] < 0 for b in bins[i:]):
                decline_threshold = bins[i][f"{label}_range"][0]
            else:
                break
        r, p = _pearsonr_approx(x, y)
        return {
            "ok": True,
            "n_weeks": int(len(x)),
            "bins": bins,
            "best_bin": best,
            "decline_threshold": decline_threshold,
            "correlation_r": r,
            "correlation_p_approx": p,
        }

    by_volume = _bin_analysis(vol_w, delta_ef, "rolling_volume_km_per_week")
    by_acwr = _bin_analysis(acwr_w, delta_ef, "acwr")

    return {
        "ok": True,
        "method": f"недельная агрегация; объём = скользящие {rolling_weeks} нед. (км/нед, все "
                  f"беговые тренировки), EF = сезонно-детрендированная по типам {list(ef_types)} "
                  f"(интерполяция между неделями с измерениями); изменение EF считается через "
                  f"{horizon_weeks} нед. вперёд; корзины квантильные по {n_bins}.",
        "ef_source_types": list(ef_types),
        "seasonal": seasonal_info,
        "n_weeks_total": int(n_weeks),
        "by_rolling_volume_km_per_week": by_volume,
        "by_acwr": by_acwr,
        "caveat": "квантильные корзины по недельным окнам с интерполяцией EF между измерениями — "
                  "не formal dose-response фит (как axes/recovery_nonlinearity), а описательная "
                  "оценка направления и порога; недельные окна пересекаются (скользящее окно) — "
                  "correlation_p_approx НЕ учитывает автокорреляцию и оптимистична, смотрите на "
                  "n_weeks и величину r, не на p как на строгий тест значимости.",
    }


def build_report(db_path, max_hr, rest_hr, sex, stimulus_map, threshold_hr=None,
                  seasonal=True, gap_days=10, acwr_threshold=1.5, use_wellness=True,
                  use_garmin_threshold=True, use_cross_training=True,
                  history_window_days=HISTORY_WINDOW_DAYS_DEFAULT, grade_adjust=True):
    acts = load_activities(db_path)
    if not acts:
        sys.exit(f"В базе {db_path} нет тренировок с датой — сначала запусти garmin_activities_export.py.")

    dates = [datetime.date.fromisoformat(a["date"]) for a in acts]
    day0, day1 = min(dates), max(dates)
    n_days = (day1 - day0).days + 1

    lt_series = load_lactate_threshold(db_path) if use_garmin_threshold else []
    threshold_resolver = make_threshold_resolver(threshold_hr, lt_series)
    if threshold_hr:
        threshold_source = "manual"
    elif lt_series:
        threshold_source = f"garmin_history({len(lt_series)} точек, {lt_series[0][0].isoformat()}..{lt_series[-1][0].isoformat()})"
    else:
        threshold_source = "none (TRIMP по %HRR)"

    cross_acts = load_cross_activities(db_path) if use_cross_training else []

    daily_load = build_daily_axis_load(acts, stimulus_map, rest_hr, max_hr, sex, day0, n_days, threshold_resolver)
    daily_total_load = build_daily_total_load(acts, cross_acts, rest_hr, max_hr, sex, day0, n_days, threshold_resolver)
    daily_category_load = build_daily_category_load(acts, rest_hr, max_hr, sex, day0, n_days, threshold_resolver)
    daily_impact_load = build_daily_impact_load(acts, day0, n_days)
    orthopedic_fit = build_orthopedic_fit(acts, daily_impact_load, day0, seasonal=seasonal, grade_adjust=grade_adjust)
    orthopedic_bb_diagnostic = orthopedic_body_battery_diagnostic(acts)

    axes_report = {}
    for axis, cfg in AXIS_SOURCE.items():
        src_acts = [a for a in acts if a["type_guess"] in cfg["types"]]
        samples = []
        for a in src_acts:
            e = _ef_of(a, grade_adjust)
            if e is None:
                continue
            idx = (datetime.date.fromisoformat(a["date"]) - day0).days
            samples.append((idx, e))
        if len(samples) < MIN_POINTS or (max(i for i, _ in samples) - min(i for i, _ in samples) if samples else 0) < MIN_SPAN_DAYS:
            axes_report[axis] = {
                "ok": False,
                "reason": f"недостаточно данных: {len(samples)} измерений EF по типу {cfg['types']} "
                          f"(нужно >= {MIN_POINTS} точек и охват >= {MIN_SPAN_DAYS} дней)",
                "n_points": len(samples),
            }
            continue
        idxs = np.array([s[0] for s in samples])
        efs_raw = np.array([s[1] for s in samples])
        if seasonal:
            efs, seasonal_info = seasonal_detrend(idxs, efs_raw, day0)
        else:
            efs, seasonal_info = efs_raw, {"applied": False, "reason": "отключено флагом --no-seasonal"}
        fit = fit_axis_model(daily_load[axis], idxs, efs, cfg["tau_adapt_bounds"], cfg["tau_fatigue_bounds"])
        fit["source_types"] = cfg["types"]
        fit["seasonal"] = seasonal_info
        if fit.get("ok"):
            status = classify_fit_quality(fit["r2"], fit["hit_bound"], fit["n_points"])
            defaults = AXIS_TAU_DEFAULTS.get(axis, {"tau_adapt": fit["tau_adapt_days"], "tau_fatigue": fit["tau_fatigue_days"]})
            tau_adapt_used, w = apply_shrinkage(fit["tau_adapt_days"], defaults["tau_adapt"], status, fit["r2"])
            tau_fatigue_used, _ = apply_shrinkage(fit["tau_fatigue_days"], defaults["tau_fatigue"], status, fit["r2"])
            fit["fit_quality"] = status
            fit["tau_adapt_used"] = tau_adapt_used
            fit["tau_fatigue_used"] = tau_fatigue_used
            fit["shrinkage_weight"] = w
            fit["note_quality"] = {
                "IDENTIFIED": "фит надёжен (R²>=0.20, не упёрся в границу, n достаточно) — используется как есть.",
                "WEAK": "фит слабый — tau_used подмешан к дефолту (см. shrinkage_weight), не подставляйте tau_adapt_days/"
                        "tau_fatigue_days напрямую в модель, используйте tau_*_used.",
                "UNIDENTIFIED": "фит недостоверен (R²<0.05 или упёрся в границу диапазона) — tau_*_used равны дефолту, "
                                 "калиброванные числа НЕ должны менять модель.",
            }[status]
        axes_report[axis] = fit

    recovery = fit_recovery_nonlinearity(
        acts, stimulus_map, rest_hr, max_hr, sex, ["interval", "threshold"], threshold_resolver,
        history_load=daily_category_load.get("neuromuscular"), day0=day0,
        history_window_days=history_window_days,
    )
    recovery_long = fit_recovery_nonlinearity(
        acts, stimulus_map, rest_hr, max_hr, sex, ["long"], threshold_resolver,
        history_load=daily_category_load.get("volume"), day0=day0,
        history_window_days=history_window_days,
    )
    overload = analyze_gaps_and_overload(acts, cross_acts, daily_total_load, day0, n_days, gap_days, acwr_threshold)
    overload_by_category = analyze_category_acwr(daily_category_load, acwr_threshold)
    volume_ef_response = analyze_volume_ef_response(acts, daily_total_load, day0, n_days, grade_adjust=grade_adjust)

    if use_wellness:
        wellness = load_wellness(db_path)
        systemic = build_systemic_report(wellness, daily_total_load, day0, n_days)
        if wellness:
            recovery_response = fit_recovery_response_readiness(wellness, daily_total_load, day0, n_days)
        else:
            recovery_response = {"ok": False, "reason": "таблица wellness пуста"}
    else:
        systemic = {"ok": False, "reason": "отключено флагом --no-wellness"}
        recovery_response = {"ok": False, "reason": "отключено флагом --no-wellness"}

    spacing = {}
    for t in ["interval", "threshold", "long"]:
        gaps = quality_session_gaps(acts, t)
        if gaps:
            spacing[t] = {
                "n": len(gaps),
                "median_days": statistics.median(gaps),
                "p25_days": sorted(gaps)[len(gaps) // 4] if len(gaps) >= 4 else min(gaps),
                "p75_days": sorted(gaps)[(3 * len(gaps)) // 4] if len(gaps) >= 4 else max(gaps),
            }

    # estimated_optimal_spacing (см. докстринг п.12) — В ОТЛИЧИЕ от quality_session_spacing
    # (=observed_spacing, статистика ФАКТИЧЕСКОГО расписания атлета — сколько дней он сам
    # ставил между качественными тренировками, это поведение, а не физиология), здесь берётся
    # результат adaptive response model (recovery_nonlinearity/_long: gap = tau_base*(1+k*load^p))
    # при типичном (медианном) load тренировок этого типа — оценка минимально необходимого
    # физиологического интервала. Только estimated_optimal_spacing должен участвовать в
    # построении плана; quality_session_spacing — справочная аналитика истории.
    def _median_training_load(types):
        loads = []
        for a in acts:
            if a["type_guess"] not in types:
                continue
            th = threshold_resolver(datetime.date.fromisoformat(a["date"])) if threshold_resolver else None
            tr = training_load((a["duration_s"] or 0) / 60.0, a["avg_hr"], rest_hr, max_hr, sex, th)
            if tr is not None:
                loads.append(tr)
        return statistics.median(loads) if loads else None

    def _estimate_optimal_spacing(rec, median_load):
        if not rec.get("ok"):
            return {"ok": False, "reason": rec.get("reason", "recovery_nonlinearity недоступен для этого типа")}
        if median_load is None:
            return {"ok": False, "reason": "нет тренировок этого типа для оценки медианного load"}
        gap = rec["tau_base_days"] * (1 + rec["k"] * (median_load ** rec["p"]))
        return {
            "ok": True,
            "estimated_days_at_median_load": round(float(gap), 2),
            "median_load_used": round(float(median_load), 1),
            "r2_of_source_fit": rec["r2"],
            "note": "оценка минимально необходимого интервала по adaptive response model "
                    "(tau_base*(1+k*load^p)) при типичном load этого типа тренировок, без учёта "
                    "k_hist/кластеров — используйте это значение для построения плана, а не "
                    "observed из quality_session_spacing.",
        }

    estimated_optimal_spacing = {
        "interval_threshold": _estimate_optimal_spacing(recovery, _median_training_load(["interval", "threshold"])),
        "long": _estimate_optimal_spacing(recovery_long, _median_training_load(["long"])),
    }

    # fatigue_category_mapping (см. докстринг п.13 / Замечание 3) — architecture.md держит
    # tauFatigue ПО КАТЕГОРИИ УТОМЛЕНИЯ (systemic/muscular/neuromuscular/orthopedic), а не по
    # оси стимула, тогда как axes-фит выше даёт tau_fatigue ПО ОСИ (aerobic/threshold/endurance/
    # vo2). Явно и честно сопоставляем один с другим, вместо того чтобы оставлять несогласованность
    # на совести того, кто руками переносит числа из axes в модель:
    #   vo2       -> neuromuscular  (интервалы грузят преимущественно нервно-мышечную систему)
    #   endurance -> orthopedic     (ЗАПАСНОЙ вариант — см. ниже, приоритет у прямого impact_load-фита)
    #   systemic  -> systemic_fatigue.aggregate (независимые wellness-метрики, см. докстринг п.8а)
    #   muscular  -> НЕ ИЗМЕРЯЕТСЯ этим скриптом (Garmin не даёт метрику, специфичную для
    #                метаболического утомления мышц отдельно от ЦНС/суставов) — только дефолт.
    # tau_fatigue у aerobic/threshold остаётся в axes (диагностика фита EF), но НЕ используется
    # ни в одной из 4 категорий модели — если понадобится 5-я/6-я категория, это нужно решить
    # архитектурно в adaptive_model_architecture.md, а не молча брать числа отсюда.
    #
    # ORTHOPEDIC (обновлено — направление 2 анализа raw-полей): раньше tau_fatigue_used всегда
    # брался с endurance-оси (load = HR-based TRIMP/IF^2, физически про сердечно-сосудистую
    # нагрузку, а не про опорно-двигательный аппарат — сам этот прокси был честно отмечен как
    # приближение в not_calibrated). Теперь ПРИОРИТЕТ у orthopedic_fit — прямого фита с тем же
    # набором точек EF по 'long', но load-драйвером impactLoad (механическая нагрузка, см.
    # build_orthopedic_fit); используется, только если он IDENTIFIED/WEAK. Если impactLoad не
    # пришёл или фит недостоверен — тихий откат на старый endurance-прокси (source меняется на
    # 'endurance_axis_proxy', это видно в отчёте).
    def _category_from_axis(axis_key, category):
        fit = axes_report.get(axis_key, {})
        if not fit.get("ok"):
            return {"ok": False, "source_axis": axis_key,
                    "reason": f"ось {axis_key} не откалибрована — {fit.get('reason')}"}
        return {
            "ok": True,
            "source": "endurance_axis_proxy",
            "source_axis": axis_key,
            "tau_fatigue_used": fit["tau_fatigue_used"],
            "fit_quality": fit["fit_quality"],
        }

    if orthopedic_fit.get("ok") and orthopedic_fit.get("fit_quality") != "UNIDENTIFIED":
        orthopedic_mapping = {
            "ok": True,
            "source": "impact_load_direct_fit",
            "tau_fatigue_used": orthopedic_fit["tau_fatigue_used"],
            "fit_quality": orthopedic_fit["fit_quality"],
            "note": "прямой фит по impactLoad (см. build_orthopedic_fit) — заменил старый "
                    "endurance-осевой прокси, т.к. фит достаточно надёжен (не UNIDENTIFIED).",
        }
    else:
        orthopedic_mapping = _category_from_axis("endurance", "orthopedic")
        if orthopedic_mapping.get("ok"):
            orthopedic_mapping["note"] = ("impact_load_direct_fit недоступен/недостоверен "
                                            f"({orthopedic_fit.get('reason', orthopedic_fit.get('fit_quality'))}) "
                                            "— используется старый endurance-осевой прокси.")

    fatigue_category_mapping = {
        "neuromuscular": _category_from_axis("vo2", "neuromuscular"),
        "orthopedic": orthopedic_mapping,
        "systemic": (
            {"ok": True, "source": "systemic_fatigue.aggregate (независимые метрики)",
             "tau_fatigue_used": systemic["aggregate"]["tau_fatigue_used"]}
            if systemic.get("ok") and systemic.get("aggregate", {}).get("ok")
            else {"ok": False, "reason": "systemic_fatigue не откалиброван или ни одна независимая метрика "
                                          "не дала фита — см. блок systemic_fatigue", "tau_fatigue_used": SYSTEMIC_TAU_DEFAULT}
        ),
        "muscular": {"ok": False, "reason": "не измеряется: нет garmin-метрики, специфичной для "
                                              "метаболического утомления мышц отдельно от ЦНС/суставов — "
                                              "используйте дефолт из architecture.md вручную."},
        "unused_axis_tau_fatigue": {
            "threshold": "фитится в axes.threshold для диагностики (EF-модель), но не подставляется ни в "
                          "одну из 4 категорий FatigueState — нет отдельной категории 'threshold' в модели.",
            "aerobic": "аналогично threshold — фитится для диагностики, не используется в категориях.",
        },
    }

    not_calibrated = [
        "capacity(exp, age) / experienceCapacity / ageAdjustment — не выводится из истории тренировок, "
        "нужен внешний вход (стаж, возраст, референсные результаты).",
        "Веса категорий muscular/neuromuscular/orthopedic в weightedFatiguePenalty — Garmin не различает "
        "ЛОКАЛИЗАЦИЮ утомления (крепатура/суставы/ЦНС отдельно не измеряются); полное разделение на все три "
        "категории не оценивается этим скриптом. Частично закрыто грубым 2-категорийным разбиением "
        "neuromuscular/volume (см. п.5a, recovery_nonlinearity_neuromuscular/_long и overload_by_category) — "
        "это прокси, не измерение крепатуры/ЦНС по отдельности. (Категория systemic из той же формулы — "
        "см. блок systemic_fatigue ниже, она оценивается по HRV/RHR/Body Battery/стрессу/training readiness, "
        "если есть таблица wellness.)",
        "marathon_specific и strength — либо смешивается с threshold/long по доступным данным "
        "(marathon_specific), либо нет измерения производительности в принципе (strength).",
        "Порог fitness >= порога, при котором окно считается «открытым» (architecture.md §10) — "
        "не откалиброван напрямую; overload_penalty частично приближается через ACWR при пропусках "
        "(см. overload_gaps), но это не то же самое, что порог окна суперкомпенсации.",
        "recovery_response_readiness (dose-response по load дня напрямую на Training Readiness) — "
        "R² выше, чем у recovery_nonlinearity (0.10-0.15 против ~0.04), но остаётся одномерным по "
        "объёму: не учитывает тип тренировки/ось стимула отдельно, только суммарный дневной load. "
        "Взаимодействие load_above_bp_x_acwr статистически значимо, но знак нестабилен при смене "
        "спецификации — не использовать как единственный источник для dose-response объёма без "
        "дополнительной проверки на бóльшей выборке. Кроме того, сама Training Readiness — не "
        "независимая метрика (см. докстринг п.8а), так что этот блок ближе к 'насколько мы "
        "воспроизводим алгоритм Garmin', чем к прямой физиологической калибровке.",
        "GEN_W (веса генератора вариантов тренировки, architecture.md §10) — этот скрипт НЕ калибрует "
        "gain=10 и подобные веса напрямую. Правильный путь — historical replay: для каждой реальной "
        "тренировки атлета сгенерировать правдоподобные альтернативы на ту же дату по текущей модели, "
        "посчитать им score(A/B/...), и требовать, чтобы score фактически выбранной/выполненной "
        "тренировки был выше правдоподобных альтернатив; GEN_W далее оптимизируется как параметр этой "
        "задачи ранжирования (например логистическая/hinge-функция потерь на парах "
        "'фактическая > альтернатива'). Это отдельный скрипт (нужны: генератор альтернатив из текущего "
        "калькулятора планов + история фактических тренировок), не реализован здесь.",
        "EF как прокси формы (см. докстринг п.3) искажается температурой/рельефом/покрытием/ветром/"
        "обезвоживанием/точностью GPS/изменением веса — сезонная поправка (п.6) убирает только годовой "
        "цикл погоды, но не рельеф конкретной пробежки и не разовые погодные аномалии. Grade-adjusted "
        "поправка по elevation_gain_m/elevation_loss_m теперь ЕСТЬ (см. ef_grade_adjusted/п.6б, включена "
        "по умолчанию, --no-grade-adjust отключает) — но это грубая эвристика 'эквивалентная дистанция' "
        "(GRADE_ADJUST_METERS_UP/DOWN), не полноценная Minetti-модель по профилю градиента (для неё нужен "
        "поминутный профиль высоты, которого в экспорте нет). Treadmill_running из поправки ИСКЛЮЧЁН "
        "принципиально (см. докстринг grade_adjusted_distance_m). Ещё НЕ сделано в этой версии: фильтрация "
        "manual_activity=1/elevation_corrected=0 из фита по рельефу, учёт avg_temperature_c как доп. "
        "регрессора наравне с сезонной поправкой (п.6), учёт water_estimated_ml (обезвоживание) —"
        " все три поля в БД уже есть (см. п.14 garmin_activities_export.py), но не подключены к фиту. "
        "Покрытие (trail/road/track) и влажность/ветер Garmin по-прежнему не отдаёт вообще.",
        "volume_ef_response (объём/ACWR -> будущее изменение EF, см. блок выше) — квантильные "
        "корзины по перекрывающимся недельным окнам, не formal регрессия с учётом автокорреляции; "
        "correlation_p_approx оптимистична (не учитывает, что соседние недели сильно скоррелированы "
        "между собой). decline_threshold — эвристика 'первая корзина снизу с устойчиво отрицательным "
        "Δ EF', не статистический breakpoint-тест. Использовать как ориентир (диапазон, а не точное "
        "число), пересчитывать при появлении новых данных, не подставлять bins напрямую как hard "
        "constraint в калькулятор без здравого смысла.",
    ]

    cross_summary = {}
    for a in cross_acts:
        cross_summary.setdefault(a["sport"], 0)
        cross_summary[a["sport"]] += 1

    return {
        "meta": {
            "db": os.path.abspath(db_path),
            "generated_at": datetime.datetime.now().replace(microsecond=0).isoformat(),
            "period": [day0.isoformat(), day1.isoformat()],
            "n_activities": len(acts),
            "n_cross_activities": len(cross_acts),
            "cross_activities_by_sport": cross_summary,
            "threshold_source": threshold_source,
            "load_params": {"max_hr": max_hr, "rest_hr": rest_hr, "threshold_hr": threshold_hr, "sex": sex},
            "stimulus_map": stimulus_map,
        },
        "axes": axes_report,
        "orthopedic_fit": orthopedic_fit,
        "orthopedic_body_battery_diagnostic": orthopedic_bb_diagnostic,
        "recovery_nonlinearity": recovery,
        "recovery_nonlinearity_long": recovery_long,
        "recovery_response_readiness": recovery_response,
        "quality_session_spacing": spacing,
        "estimated_optimal_spacing": estimated_optimal_spacing,
        "overload_gaps": overload,
        "overload_by_category": overload_by_category,
        "volume_ef_response": volume_ef_response,
        "systemic_fatigue": systemic,
        "fatigue_category_mapping": fatigue_category_mapping,
        "not_calibrated": not_calibrated,
    }


def print_summary(report):
    print("\n=== Калибровочный профиль ===")
    m = report["meta"]
    lp = m["load_params"]
    print(f"Период: {m['period'][0]} .. {m['period'][1]}  |  тренировок: {m['n_activities']}  |  "
          f"кросс-тренировок: {m['n_cross_activities']} {m['cross_activities_by_sport'] or ''}")
    print(f"Источник ПАНО: {m['threshold_source']}  |  HRmax={lp['max_hr']} HRrest={lp['rest_hr']} пол={lp['sex']}")

    print("\n-- Оси (tau_adapt / tau_fatigue из фита EF по Banister fitness-fatigue) --")
    for axis, r in report["axes"].items():
        if not r.get("ok"):
            print(f"  {axis:10s}: не откалибровано — {r.get('reason')}")
            continue
        warn = "  [!] уперлось в границу диапазона — расширь bounds и перезапусти" if r.get("hit_bound") else ""
        seas = r.get("seasonal", {})
        seas_note = f"  | сезонность: -{seas['drop_peak_to_trough_pct']}% к {seas['trough_around']}" if seas.get("applied") else "  | сезонность: не применена"
        print(f"  {axis:10s}: tau_adapt={r['tau_adapt_days']:>5}д  tau_fatigue={r['tau_fatigue_days']:>5}д  "
              f"k1={r['k1']:.5f}  k2={r['k2']:.5f}  R²={r['r2']}  (n={r['n_points']}, источник={r['source_types']}){warn}{seas_note}")
        print(f"    -> статус: {r['fit_quality']}  tau_adapt_used={r['tau_adapt_used']}д  "
              f"tau_fatigue_used={r['tau_fatigue_used']}д  (shrinkage w={r['shrinkage_weight']}) — {r['note_quality']}")

    print("\n-- Нелинейность восстановления: tau = tau_base*(1+k*load_eff^p), "
          "load_eff=load*(1+k_hist*hist/mean_load) --")
    for label, key in [("Интервалы/порог (neuromuscular)", "recovery_nonlinearity"),
                        ("Длинная (volume)", "recovery_nonlinearity_long")]:
        rec = report.get(key, {})
        if rec.get("ok"):
            hist_part = (f"  k_hist={rec['k_hist']} (окно {rec.get('history_window_days')}д)"
                         if rec.get("k_hist") is not None else "  история не учтена")
            print(f"  {label}: tau_base={rec['tau_base_days']}д  k={rec['k']}  p={rec['p']}  R²={rec['r2']}  "
                  f"(n={rec['n_pairs']} пар, типы {rec['types_used']}){hist_part}")
        else:
            print(f"  {label}: не откалибровано — {rec.get('reason')}")

    rr = report.get("recovery_response_readiness", {})
    print("\n-- Dose-response по load дня напрямую на wellness-метрику (альтернатива блоку выше) --")
    if rr.get("ok"):
        print(f"  метрика={rr['metric']}  n={rr['n_points']}  порог(load)={rr['breakpoint_load']}  "
              f"R²={rr['r2']}  (R² без ACWR: {rr['r2_without_acwr']})")
        for name, c in rr["coefficients"].items():
            sig = " *" if c["p"] < 0.05 else ""
            print(f"    {name:24s} coef={c['coef']:>10.5f}  p={c['p']:.4f}{sig}")
    else:
        print(f"  не откалибровано — {rr.get('reason')}")

    print("\n-- observed_spacing: фактическое расписание атлета между качественными тренировками "
          "одного типа (справочная аналитика, НЕ физиология — см. estimated_optimal_spacing ниже) --")
    for t, s in report["quality_session_spacing"].items():
        print(f"  {t:10s}: медиана {s['median_days']}д (IQR {s['p25_days']}–{s['p75_days']}д, n={s['n']})")

    print("\n-- estimated_optimal_spacing: интервал из adaptive response model при типичном load "
          "(ЭТО должно идти в построение плана, а не observed выше) --")
    for label, key in [("Интервалы/порог", "interval_threshold"), ("Длинная", "long")]:
        eo = report["estimated_optimal_spacing"].get(key, {})
        if eo.get("ok"):
            print(f"  {label:16s}: {eo['estimated_days_at_median_load']}д при медианном load={eo['median_load_used']} "
                  f"(R² источника={eo['r2_of_source_fit']})")
        else:
            print(f"  {label:16s}: не оценено — {eo.get('reason')}")

    ov = report["overload_gaps"]
    print(f"\n-- Пропуски бега >= {ov['gap_threshold_days']}д и ACWR перед ними (медиана ACWR за весь период: {ov['median_acwr_overall']}) --")
    if not ov["gaps"]:
        print(f"  пропусков >= {ov['gap_threshold_days']}д не найдено")
    for g in ov["gaps"]:
        flag = f"  [!] ACWR>={ov['acwr_threshold']} — вероятна перегрузка перед пропуском" if g["elevated_acwr"] else ""
        print(f"  {g['start_date']} .. {g['end_date']} ({g['gap_days']}д)  ACWR перед пропуском: {g['acwr_before']}{flag}")

    print("\n-- ACWR отдельно по категориям утомления (см. п.5a/п.7 — общий ACWR может быть в норме, "
          "даже если весь рост дала одна категория) --")
    for cat, r in report.get("overload_by_category", {}).items():
        if not r.get("ok"):
            print(f"  {cat:14s}: не откалибровано — {r.get('reason')}")
            continue
        print(f"  {cat:14s}: медиана ACWR={r['median_acwr']}  максимум={r['max_acwr']}  "
              f"дней с ACWR>=порога: {r['pct_days_elevated']}%")

    print("\n-- Дозозависимость объём/ACWR -> будущее изменение EF (volume_ef_response) --")
    ver = report.get("volume_ef_response", {})
    if not ver.get("ok"):
        print(f"  не откалибровано — {ver.get('reason')}")
    else:
        print(f"  n_weeks={ver['n_weeks_total']}  EF-типы={ver['ef_source_types']}")
        for label, key in (("Объём (скользящие 4 нед., км/нед)", "by_rolling_volume_km_per_week"),
                            ("ACWR", "by_acwr")):
            r = ver.get(key, {})
            if not r.get("ok"):
                print(f"  {label}: не откалибровано — {r.get('reason')}")
                continue
            print(f"  {label}: r={r['correlation_r']} (p~{r['correlation_p_approx']}, n={r['n_weeks']})")
            for b in r["bins"]:
                rng_key = [k for k in b if k.endswith("_range")][0]
                mean_key = [k for k in b if k.endswith("_mean")][0]
                delta_key = [k for k in b if k.startswith("mean_delta_ef")][0]
                print(f"    {b[rng_key][0]:>6.1f}..{b[rng_key][1]:<6.1f} (сред. {b[mean_key]:>6.1f}, n={b['n_weeks']:>2d}): "
                      f"Δ EF за горизонт = {b[delta_key]:+.4f}")
            print(f"    лучшая корзина: {r['best_bin']}")
            if r["decline_threshold"] is not None:
                print(f"    устойчивый переход в спад начиная с ~{r['decline_threshold']}")
            else:
                print("    устойчивого перехода в спад по крайним корзинам не обнаружено")

    print("\n-- Systemic fatigue по wellness-метрикам (HRV/RHR/Body Battery/стресс/сон — независимые; "
          "Training Readiness показан отдельно как сверка, не источник калибровки, см. п.8а) --")
    sysrep = report["systemic_fatigue"]
    if not sysrep.get("ok"):
        print(f"  не откалибровано — {sysrep.get('reason')}")
    else:
        print(f"  wellness-дней в базе: {sysrep['n_wellness_days']}")
        for col, r in sysrep["metrics"].items():
            if not r.get("ok"):
                print(f"  {r['label']:24s}: не откалибровано — {r.get('reason')}")
                continue
            warn = "  [!] уперлось в границу" if r.get("hit_bound") else ""
            indep = "" if r.get("independent") else "  (справочно, не в aggregate)"
            print(f"  {r['label']:24s}: tau_fatigue={r['tau_fatigue_days']:>5}д  k={r['k']:.5f}  "
                  f"baseline={r['baseline']}  R²={r['r2']}  (n={r['n_points']}){warn}  "
                  f"[{r.get('fit_quality')}, tau_used={r.get('tau_fatigue_used')}д]{indep}")
        agg = sysrep.get("aggregate", {})
        if agg.get("ok"):
            print(f"  -> AGGREGATE (только независимые метрики): tau_fatigue_used={agg['tau_fatigue_used']}д "
                  f"из {agg['n_metrics_used']} метрик {agg['metrics_used']}")
        else:
            print(f"  -> AGGREGATE: {agg.get('reason')} (дефолт {agg.get('tau_fatigue_used')}д)")
        trc = sysrep.get("training_readiness_check", {})
        if trc.get("fit_quality") is not None:
            diff = trc.get("diff_vs_independent_aggregate_days")
            diff_str = f"{diff}д" if diff is not None else "н/д (aggregate недоступен)"
            print(f"  -> Training Readiness (сверка): tau={trc['tau_fatigue_days']}д [{trc['fit_quality']}], "
                  f"расхождение с aggregate: {diff_str}")

    print("\n-- Сопоставление осей фита с категориями FatigueState модели (systemic/muscular/"
          "neuromuscular/orthopedic, см. adaptive_model_architecture.md) --")
    for cat, r in report.get("fatigue_category_mapping", {}).items():
        if cat == "unused_axis_tau_fatigue":
            continue
        if r.get("ok"):
            src = r.get("source_axis", r.get("source", "?"))
            print(f"  {cat:14s}: tau_fatigue_used={r['tau_fatigue_used']}д  (источник: {src})")
        else:
            print(f"  {cat:14s}: не откалибровано — {r.get('reason')}")

    ortho = report.get("orthopedic_fit", {})
    if ortho.get("ok"):
        print(f"  -> orthopedic direct fit (impact_load): tau_adapt={ortho['tau_adapt_days']}д "
              f"tau_fatigue={ortho['tau_fatigue_days']}д R²={ortho['r2']} [{ortho['fit_quality']}]")
    else:
        print(f"  -> orthopedic direct fit (impact_load): недоступен — {ortho.get('reason')}")
    bb = report.get("orthopedic_body_battery_diagnostic", {})
    if bb:
        bb_str = ", ".join(f"{t}={v['avg_difference_body_battery']} (n={v['n']})" for t, v in sorted(bb.items()))
        print(f"  -> Body Battery по типам (справочно, не в фите): {bb_str}")

    print("\n-- Не откалибровано этим скриптом --")
    for line in report["not_calibrated"]:
        print(f"  - {line}")


def maybe_plot(report, acts_by_axis_load, day0, n_days):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("\n(matplotlib не установлен — графики пропущены; pip install matplotlib для --plot)")
        return
    t = [day0 + datetime.timedelta(days=i) for i in range(n_days)]
    for axis, r in report["axes"].items():
        if not r.get("ok"):
            continue
        load_arr = acts_by_axis_load[axis]
        fit_arr = recursive_convolution(load_arr, r["tau_adapt_days"])
        fat_arr = recursive_convolution(load_arr, r["tau_fatigue_days"])
        pred = r["baseline_ef"] + r["k1"] * fit_arr - r["k2"] * fat_arr
        fig, ax = plt.subplots(figsize=(11, 4))
        ax.plot(t, pred, label="EF_pred (adaptation - fatigue)", color="#2b6cb0")
        ax.set_title(f"Ось {axis}: tau_adapt={r['tau_adapt_days']}д, tau_fatigue={r['tau_fatigue_days']}д, R²={r['r2']}")
        ax.set_ylabel("EF (м/мин на уд.пульса)")
        ax.legend()
        fig.tight_layout()
        out = f"calibration_plot_{axis}.png"
        fig.savefig(out, dpi=110)
        plt.close(fig)
        print(f"График сохранён: {out}")


def main():
    ap = argparse.ArgumentParser(description="Калибровка коэффициентов адаптивной модели по выгрузке тренировок Garmin (SQLite).")
    ap.add_argument("--db", default="garmin_running.db", help="путь к SQLite-базе от garmin_activities_export.py")
    ap.add_argument("--max-hr", type=int, required=True, help="максимальный пульс атлета")
    ap.add_argument("--rest-hr", type=int, required=True, help="пульс покоя атлета")
    ap.add_argument("--sex", choices=["m", "f"], default="m", help="пол (влияет на коэффициенты формулы TRIMP, если --threshold-hr не задан)")
    ap.add_argument("--threshold-hr", type=int, help="пульс на ПАНО — если задан, load считается по нему (точнее лично для атлета, "
                                                        "см. докстринг п.1), вместо TRIMP по %%HRR")
    ap.add_argument("--weights-json", help="путь к JSON с таблицей весов STIMULUS_MAP (переопределяет DEFAULT_STIMULUS_MAP целиком)")
    ap.add_argument("--out", default="calibration_profile.json", help="куда сохранить JSON-отчёт")
    ap.add_argument("--plot", action="store_true", help="сохранить PNG-графики fitness/fatigue по осям (нужен matplotlib)")
    ap.add_argument("--no-seasonal", action="store_true", help="отключить сезонную коррекцию EF по дню года (см. докстринг п.6)")
    ap.add_argument("--gap-days", type=int, default=10, help="от скольки дней подряд без бега считать это 'пропуском' для анализа перегрузки (по умолчанию 10)")
    ap.add_argument("--acwr-threshold", type=float, default=1.5, help="порог ACWR (острая/хроническая нагрузка), выше которого пропуск помечается как вероятная перегрузка (по умолчанию 1.5, Gabbett 2016)")
    ap.add_argument("--history-window-days", type=int, default=HISTORY_WINDOW_DAYS_DEFAULT,
                     help=f"окно (дни) ДО тренировки, за которое считается накопленная нагрузка её категории утомления "
                          f"для эффекта 'несколько подряд' в recovery_nonlinearity (см. докстринг п.5a, по умолчанию {HISTORY_WINDOW_DAYS_DEFAULT})")
    ap.add_argument("--no-wellness", action="store_true", help="не использовать таблицу wellness (HRV/RHR/Body Battery/стресс/training readiness) даже если она есть в БД (см. докстринг п.8)")
    ap.add_argument("--no-garmin-threshold", action="store_true",
                     help="не использовать историю ПАНО от Garmin (таблица lactate_threshold) даже если она есть — "
                          "откатиться на --threshold-hr (если задан) или TRIMP по %%HRR (см. докстринг п.1)")
    ap.add_argument("--no-cross-training", action="store_true",
                     help="не учитывать вело/лыжи/плавание/силовые в суммарной нагрузке (ACWR, systemic-фит) "
                          "даже если таблица cross_activities есть в БД (см. докстринг п.9)")
    ap.add_argument("--no-grade-adjust", action="store_true",
                     help="отключить grade-adjusted поправку EF по elevation_gain_m/elevation_loss_m "
                          "(см. докстринг п.6б/ef_grade_adjusted) — тредмил в поправку и так не попадает")
    args = ap.parse_args()

    stimulus_map = DEFAULT_STIMULUS_MAP
    if args.weights_json:
        with open(args.weights_json, "r", encoding="utf-8") as f:
            stimulus_map = json.load(f)

    report = build_report(args.db, args.max_hr, args.rest_hr, args.sex, stimulus_map,
                           threshold_hr=args.threshold_hr, seasonal=not args.no_seasonal,
                           gap_days=args.gap_days, acwr_threshold=args.acwr_threshold,
                           use_wellness=not args.no_wellness,
                           use_garmin_threshold=not args.no_garmin_threshold,
                           use_cross_training=not args.no_cross_training,
                           history_window_days=args.history_window_days,
                           grade_adjust=not args.no_grade_adjust)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print_summary(report)
    print(f"\nПолный отчёт: {args.out}")

    if args.plot:
        acts = load_activities(args.db)
        dates = [datetime.date.fromisoformat(a["date"]) for a in acts]
        day0, day1 = min(dates), max(dates)
        n_days = (day1 - day0).days + 1
        lt_series = load_lactate_threshold(args.db) if not args.no_garmin_threshold else []
        resolver = make_threshold_resolver(args.threshold_hr, lt_series)
        daily_load = build_daily_axis_load(acts, stimulus_map, args.rest_hr, args.max_hr, args.sex, day0, n_days, resolver)
        maybe_plot(report, daily_load, day0, n_days)


if __name__ == "__main__":
    main()
