#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ТЕСТ: создаём ОДНУ интервальную тренировку напрямую в Garmin Connect и ставим её
на дату. Это нативная тренировка Garmin — её потом можно РЕДАКТИРОВАТЬ в Garmin
Connect и она уедет на часы.

Обход блокировки Garmin (garth deprecated, ловит 429): подменяем User-Agent на
браузерный ПЕРЕД login() — рабочий способ из discussions/222.

ПОДГОТОВКА
  pip install garth
АВТОРИЗАЦИЯ (пароль вводишь ты, я его не вижу)
  PowerShell:
    $env:GARMIN_EMAIL="you@mail.com"
    $env:GARMIN_PASSWORD="********"
  При 2FA скрипт спросит код. Токен сохранится в C:/Users/<ты>/.garth,
  повторный вход не потребуется.

ЗАПУСК
  python garmin_one_test.py            # создать тест-тренировку и запланировать
  python garmin_one_test.py --date 2026-06-28
  python garmin_one_test.py --delete   # удалить тест-тренировку

Если снова 429 — это rate-limit: подожди 30–60 мин и попробуй ОДИН раз.
"""

import os, sys, json, argparse, datetime

NAME = "[M315 TEST] МПК 6x1000"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

# ---- справочники Garmin ----
SPORT = {"sportTypeId": 1, "sportTypeKey": "running"}
STEP = {k: {"stepTypeId": i, "stepTypeKey": k} for i, k in
        {1:"warmup",2:"cooldown",3:"interval",4:"recovery",5:"rest",6:"repeat",7:"other"}.items()}
END = {"time":{"conditionTypeId":2,"conditionTypeKey":"time"},
       "distance":{"conditionTypeId":3,"conditionTypeKey":"distance"}}
TT = {"none":{"workoutTargetTypeId":1,"workoutTargetTypeKey":"no.target"},
      "hr":  {"workoutTargetTypeId":4,"workoutTargetTypeKey":"heart.rate.zone"},
      "pace":{"workoutTargetTypeId":6,"workoutTargetTypeKey":"pace.zone"}}

def pace_ms(p):           # "3:55" -> м/с
    m, s = p.split(":"); return round(1000.0/(int(m)*60+int(s)), 4)

def ex(kind, end_t, end_v, target):
    return {"type":"ExecutableStepDTO","stepType":STEP[kind],
            "endCondition":END[end_t],"endConditionValue":float(end_v), **target}
def hr_zone(z): return {"targetType":TT["hr"],"zoneNumber":z}
def pace(p_fast, p_slow):
    a, b = pace_ms(p_fast), pace_ms(p_slow)
    return {"targetType":TT["pace"],"targetValueOne":min(a,b),"targetValueTwo":max(a,b),"zoneNumber":None}
def none(): return {"targetType":TT["none"]}

def build_workout():
    # МПК: разминка 15м Z2 / 6×(1000м 3:55–4:00 / 90с Z1) / заминка 10м Z1
    steps = [
        ex("warmup","time",15*60, hr_zone(2)),
        {"type":"RepeatGroupDTO","stepType":STEP["repeat"],"numberOfIterations":6,
         "smartRepeat":False,"workoutSteps":[
            ex("interval","distance",1000, pace("3:55","4:00")),
            ex("recovery","time",90, hr_zone(1)),
        ]},
        ex("cooldown","time",10*60, hr_zone(1)),
    ]
    # проставить stepOrder сквозным счётчиком
    c = [1]
    def order(lst):
        for s in lst:
            s["stepOrder"] = c[0]; c[0] += 1
            if s["type"] == "RepeatGroupDTO": order(s["workoutSteps"])
    order(steps)
    return {"sportType":SPORT,"workoutName":NAME,
            "workoutSegments":[{"segmentOrder":1,"sportType":SPORT,"workoutSteps":steps}]}

import garth
TOKENS = os.path.expanduser("d:/Downloads/7")

def connect():
    garth.client.sess.headers.update({"User-Agent": UA})       # ОБХОД #222: браузерный UA
    try:
        garth.resume(TOKENS)
        _ = garth.client.username                              # проверка валидности токена
        print("Вход по сохранённому токену.")
    except Exception:
        email = os.environ.get("GARMIN_EMAIL"); pwd = os.environ.get("GARMIN_PASSWORD")
        if not email or not pwd:
            sys.exit("Нет токена. Задай GARMIN_EMAIL и GARMIN_PASSWORD и войди один раз.")
        garth.client.sess.headers.update({"User-Agent": UA})
        garth.login(email, pwd, prompt_mfa=lambda: input("Код 2FA: ").strip())
        garth.save(TOKENS)
        print("Вход выполнен, токен сохранён в", TOKENS)
    garth.client.sess.headers.update({"User-Agent": UA})       # держим UA и для API-запросов

def post(path, payload): return garth.connectapi(path, method="POST", json=payload)
def get(path):           return garth.connectapi(path)
def delete(path):        return garth.connectapi(path, method="DELETE")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default="2026-06-28")
    ap.add_argument("--delete", action="store_true")
    args = ap.parse_args()

    connect()

    if args.delete:
        lst = get("/workout-service/workouts?start=0&limit=999") or []
        mine = [w for w in lst if str(w.get("workoutName","")) == NAME]
        for w in mine:
            delete(f"/workout-service/workout/{w['workoutId']}")
            print("удалено:", w["workoutId"], w["workoutName"])
        print("Готово." if mine else "Нечего удалять."); return

    wk = build_workout()
    print("Отправляю тренировку:\n", json.dumps(wk, ensure_ascii=False, indent=1)[:600], "...\n")
    res = post("/workout-service/workout", wk)
    wid = res.get("workoutId")
    print(f"Создана тренировка workoutId={wid}")

    sched = post(f"/workout-service/schedule/{wid}", {"date": args.date})
    print(f"Запланирована на {args.date}: {sched if sched else 'OK'}")
    print("\nОткрой Garmin Connect → Тренировки и Календарь. "
          "Тренировку можно редактировать вручную. Синхронизируй часы.")
    print("Удалить тест:  python garmin_one_test.py --delete")

if __name__ == "__main__":
    main()
