# cg-android

Aplikacja Android CG-connector (Kotlin, Jetpack Compose Material 3). Stan: **M1, szkielet**.

## Co jest w M1

- Ekran **Konfiguracja**: adres serwera (tylko `https://`) i token aplikacji.
  Adres jest w DataStore jawnie, token jest zaszyfrowany AES-256-GCM kluczem z **Android Keystore**
  (`KeystoreTokenCipher`). Nie używamy `EncryptedSharedPreferences`, bo `androidx.security:security-crypto`
  jest wycofywane. Zapisany token nigdy nie wraca do pola tekstowego. Puste pole oznacza „zostaw obecny”.
- `network_security_config`: brak cleartext, tylko systemowe CA. Kopie zapasowe są wyłączone
  (`allowBackup=false` i `data_extraction_rules`).
- **Room**: tabela `activities` (`ActivityEntity` i `ActivityDao`). Ekran **Treningi** czyta tylko z Room
  (offline-first), ma filtr typu (bieg, siłownia, wspinaczka, inne) oraz „Odśwież” i pull-to-refresh.
- **Repozytorium** `OfflineFirstActivityRepository`: przy odświeżeniu pobiera z serwera dane od
  (najnowsza lokalna aktywność − 3 dni), a przy pierwszym uruchomieniu za 365 dni. Wynik zapisuje do Room.
  Niedostępny serwer to wynik `Unavailable`, nie błąd. Dane lokalne zostają.
- **WorkManager**: `RefreshWorker` raz dziennie ok. 14:20 (Europe/Warsaw), z ponowieniami co 1 h
  (backoff liniowy), maksymalnie 9 prób, czyli mniej więcej do 23:00.
- **Dane na razie są fałszywe.** `FakeActivityRemoteDataSource` generuje deterministyczne dane syntetyczne,
  bo serwer nie ma jeszcze `/api/v1`. Przełącznik to `USE_FAKE_API` w `app/build.gradle.kts`.
  Klient Retrofit (`CgApi`, `ApiActivityRemoteDataSource`) jest gotowy.

## Budowanie

Wymagania: JDK 17+ (CI używa 21) i Android SDK z platformą 37 (`ANDROID_HOME` albo `local.properties`).

```bash
cd android
./gradlew assembleDebug        # APK debug
./gradlew testDebugUnitTest    # testy jednostkowe (JVM + Robolectric, bez emulatora)
./gradlew lintDebug
```

CI: `.github/workflows/ci-android.yml`.

Wersje bibliotek są przypięte w `gradle/libs.versions.toml` (sprawdzone 2026-11-18). Aktualizuje je
Dependabot (ekosystem `gradle`). Wrapper Gradle ma przypiętą sumę SHA-256 dystrybucji.

Po pierwszym buildzie Room zapisuje schemat bazy w `app/schemas/`. Ten plik trzeba zacommitować,
bo jest potrzebny do przyszłych migracji.

## Struktura

```
app/src/main/java/pl/cgconnector/app/
├─ domain/          # modele i walidacja (czysty Kotlin)
├─ data/local/      # Room: encja, DAO, baza
├─ data/remote/     # CgApi (Retrofit), DTO, źródła danych (fake / API)
├─ data/settings/   # konfiguracja: DataStore + szyfrowanie tokenu (Keystore)
├─ data/repository/ # offline-first repozytorium treningów
├─ ui/              # Compose: konfiguracja, lista treningów, nawigacja, motyw
├─ work/            # WorkManager: codzienne odświeżanie
└─ di/              # moduły Hilt
```

## Propozycja kontraktu API (do uzgodnienia z backendem)

Obowiązujący kontrakt powstanie jako `docs/api/openapi.json` z FastAPI. Do tego czasu aplikacja zakłada
poniższy kontrakt (`data/remote/CgApi.kt`, `data/remote/Dto.kt`). Zmiany prosimy zgłaszać w issue z
etykietą `api-change`.

**Wspólne zasady**

- Bazowy adres to adres tunelu, np. `https://cg-server.<tailnet>.ts.net`. Ścieżki zaczynają się od `/api/v1/`.
- Nagłówek `Authorization: Bearer <token aplikacji>`. Użytkownika wyznacza **wyłącznie** token.
  Odpowiedź 401 oznacza zły lub unieważniony token.
- JSON w `snake_case`. Pola opcjonalne mogą być pominięte albo `null`. Klient ignoruje nieznane pola.
- Czas to ISO-8601 z offsetem (`2026-11-17T07:30:00+01:00`), daty to `YYYY-MM-DD`, jednostki SI
  (m, s, bpm, W, kroki/min).

### `GET /api/v1/me`

```json
{
  "user_id": "u_7f3a",
  "display_name": "Użytkownik A",
  "watch_model": "Enduro 3",
  "last_sync_at": "2026-11-18T14:03:12+01:00"
}
```

`last_sync_at` to ostatnia **udana** synchronizacja serwera z Garmin Connect (`null`, jeśli nie było żadnej).
Aplikacja pokaże ją na ekranie Status i ostrzeże, gdy jest starsza niż ok. 24 h.

### `GET /api/v1/activities?from=YYYY-MM-DD&to=YYYY-MM-DD`

Podsumowania aktywności, których początek wypada w przedziale `[from, to]` (oba końce włącznie,
daty w strefie Europe/Warsaw), posortowane malejąco po `start_time`.

```json
{
  "items": [
    {
      "id": 18234567890,
      "type": "running",
      "name": "Bieg spokojny",
      "start_time": "2026-11-17T07:30:00+01:00",
      "duration_sec": 2700,
      "distance_m": 8012.5,
      "avg_hr": 142,
      "max_hr": 158,
      "elevation_gain_m": 45.0,
      "avg_power_w": 265,
      "avg_cadence_spm": 172
    }
  ]
}
```

| Pole | Typ | Uwagi |
|---|---|---|
| `id` | int64 | stabilny identyfikator, klucz upsertu po stronie aplikacji (np. ID aktywności Garmina) |
| `type` | string | `running` \| `strength` \| `climbing` \| `other`; nieznane wartości klient traktuje jak `other` |
| `name` | string? | |
| `start_time` | string | ISO-8601 z offsetem |
| `duration_sec` | int64 | czas trwania (elapsed) |
| `distance_m`, `elevation_gain_m` | number? | brak dla siłowni i ścianki |
| `avg_hr`, `max_hr` | int? | bpm |
| `avg_power_w` | int? | moc biegowa |
| `avg_cadence_spm` | int? | kroki/min |

Otwarte pytania do backendu:

1. Czy przy pierwszej synchronizacji (365 dni) potrzebna jest paginacja (`cursor`/`limit`)? Odpowiedź jest
   opakowana w obiekt `{"items": …}`, więc da się ją dodać bez łamania kontraktu.
2. Czy aktywności usunięte w Garmin Connect mają znikać z aplikacji (wtedy potrzebne np. `deleted_ids`
   albo `updated_since`)?
3. Szczegóły aktywności (okrążenia, strefy, serie siłowe) proponujemy jako osobny
   `GET /api/v1/activities/{id}` w kolejnym kamieniu milowym.
