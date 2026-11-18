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
