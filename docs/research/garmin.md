# Research: Garmin Connect — biblioteka, dane, treningi, urządzenia

> Autor: Researcher (agent) · Data sprawdzenia źródeł: **2026-11-18** · Dotyczy: MASTER_PROMPT §2.6, §3.1, §3.6, §9
> Oznaczenia: **[F]** = fakt sprawdzony w źródle (kod z PyPI, README, strona); **[P]** = przypuszczenie / niezweryfikowane na żywym koncie.
> Nie logowano się do żadnego konta Garmin. Przykłady JSON wygenerowano **offline** modelami Pydantic z biblioteki (bez wysyłki).
> Ograniczenie: API GitHub i strony listy issues były niedostępne z sandboxa; issues/discussions czytane pojedynczo przez stronę WWW.

## TL;DR i rekomendacja

1. **`garth` jest wycofany (deprecated)** od 2026-03-27 [F]. Nowe logowania przez garth nie działają; README: „New logins will not work”. **Nie używać garth.**
2. **`python-garminconnect` od 0.3.0 (2026-04-02) nie zależy już od garth** [F] — ma własny klient (curl_cffi + mobilne SSO + tokeny „DI OAuth”). Biblioteka jest aktywnie utrzymywana: 22 wydania 0.3.x w 2026, ostatnie **0.3.20 z 2026-10-23** [F].
3. **Przypiąć:** `garminconnect[workout]==0.3.20` (wymaga Python >=3.12, `curl_cffi>=0.15.0`, `requests>=2.33.0`, `ua-generator>=1.0`, extra `workout` → `pydantic>=2.4`). Minimalnie **>=0.3.10** (hardening tokenów; CVE-2026-54447 naprawione w 0.3.5).
4. **Korekta Master Promptu (§3.1):** „zapisywane są tylko tokeny OAuth z `garth`” → zamienić na „**tokeny DI (access + refresh) z garminconnect**, serializowane `garmin.client.dumps()` i szyfrowane Fernetem”. Format starych tokenów garth (oauth1/oauth2) jest niekompatybilny [F].
5. **Treningi siłowe w v1.0: TAK (warunkowo).** Biblioteka ma typowane modele `StrengthWorkout` + `upload_strength_workout()` + wbudowany katalog 1527 ćwiczeń / 47 kategorii [F]; Enduro 3 i Fenix 8 (w tym 8 Pro) wg instrukcji obsługują treningi siłowe z Garmin Connect [F]. Warunek: spike w M0 na koncie testowym/Tomka (jedno wysłanie + podgląd na zegarku), bo poprawność wyświetlania ciężaru/ćwiczeń na zegarku nie jest potwierdzona przeze mnie na żywo [P].
6. **Główne ryzyko:** logowanie (HTTP 429 na SSO, fingerprinting klienta). Zdarza się nadal w 2026 (issue #453 otwarte, 2026-10-07) [F]. Mitygacja: logować się **rzadko** (raz, potem tylko refresh tokenu), nie powtarzać logowania w pętli, wybudzać sync bez ponownego logowania.

---

## 1. Biblioteki: stan, logowanie, MFA, tokeny

### 1.1 Wersje i utrzymanie [F]

| Pakiet | Wersja na PyPI | Data | Stan |
|---|---|---|---|
| `garminconnect` (cyberjunky) | **0.3.20** | 2026-10-23 | utrzymywany; 0.3.0 → 0.3.20 między 2026-04-02 a 2026-10-23 |
| `garth` (matin) | 0.8.0 | 2026-03-28 | **deprecated**, „final release”, repo bez dalszego rozwoju |

- garth README: *„Garth is deprecated and no longer maintained. Garmin changed their auth flow, breaking the mobile auth approach that Garth depends on.”* Ogłoszenie: discussion #222 z 2026-03-27. Istniejące sesje garth „may continue to work until that token expires (~1 year)”, nowe logowania nie.
- Przyczyna (z dyskusji, relacje użytkowników): Cloudflare/blokada domyślnego User-Agenta, HTTP 429 na mobilnym endpointcie SSO.
- **Następca:** brak oficjalnego następcy garth. W praktyce: `garminconnect>=0.3.0` (własny klient z TLS-impersonation przez `curl_cffi`). Inne alternatywy wspomniane w dyskusji: `garmin-health-data` (diegoscarabelli, curl_cffi + losowe opóźnienie przed POST z hasłem), logowanie przez Playwright. Dla nas: zostajemy przy garminconnect (zgodne z decyzją Tomka), interfejs `GarminGateway` bez zmian.
- garminconnect README: *„Since 0.3.0 this library no longer uses `garth`, which is deprecated. Sessions saved by older versions (oauth1_token.json/oauth2_token.json) cannot be converted.”*

### 1.2 Jak działa logowanie (0.3.x) [F, z README i kodu `client/`]
1. Logowanie przez `sso.garmin.com/mobile/api/login` z client ID aplikacji iOS (`GCM_IOS_DARK`); `login()` próbuje kilku strategii po kolei (mobile, SSO widget, web portal — każda z/bez TLS impersonation przez curl_cffi) i uznaje sukces dopiero, gdy API zaakceptuje token (`verify_login=True` domyślnie).
2. Service ticket wymieniany na **DI OAuth Bearer tokens** (`access_token` + `refresh_token`) przez `diauth.garmin.com`.
3. **Auto-refresh:** przed każdym żądaniem biblioteka sprawdza, czy token wygasa, i odświeża go refresh tokenem. Pełne ponowne logowanie (hasło + ewentualnie MFA) tylko, gdy refresh token wygaśnie lub zostanie unieważniony.
4. **Czas życia refresh tokenu DI: nieudokumentowany** [P]. (Stare garth: OAuth1 ~1 rok, OAuth2 krótko żyjący, odświeżany OAuth1 — to już nieaktualne.) Trzeba to zmierzyć w praktyce i obsłużyć błąd `GarminConnectAuthenticationError` → status „wymaga ponownego logowania” w aplikacji/`get_profile`.

### 1.3 MFA [F]
- Tryb CLI: `Garmin(email, password, prompt_mfa=lambda: input("MFA code: "))` + `login(tokenstore)`.
- Tryb serwisowy: `Garmin(email, password, return_on_mfa=True)`; `status, client_state = garmin.login()`; jeśli `status == "needs_mfa"` → `client_state` (JSON-serializowalny, **zawiera ciasteczka sesji — traktować jak hasło**) można zapisać, a potem `Garmin().resume_login(client_state, mfa_code)` nawet w innym procesie. Od 0.3.12 błędny kod/rate-limit nie kasuje oczekującej sesji MFA.
- Dla CG: CLI `cg-server garmin-login --user X` → wystarczy `prompt_mfa` (getpass/input). `return_on_mfa` przyda się dopiero, gdyby logowanie miało iść z aplikacji.

### 1.4 Zapis/wczytanie tokenów [F]
- Domyślnie plik `~/.garminconnect/garmin_tokens.json` (mode 0600, katalog 0700), pola: `di_token`, `di_refresh_token`, `di_client_id`.
- `garmin.client.dumps()` → string JSON; `login(tokenstore=<string JSON>)` akceptuje **tokeny inline** (wykrywa JSON), także zmienna `GARMINTOKENS`.
- **Rekomendacja dla CG:** trzymać `dumps()` zaszyfrowany Fernetem w SQLite per user; przy starcie `Garmin().login(decrypted_json)`; **po każdej sesji syncu/wysyłki ponownie `dumps()` i zapisać** (refresh token może się zmienić przy odświeżeniu — `data.get("refresh_token", self.di_refresh_token)` w `session.py`). [P] Przy tokenach inline biblioteka nie ma ścieżki pliku, więc sama ich nie zapisze — trzeba to robić w `GarminGateway`.
- Hasło nie jest potrzebne po pierwszym logowaniu (zgodne z §3.1). `logout()` czyści tylko lokalny plik, nie unieważnia tokenu po stronie Garmina.
- Bezpieczeństwo: CVE-2026-54447 (uprawnienia pliku tokenów, <=0.3.4, fix 0.3.5); 0.3.10 — atomowe zapisy, blokada odświeżania, brak tokenów w logach.

## 2. Pobieranie danych (metody → endpointy) [F, z kodu 0.3.20]

| Cel | Metoda | Endpoint (prefiks connectapi) |
|---|---|---|
| Lista aktywności po datach | `get_activities_by_date(startdate, enddate=None, activitytype=None, sortorder=None)` (paginuje sama po 20) | `GET /activitylist-service/activities/search/activities?startDate=&endDate=&start=&limit=&activityType=` |
| Lista stronicowana | `get_activities(start, limit, activitytype)` | `/activitylist-service/activities/search/activities` |
| Podsumowanie | `get_activity(id)` | `/activity-service/activity/{id}` |
| Splity / okrążenia | `get_activity_splits(id)`, `get_activity_laps(id)`, `get_activity_split_summaries(id)`, `get_activity_typed_splits(id)` | `/activity-service/activity/{id}/splits`, `/laps`, `/split_summaries`, `/typedsplits` |
| Strefy HR | `get_activity_hr_in_timezones(id)` | `/activity-service/activity/{id}/hrTimeInZones` |
| Strefy mocy | `get_activity_power_in_timezones(id)` | `/activity-service/activity/{id}/powerTimeInZones` |
| Serie siłowe | `get_activity_exercise_sets(id)` | `/activity-service/activity/{id}/exerciseSets` |
| Kroki treningu strukturalnego | `get_activity_step_details(id)` | (subresource activity) |
| Wykresy/próbki (opcjonalnie, ciężkie) | `get_activity_details(id, maxchart, maxpoly)` | `/activity-service/activity/{id}/details` |
| VO2max dzień | `get_max_metrics(cdate)` | `/metrics-service/metrics/maxmet/daily/{d}/{d}` |
| VO2max zakres | `get_max_metrics_range(start, end, aggregation='daily', sport=None)` | `/metrics-service/metrics/maxmet/{aggregation}/{start}/{end}` |
| Ostatni VO2max | `get_max_metrics_latest(cdate=None)` | j.w. |
| Training status | `get_training_status(cdate)` | `/metrics-service/metrics/trainingstatus/aggregated/{d}` |
| Prognozy wyników | `get_race_predictions(startdate=None, enddate=None, _type=None)` | `/metrics-service/metrics/racepredictions/latest/{displayName}` lub `/{daily|monthly}/{displayName}?fromCalendarDate=&toCalendarDate=` |
| Próg mleczanowy (opcj.) | `get_lactate_threshold(...)` | metrics-service |

Uwagi: dla VO2max trendu najtaniej `get_max_metrics_range(..., sport="running")` jednym zapytaniem zamiast dziennych wywołań [P co do pokrycia 12 mies. w jednym zapytaniu — sprawdzić limity zakresu]. Wspinaczka: typ aktywności z `get_activity_types()`; filtr `activitytype`.

## 3. Wysyłanie treningów

### 3.1 Metody biblioteki [F]
- **Typowane modele (extra `workout`)**: `RunningWorkout`, `StrengthWorkout`, `WorkoutSegment`, helpery `create_warmup_step`, `create_interval_step`, `create_targeted_interval_step`, `create_distance_interval_step`, `create_targeted_distance_interval_step`, `create_recovery_step`, `create_cooldown_step`, `create_repeat_group`, `create_strength_set`, `create_strength_timed_set`, `create_strength_exercise_step`, `create_strength_rest_step`; cele: `PaceTarget`, `HeartRateZoneTarget`, `CustomHeartRateTarget`, `SpeedTarget`, `CadenceTarget`, `PowerZoneTarget`, `CustomPowerTarget`; `pace_to_mps(min, s, "km")`.
- Wysyłka: `upload_running_workout(w)`, `upload_strength_workout(w)` (obie robią `upload_workout(w.to_dict())`), lub surowo `upload_workout(dict)` → `POST /workout-service/workout`. Odpowiedź zawiera `workoutId`.
- Planowanie: `schedule_workout(workout_id, "YYYY-MM-DD")` → `POST /workout-service/schedule/{workoutId}` body `{"date": "YYYY-MM-DD"}` (odpowiedź zawiera id wpisu kalendarza — [P] pole `workoutScheduleId`, potwierdzić w spike).
- Zdejmowanie z kalendarza: `unschedule_workout(scheduled_workout_id)` → `DELETE /workout-service/schedule/{scheduleId}` (szablon zostaje).
- Usuwanie szablonu: `delete_workout(workout_id)` → `DELETE /workout-service/workout/{workoutId}`.
- Przeniesienie: `reschedule_workout(scheduled_workout_id, cdate)` → `PUT /workout-service/schedule/{id}`.
- Edycja w miejscu: `update_workout(workout_id, json)` → `PUT /workout-service/workout/{id}`.
- Lista zaplanowanych: `get_scheduled_workouts_by_period(start, end)` → `GET /workout-service/schedule/workoutScheduleByPeriod?startDate=&endDate=`; `get_scheduled_workouts(year, month)` → `/calendar-service/year/{y}/month/{m-1}`.
- `push_workout_to_device(workout_id, device_id)` też istnieje — niepotrzebne (zegarek bierze z kalendarza).
- **Surowy POST przez garth niepotrzebny** (i niemożliwy — garth nie jest już zależnością).
- Rekomendacja idempotencji / „usuwa tylko nasze”: zapisywać w SQLite `workoutId` + `scheduleId` + `client_id`; nazwa treningu z prefiksem np. `CG · ` ; `delete_scheduled_workout` działa tylko na id z naszej tabeli.

### 3.2 Katalog ćwiczeń siłowych [F]
- Wbudowany w bibliotekę: moduł `garminconnect.exercises` — 1527 ćwiczeń w 47 kategoriach, krotki (nazwa, `category`, `exercise`), helpery `exercises.resolve("Barbell Back Squat")` → `{'category': 'SQUAT', 'exercise': 'BARBELL_BACK_SQUAT'}` i `exercises.find("bench")`. Generowany skryptem `scripts/generate_exercises.py` z edytora treningów Garmin Connect.
- Do kreatora w aplikacji Android: wyeksportować tę listę do JSON (endpoint REST `/api/v1/exercises`), nie wpisywać ręcznie.
- `get_workout_types()` zwraca słowniki typów kroków/celów/sportów; `get_strength_benchmarks()` — ćwiczenia z 1RM.

### 3.3 Przykład JSON — bieg: rozgrzewka 10 min, 5× (1 km @ 4:30–4:15/km, 90 s trucht), schłodzenie 10 min
(wygenerowane offline `RunningWorkout(...).to_dict()`; pełny wynik, tylko przeformatowany)
```json
{
 "workoutName": "CG 5x1km",
 "sportType": {"sportTypeId": 1, "sportTypeKey": "running", "displayOrder": 1},
 "estimatedDurationInSecs": 2700,
 "workoutSegments": [{
  "segmentOrder": 1,
  "sportType": {"sportTypeId": 1, "sportTypeKey": "running"},
  "workoutSteps": [
   {"type": "ExecutableStepDTO", "stepOrder": 1,
    "stepType": {"stepTypeId": 1, "stepTypeKey": "warmup", "displayOrder": 1},
    "endCondition": {"conditionTypeId": 2, "conditionTypeKey": "time", "displayOrder": 2, "displayable": true},
    "endConditionValue": 600.0,
    "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target", "displayOrder": 1}},
   {"type": "RepeatGroupDTO", "stepOrder": 2,
    "stepType": {"stepTypeId": 6, "stepTypeKey": "repeat", "displayOrder": 6},
    "numberOfIterations": 5, "smartRepeat": false,
    "endCondition": {"conditionTypeId": 7, "conditionTypeKey": "iterations", "displayOrder": 7, "displayable": false},
    "endConditionValue": 5.0,
    "workoutSteps": [
     {"type": "ExecutableStepDTO", "stepOrder": 3,
      "stepType": {"stepTypeId": 3, "stepTypeKey": "interval", "displayOrder": 3},
      "endCondition": {"conditionTypeId": 3, "conditionTypeKey": "distance", "displayOrder": 3, "displayable": true},
      "endConditionValue": 1000.0,
      "targetType": {"workoutTargetTypeId": 6, "workoutTargetTypeKey": "pace.zone", "displayOrder": 1},
      "targetValueOne": 3.7037037037037037, "targetValueTwo": 3.9215686274509802},
     {"type": "ExecutableStepDTO", "stepOrder": 4,
      "stepType": {"stepTypeId": 4, "stepTypeKey": "recovery", "displayOrder": 4},
      "endCondition": {"conditionTypeId": 2, "conditionTypeKey": "time", "displayOrder": 2, "displayable": true},
      "endConditionValue": 90.0,
      "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target", "displayOrder": 1}}
    ]},
   {"type": "ExecutableStepDTO", "stepOrder": 5,
    "stepType": {"stepTypeId": 2, "stepTypeKey": "cooldown", "displayOrder": 2},
    "endCondition": {"conditionTypeId": 2, "conditionTypeKey": "time", "displayOrder": 2, "displayable": true},
    "endConditionValue": 600.0,
    "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target", "displayOrder": 1}}
  ]}],
 "author": {}
}
```
- Tempo podaje się w **m/s** (`pace_to_mps`), `targetValueOne` = wolniejsza granica, `targetValueTwo` = szybsza.
- Cel tętna — zamiast `targetType`/`targetValue*` powyżej:
  - strefa: `"targetType": {"workoutTargetTypeId": 4, "workoutTargetTypeKey": "heart.rate.zone", "displayOrder": 1}, "zoneNumber": 2`
  - zakres bpm: ten sam `targetType` + `"targetValueOne": 140.0, "targetValueTwo": 155.0`
- `stepOrder` musi być unikalny i rosnący w całym treningu (także wewnątrz grup powtórzeń).

### 3.4 Przykład JSON — siłowy: przysiad ze sztangą 3×5 @ 80 kg, przerwa 120 s
```json
{
 "workoutName": "CG Sila A",
 "sportType": {"sportTypeId": 5, "sportTypeKey": "strength_training", "displayOrder": 5},
 "estimatedDurationInSecs": 0,
 "workoutSegments": [{
  "segmentOrder": 1,
  "sportType": {"sportTypeId": 5, "sportTypeKey": "strength_training"},
  "workoutSteps": [
   {"type": "RepeatGroupDTO", "stepOrder": 1,
    "stepType": {"stepTypeId": 6, "stepTypeKey": "repeat", "displayOrder": 6},
    "numberOfIterations": 3, "smartRepeat": false,
    "endCondition": {"conditionTypeId": 7, "conditionTypeKey": "iterations", "displayOrder": 7, "displayable": false},
    "endConditionValue": 3.0,
    "workoutSteps": [
     {"type": "ExecutableStepDTO", "stepOrder": 2,
      "stepType": {"stepTypeId": 3, "stepTypeKey": "interval", "displayOrder": 3},
      "endCondition": {"conditionTypeId": 10, "conditionTypeKey": "reps", "displayOrder": 10, "displayable": true},
      "endConditionValue": 5.0,
      "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target", "displayOrder": 1},
      "category": "SQUAT", "exerciseName": "BARBELL_BACK_SQUAT",
      "weightValue": 80000.0,
      "weightUnit": {"unitId": 8, "unitKey": "kilogram", "factor": 1000.0}},
     {"type": "ExecutableStepDTO", "stepOrder": 3,
      "stepType": {"stepTypeId": 5, "stepTypeKey": "rest", "displayOrder": 5},
      "endCondition": {"conditionTypeId": 2, "conditionTypeKey": "time", "displayOrder": 2, "displayable": true},
      "endConditionValue": 120.0,
      "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target", "displayOrder": 1}}
    ]}
  ]}],
 "author": {}
}
```
- **Pułapka [F, komentarz w kodzie]:** `weightValue` jest w **gramach** (80 kg → 80000.0) z jednostką `kilogram` i `factor: 1000`. Używać helpera `create_strength_set(..., weight_kg=80)`, nie liczyć ręcznie.
- Ćwiczenia na czas (plank): `create_strength_timed_set` → `endCondition` `time` zamiast `reps`.
- Kolejne ćwiczenie: następny `create_strength_set` ze `step_order` += 3.
- Rekomendacja: backend buduje JSON **wyłącznie** przez typowane modele biblioteki (walidacja Pydantic), nasz własny schemat `definition` (MCP/REST) mapujemy na helpery.

### 3.5 Do potwierdzenia w spike'u na Enduro 3 (implementacja: `server/src/cg_server/outbox.py`)

Uruchomienie: `cg-server workout-test --user tomek --date RRRR-MM-DD --kind run` (potem `--kind strength`), następnie synchronizacja zegarka z aplikacją Garmin Connect. Serwer loguje raz na proces **same nazwy pól** (bez wartości) odpowiedzi Garmina: `Garmin schedule_workout answer keys: [...]` i `Garmin workoutScheduleByPeriod entry answer keys: [...]`. Do sprawdzenia i wpisania tutaj:

1. Pole z id wpisu kalendarza w odpowiedzi `schedule_workout` [P]. Kod próbuje kolejno `workoutScheduleId`, `scheduleId`, `id`.
2. Pola wpisu w `get_scheduled_workouts_by_period` [P]: id treningu (`workoutId` albo `workout.workoutId`) i id wpisu (jak w p. 1).
3. Czy trening (bieg z celem tempa, siła z ciężarem w kg) wyświetla się na zegarku poprawnie i czy jest widoczny ciężar.

Zachowanie, dopóki pola nie są potwierdzone: jeśli odpowiedź nie ma id, serwer szuka wpisu w kalendarzu (trening + dzień). Wpis znaleziony bez id traktujemy jako już zaplanowany (nigdy nie planujemy drugi raz). Usuwanie takiego wpisu bez ustalonego id kończy się błędem `delete_failed: ...` (status `failed`, do usunięcia ręcznie w Garmin Connect), nigdy fałszywym „deleted”.

## 4. Zegarki: Enduro 3 i Fenix 8 / 8 Pro [F z instrukcji obsługi]

| Funkcja | Enduro 3 | Fenix 8 (seria, w tym 8 Pro) |
|---|---|---|
| Strukturalne treningi z Garmin Connect, cele kroków, kalendarz treningowy | **TAK** (s. 10–11, 14: „All scheduled workouts sent to the device appear in the calendar glance”) | **TAK** (s. 10–14, ten sam tekst) |
| Treningi siłowe z Garmin Connect | **TAK** — s. 35–36: „You can create and find strength workouts in your Garmin Connect account and send them to your watch… To follow a structured workout, select a workout… view the exercise animation (if available)” | **TAK** — s. 39: identyczne zdania; zegarek liczy powtórzenia, można edytować powtórzenia i ciężar serii |
| Fenix 8 Pro objęty instrukcją | — | TAK (instrukcja serii fēnix 8, sekcja LTE/satelita dla 8 Pro, s. 6, 98) |

- Instrukcje nie mówią wprost, czy docelowy **ciężar** z treningu jest pokazywany na zegarku [P — sprawdzić w spike M0].
- Znany problem z przeszłości: wątek na forum Garmin o niesynchronizowaniu zaplanowanych treningów na Enduro 3 (fw 13.37, aplikacja iOS 5.12) — bug po stronie aplikacji Garmin Connect, nie API; my mamy Androida [F że zgłoszenie istnieje; aktualność P].
- Wysłanie nowych zaplanowanych treningów nadpisuje kalendarz na zegarku; trening zostaje na zegarku po wykonaniu/pominięciu [F, s. 14].

## 5. Rate limiting i ryzyko blokady

Fakty [F]:
- 429 przy logowaniu to najczęstszy problem 2026: issue #337 (429 OAuth Preauthorized), #344 (2026-04: limit jest **per konto + clientId**, nie per IP/UA; zmiana IP/UA nie pomaga; fix: strategia `widget+cffi` w 0.3.2, ale nie u wszystkich), #453 (otwarte, 2026-10-07: 429 przy pierwszym żądaniu SSO w 0.3.17, bez `Retry-After`, utrzymuje się >24 h). PR #449 / 0.3.17: komunikat 429 „nie obwinia samego IP” — Garmin może blokować po **odcisku klienta (fingerprint)**.
- Biblioteka sama robi opóźnienia w SSO (`time.sleep` w `client/sso.py`) i retry w części wywołań.
- Nie znalazłem doniesień o trwałym banie konta za normalne używanie API danych; problemy dotyczą głównie endpointów logowania [P co do braku banów — brak twardych danych].

Dobre praktyki (rekomendacje) [P, wynikające z powyższego]:
1. **Logować się hasłem raz**, potem wyłącznie tokeny + auto-refresh. Nigdy nie robić automatycznego ponownego logowania hasłem (hasła i tak nie przechowujemy — dobrze).
2. Po 429 przy logowaniu: **stop, nie ponawiać** przez co najmniej kilka godzin (najlepiej 24 h); komunikat dla Tomka.
3. Sync danych: sekwencyjnie, 1 użytkownik naraz, przerwa ~1–2 s między żądaniami; pierwszy sync 12 mies. rozłożyć (np. paczki po 20 aktywności + pauza); szczegóły tylko dla **nowych** aktywności (zakładka 3 dni = tylko lista, nie pełne detale ponownie).
4. Nie pobierać ciężkich `get_activity_details` (próbki) jeśli nie są potrzebne — splity/laps/strefy wystarczą do zakresu v1.0.
5. Backoff wykładniczy na 429/5xx dla API danych; limit np. max kilkaset żądań/dzień/użytkownika.
6. Przypięta wersja + Renovate/dependabot tylko ręcznie zatwierdzany; śledzić release notes (logowanie zmienia się co kilka tygodni — 0.3.x ma 22 wydania w 7 miesięcy).
7. Jeden adres IP/urządzenie (laptop) — nie mieszać logowań z wielu miejsc (np. przyszłe Chaquopy) na tym samym koncie.

## 6. Chaquopy (Python na Androidzie) — ocena

Fakty [F]: Chaquopy jest **open source na licencji MIT, darmowy** od 2022-07-24 (wersja 12.0.1). `garminconnect` 0.3.x jest czysto pythonowy, ale wymaga **`curl_cffi` (natywny, libcurl-impersonate)**; na PyPI `curl_cffi` 0.17.0 ma koła `android_24_arm64_v8a` dla cp313/cp314/cp315 (PEP 738). `requests`, `ua-generator` — czysty Python. Extra `workout` wymaga `pydantic-core` (Rust) — **brak kół Androida na PyPI**.
Ocena [P]: uruchomienie samego syncu (bez typowanych modeli treningów) na telefonie jest **prawdopodobnie wykonalne**, pod warunkiem że używana wersja Chaquopy potrafi instalować standardowe koła Androida z PyPI dla Pythona 3.13 (do weryfikacji — historycznie Chaquopy używał własnego repozytorium pakietów natywnych). Ryzyka: inny odcisk TLS/urządzenia → osobne logowanie z telefonu może wywołać 429/MFA; podwójne tokeny dla jednego konta; utrzymanie dwóch środowisk. Rekomendacja: zgodnie z §3.6 — **nie w v1.0**; w v1.1 tylko jeśli laptop faktycznie często jest wyłączony, a najpierw krótki spike (APK z Chaquopy + `import curl_cffi`).

## Ryzyka (podsumowanie)

| Ryzyko | Ocena | Mitygacja |
|---|---|---|
| Garmin zmieni SSO / 429 przy logowaniu | **wysokie** (dzieje się regularnie w 2026) | logowanie raz, tokeny + refresh, przypięta wersja, szybka aktualizacja po release, plan B intervals.icu |
| Refresh token wygaśnie (czas nieznany) | średnie | wykrycie błędu auth → status w app/`get_profile` → Tomek uruchamia `garmin-login` |
| Master Prompt opisuje tokeny garth | pewne (dokument nieaktualny) | poprawić §3.1 i ADR: tokeny DI z garminconnect |
| Ciężar/ćwiczenia siłowe źle wyświetlone na zegarku | niskie–średnie | spike M0: 1 trening siłowy na koncie Tomka, sprawdzenie na Enduro 3 |
| Zmiany w strukturze JSON odpowiedzi (exerciseSets, splits) | średnie | przechowywać surowy JSON obok sparsowanych pól, testy na nagranych fixture'ach (bez danych osobowych w repo!) |
| Python 3.12 wymagany przez garminconnect | brak (projekt i tak 3.12) | — |

## Źródła (sprawdzone 2026-11-18)

- PyPI garminconnect (JSON API, wersje/daty/zależności): https://pypi.org/project/garminconnect/ — 0.3.20, 2026-10-23
- PyPI garth: https://pypi.org/project/garth/ — 0.8.0, 2026-03-28; README w sdist 0.8.0 (deprecation)
- Kod źródłowy garminconnect 0.3.20 (sdist z PyPI): `api/workouts.py`, `api/activities.py`, `api/metrics.py`, `api/base.py`, `workout.py`, `exercises.py`, `client/tokens.py`, `client/session.py`, README (sekcje Authentication, Typed Workouts)
- Repo: https://github.com/cyberjunky/python-garminconnect ; release notes: https://github.com/cyberjunky/python-garminconnect/releases
- garth deprecation: https://github.com/matin/garth/discussions/222 (2026-03-27), https://github.com/matin/garth/issues/217
- Issues 429: https://github.com/cyberjunky/python-garminconnect/issues/337 , https://github.com/cyberjunky/python-garminconnect/issues/344 , https://github.com/cyberjunky/python-garminconnect/issues/453 , PR https://github.com/cyberjunky/python-garminconnect/pull/449
- CVE-2026-54447: https://www.miggo.io/vulnerability-database/cve/CVE-2026-54447
- Instrukcja Enduro 3: https://www8.garmin.com/manuals/webhelp/GUID-7AD1A592-9044-4D84-9688-7B5209F85BFF/EN-US/Enduro_3_OM_EN-US.pdf (s. 10–11, 14, 35–36)
- Instrukcja serii fēnix 8: https://www8.garmin.com/manuals/webhelp/GUID-EECCAC99-90D6-4AB1-9A3A-EC433D3365E2/EN-US/fenix_8_Series_OM_EN-US.pdf (s. 6, 10–14, 39, 98)
- Forum Garmin (Enduro 3, zaplanowane treningi): https://forums.garmin.com/outdoor-recreation/outdoor-recreation/f/enduro-3/411116/scheduled-workouts-are-not-sent-anymore-to-my-enduro-3-13-37
- Chaquopy open source (MIT): https://chaquo.com/chaquopy/chaquopy-is-now-open-source (via czytnik), repo https://github.com/chaquo/chaquopy
- PyPI curl-cffi (koła Android): https://pypi.org/project/curl-cffi/ ; PEP 738: https://peps.python.org/738
