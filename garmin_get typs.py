import os, garth
garth.resume(os.path.expanduser("~/.garth"))          # тот же токен, что у импортёра
lst = garth.connectapi("/workout-service/workouts?start=0&limit=999") or []
seen = {}
for w in lst:
    st = w.get("sportType", {}) or {}
    key = (st.get("sportTypeId"), st.get("sportTypeKey"))
    seen.setdefault(key, w.get("workoutName"))
for (sid, skey), name in sorted(seen.items(), key=lambda x: (x[0][0] or 0)):
    print(f"sportTypeId={sid:<3} key={skey:<22} пример: {name}")