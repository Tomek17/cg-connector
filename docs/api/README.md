# API CG-connector

Obowiązujący kontrakt REST dla aplikacji Android to [`openapi.json`](openapi.json), generowany przez
FastAPI. Nie edytuj go ręcznie. Po każdej zmianie API wygeneruj go ponownie:

```bash
cd server
CG_ENV=test uv run cg-server export-openapi ../docs/api/openapi.json
```

CI (`ci-server.yml`) i test `tests/test_openapi.py` sprawdzają, czy plik jest aktualny.

## REST `/api/v1` (aplikacja Android)

- Nagłówek `Authorization: Bearer <token aplikacji>` (`cg-server app-token --user <nazwa>`).
  Użytkownika wyznacza **wyłącznie** token. Brak albo zły token daje `401`.
- JSON w `snake_case`. Pola bez wartości mają `null`; klient powinien ignorować nieznane pola.
- Jednostki SI: m, s, bpm, W, kroki/min, kg. Pola wygodne: `pace_min_per_km` (minuty dziesiętnie,
  `5.5` = 5:30 min/km, tylko bieg).
- Czas to ISO-8601 z offsetem strefy **Europe/Warsaw** (`2026-11-17T07:30:00+01:00`). Daty
  (`from`, `to`, `date`) to dni kalendarzowe w Europe/Warsaw, oba końce włącznie.
- Błędy: `401` (token), `404` (brak aktywności, także cudzej), `422` (złe parametry, np. `from` > `to`).
  Treść błędu to `{"detail": ...}` bez szczegółów technicznych.

| Endpoint | Opis | Domyślnie |
|---|---|---|
| `GET /me` | użytkownik, status Garmina, ostatni udany sync | |
| `GET /activities?from&to&type&limit` | podsumowania aktywności, od najnowszej | ostatnie 365 dni, `limit` 2000 (maks.) |
| `GET /activities/{id}` | szczegóły: okrążenia, czas w strefach HR, serie siłowe | |
| `GET /vo2max?from&to` | punkty VO2max (wszystkie sporty, pole `sport`) | ostatnie 365 dni |
| `GET /summary?period=week\|month&from&to` | sumy per okres i typ | 12 ostatnich okresów; maks. 2 lata |

### `GET /me`

`name`, `display_name`, `garmin_status` (`ok` / `needs_login` / `rate_limited` / `null`),
`last_sync_at` (ostatnia **udana** synchronizacja), `last_sync_attempt_at` i `last_sync_status`
(ostatnia próba, także nieudana), `latest_activity_date`.

### `GET /activities`

`{"items": [...], "has_more": false}`. Element: `id` (ID aktywności w Garmin Connect, stabilny klucz
upsertu), `type` (`running` / `strength` / `climbing` / `other`), `type_key` (surowy typ Garmina),
`name`, `start_time`, `duration_sec`, `moving_duration_sec`, `distance_m`, `avg_hr`, `max_hr`,
`elevation_gain_m`, `avg_power_w`, `avg_cadence_spm`, `avg_speed_mps`, `pace_min_per_km`,
`aerobic_training_effect`, `anaerobic_training_effect`, `training_load`.

`has_more: true` oznacza, że w przedziale jest więcej aktywności niż `limit`. Klient pobiera wtedy
resztę, ustawiając `to` na dzień najstarszej otrzymanej aktywności (duplikaty z tego dnia rozwiązuje
upsert po `id`).

### `GET /activities/{id}`

Pola z listy oraz `details_available`, `laps[]` (`index`, `distance_m`, `duration_sec`, `avg_hr`,
`max_hr`, `avg_speed_mps`, `pace_min_per_km`, `elevation_gain_m`, `avg_power_w`, `avg_cadence_spm`),
`hr_zones[]` (`zone`, `seconds`, `low_bpm`), `exercise_sets[]` (`set_type` `ACTIVE`/`REST`,
`category`, `exercise`, `reps`, `weight_kg`, `duration_sec`). Gdy szczegółów nie udało się pobrać z
Garmina, `details_available` = `false`, a listy są puste.

### `GET /summary`

`{"period": "week", "from": "...", "to": "...", "items": [...]}`. Element: `period_start`,
`period_end` (tydzień od poniedziałku do niedzieli albo miesiąc kalendarzowy), `type`, `count`,
`distance_m`, `duration_sec`, `elevation_gain_m`, `avg_pace_min_per_km` (bieg: łączny czas / łączny
dystans). Sumowane są tylko aktywności z dni `[from, to]`, więc pierwszy i ostatni okres mogą być
niepełne. Okresy bez aktywności są pomijane.

## Rozbieżności z propozycją z `android/README.md`

| Propozycja aplikacji | Stan w API | Powód / co zrobić w aplikacji |
|---|---|---|
| `/me`: `user_id` | brak | wewnętrzny identyfikator nie jest potrzebny aplikacji; użytkownika wyznacza token |
| `/me`: `watch_model` | brak | serwer nie pobiera listy urządzeń z Garmina (dane urządzeń są celowo odrzucane przy syncu) |
| `/me`: `display_name` | jest, równe `name` | nazwa użytkownika z `cg-server user-add` |
| `/me`: tylko `last_sync_at` | dodatkowo `garmin_status`, `last_sync_attempt_at`, `last_sync_status`, `latest_activity_date` | ekran Status może pokazać przyczynę braku danych |
| `/activities`: `from` i `to` wymagane | opcjonalne | domyślnie ostatnie 365 dni do dziś |
| `/activities`: filtr typu po stronie klienta | dodatkowo `?type=` i `?limit=` | |
| Pytanie 1: paginacja przy 365 dniach | `limit` (domyślnie i maks. 2000) + `has_more` | rok to typowo kilkaset aktywności, więc w praktyce jedno zapytanie |
| Pytanie 2: aktywności usunięte w Garmin Connect | nieobsługiwane w v1.0 | sync nie usuwa aktywności; ewentualne `deleted_ids` / `updated_since` później (issue `api-change`) |
| Pytanie 3: `GET /activities/{id}` | jest już teraz | |
| `start_time` w czasie lokalnym zegarka | w strefie Europe/Warsaw | czas przeliczony z UTC; dla aktywności z innej strefy godzina różni się od tej na zegarku |
| `duration_sec` wymagane | zawsze liczba całkowita | `0`, jeśli Garmin nie podał czasu |
| `avg_hr`, `max_hr`, `avg_power_w`, `avg_cadence_spm` int | int (zaokrąglone) | zgodnie z propozycją |
| dodatkowe pola | `type_key`, `moving_duration_sec`, `avg_speed_mps`, `pace_min_per_km`, efekty treningowe, `training_load` | klient może je ignorować |

## Narzędzia MCP (`/mcp`, claude.ai)

Użytkownik zawsze z tokenu OAuth, nigdy z argumentu. Odpowiedzi to zwięzły JSON (bez `null`),
jednostki km, min/km (`"5:00"`), bpm, czasy lokalne Europe/Warsaw (`"2026-11-17 07:30"`). Gdy brak
danych w zakresie, sync jest starszy niż 24 h, nigdy się nie odbył albo trzeba ponownie zalogować
Garmina, odpowiedź ma krótkie pole `note`.

| Narzędzie | Argumenty | Domyślnie |
|---|---|---|
| `get_profile` | | |
| `list_activities` | `from_date?`, `to_date?`, `type?`, `limit?` (1–50) | ostatnie 30 dni, 20 aktywności (`more: true`, gdy jest więcej) |
| `get_activity` | `activity_id` (ID z `list_activities`) | do 30 okrążeń, minuty w strefach HR, serie siłowe jako `"5x80kg"` |
| `get_vo2max_trend` | `from_date?`, `to_date?` | ostatnie 180 dni, bieg; tylko zmiany wartości, przy >60 punktach tygodniowo/miesięcznie; maks. 2 lata |
| `get_training_summary` | `period` (`week`/`month`), `from_date?`, `to_date?` | 8 tygodni / 6 miesięcy; pełne okresy; maks. 26 tygodni / 24 miesiące |

Argumenty nazywają się `from_date`/`to_date`, bo `from` jest słowem kluczowym Pythona.
Lista 20 aktywności mieści się w ok. 3–4 kB JSON (test `test_list_of_20_activities_is_small`).
