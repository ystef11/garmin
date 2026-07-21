#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Импорт плана из plan.json (сгенерирован run_plan_calculator.html) в Garmin Connect
как нативные СТРУКТУРИРОВАННЫЕ тренировки + расписание по датам.

Формат plan.json:
  {"meta":{"tag":"[GEN]","name":"...","marathon":"2026-09-27","schema":1},
   "workouts":[
     {"date":"2026-07-06","kind":"run","name":"[GEN] Н1 Пн Восстановительный",
      "steps":[{"t":"interval","end":"time","v":2100,"tg":{"bpm":[116,144],"z":1},"d":"Восстановительный"}, ...],
      "note":"Питание ~55 г/ч ..."},
     {"date":"2026-07-06","kind":"str","name":"[GEN] Н1 Пн Силовая A","desc":"...","mins":25},
     {"date":"2026-07-07","kind":"cross","gtype":"cycling","name":"[GEN] Н1 Вт Велосипед","desc":"...","mins":75}]}
Шаги: t: warmup|cooldown|interval|recovery|other|repeat; end: time(сек)|distance(м);
цели tg: {"hr":1..5} зона Garmin | {"bpm":[lo,hi]} конкретный пульс | {"pace":["м:сс","м:сс"]} | {"none":1}.

Обход блокировки Garmin (#222): браузерный User-Agent на garth.client.sess до login.
Проверено на garth==0.6.3 (0.7.0 — первая версия с ошибкой, см. discussions/222).

ПОДГОТОВКА
  pip install garth==0.6.3
АВТОРИЗАЦИЯ (PowerShell):
  $env:GARMIN_EMAIL="you@mail.com"
  $env:GARMIN_PASSWORD="********"
  При 2FA скрипт спросит код. Токен сохраняется ПО-УЧЁТОЧНО:
  C:/Users/<ты>/.garth/<логин garmin>/ — можно держать несколько аккаунтов.
  Выбор учётки: --account you@mail.com (или через GARMIN_EMAIL).
  Если сохранена ровно одна учётка — подхватится сама.
  Старый токен прямо в C:/Users/<ты>/.garth тоже распознаётся.

ЗАПУСК
  python garmin_plan_import.py plan.json --account you@mail.com --dry-run
  python garmin_plan_import.py plan.json --dry-run     # печать примеров, без отправки
  python garmin_plan_import.py plan.json --test        # только первая неделя (7 дней от первой даты)
  python garmin_plan_import.py plan.json --clear       # удалить все с тегом из meta.tag и выйти
  python garmin_plan_import.py plan.json --clear-past  # удалить только уже прошедшие (дата < сегодня)
  python garmin_plan_import.py plan.json --clear-past --before 2026-08-01
  python garmin_plan_import.py plan.json               # весь план
"""

import os, sys, json, time, datetime, argparse
try: sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception: pass

TOKENS_BASE = os.path.expanduser("~/.garth")

def _has_tokens(d):
    return os.path.isdir(d) and any(f.startswith("oauth") for f in os.listdir(d))

def saved_accounts():
    if not os.path.isdir(TOKENS_BASE): return []
    return sorted(a for a in os.listdir(TOKENS_BASE) if _has_tokens(os.path.join(TOKENS_BASE, a)))

def pick_account(cli_account):
    """логин garmin: --account > GARMIN_EMAIL > единственная сохранённая учётка > legacy ~/.garth"""
    acct = cli_account or os.environ.get("GARMIN_EMAIL")
    if acct: return acct.strip().lower(), os.path.join(TOKENS_BASE, acct.strip().lower())
    accs = saved_accounts()
    if len(accs) == 1:
        return accs[0], os.path.join(TOKENS_BASE, accs[0])
    if len(accs) > 1:
        sys.exit("Сохранено несколько учёток Garmin:\n  " + "\n  ".join(accs) +
                 "\nУкажи, какую использовать: --account <логин> (или GARMIN_EMAIL).")
    if _has_tokens(TOKENS_BASE):  # старое расположение
        return None, TOKENS_BASE
    return None, None
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

SPORT_RUN = {"sportTypeId": 1, "sportTypeKey": "running"}
SPORT_STR = {"sportTypeId": 5, "sportTypeKey": "strength_training"}
SPORT_BIKE   = {"sportTypeId": 2, "sportTypeKey": "cycling"}
SPORT_SWIM   = {"sportTypeId": 4, "sportTypeKey": "swimming"}
SPORT_OTHER  = {"sportTypeId": 3, "sportTypeKey": "other"}   # в аккаунте нет "кардио" — лыжи/прочий кросс сюда
# gtype из плана -> тип тренировки Garmin. Если тип/локаль отличается — уточните ID дампом (см. README ниже).
CROSS_SPORT = {
    "cycling":           SPORT_BIKE,
    "lap_swimming":      SPORT_SWIM,    # плавание в бассейне
    "swimming":          SPORT_SWIM,
    "cardio_training":   SPORT_OTHER,   # обратная совместимость
    "other":             SPORT_OTHER,   # лыжи и прочий кросс без своего типа
    "strength_training": SPORT_STR,
}
STEP = {k: {"stepTypeId": i, "stepTypeKey": k} for i, k in
        {1:"warmup",2:"cooldown",3:"interval",4:"recovery",5:"rest",6:"repeat",7:"other"}.items()}
END = {"time":{"conditionTypeId":2,"conditionTypeKey":"time"},
       "distance":{"conditionTypeId":3,"conditionTypeKey":"distance"}}
TT = {"none":{"workoutTargetTypeId":1,"workoutTargetTypeKey":"no.target"},
      "hr":  {"workoutTargetTypeId":4,"workoutTargetTypeKey":"heart.rate.zone"},
      "pace":{"workoutTargetTypeId":6,"workoutTargetTypeKey":"pace.zone"}}

def pace_ms(p):
    try:
        m, s = str(p).strip().split(":"); return round(1000.0/(int(m)*60+int(s)), 4)
    except Exception:
        sys.exit(f"Не разобрал темп «{p}» — ожидается формат м:сс (например 4:37).")

def conv_target(tg):
    tg = tg or {}
    if "pace" in tg:
        a, b = pace_ms(tg["pace"][0]), pace_ms(tg["pace"][1])
        return {"targetType":TT["pace"],"targetValueOne":min(a,b),"targetValueTwo":max(a,b),"zoneNumber":None}
    if "bpm" in tg:
        lo, hi = tg["bpm"]
        return {"targetType":TT["hr"],"targetValueOne":int(lo),"targetValueTwo":int(hi),"zoneNumber":None}
    if "hr" in tg:
        return {"targetType":TT["hr"],"zoneNumber":int(tg["hr"])}
    return {"targetType":TT["none"]}

def conv_step(s):
    if s.get("t") == "repeat":
        return {"type":"RepeatGroupDTO","stepType":STEP["repeat"],
                "numberOfIterations":int(s["n"]),"smartRepeat":False,
                "workoutSteps":[conv_step(x) for x in s["steps"]]}
    o = {"type":"ExecutableStepDTO","stepType":STEP.get(s["t"], STEP["interval"]),
         "endCondition":END[s["end"]],"endConditionValue":float(s["v"]),
         **conv_target(s.get("tg"))}
    if s.get("d"): o["description"] = s["d"]
    return o

def order_steps(steps, c):
    for s in steps:
        s["stepOrder"] = c[0]; c[0] += 1
        if s["type"] == "RepeatGroupDTO": order_steps(s["workoutSteps"], c)
    return steps

# ---- человекочитаемый комментарий из шагов ----
def _ms_pace(ms): s = round(1000/ms); return f"{s//60}:{s%60:02d}"
def _amt(s):
    ec = s["endCondition"]["conditionTypeKey"]; v = s["endConditionValue"]
    if ec == "time":
        v = int(v)
        if v < 60: return f"{v}с"
        return f"{v//60} мин" if v % 60 == 0 else f"{v//60}:{v%60:02d}"
    return f"{v/1000:g} км"
def _tgt(s):
    t = s.get("targetType", {}).get("workoutTargetTypeKey")
    if t == "heart.rate.zone":
        if s.get("zoneNumber"): return f"Z{s['zoneNumber']}"
        return f"{s.get('targetValueOne')}–{s.get('targetValueTwo')} уд"
    if t == "pace.zone":
        a, b = s.get("targetValueOne"), s.get("targetValueTwo")
        return f"{_ms_pace(max(a,b))}-{_ms_pace(min(a,b))}/км"
    return ""
def _prose(s):
    if s["type"] == "RepeatGroupDTO":
        return f"{s['numberOfIterations']}×(" + "; ".join(_prose(x) for x in s["workoutSteps"]) + ")"
    lbl = s.get("description"); body = " ".join(x for x in [_amt(s), _tgt(s)] if x)
    return f"{lbl} {body}".strip() if lbl else body
def describe(steps): return " · ".join(_prose(s) for s in steps)

def run_json(w):
    steps = order_steps([conv_step(s) for s in w["steps"]], [1])
    desc = describe(steps)
    if w.get("note"): desc = (w["note"] + " || " + desc)
    return {"sportType":SPORT_RUN,"workoutName":w["name"][:79],"description":desc[:1024],
            "workoutSegments":[{"segmentOrder":1,"sportType":SPORT_RUN,"workoutSteps":steps}]}

def str_json(w):
    st = {"type":"ExecutableStepDTO","stepType":STEP["other"],
          "endCondition":END["time"],"endConditionValue":float(int(w.get("mins",20))*60),
          "targetType":TT["none"],"description":"Силовая","stepOrder":1}
    return {"sportType":SPORT_STR,"workoutName":w["name"][:79],"description":str(w.get("desc",""))[:1024],
            "workoutSegments":[{"segmentOrder":1,"sportType":SPORT_STR,"workoutSteps":[st]}]}

def cross_json(w):
    sport = CROSS_SPORT.get(w.get("gtype"), SPORT_OTHER)
    st = {"type":"ExecutableStepDTO","stepType":STEP.get("interval", STEP["other"]),
          "endCondition":END["time"],"endConditionValue":float(int(w.get("mins",45))*60),
          "targetType":TT["none"],"description":str(w.get("desc",""))[:512],"stepOrder":1}
    wk = {"sportType":sport,"workoutName":w["name"][:79],
          "description":str(w.get("desc",""))[:1024],
          "workoutSegments":[{"segmentOrder":1,"sportType":sport,"workoutSteps":[st]}]}
    if sport is SPORT_SWIM:                      # Garmin ждёт длину бассейна для плавания
        wk["poolLength"] = 50.0
        wk["poolLengthUnit"] = {"unitId":1,"unitKey":"meter","factor":100.0}
    return wk

def load_plan(path, skip_cross=None):
    skip_cross = set(x.strip().lower() for x in (skip_cross or []))
    try:
        with open(path, encoding="utf-8") as f: plan = json.load(f)
    except FileNotFoundError:
        sys.exit(f"Файл не найден: {path}")
    except json.JSONDecodeError as e:
        sys.exit(f"Не разобрал JSON {path}: {e}")
    tag = plan.get("meta", {}).get("tag") or "[GEN]"
    items = []
    skipped = 0
    for w in plan.get("workouts", []):
        d = datetime.date.fromisoformat(w["date"])
        k = w.get("kind")
        if k == "cross" and skip_cross and (
                "all" in skip_cross or "cross" in skip_cross
                or str(w.get("sport","")).lower() in skip_cross
                or str(w.get("gtype","")).lower() in skip_cross):
            skipped += 1
            continue
        wk = run_json(w) if k=="run" else cross_json(w) if k=="cross" else str_json(w)
        items.append((d, w["name"][:79], wk))
    if skip_cross and skipped: print(f"Пропущено кросс-тренировок по фильтру ({','.join(sorted(skip_cross))}): {skipped}")
    if not items: sys.exit("В плане нет тренировок.")
    items.sort(key=lambda x: x[0])
    return tag, items, plan.get("meta", {})  # items — весь план; --test фильтрует копию в main()

def connect(cli_account=None):
    import garth
    garth.client.sess.headers.update({"User-Agent": UA})
    acct, tdir = pick_account(cli_account)
    if tdir and _has_tokens(tdir):
        try:
            garth.resume(tdir); _ = garth.client.username
            print(f"Вход по сохранённому токену: {acct or 'legacy ~/.garth'}")
            garth.client.sess.headers.update({"User-Agent": UA})
            return garth
        except Exception as e:
            print(f"Токен {tdir} не подошёл ({e}) — пробую войти заново.")
    email = (cli_account or os.environ.get("GARMIN_EMAIL") or acct)
    pwd = os.environ.get("GARMIN_PASSWORD")
    if not email or not pwd:
        sys.exit("Нет валидного токена. Задай GARMIN_EMAIL и GARMIN_PASSWORD "
                 "(и/или --account <логин>).")
    email = email.strip().lower()
    garth.client.sess.headers.update({"User-Agent": UA})
    garth.login(email, pwd, prompt_mfa=lambda: input("Код 2FA: ").strip())
    save_dir = os.path.join(TOKENS_BASE, email)
    os.makedirs(save_dir, exist_ok=True)
    garth.save(save_dir); print("Токен сохранён в", save_dir)
    garth.client.sess.headers.update({"User-Agent": UA})
    return garth

def main():
    ap = argparse.ArgumentParser(description="Импорт plan.json в Garmin Connect")
    ap.add_argument("plan", help="путь к plan.json из калькулятора")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--test", action="store_true", help="только первая неделя плана")
    ap.add_argument("--clear", action="store_true", help="удалить все тренировки с тегом плана")
    ap.add_argument("--clear-past", action="store_true",
                    help="удалить только тренировки с датой раньше сегодняшней")
    ap.add_argument("--before", metavar="ГГГГ-ММ-ДД",
                    help="граничная дата для --clear-past (по умолчанию сегодня)")
    ap.add_argument("--skip-cross", metavar="ТИПЫ", default="",
                    help="не импортировать заданные кросс-тренировки: sport (ski,pool,bike,fitness) "
                         "или тип Garmin (cycling,swimming,other) через запятую; 'all' — весь кросс")
    ap.add_argument("--account", metavar="ЛОГИН",
                    help="логин Garmin: токен берётся/кладётся в ~/.garth/<логин>")
    args = ap.parse_args()

    tag, all_items, meta = load_plan(args.plan, (args.skip_cross or '').split(','))
    items = all_items
    if args.test:
        wk_end = all_items[0][0] + datetime.timedelta(days=7)
        items = [x for x in all_items if x[0] < wk_end]

    if args.clear_past:
        try:
            cutoff = datetime.date.fromisoformat(args.before) if args.before else datetime.date.today()
        except ValueError:
            sys.exit(f"Неверная дата в --before: {args.before} (нужен формат ГГГГ-ММ-ДД)")

    if args.dry_run:
        runs  = sum(1 for _,_,w in items if w["sportType"]["sportTypeKey"] == "running")
        strs  = sum(1 for _,_,w in items if w["sportType"]["sportTypeKey"] == "strength_training")
        cross = len(items) - runs - strs
        print(f"План: {meta.get('name','—')} | тег {tag}")
        print(f"Будет создано: {len(items)} (бег {runs}, силовые {strs}, кросс {cross}); "
              f"{items[0][0].isoformat()} … {items[-1][0].isoformat()}\n")
        shown = 0
        for d, name, w in items:
            if w["sportType"]["sportTypeKey"] == "running" and shown < 3:
                print("###", d.isoformat(), name, "\nКОММЕНТАРИЙ:", w["description"], "\n"); shown += 1
        st = next((w for _,_,w in items if w["sportType"]["sportTypeKey"] == "strength_training"), None)
        if st: print("### СИЛОВАЯ", st["workoutName"], "\nКОММЕНТАРИЙ:", st["description"], "\n")
        cx = next((w for _,_,w in items if w["sportType"]["sportTypeKey"] not in ("running","strength_training")), None)
        if cx: print("### КРОСС", cx["workoutName"], f"({cx['sportType']['sportTypeKey']})", "\nКОММЕНТАРИЙ:", cx["description"], "\n")
        last = items[-1][2]
        print("### ФИНАЛ", last["workoutName"], "\nJSON:",
              json.dumps(last, ensure_ascii=False, indent=1)[:800], "...")
        return

    garth = connect(args.account)
    def post(p, body): return garth.connectapi(p, method="POST", json=body)
    def get(p):        return garth.connectapi(p)
    def delete(p):     return garth.connectapi(p, method="DELETE")

    if args.clear or args.clear_past:
        plan_dates = {name: d for d, name, _ in all_items}  # всегда полный план, независимо от --test
        lst = get("/workout-service/workouts?start=0&limit=999") or []
        mine = [w for w in lst if str(w.get("workoutName","")).startswith(tag)]
        if args.clear_past:
            sel, unknown = [], []
            for w in mine:
                d = plan_dates.get(w.get("workoutName"))
                if d is None: unknown.append(w)
                elif d < cutoff: sel.append((d, w))
            sel.sort(key=lambda x: x[0])
            print(f"Найдено с тегом {tag}: {len(mine)}; прошедших (до {cutoff.isoformat()}): {len(sel)}")
            if unknown:
                print(f"Пропущено (нет в этом plan.json, дату не определить): {len(unknown)}")
                for w in unknown: print("  ?", w.get("workoutName"))
        else:
            sel = [(plan_dates.get(w.get("workoutName")), w) for w in mine]
            foreign = sum(1 for d, _ in sel if d is None)
            print(f"Найдено с тегом {tag}: {len(mine)}"
                  + (f" (из них {foreign} нет в этом plan.json — возможно, другой план с тем же тегом!)" if foreign else ""))
        for d, w in sel:
            try:
                delete(f"/workout-service/workout/{w['workoutId']}")
                print(f"  удалено: {(d.isoformat()+'  ') if d else ''}{w['workoutName']}")
            except Exception as e:
                print(f"  FAIL удаления: {w.get('workoutName')} -> {e}")
            time.sleep(0.2)
        print("Очистка завершена."); return

    try:
        existing = [w for w in (get("/workout-service/workouts?start=0&limit=999") or [])
                    if str(w.get("workoutName","")).startswith(tag)]
        if existing:
            print(f"ВНИМАНИЕ: в Garmin уже есть {len(existing)} тренировок с тегом {tag} — "
                  f"повторный импорт создаст дубли. Обычно сначала нужен --clear.\n")
    except Exception:
        pass
    runs = sum(1 for _,_,w in items if w["sportType"]["sportTypeKey"] == "running")
    print(f"К созданию: {len(items)} (бег {runs}, силовые {len(items)-runs})"
          f"{' — только первая неделя' if args.test else ''}\n")
    ok = fail = 0
    for d, name, wk in items:
        try:
            res = post("/workout-service/workout", wk)
            wid = res.get("workoutId")
            post(f"/workout-service/schedule/{wid}", {"date": d.isoformat()})
            print(f"OK   {d.isoformat()}  {name}  (id={wid})"); ok += 1
            time.sleep(0.4)
        except Exception as e:
            print(f"FAIL {d.isoformat()}  {name} -> {e}"); fail += 1
    print(f"\nГотово: {ok} создано, {fail} с ошибкой. "
          "Проверь Garmin Connect → Тренировки и Календарь, синхронизируй часы.")

if __name__ == "__main__":
    main()
