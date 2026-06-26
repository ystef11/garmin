#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Импорт ПЛАНА «Марафон 3:15 (27.09.2026)» напрямую в Garmin Connect как нативные
СТРУКТУРИРОВАННЫЕ тренировки (их можно редактировать в Connect) + расписание по датам.
Силовые — отдельными тренировками strength_training. К каждой тренировке добавляется
комментарий (описание) и подписи к шагам (видны на часах).

Обход блокировки Garmin (#222): браузерный User-Agent ставится на garth.client.sess до login.
Проверено на garth==0.6.3 (The version 0.6.3 works fine. I can also confirm that 0.7.0 is the 
first version that creates the error https://github.com/matin/garth/discussions/222)

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
  python garmin_import.py --dry-run     # печать примеров, без отправки
  python garmin_import.py --test        # только НЕДЕЛЯ 1
  python garmin_import.py --clear       # удалить ранее созданные ([M315]) и выйти
  python garmin_import.py               # весь план (рекомендуется сначала --clear)

Зоны (ПАНО 178): Z1 восстановление | Z2 лёгкий/длинный | Z3 марафон | Z4 порог | Z5 МПК.
"""

import os, sys, time, datetime, argparse

TAG = "[M315]"
START = datetime.date(2026, 6, 22)
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

# темповые диапазоны (быстрее, медленнее)
RV=("3:50","4:00"); RT=("3:58","4:02"); R4=("3:42","3:48"); R4b=("3:45","3:52")
RTH=("4:12","4:20"); RAC=("4:00","4:05"); R8=("4:12","4:18")
RMP=("4:35","4:40"); RRACE=("3:55","4:00"); RST=("3:20","3:40")

# ---- блоки ----
def warm(m=15): return [ex("warmup","time",m*60, hr_zone(2), "Разминка")]
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
def mp_cont(mins):       return [ex("interval","time",mins*60, pace(*RMP), "Марафонский")]
def mp_reps(n, mins, rec_m): return [REP(n,[ex("interval","time",mins*60, pace(*RMP)),
                                            ex("recovery","time",rec_m*60, hr_zone(2))])]
def long_easy(mins):     return [ex("interval","time",mins*60, hr_zone(2), "Длинная")]
def long_mp_end(e, mp):  return [ex("interval","time",e*60, hr_zone(2), "Длинная"),
                                 ex("interval","time",mp*60, pace(*RMP), "Концовка МР")]
def long_mp_mid(w, mp, c): return [ex("interval","time",w*60, hr_zone(2), "Длинная"),
                                   ex("interval","time",mp*60, pace(*RMP), "МР"),
                                   ex("interval","time",c*60, hr_zone(2), "Лёгкий")]
def long_mp_blocks(w, n, mp, r):
    return [ex("interval","time",w*60, hr_zone(2), "Длинная"),
            REP(n,[ex("interval","time",mp*60, pace(*RMP), "МР"),
                   ex("recovery","time",r*60, hr_zone(2))])]
def race(km=10): return warm(15) + [ex("interval","distance",km*1000, pace(*RRACE), "Гонка")] + cool(10)

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

# ---- силовые ----
DESC_A = ("Силовая A. Приседания 3×8 (2 в запасе); Румынская тяга 3×8; Болгарские сплит-приседания 3×8/ногу; "
          "Подъёмы на носки 3×15; Планка 3×45с + боковая 3×30с/сторона.")
DESC_B = ("Силовая B. Беговые дрилы (A/B-skip, захлёст) 2×20м; Выпрыгивания 3×6; Запрыгивания на тумбу 3×5; "
          "Ягодичный мостик 3×10/ногу; Кор: мёртвый жук 3×10, боковая планка 3×8, супермен 3×12.")
DESC_B_NOJ = ("Силовая B (без прыжков). Дрилы (A/B-skip, захлёст) 2×20м; Ягодичный мостик 3×10/ногу; "
              "Кор: мёртвый жук 3×10, боковая планка 3×8, супермен 3×12.")
DESC_COR = "Кор. Планка 3×45с; боковая 3×30с/сторона; мёртвый жук 3×10; супермен 3×12."

def RUN(name, steps): return {"kind":"run","name":name,"steps":steps}
def STR(name, desc, mins): return {"kind":"str","name":name,"desc":desc,"mins":mins}
def AM(m=40): return RUN("Восст утро", recrun(m))
SA   = lambda: STR("Силовая A", DESC_A, 30)
SA2  = lambda: STR("Силовая A (2 подхода)", DESC_A, 25)
SAL  = lambda: STR("Силовая A (лёгко)", DESC_A, 20)
SB   = lambda: STR("Силовая B", DESC_B, 20)
SBNJ = lambda: STR("Силовая B (без прыжков)", DESC_B_NOJ, 20)
COR  = lambda: STR("Кор", DESC_COR, 15)

WEEKS = [
 [ [RUN("Восстановительный", recrun(40)), SA()],
   [AM(40), RUN("МПК 6x1000", warm()+reps(6,1000,RV,90)+cool())],
   [RUN("Лёгкий", easy(55,6)), SB()],
   [AM(40), RUN("Порог 2x15'", warm()+tmin(2,15,RTH,3)+cool())],
   [RUN("Восстановительный", recrun(40))],
   [AM(40), RUN("Лёгкий", easy(32,5))],
   [RUN("Длинная 2:15 концовка МР", long_mp_end(110,25))] ],
 [ [RUN("Восстановительный", recrun(35)), SA()],
   [AM(35), RUN("Освежающие 6x400", warm(12)+reps(6,400,R4b,90)+cool(8))],
   [RUN("Лёгкий", easy(45,5)), SB()],
   [AM(35), RUN("Порог 2x10'", warm(12)+tmin(2,10,RTH,2)+cool(8))],
   [RUN("Восстановительный", recrun(35))],
   [AM(35), RUN("Лёгкий", easy(30,4))],
   [RUN("Длинная 1:40 лёгкий", long_easy(100))] ],
 [ [RUN("Восстановительный", recrun(40)), SA()],
   [AM(40), RUN("Темп10к 5x1200", warm()+reps(5,1200,RT,90)+cool())],
   [RUN("Лёгкий", easy(50,6)), SB()],
   [AM(40), RUN("Порог 2x20'", warm()+tmin(2,20,RTH,3)+cool())],
   [RUN("Восстановительный", recrun(40))],
   [AM(40), RUN("Лёгкий", easy(32,5))],
   [RUN("Длинная 2:20 лёгкий", long_easy(140))] ],
 [ [RUN("Восстановительный", recrun(40)), SA()],
   [AM(40), RUN("Темп10к 6x1200", warm()+reps(6,1200,RT,90)+cool())],
   [RUN("Лёгкий", easy(50,6)), SB()],
   [AM(40), RUN("Порог 3x12'", warm()+tmin(3,12,RTH,2)+cool())],
   [RUN("Восстановительный", recrun(40))],
   [AM(40), RUN("Лёгкий", easy(30,5))],
   [RUN("Длинная 2:20 концовка МР", long_mp_end(105,35))] ],
 [ [RUN("Восстановительный", recrun(40)), SA()],
   [AM(40), RUN("МПК 5x1200", warm()+reps(5,1200,RV,120)+cool())],
   [RUN("Лёгкий", easy(50,6)), SB()],
   [RUN("МР 45' одна трен.", warm()+mp_cont(45)+cool())],
   [RUN("Восстановительный", recrun(40))],
   [AM(40), RUN("Лёгкий", easy(30,5))],
   [RUN("Длинная 2:30 ровный", long_easy(150))] ],
 [ [RUN("Восстановительный", recrun(35)), SA2()],
   [AM(35), RUN("Короткие 8x400", warm(12)+reps(8,400,R4,90)+cool(8))],
   [RUN("Лёгкий", easy(45,5)), SB()],
   [AM(35), RUN("Порог 2x12'", warm(12)+tmin(2,12,RTH,2)+cool(8))],
   [RUN("Восстановительный", recrun(35))],
   [AM(35), RUN("Лёгкий", easy(30,4))],
   [RUN("Длинная 1:45 лёгкий", long_easy(105))] ],
 [ [RUN("Восстановительный", recrun(40)), SA()],
   [AM(40), RUN("МПК 5x1200", warm()+reps(5,1200,RV,120)+cool())],
   [RUN("Лёгкий", easy(50,6)), SB()],
   [RUN("МР 2x28' одна трен.", warm()+mp_reps(2,28,3)+cool())],
   [RUN("Восстановительный", recrun(40))],
   [AM(40), RUN("Лёгкий", easy(30,5))],
   [RUN("Длинная 2:30 середина МР", long_mp_mid(50,70,30))] ],
 [ [RUN("Восстановительный", recrun(35)), SA2()],
   [AM(35), RUN("Лёгкий", easy(45,4))],
   [RUN("Лёгкий", easy(45)), SB()],
   [AM(35), RUN("Заострение 3x1000", warm()+reps(3,1000,RT,120)+cool())],
   [RUN("Восстановительный", recrun(35))],
   [RUN("КОНТРОЛЬНАЯ 10 КМ", race(10))],
   [RUN("Восст. длинная 1:25", recrun(85))] ],
 [ [RUN("Восстановительный", recrun(40)), SA2()],
   [AM(40), RUN("МПК 6x1000", warm()+reps(6,1000,RV,90)+cool())],
   [RUN("Лёгкий", easy(50,6)), SB()],
   [RUN("МР 75' одна трен.", warm()+mp_cont(75)+cool())],
   [RUN("Восстановительный", recrun(40))],
   [AM(40), RUN("Лёгкий", easy(30,5))],
   [RUN("Длинная 2:40 концовка МР", long_mp_end(115,45))] ],
 [ [RUN("Восстановительный", recrun(40)), SA2()],
   [AM(40), RUN("Темп10к 5x1600", warm()+reps(5,1600,RT,150)+cool())],
   [RUN("Лёгкий", easy(50,6)), SB()],
   [RUN("МР 2x37' одна трен.", warm()+mp_reps(2,37,2)+cool())],
   [RUN("Восстановительный", recrun(40))],
   [AM(40), RUN("Лёгкий", easy(30,5))],
   [RUN("Длинная 2:45 ПИК 3x20 МР", long_mp_blocks(75,3,20,5))] ],
 [ [RUN("Восстановительный", recrun(40)), SA2()],
   [AM(35), RUN("Острая 4x1000", warm()+reps(4,1000,RV,90)+cool())],
   [RUN("Лёгкий", easy(55,6)), SBNJ()],
   [AM(35), RUN("Лёгкий", easy(40,4))],
   [RUN("Восстановительный", recrun(35))],
   [RUN("Предстартовый шейкаут", easy(30,4))],
   [RUN("СТАРТ 10 КМ <40:00", race(10))] ],
 [ [RUN("Восстановительный", recrun(35)), SAL()],
   [AM(35), RUN("Темп10к 4x1200", warm()+reps(4,1200,RT,90)+cool())],
   [RUN("Лёгкий", easy(50,6)), STR("Кор (без прыжков)", DESC_COR, 15)],
   [RUN("МР 55' одна трен.", warm()+mp_cont(55)+cool())],
   [RUN("Восстановительный", recrun(35))],
   [AM(35), RUN("Лёгкий", easy(30,5))],
   [RUN("Длинная 2:10 концовка МР", long_mp_end(100,30))] ],
 [ [RUN("Восстановительный", recrun(30)), COR()],
   [AM(30), RUN("Острая 5x1000", warm(12)+reps(5,1000,RAC,90)+cool())],
   [RUN("Лёгкий", easy(45,6))],
   [AM(30), RUN("Порог 4x800", warm(12)+reps(4,800,R8,90)+cool())],
   [RUN("Восстановительный", recrun(30))],
   [RUN("Лёгкий", easy(35,5))],
   [RUN("Длинная 1:30 концовка МР", long_mp_end(70,20))] ],
 [ [RUN("Восстановительный", recrun(35)), COR()],
   [RUN("Настройка 3x1000 @МР", warm()+reps(3,1000,RMP,90)+strides(4)+cool())],
   [RUN("Лёгкий", easy(35))],
   [RUN("Лёгкий", easy(35,4))],
   [RUN("Отдых / трусца 30'", recrun(30))],
   [RUN("Разминочный выезд", easy(25,3))],
   [RUN("МАРАФОН 3:15 (4:37/км)", warm(10)+[ex("interval","distance",42195, pace(*RMP), "Марафон 4:37")])] ],
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
    st=ex("other","time",mins*60, none(), "Силовая"); st["stepOrder"]=1
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
        print(f"Будет создано: {len(items)} (бег {runs}, силовые {len(items)-runs})\n")
        import json
        sr=next(w for _,_,w in items if "МПК 6" in w["workoutName"])
        st=next(w for _,_,w in items if w["sportType"]["sportTypeKey"]=="strength_training")
        lr=next(w for _,_,w in items if "Длинная 2:15" in w["workoutName"])
        for w in (sr,lr):
            print("###", w["workoutName"], "\nКОММЕНТАРИЙ:", w["description"]); print()
        print("### СИЛОВАЯ", st["workoutName"], "\nКОММЕНТАРИЙ:", st["description"]); print()
        print("JSON (МПК):"); print(json.dumps(sr, ensure_ascii=False, indent=1)[:900], "...")
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
    print(f"К созданию: {len(items)} (бег {runs}, силовые {len(items)-runs})"
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
