#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Импорт ПЛАНА «10 км · 06.09 и 27.09.2026 (жена)» напрямую в Garmin Connect как нативные
СТРУКТУРИРОВАННЫЕ тренировки (их можно редактировать в Connect) + расписание по датам.
ОФП/силовые — отдельными тренировками strength_training в свои дни (Пн/Пт), не более одной
тренировки в день. К каждой тренировке добавляется комментарий (описание) и подписи к шагам
(видны на часах). Дни отдыха не создаются.

Обход блокировки Garmin (#222): браузерный User-Agent ставится на garth.client.sess до login.
Проверено на garth==0.6.3 (0.7.0 — первая версия с ошибкой, см. discussions/222).

ПОДГОТОВКА
  pip install garth==0.6.3
АВТОРИЗАЦИЯ (PowerShell):
  $env:GARMIN_EMAIL="you@mail.com"
  $env:GARMIN_PASSWORD="********"
АВТОРИЗАЦИЯ (cmd):
  set GARMIN_EMAIL="you@mail.com"
  set GARMIN_PASSWORD="********"

  При 2FA скрипт спросит код; токен ляжет в C:/Users/<ты>/.garth.

ЗАПУСК
  python wife_10k_garmin_import.py --dry-run     # печать примеров, без отправки
  python wife_10k_garmin_import.py --test        # только НЕДЕЛЯ 1
  python wife_10k_garmin_import.py --clear       # удалить ранее созданные ([W10K]) и выйти
  python wife_10k_garmin_import.py               # весь план (рекомендуется сначала --clear)

Зоны (ПАНО 184): Z1 восстановление | Z2 лёгкий/длинный | Z4 порог | Z5 МПК/темп 10к.
Темпы (мин/км): порог 5:30–5:40 (острее 5:25–5:35) · интервалы/темп10к 5:10–5:20 ·
освежающие 400 ≈4:55–5:00 · старт 06.09 ≈5:38–5:45 · старт 27.09 ≈5:30–5:38.
Внимание: Garmin показывает ПАНО 5:21/км — это быстрее реальных забегов; на пороге/интервалах
ориентир прежде всего ПУЛЬС (178–190) и ощущение, темп — следствие. Старт 06.09 откалибрует цели.
"""

import os, sys, time, datetime, argparse

TAG = "[W10K]"
START = datetime.date(2026, 6, 22)          # пн, неделя 1 (текущая)
DOW = ["Пн","Вт","Ср","Чт","Пт","Сб","Вс"]
TOKENS = os.path.expanduser("~/.garth")
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

SPORT_RUN = {"sportTypeId": 1, "sportTypeKey": "running"}
SPORT_STR = {"sportTypeId": 5, "sportTypeKey": "strength_training"}
STEP = {k: {"stepTypeId": i, "stepTypeKey": k} for i, k in
        {1:"warmup",2:"cooldown",3:"interval",4:"recovery",5:"rest",6:"repeat",7:"other"}.items()}
END = {"time":{"conditionTypeId":2,"conditionTypeKey":"time"},
       "distance":{"conditionTypeId":3,"conditionTypeKey":"distance"},
       "lap":{"conditionTypeId":1,"conditionTypeKey":"lap.button"}}
TT = {"none":{"workoutTargetTypeId":1,"workoutTargetTypeKey":"no.target"},
      "hr":  {"workoutTargetTypeId":4,"workoutTargetTypeKey":"heart.rate.zone"},
      "pace":{"workoutTargetTypeId":6,"workoutTargetTypeKey":"pace.zone"}}

def pace_ms(p): m,s = p.split(":"); return round(1000.0/(int(m)*60+int(s)), 4)
def hr_zone(z): return {"targetType":TT["hr"],"zoneNumber":z}
def pace(p_fast, p_slow):
    a,b = pace_ms(p_fast), pace_ms(p_slow)
    return {"targetType":TT["pace"],"targetValueOne":min(a,b),"targetValueTwo":max(a,b),"zoneNumber":None}
def none(): return {"targetType":TT["none"]}
def ex(kind, end_t, end_v, target, desc=None):
    s = {"type":"ExecutableStepDTO","stepType":STEP[kind],
         "endCondition":END[end_t],"endConditionValue":float(end_v), **target}
    if desc: s["description"] = desc
    return s
def REP(n, substeps):
    return {"type":"RepeatGroupDTO","stepType":STEP["repeat"],
            "numberOfIterations":n,"smartRepeat":False,"workoutSteps":substeps}

# ---- темповые диапазоны (быстрее, медленнее), мин/км ----
RTHR  = ("5:30","5:40")   # порог
RTHR2 = ("5:25","5:35")   # порог острее (нед. 8–10, 13)
RI515 = ("5:13","5:17")   # 1000 @5:15
RI510 = ("5:08","5:13")   # 1000 @5:10–5:15
RI510s= ("5:07","5:11")   # 1000 @5:10
RI520 = ("5:18","5:22")   # 1200 @5:20
RI800 = ("5:10","5:20")   # 800
RREP  = ("4:58","5:02")   # 400 @5:00
RREP2 = ("4:53","4:57")   # 400 @4:55
RTUNE = ("5:15","5:25")   # настройка / фартлек @темп 10к
RRACE1= ("5:38","5:45")   # старт 06.09
RRACE2= ("5:30","5:38")   # старт 27.09 (быстрее по итогам 06.09)
RST   = ("4:20","4:45")   # ускорения

# ---- беговые блоки ----
def warm(m=12): return [ex("warmup","time",m*60, hr_zone(2), "Разминка")]
def cool(m=10): return [ex("cooldown","time",m*60, hr_zone(1), "Заминка")]
def recrun(m):  return [ex("interval","time",m*60, hr_zone(1), "Восстановительный")]
def estep(m):   return [ex("interval","time",m*60, hr_zone(2), "Лёгкий")]
def strides(n): return [REP(n,[ex("interval","time",20, pace(*RST), "Ускорение"),
                               ex("recovery","time",60, hr_zone(1))])]
def easy(m, st=0): return estep(m) + (strides(st) if st else [])
def reps(n, distm, rng, rec_s): return [REP(n,[ex("interval","distance",distm, pace(*rng)),
                                               ex("recovery","time",rec_s, hr_zone(1))])]
def tmin(n, mins, rng, rec_m):  return [REP(n,[ex("interval","time",mins*60, pace(*rng)),
                                               ex("recovery","time",rec_m*60, hr_zone(1))])]
def tsec(n, secs, rng, rec_s):  return [REP(n,[ex("interval","time",secs, pace(*rng)),
                                               ex("recovery","time",rec_s, hr_zone(1))])]
def fartlek(n, on_s, off_s, rng): return [REP(n,[ex("interval","time",on_s, pace(*rng), "Темп 10к"),
                                                 ex("recovery","time",off_s, hr_zone(1), "Легко")])]
def thr_cont(mins, rng): return [ex("interval","time",mins*60, pace(*rng), "Темп непрерывно")]
def long_easy(mins):     return [ex("interval","time",mins*60, hr_zone(2), "Длинная")]
def race(km, rng):       return warm(12) + strides(3) + \
                                [ex("interval","distance",km*1000, pace(*rng), "Гонка")] + cool(10)

# ---- описание (комментарий) из шагов ----
def _ms_pace(ms): s=round(1000/ms); return f"{s//60}:{s%60:02d}"
def _amt(s):
    ec=s["endCondition"]["conditionTypeKey"]; v=s["endConditionValue"]
    if ec=="time":
        v=int(v)
        if v<60: return f"{v}с"
        return f"{v//60} мин" if v%60==0 else f"{v}с"
    if ec=="distance": return f"{v/1000:g} км"
    return "до круга"
def _tgt(s):
    t=s.get("targetType",{}).get("workoutTargetTypeKey")
    if t=="heart.rate.zone": return f"Z{s.get('zoneNumber')}"
    if t=="pace.zone":
        a,b=s.get("targetValueOne"),s.get("targetValueTwo")
        return f"{_ms_pace(max(a,b))}-{_ms_pace(min(a,b))}/км"
    return ""
def _prose(s):
    if s["type"]=="RepeatGroupDTO":
        return f"{s['numberOfIterations']}×(" + "; ".join(_prose(x) for x in s["workoutSteps"]) + ")"
    lbl=s.get("description"); body=" ".join(x for x in [_amt(s),_tgt(s)] if x)
    return f"{lbl} {body}".strip() if lbl else body
def describe(steps): return " · ".join(_prose(s) for s in steps)

# ---- ОФП / силовые ----
DESC_A = ("ОФП A (ноги+кор), 20–25 мин. Гоблет-приседания 3×10; Болгарские сплит-приседания 3×8/ногу; "
          "Ягодичный мостик на одной ноге 3×10/ногу; Подъёмы на носки 3×15; Планка 3×40с + боковая 3×25с/сторона.")
DESC_B = ("ОФП B (кор+стабилизация), 20 мин. Мёртвый жук 3×10; Боковая планка 3×25с/сторона; Супермен 3×12; "
          "Ягодичный мостик 3×12; Лёгкие подскоки 3×6 (по желанию).")
DESC_AL = ("ОФП A (лёгко), 15–20 мин. Гоблет-приседания 2×10; Болгарские 2×8/ногу; "
           "Ягодичный мостик 2×10/ногу; Подъёмы на носки 2×15; Планка 2×40с.")
DESC_COR = "Кор, 12–15 мин. Планка 3×40с; боковая 3×25с/сторона; мёртвый жук 3×10; супермен 3×12."

def RUN(name, steps): return {"kind":"run","name":name,"steps":steps}
def STR(name, desc, mins): return {"kind":"str","name":name,"desc":desc,"mins":mins}
SA  = lambda: STR("ОФП A", DESC_A, 25)
SB  = lambda: STR("ОФП B", DESC_B, 20)
SAL = lambda: STR("ОФП A (лёгко)", DESC_AL, 18)
COR = lambda: STR("Кор", DESC_COR, 15)

# Дни недели: Пн Вт Ср Чт Пт Сб Вс. [] = день отдыха (не создаётся).
# Не более одной тренировки в день: ОФП — отдельными днями (Пн/Пт), не совмещается с бегом.
WEEKS = [
 # Н1 — Пиковая (текущая)
 [ [SA()],
   [RUN("Интервалы 6x1000", warm(15)+reps(6,1000,RI515,90)+cool())],
   [RUN("Лёгкий", easy(55,6))],
   [RUN("Лёгкий", easy(45))],
   [SB()],
   [RUN("Порог 2x15'", warm(12)+tmin(2,15,RTHR,3)+cool())],
   [RUN("Длинная 1:55", long_easy(115))] ],
 # Н2 — Разгрузка (после пика)
 [ [SA()],
   [RUN("Освежающие 6x400", warm(12)+reps(6,400,RREP,90)+cool(8))],
   [RUN("Лёгкий", easy(40,5))],
   [RUN("Лёгкий", easy(35))],
   [SB()],
   [RUN("Порог 2x8'", warm(12)+tmin(2,8,RTHR,2)+cool(8))],
   [RUN("Длинная 1:25 лёгкий", long_easy(85))] ],
 # Н3 — Развитие
 [ [SA()],
   [RUN("Интервалы 5x1000", warm(15)+reps(5,1000,RI515,90)+cool())],
   [RUN("Лёгкий", easy(50,6))],
   [RUN("Лёгкий", easy(45))],
   [SB()],
   [RUN("Порог 2x12'", warm(12)+tmin(2,12,RTHR,3)+cool())],
   [RUN("Длинная 1:45", long_easy(105))] ],
 # Н4 — Развитие
 [ [SA()],
   [RUN("Интервалы 6x1000", warm(15)+reps(6,1000,RI510,90)+cool())],
   [RUN("Лёгкий", easy(55,6))],
   [RUN("Лёгкий", easy(45))],
   [SB()],
   [RUN("Порог 2x15'", warm(12)+tmin(2,15,RTHR,3)+cool())],
   [RUN("Длинная 1:50", long_easy(110))] ],
 # Н5 — Развитие · объём (пик длинной 2:00)
 [ [SA()],
   [RUN("Темп10к 5x1200", warm(15)+reps(5,1200,RI520,90)+cool())],
   [RUN("Лёгкий", easy(55,6))],
   [RUN("Лёгкий", easy(45))],
   [SB()],
   [RUN("Темп 25' непрерывно", warm(12)+thr_cont(25,RTHR)+cool())],
   [RUN("Длинная 2:00 ПИК", long_easy(120))] ],
 # Н6 — Разгрузка
 [ [SA()],
   [RUN("Освежающие 8x400", warm(12)+reps(8,400,RREP2,90)+cool(8))],
   [RUN("Лёгкий", easy(45,5))],
   [RUN("Лёгкий", easy(35))],
   [SB()],
   [RUN("Порог 2x8'", warm(12)+tmin(2,8,RTHR,2)+cool(8))],
   [RUN("Длинная 1:25 лёгкий", long_easy(85))] ],
 # Н7 — Специфика 10К
 [ [SA()],
   [RUN("Темп10к 5x1000", warm(15)+reps(5,1000,RI515,90)+cool())],
   [RUN("Лёгкий", easy(55,6))],
   [RUN("Лёгкий", easy(45))],
   [SB()],
   [RUN("Порог 3x10'", warm(12)+tmin(3,10,RTHR,2)+cool())],
   [RUN("Длинная 1:50", long_easy(110))] ],
 # Н8 — Специфика 10К
 [ [SA()],
   [RUN("Темп10к 6x1000", warm(15)+reps(6,1000,RI510,90)+cool())],
   [RUN("Лёгкий", easy(55,6))],
   [RUN("Лёгкий", easy(45))],
   [SB()],
   [RUN("Порог 2x15'", warm(12)+tmin(2,15,RTHR2,3)+cool())],
   [RUN("Длинная 1:50", long_easy(110))] ],
 # Н9 — Заострение
 [ [SA()],
   [RUN("Темп10к 5x1000", warm(15)+reps(5,1000,RI510,90)+cool())],
   [RUN("Лёгкий", easy(50,6))],
   [RUN("Лёгкий", easy(45))],
   [SB()],
   [RUN("Порог 2x12'", warm(12)+tmin(2,12,RTHR2,3)+cool())],
   [RUN("Длинная 1:40", long_easy(100))] ],
 # Н10 — Заострение (силовая снижается)
 [ [SAL()],
   [RUN("Темп10к 5x1000", warm(15)+reps(5,1000,RI510s,90)+cool())],
   [RUN("Лёгкий", easy(45,6))],
   [RUN("Лёгкий", easy(40))],
   [],
   [RUN("Порог 2x10'", warm(12)+tmin(2,10,RTHR2,3)+cool())],
   [RUN("Длинная 1:30", long_easy(90))] ],
 # Н11 — Подводка · старт 10 км (силовые сняты)
 [ [],
   [RUN("Настройка 5x2'", warm(12)+tsec(5,120,RTUNE,120)+cool())],
   [RUN("Лёгкий", easy(35,4))],
   [],
   [RUN("Разбежка", easy(20,4))],
   [],
   [RUN("СТАРТ 10 КМ 06.09", race(10,RRACE1))] ],
 # Н12 — Восстановление (только лёгкий кор в понедельник)
 [ [COR()],
   [RUN("Лёгкий", easy(35))],
   [],
   [RUN("Фартлек 6x1'", warm(12)+fartlek(6,60,60,RTUNE)+cool())],
   [],
   [RUN("Лёгкий", easy(45,5))],
   [RUN("Длинная 1:10 лёгкий", long_easy(70))] ],
 # Н13 — Повторное заострение (лёгкий кор в понедельник)
 [ [COR()],
   [RUN("Интервалы 6x800", warm(15)+reps(6,800,RI800,90)+cool())],
   [RUN("Лёгкий", easy(45,6))],
   [RUN("Лёгкий", easy(40))],
   [],
   [RUN("Порог 2x10'", warm(12)+tmin(2,10,RTHR2,3)+cool())],
   [RUN("Длинная 1:30", long_easy(90))] ],
 # Н14 — Неделя гонки
 [ [],
   [RUN("Настройка 4x90с", warm(12)+tsec(4,90,RTUNE,120)+strides(4)+cool())],
   [RUN("Лёгкий", easy(30))],
   [RUN("Лёгкий", easy(25,4))],
   [],
   [RUN("Шейкаут", easy(18,3))],
   [RUN("ГЛАВНЫЙ СТАРТ 10 КМ 27.09", race(10,RRACE2))] ],
]

# ---- сборка JSON ----
def order_steps(steps, c):
    for s in steps:
        s["stepOrder"]=c[0]; c[0]+=1
        if s["type"]=="RepeatGroupDTO": order_steps(s["workoutSteps"], c)
    return steps
def run_json(name, steps):
    order_steps(steps,[1])
    return {"sportType":SPORT_RUN,"workoutName":name[:79],"description":describe(steps)[:1024],
            "workoutSegments":[{"segmentOrder":1,"sportType":SPORT_RUN,"workoutSteps":steps}]}
def str_json(name, desc, mins):
    st=ex("other","time",mins*60, none(), "ОФП"); st["stepOrder"]=1
    return {"sportType":SPORT_STR,"workoutName":name[:79],"description":desc[:1024],
            "workoutSegments":[{"segmentOrder":1,"sportType":SPORT_STR,"workoutSteps":[st]}]}

def date_for(wi, di): return START + datetime.timedelta(days=wi*7 + di)
def build_all(only_week=None):
    out=[]
    for wi, week in enumerate(WEEKS):
        if only_week is not None and wi != only_week: continue
        for di, day in enumerate(week):
            d=date_for(wi,di)
            for sess in day:
                full=f"{TAG} Н{wi+1} {DOW[di]} {sess['name']}"
                if sess["kind"]=="run": wk=run_json(full, sess["steps"])
                else: wk=str_json(full, sess["desc"], sess["mins"])
                out.append((d, full, wk))
    return out

# ---- Garmin API (garth напрямую) ----
def connect():
    import garth
    garth.client.sess.headers.update({"User-Agent": UA})
    try:
        garth.resume(TOKENS); _=garth.client.username
        print("Вход по сохранённому токену.")
    except Exception:
        email=os.environ.get("GARMIN_EMAIL"); pwd=os.environ.get("GARMIN_PASSWORD")
        if not email or not pwd: sys.exit("Нет токена. Задай GARMIN_EMAIL и GARMIN_PASSWORD.")
        garth.client.sess.headers.update({"User-Agent": UA})
        garth.login(email, pwd, prompt_mfa=lambda: input("Код 2FA: ").strip())
        garth.save(TOKENS); print("Токен сохранён в", TOKENS)
    garth.client.sess.headers.update({"User-Agent": UA})
    return garth

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--test", action="store_true", help="только неделя 1")
    ap.add_argument("--clear", action="store_true")
    args=ap.parse_args()

    if args.dry_run:
        items=build_all(only_week=0 if args.test else None)
        runs=sum(1 for _,_,w in items if w["sportType"]["sportTypeKey"]=="running")
        print(f"Будет создано: {len(items)} (бег {runs}, ОФП {len(items)-runs})\n")
        import json
        sr=next(w for _,_,w in items if "Интервалы 6x1000" in w["workoutName"])
        st=next(w for _,_,w in items if w["sportType"]["sportTypeKey"]=="strength_training")
        lr=next(w for _,_,w in items if "Длинная 1:55" in w["workoutName"])
        rc=next(w for _,_,w in items if "СТАРТ 10 КМ 06.09" in w["workoutName"])
        for w in (sr,lr,rc):
            print("###", w["workoutName"], "\nКОММЕНТАРИЙ:", w["description"]); print()
        print("### ОФП", st["workoutName"], "\nКОММЕНТАРИЙ:", st["description"]); print()
        print("JSON (Интервалы):"); print(json.dumps(sr, ensure_ascii=False, indent=1)[:900], "...")
        return

    garth=connect()
    def post(p, body): return garth.connectapi(p, method="POST", json=body)
    def get(p):        return garth.connectapi(p)
    def delete(p):     return garth.connectapi(p, method="DELETE")

    if args.clear:
        lst=get("/workout-service/workouts?start=0&limit=999") or []
        mine=[w for w in lst if str(w.get("workoutName","")).startswith(TAG)]
        print(f"Найдено наших: {len(mine)}")
        for w in mine:
            try: delete(f"/workout-service/workout/{w['workoutId']}"); print("  удалено:", w["workoutName"])
            except Exception as e: print("  FAIL удаления:", w.get("workoutName"), e)
            time.sleep(0.2)
        print("Очистка завершена."); return

    items=build_all(only_week=0 if args.test else None)
    runs=sum(1 for _,_,w in items if w["sportType"]["sportTypeKey"]=="running")
    print(f"К созданию: {len(items)} (бег {runs}, ОФП {len(items)-runs})"
          f"{' — только неделя 1' if args.test else ''}\n")
    ok=fail=0
    for d, name, wk in items:
        try:
            res=post("/workout-service/workout", wk)
            wid=res.get("workoutId")
            post(f"/workout-service/schedule/{wid}", {"date": d.isoformat()})
            print(f"OK   {d.isoformat()}  {name}  (id={wid})"); ok+=1
            time.sleep(0.4)
        except Exception as e:
            print(f"FAIL {d.isoformat()}  {name} -> {e}"); fail+=1
    print(f"\nГотово: {ok} создано, {fail} с ошибкой. "
          "Проверь Garmin Connect → Тренировки и Календарь, синхронизируй часы.")

if __name__ == "__main__":
    main()
