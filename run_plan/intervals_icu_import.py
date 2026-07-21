#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Импорт плана (plan.json, schema-1 из калькулятора) в intervals.icu
как запланированные тренировки (события категории WORKOUT в календаре).

Аутентификация (intervals.icu → Settings → Developer):
  - API key
  - Athlete ID (вида i123456)
Передать через переменные окружения или флаги:
  INTERVALS_API_KEY / --key
  INTERVALS_ATHLETE_ID / --athlete

Примеры:
  python intervals_icu_import.py plan.json --dry-run
  INTERVALS_API_KEY=xxxx INTERVALS_ATHLETE_ID=i123456 python intervals_icu_import.py plan.json
  python intervals_icu_import.py plan.json --skip-cross fitness,pool
  python intervals_icu_import.py plan.json --clear        # удалить ранее загруженные события этого плана (по тегу и датам)

Без зависимостей — только стандартная библиотека (urllib).
"""

import os, sys, json, base64, argparse, datetime, urllib.request, urllib.error

BASE = "https://intervals.icu/api/v1"

# kind / gtype -> тип активности intervals.icu
TYPE_RUN = "Run"
TYPE_STR = "WeightTraining"
CROSS_TYPE = {
    "cycling":           "Ride",
    "lap_swimming":      "Swim",
    "swimming":          "Swim",
    "other":             "Workout",   # лыжи и прочий кросс без своего типа
    "cardio_training":   "Workout",
    "strength_training": "WeightTraining",
}


# ---------- преобразование шагов плана в текст тренировки intervals.icu ----------

def _pace_sec(p):
    m, s = p.split(":")
    return int(m) * 60 + int(s)


def _fmt_dur(sec):
    sec = int(round(sec))
    if sec >= 60 and sec % 60 == 0:
        return "%dm" % (sec // 60)
    return "%ds" % sec


def _step_lines(st, indent="- "):
    """Одна строка (или блок для повторов) в синтаксисе intervals.icu."""
    if st.get("t") == "repeat":
        n = int(st.get("n", 1))
        out = ["%dx" % n]
        for sub in st.get("steps", []):
            out += ["  " + ln for ln in _step_lines(sub)]
        return out
    end = st.get("end")
    v = st.get("v", 0) or 0
    tg = st.get("tg", {}) or {}
    if end == "distance":
        # переводим дистанцию во время по целевому темпу (intervals.icu не путает мин/метры)
        if "pace" in tg:
            avg = (_pace_sec(tg["pace"][0]) + _pace_sec(tg["pace"][1])) / 2.0
            dur = v / 1000.0 * avg
        else:
            dur = v / 1000.0 * 360  # запасной темп ~6:00/км
        dur_s = _fmt_dur(dur)
    else:
        dur_s = _fmt_dur(v)
    if "pace" in tg:
        target = "%s-%s/km" % (tg["pace"][0], tg["pace"][1])   # темп — основной ориентир
    elif "hr" in tg:
        target = "Z%d" % max(1, min(5, int(tg["hr"])))          # пульсовая зона
    else:
        target = "Z2"
    return [indent + "%s %s" % (dur_s, target)]


def run_description(w):
    lines = []
    note = w.get("note")
    if note:
        lines.append("# " + str(note))
    for st in w.get("steps", []):
        lines += _step_lines(st)
    return "\n".join(lines)


def event_for(w, skip_cross):
    """Возвращает тело события intervals.icu или None (если кросс исключён фильтром)."""
    k = w.get("kind")
    date = w["date"]
    base = {
        "start_date_local": date + "T00:00:00",
        "category": "WORKOUT",
        "name": w["name"],
    }
    if k == "cross":
        sp = str(w.get("sport", "")).lower()
        gt = str(w.get("gtype", "")).lower()
        if skip_cross and ("all" in skip_cross or "cross" in skip_cross
                           or sp in skip_cross or gt in skip_cross):
            return None
        base["type"] = CROSS_TYPE.get(w.get("gtype"), "Workout")
        base["description"] = w.get("desc", "") or ""
        if w.get("mins"):
            base["moving_time"] = int(w["mins"]) * 60
        return base
    if k == "str":
        base["type"] = TYPE_STR
        base["description"] = w.get("desc", "") or ""
        if w.get("mins"):
            base["moving_time"] = int(w["mins"]) * 60
        return base
    # бег
    base["type"] = TYPE_RUN
    base["description"] = run_description(w)
    return base


# ---------- сеть ----------

def _auth_header(key):
    tok = base64.b64encode(("API_KEY:" + key).encode()).decode()
    return "Basic " + tok


def _request(method, url, key, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", _auth_header(key))
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw.strip() else None
    except urllib.error.HTTPError as e:
        msg = e.read().decode(errors="replace")
        sys.exit("Ошибка API intervals.icu %s %s: %s\n%s" % (method, url, e.code, msg[:400]))
    except urllib.error.URLError as e:
        sys.exit("Сеть недоступна (%s). Проверьте подключение/настройки." % e)


# ---------- загрузка плана ----------

def load_plan(path, skip_cross=None):
    skip_cross = set(x.strip().lower() for x in (skip_cross or []) if x.strip())
    try:
        with open(path, encoding="utf-8") as f:
            plan = json.load(f)
    except FileNotFoundError:
        sys.exit("Файл не найден: " + path)
    except json.JSONDecodeError as e:
        sys.exit("Не разобрал JSON %s: %s" % (path, e))
    meta = plan.get("meta", {}) or {}
    tag = meta.get("tag") or "[GEN]"
    events, skipped = [], 0
    for w in plan.get("workouts", []):
        ev = event_for(w, skip_cross)
        if ev is None:
            skipped += 1
            continue
        events.append(ev)
    if skip_cross and skipped:
        print("Пропущено кросс-тренировок по фильтру (%s): %d" % (",".join(sorted(skip_cross)), skipped))
    if not events:
        sys.exit("В плане нет тренировок для импорта.")
    events.sort(key=lambda e: e["start_date_local"])
    return tag, events, meta


# ---------- main ----------

def main():
    ap = argparse.ArgumentParser(description="Импорт плана в intervals.icu")
    ap.add_argument("plan", help="путь к plan.json")
    ap.add_argument("--key", default=os.environ.get("INTERVALS_API_KEY", ""),
                    help="API key intervals.icu (или переменная INTERVALS_API_KEY)")
    ap.add_argument("--athlete", default=os.environ.get("INTERVALS_ATHLETE_ID", ""),
                    help="Athlete ID, напр. i123456 (или INTERVALS_ATHLETE_ID)")
    ap.add_argument("--skip-cross", default="",
                    help="исключить кросс: sport (ski,pool,bike,fitness) или тип (cycling,swimming,other); 'all' — весь кросс")
    ap.add_argument("--dry-run", action="store_true", help="показать, что будет создано, без отправки")
    ap.add_argument("--clear", action="store_true",
                    help="удалить ранее загруженные события этого плана (по тегу и диапазону дат) перед импортом")
    args = ap.parse_args()

    tag, events, meta = load_plan(args.plan, (args.skip_cross or "").split(","))
    d0 = events[0]["start_date_local"][:10]
    d1 = events[-1]["start_date_local"][:10]
    runs = sum(1 for e in events if e["type"] == "Run")
    strs = sum(1 for e in events if e["type"] == "WeightTraining")
    cross = len(events) - runs - strs
    print("План: %s | тег %s" % (meta.get("name", "—"), tag))
    print("Будет создано: %d (бег %d, силовые %d, кросс %d); %s … %s\n" % (len(events), runs, strs, cross, d0, d1))

    if args.dry_run:
        run_ex = next((e for e in events if e["type"] == "Run"), None)
        if run_ex:
            print("### БЕГ", run_ex["name"], "\n" + run_ex["description"] + "\n")
        cx = next((e for e in events if e["type"] not in ("Run", "WeightTraining")), None)
        if cx:
            print("### КРОСС", cx["name"], "(%s)" % cx["type"], "\n" + (cx.get("description") or "") + "\n")
        print("Сухой прогон — ничего не отправлено. Уберите --dry-run для загрузки.")
        return

    if not args.key or not args.athlete:
        sys.exit("Нужны --key и --athlete (или INTERVALS_API_KEY / INTERVALS_ATHLETE_ID). "
                 "Возьмите их в intervals.icu → Settings → Developer.")

    ath = args.athlete
    ev_url = "%s/athlete/%s/events" % (BASE, ath)

    if args.clear:
        lst = _request("GET", "%s?oldest=%s&newest=%s&category=WORKOUT" % (ev_url, d0, d1), args.key) or []
        gone = 0
        for e in lst:
            if str(e.get("name", "")).startswith(tag):
                _request("DELETE", "%s/%s" % (ev_url, e["id"]), args.key)
                gone += 1
        print("Удалено ранее загруженных событий этого плана: %d" % gone)

    ok = 0
    for e in events:
        _request("POST", ev_url, args.key, e)
        ok += 1
        if ok % 10 == 0:
            print("  создано %d/%d…" % (ok, len(events)))
    print("Готово: создано %d запланированных тренировок в intervals.icu (%s … %s)." % (ok, d0, d1))


if __name__ == "__main__":
    main()
