# cg-android

Aplikacja Android CG-connector (Kotlin, Jetpack Compose Material 3). Stan: **M2, ekrany danych**.

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
- **WorkManager**: łańcuch jednorazowych zadań `RefreshWorker` pod unikalną nazwą. Pierwsze startuje
  o najbliższej 14:20 (Europe/Warsaw, poprawnie przy zmianie czasu). Każde uruchomienie planuje
  następne: przy braku serwera za 1 h, ale tylko do 23:00; w pozostałych przypadkach jutro o 14:20.
  Logika jest w czystej funkcji `RefreshSchedule` z testami. Start aplikacji tylko odtwarza łańcuch
  (`KEEP`), gdy go brakuje.
- **Źródło danych zależy od buildu** (`USE_FAKE_API` w `app/build.gradle.kts`): **debug** używa
  `FakeActivityRemoteDataSource` (deterministyczne dane syntetyczne, bez serwera), **release** łączy się
  z `/api/v1` przez `ApiActivityRemoteDataSource`.

## Co doszło w M2

- **Nawigacja:** dolny pasek Treningi / VO2max / Status / Ustawienia. Kliknięcie treningu otwiera szczegóły.
- **Szczegóły treningu:** podsumowanie, okrążenia z tempem min/km, strefy tętna i serie siłowe.
  Szczegóły pobierają się z `GET /activities/{id}` przy pierwszym otwarciu i zostają w Room.
  Offline ekran pokazuje to, co jest w bazie, i komunikat. Przycisk „Odśwież” pobiera je ponownie.
- **VO2max:** aktualna wartość, zmiana w ostatnich 90 dniach i wykres trendu rysowany w `Canvas`,
  bez zewnętrznej biblioteki.
- **Status:** serwer online/offline (wynik ostatniej próby połączenia), ostatni sync serwera z Garminem
  (z `/me`), ostatnie udane odświeżenie aplikacji, stan konta Garmin i bieżący tydzień z `/summary`.
  Ostrzeżenie „dane mogą być nieaktualne” pojawia się, gdy serwer nie zsynchronizował się z Garminem od ponad
  26 h albo aplikacja nie odświeżyła danych od ponad 30 h. Progi mają zapas, bo przy progu 24 h ostrzeżenie
  pojawiałoby się codziennie między syncem serwera (8:00, 17:00, 21:00) a odświeżeniem aplikacji (20 minut później).
  Ekran przelicza to co minutę, gdy jest widoczny. Po świeżej instalacji zamiast ostrzeżenia jest komunikat
  „Dane nie były jeszcze pobrane”.
- **Odświeżanie** (ręczne i WorkManager) pobiera po kolei: treningi, VO2max (od najnowszego lokalnego
  pomiaru − 3 dni), `/me` i podsumowanie tygodni. Wynik każdej próby trafia do tabeli `sync_status`.
- **Room w wersji 2** (migracja automatyczna 1→2): tabele `activity_details`, `activity_laps`,
  `activity_hr_zones`, `activity_exercise_sets`, `vo2max`, `summaries`, `sync_status` oraz nowe kolumny
  NULL w `activities` (tempo, TE, obciążenie). `MigrationTest` sprawdza migrację na pliku bazy w wersji 1.

## Budowanie

Wymagania: JDK 17+ (CI używa 21) i Android SDK z platformą 37 (`ANDROID_HOME` albo `local.properties`).

```bash
cd android
./gradlew assembleDebug        # APK debug
./gradlew testDebugUnitTest    # testy jednostkowe (JVM + Robolectric, bez emulatora)
./gradlew lintDebug
```

CI: `.github/workflows/ci-android.yml`. Wydanie podpisanego APK (tag `vX.Y.Z`): [docs/release.md](../docs/release.md).

Wersje bibliotek są przypięte w `gradle/libs.versions.toml` (sprawdzone 2026-11-18). Aktualizuje je
Dependabot (ekosystem `gradle`). Wrapper Gradle ma przypiętą sumę SHA-256 dystrybucji.

Build zapisuje schemat bazy Room w `app/schemas/<wersja>.json`. Pliki trzeba commitować, bo migracje
automatyczne są generowane na ich podstawie. CI sprawdza, że zacommitowany schemat zgadza się z kodem.

## Struktura

```
app/src/main/java/pl/cgconnector/app/
├─ domain/          # modele i walidacja (czysty Kotlin)
├─ data/local/      # Room: encje, DAO, baza
├─ data/remote/     # CgApi (Retrofit), DTO, źródła danych (fake / API)
├─ data/settings/   # konfiguracja: DataStore + szyfrowanie tokenu (Keystore)
├─ data/repository/ # offline-first: treningi i szczegóły, VO2max, podsumowania, status
├─ ui/              # Compose: konfiguracja, treningi, szczegóły, VO2max, status, nawigacja, motyw
│  └─ format/       # formatowanie czasu, tempa, dat (czyste funkcje)
├─ work/            # WorkManager: codzienne odświeżanie
└─ di/              # moduły Hilt
```

## Kontrakt API

Obowiązuje `docs/api/openapi.json` (generowany przez FastAPI) i jego opis w `docs/api/README.md`.
Aplikacja odwzorowuje go w `data/remote/CgApi.kt` i `data/remote/Dto.kt`: `GET /api/v1/me`,
`/activities?from&to&type&limit` (z obsługą `has_more`), `/activities/{id}`, `/vo2max?from&to` oraz
`/summary?period&from&to`. Testy parsowania są w `DtoParsingTest`. Zmiany kontraktu zgłasza się w issue
z etykietą `api-change`.

Obsługa odpowiedzi przy odświeżaniu:

| Sytuacja | Wynik w aplikacji |
|---|---|
| brak sieci, timeout, 5xx | `Unavailable`: dane lokalne zostają, WorkManager ponawia co ok. 1 h do 23:00 |
| 401 | `Unauthorized`: bez ponowień, komunikat „wklej nowy token” |
| nieprawidłowa odpowiedź (JSON, inne 4xx) | `InvalidResponse`: bez ponowień, komunikat o niezgodności wersji |
