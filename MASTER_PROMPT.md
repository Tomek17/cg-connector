# CG-connector — Master Prompt

> Wspólna instrukcja dla wszystkich agentów pracujących nad CG-connector.
> Wersja 1.0 · 2026-11-18 · Właściciel produktu: Tomek (GitHub: `Tomek17`)
> Każdy agent czyta ten dokument w całości przed rozpoczęciem pracy. W razie konfliktu: polecenie Tomka > ten dokument > ADR-y w repo > Twoja własna ocena.

---

## 1. Cel

CG-connector łączy zegarki Garmin z Claude. Ma pozwolić:

1. **Pobierać dane treningowe** z Garmin Connect (treningi + VO2max) raz dziennie i trzymać je w telefonie.
2. **Rozmawiać o nich z Claude** w zwykłym oknie czatu claude.ai (także w darmowym planie) przez własny konektor MCP.
3. **Wysyłać treningi na zegarek**: trening ułożony w aplikacji albo przez Claude trafia do kalendarza Garmin Connect, skąd zegarek pobiera go sam.

**Termin:** wersja 1.0 gotowa ok. **2026-12-02** (2 tygodnie od startu).
**Budżet:** 0 zł. Żadnych płatnych usług, domen, kont deweloperskich ani subskrypcji.

### Użytkownicy

| Osoba | Zegarek | Konto Garmin | Claude |
|---|---|---|---|
| Tomek | Garmin Enduro 3 | własne | ma subskrypcję |
| Brat Tomka | Garmin Fenix 8 Pro | własne | **darmowy plan** |

System od początku jest **wieloużytkownikowy**: dane, tokeny i treningi każdej osoby są ściśle rozdzielone.

### Zakres danych

- **W zakresie:** aktywności (podsumowanie, okrążenia/splity, tętno, tempo, przewyższenia, moc biegowa, kadencja, serie ćwiczeń siłowych), typ aktywności (bieg, siłownia, wspinaczka/ścianka, inne), VO2max (bieg) i jego trend. Opcjonalnie, jeśli tanie w implementacji: prognozy wyników, status treningowy, obciążenie.
- **Poza zakresem (nie implementować):** sen, HRV, Body Battery, stres, SpO2. Tomek nie śpi z zegarkiem.
- **Poza zakresem v1.0:** iOS, aplikacja webowa, publikacja w Google Play, analizy AI w samej aplikacji (od tego jest Claude).

---

## 2. Twarde ograniczenia

1. **Serwerem jest laptop Tomka i nie działa 24/7.** Tomek włącza go sam, ręcznie; przez większość dnia jest włączony, ale bywa uśpiony (zamknięta klapa) albo wyłączony i zmienia sieci (dom, praca, hotspot). **Bez automatycznego wybudzania i bez zmian w BIOS.** System musi poprawnie działać przy dowolnych przerwach w pracy laptopa.
   - *Przyszłość (poza v1.0):* przeniesienie serwera na stacjonarny PC w Łomży. Kod i instrukcje piszemy tak, żeby ta migracja była przeniesieniem folderu z danymi + `.env` i ponownym uruchomieniem, bez zmian w kodzie.
2. **Windows + WSL Ubuntu.** Usługi działają w Dockerze w WSL.
3. **Dostęp spoza domu jest konieczny**, bez otwierania portów na routerze.
4. **Repo jest publiczne.** Zero sekretów, haseł, tokenów i danych osobowych/zdrowotnych w repo, w historii gita, w issues, w PR-ach i w logach CI.
5. **0 zł.**
6. **Garmin Connect przez nieoficjalną bibliotekę** `python-garminconnect` (od 0.3.0 z własnym klientem, bez wycofanego `garth`; przypięta `garminconnect[workout]==0.3.20`, patrz `docs/research/garmin.md`). Wybór Tomka: pełna kontrola. To ryzyko (sekcja 9).
7. **Natywna aplikacja Android** na Samsung Galaxy S23 Ultra (Android 14+).

### Co z tego wynika dla użytkownika (musi trafić do dokumentacji)

- Dane w telefonie są dostępne **cały dzień, offline**.
- **Claude (MCP) i wysyłka treningów działają tylko wtedy, gdy laptop jest włączony** (i ma internet). Gdy jest wyłączony, Claude dostaje błąd „serwer niedostępny”, a aplikacja **kolejkuje** wysyłkę treningu i wysyła go przy następnym kontakcie z serwerem.
- Synchronizacja odbywa się o 14:00, jeśli laptop wtedy działa; w przeciwnym razie zaraz po jego włączeniu/wybudzeniu (catch-up). Aplikacja zawsze pokazuje datę ostatniej udanej synchronizacji i ostrzeżenie, gdy jest starsza niż ~24 h.

---

## 3. Architektura

```
 Garmin Enduro 3 / Fenix 8 Pro
          │  (Bluetooth, aplikacja Garmin Connect na telefonie)
          ▼
   Garmin Connect (chmura Garmina)
          ▲ │   python-garminconnect (nieoficjalne API)
          │ ▼
 ┌──────────────── Laptop Tomka (Windows → WSL Ubuntu → Docker) ─────────────┐
 │  cg-server (Python 3.12, FastAPI)                                          │
 │   ├─ sync worker   — 14:00 pobiera aktywności + VO2max dla każdego usera   │
 │   ├─ REST API      — /api/v1/... dla aplikacji Android                     │
 │   ├─ MCP endpoint  — /mcp (Streamable HTTP) dla claude.ai                  │
 │   ├─ outbox        — kolejka treningów do wysłania do Garmin Connect       │
 │   └─ SQLite (plik na wolumenie) + zaszyfrowane tokeny Garmina              │
 │  tunel (Tailscale Funnel lub Cloudflare Tunnel) → publiczny HTTPS          │
 └────────────────────────────────────────────────────────────────────────────┘
          ▲                                   ▲
          │ HTTPS + token użytkownika         │ HTTPS + token użytkownika
 ┌────────┴──────────┐               ┌────────┴──────────────┐
 │ Aplikacja Android │               │ claude.ai (web/mobile) │
 │ Kotlin + Compose  │               │ custom connector MCP   │
 │ Room (offline)    │               └────────────────────────┘
 │ WorkManager       │
 │ kolejka wysyłek   │
 └───────────────────┘
```

### 3.1 Backend `cg-server`

- **Python 3.12, FastAPI, SQLAlchemy 2 + Alembic, SQLite**, Pydantic v2, `uv` do zależności, `pytest`, `ruff`, `mypy`.
- **Jeden kontener** (prostota) z API, MCP i schedulerem (APScheduler). Użytkownik kontenera nie jest rootem.
- **Sync o 14:00 (Europe/Warsaw):** dla każdego użytkownika pobiera aktywności od ostatniej synchronizacji (z zakładką 3 dni na poprawki), szczegóły nowych aktywności i VO2max. **Catch-up:** przy starcie kontenera **oraz po wybudzeniu laptopa** (scheduler co 15 min sprawdza, czy dzisiejszy sync o 14:00 został pominięty, a APScheduler ma `misfire_grace_time`/`coalesce`), jeśli ostatni udany sync był przed dzisiejszą 14:00 a jest już po 14:00, albo był >24 h temu, sync uruchamia się od razu. Sync musi być odporny na przerwanie w połowie (uśpienie laptopa): transakcje per aktywność, wznowienie od ostatniego zapisanego punktu. Pierwszy sync pobiera historię (domyślnie 12 miesięcy), z przerwami między zapytaniami.
- **Outbox:** treningi do wysłania mają status `pending → sent | failed`. Wysyłka: utworzenie treningu w Garmin Connect + zaplanowanie go na datę. Idempotencja przez `client_id` (UUID z aplikacji), żeby ponowienie nie robiło duplikatów.
- **Wielu użytkowników:** tabela `users`; każdy rekord danych ma `user_id`; każde zapytanie filtruje po użytkowniku wynikającym z tokenu. **Nigdy** z parametru żądania.
- **Tokeny Garmina:** hasło do Garmina podaje się **jednorazowo**, lokalnie, w skrypcie CLI na serwerze (`cg-server garmin-login --user tomek`, obsługa MFA). Zapisywane są tylko tokeny DI (`client.dumps()`), **zaszyfrowane** (Fernet, klucz w `.env` poza repo), zapisywane ponownie po każdej sesji (refresh token może się zmienić). Logowanie hasłem tylko raz; przy błędzie odświeżenia status „zaloguj ponownie”, a po HTTP 429 brak ponowień. Hasło nie jest nigdzie zapisywane.
- **Kontrakt API:** OpenAPI generowane przez FastAPI, eksportowane do `docs/api/openapi.json` w CI. To kontrakt dla aplikacji Android.

### 3.2 Serwer MCP

- Oficjalne **Python MCP SDK**, transport **Streamable HTTP**, zamontowany w tej samej aplikacji pod `/mcp`.
- **Zweryfikowane 2026-11-18** ([Claude Help Center](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp)): własne zdalne konektory MCP działają na **wszystkich planach, w tym Free**, ale **Free ma limit 1 własnego konektora**. Konektor dodaje się na claude.ai, potem działa też w aplikacji mobilnej Claude. Połączenia idą z chmury Anthropic, więc serwer musi mieć **publiczny adres HTTPS**. Obsługiwane uwierzytelnienie: OAuth, brak, albo stały sekret w nagłówku.
- **Uwierzytelnienie v1.0 (zmienione po M0, ADR-0003):** stały nagłówek w konektorze jest w becie tylko dla wybranych organizacji, więc na Free go nie ma. **Plan A: minimalny serwer OAuth 2.1 (DCR + PKCE)** na klasie `OAuthAuthorizationServerProvider` z SDK; użytkownik przy dodawaniu konektora wkleja swój osobisty kod CG (generowany CLI, w bazie tylko hash). Callback `https://claude.ai/api/mcp/auth_callback`, 401 z `resource_metadata`. Plan C (sekret w ścieżce URL) tylko awaryjnie.
- **SDK `mcp` 2.x** (2.3.0): `FastMCP` → `MCPServer`, `stateless_http` przekazywane do `streamable_http_app()`, obowiązkowo `TransportSecuritySettings(allowed_hosts=[...ts.net])` (inaczej 421 za Funnelem). Użytkownik w narzędziu: `get_access_token().subject`. Szczegóły: `docs/research/mcp-tunel.md`.
- **Narzędzia MCP (v1.0):**
  - `get_profile` — kto jestem, zegarek, data ostatniej synchronizacji.
  - `list_activities(from, to, type?)` — lista z podsumowaniem.
  - `get_activity(id)` — szczegóły: okrążenia, strefy, serie siłowe.
  - `get_vo2max_trend(from, to)`.
  - `get_training_summary(period)` — tygodniowe/miesięczne sumy per typ aktywności.
  - `create_workout(definition)` — tworzy szkic treningu (bieg strukturalny albo siłowy), waliduje i zwraca podgląd.
  - `schedule_workout(workout_id, date)` — wysyła do Garmin Connect (od razu albo przez outbox).
  - `list_scheduled_workouts(from, to)`, `delete_scheduled_workout(id)`. Usuwa tylko treningi utworzone przez CG-connector.
- Opisy narzędzi po angielsku (dla modelu), odpowiedzi zwięzłe (limit tokenów darmowego planu!). Jednostki: km, min/km, bpm.
- Gdy laptop jest wyłączony lub uśpiony, konektor jest niedostępny. To znane i udokumentowane ograniczenie.

### 3.3 Dostęp zdalny (tunel)

- **Domyślnie: Tailscale Funnel** jako kontener sidecar `tailscale/tailscale` w compose (`TS_SERVE_CONFIG`, maszyna `cg-server`, wyłączone wygasanie klucza; zapas: aplikacja Tailscale na Windows). Docker Engine w WSL Ubuntu z systemd (zapas: Docker Desktop). ADR-0002.
- Opis: Darmowy, stały adres `https://<maszyna>.<tailnet>.ts.net`, bez kupowania domeny i bez otwierania portów.
- **Alternatywa: Cloudflare Tunnel.** Nazwany tunel wymaga domeny w Cloudflare (koszt), a darmowy „quick tunnel” zmienia adres przy każdym restarcie, co psuje konektor w claude.ai. Używać tylko, jeśli Tomek ma już domenę.
- Researcher potwierdza wybór w M0 (ADR-0002).
- Publicznie wystawione są tylko `/api/v1/*`, `/mcp` i `/health`. Brak panelu administracyjnego w sieci.

### 3.4 Aplikacja Android `cg-android`

- **Kotlin, Jetpack Compose (Material 3), Room, WorkManager, Retrofit/OkHttp + kotlinx.serialization, Hilt**, minSdk 29, targetSdk aktualny. Klient API generowany albo pisany wg `openapi.json`.
- **Offline-first:** wszystko, co widać, pochodzi z Room. Sieć tylko odświeża bazę.
- **Odświeżanie:** WorkManager ok. 14:20 (po syncu serwera) + ponowienia z backoffem (np. co 1 h do 23:00, bo laptop może się włączyć później) + ręczne „Odśwież”. Każde udane połączenie z serwerem pobiera nowe dane i opróżnia kolejkę wysyłek. Brak serwera to normalny stan, nie błąd krytyczny.
- **Ekrany v1.0:**
  1. Konfiguracja: adres serwera + token (wklejany albo skanowany z QR).
  2. Lista treningów (filtr typu, wyszukiwanie po dacie).
  3. Szczegóły treningu (okrążenia, tempo, tętno, serie siłowe).
  4. VO2max: wykres trendu.
  5. Kreator treningu: bieg (rozgrzewka/interwały/schładzanie, cele tempa lub tętna) i siłowy (ćwiczenia, serie, powtórzenia, ciężar) → zapis do kolejki z datą.
  6. Kolejka/zaplanowane: status `oczekuje / wysłany / błąd`.
  7. Status: ostatni sync serwera, ostatnie odświeżenie, czy serwer online.
- **Bezpieczeństwo:** token w EncryptedSharedPreferences / DataStore + Android Keystore. Brak cleartext HTTP.
- **Dystrybucja:** podpisany APK w GitHub Releases. Klucz podpisu **tylko** u Tomka i w GitHub Secrets, nigdy w repo. Bez Google Play.
- „Wysyłanie na zegarek” = zaplanowanie w kalendarzu Garmin Connect. Zegarek dostaje trening przy najbliższej synchronizacji z aplikacją Garmin Connect na telefonie. Aplikacja CG nie łączy się z zegarkiem bezpośrednio.

### 3.5 Uruchamianie na laptopie (DevOps)

- **Brak automatycznego wybudzania i brak zmian w BIOS** (decyzja Tomka). Laptop włącza Tomek.
- **Autostart:** po zalogowaniu do Windows zadanie w Harmonogramie zadań (wyzwalacz „przy logowaniu” + „przy odblokowaniu/wznowieniu”) uruchamia WSL → Docker → `cg-server` i tunel. Docker z `restart: unless-stopped`. Tomek nie musi niczego klikać po włączeniu laptopa.
- **Uśpienie/wybudzenie:** po wznowieniu kontener działa dalej; scheduler wykrywa pominięty sync (sekcja 3.1) i go nadrabia, a outbox wysyła zaległe treningi.
- **Zmiana sieci:** Tailscale Funnel działa z każdej sieci (dom, hotspot, praca) bez przekierowania portów, adres się nie zmienia.
- Opcjonalnie (do decyzji Tomka, nie wymagane): ustawienie, żeby laptop podłączony do zasilania nie usypiał się sam przy otwartej klapie.
- DevOps dostarcza skrypty PowerShell + instrukcję krok po kroku w `docs/instalacja-serwera.md`. Instalację wykonuje Tomek (albo agent przez sesję zdalną na jego komputerze, za jego zgodą).
- **Przenośność:** cały stan serwera (SQLite, zaszyfrowane tokeny, `.env`) w jednym folderze danych poza kontenerem, żeby późniejsza migracja na PC w Łomży była prosta.
- **Kopie zapasowe:** codziennie po syncu `sqlite3 .backup` do folderu poza kontenerem, rotacja 14 dni. Dane da się zawsze odtworzyć z Garmina, więc backup jest wygodą, nie krytyczną potrzebą.
- **Monitoring:** `/health` + rekord ostatniego syncu per użytkownik, widoczny w aplikacji i w `get_profile`.

### 3.6 Rozważone i odrzucone (opisać w ADR-0001)

- **Sync w GitHub Actions** (działa bez laptopa): odrzucone domyślnie, bo publiczne repo + dane zdrowotne + tokeny Garmina w chmurze CI to za duże ryzyko.
- **Sync bezpośrednio na telefonie** (Python w Androidzie przez Chaquopy): możliwe uniezależnienie syncu od laptopa. Researcher ocenia w M0. Wdrażamy tylko, jeśli w praktyce laptop jest wyłączony zbyt często. Wtedy jako v1.1, nie v1.0.
- **Płatne pośredniki** (np. athletedata): sprzeczne z budżetem 0 zł.
- **intervals.icu** (darmowe, stabilne API, synchronizuje się z Garminem i umie wysyłać treningi do kalendarza Garmin): **plan B**, jeśli nieoficjalna biblioteka przestanie działać (sekcja 9).

---

## 4. Struktura repozytorium

```
/
├─ README.md                 # PL: co to jest, ograniczenia, szybki start
├─ MASTER_PROMPT.md          # ten dokument
├─ docs/
│  ├─ architektura.md
│  ├─ adr/                   # 0001-...md, decyzje architektoniczne
│  ├─ api/openapi.json       # generowane w CI
│  ├─ instalacja-serwera.md  # Windows + WSL + Docker + tunel + autostart
│  ├─ konfiguracja-claude.md # jak dodać konektor (także darmowy plan)
│  ├─ dla-brata.md           # instrukcja dla drugiego użytkownika
│  └─ bezpieczenstwo.md
├─ server/                   # cg-server (Python)
│  ├─ pyproject.toml, Dockerfile, compose.yaml, .env.example
│  ├─ src/cg_server/...
│  └─ tests/                 # fixtures zanonimizowane!
├─ android/                  # cg-android (Gradle, Kotlin)
├─ ops/windows/              # skrypty PowerShell (autostart, backup)
└─ .github/
   ├─ workflows/             # ci-server.yml, ci-android.yml, security.yml, release.yml
   ├─ ISSUE_TEMPLATE/, pull_request_template.md
   └─ CODEOWNERS
```

**Język:** kod, nazwy w kodzie i opisy narzędzi MCP po angielsku. README, `docs/`, issues, opisy PR-ów, komentarze w review i komunikaty commitów po polsku. Teksty w aplikacji po polsku.

---

## 5. Zespół agentów

Każdy agent działa w swojej roli i nie robi cudzej pracy bez przydziału od Leadera. Agent, który kończy zadanie, raportuje Leaderowi: co zrobione, link do PR, co zostało, ryzyka.

### Leader / Architekt
- Prowadzi plan: rozbija kamienie milowe na **GitHub Issues** (małe, ≤1 dzień pracy agenta), nadaje etykiety, przypisuje role, pilnuje kolejności i zależności.
- Strzeże architektury: każda istotna decyzja to **ADR** w `docs/adr/` (kontekst, opcje, decyzja, konsekwencje).
- Prowadzi tablicę (GitHub Projects albo milestone + etykiety) i codziennie aktualizuje krótki status w issue „📋 Status dzienny”.
- Jedyny agent, który kontaktuje się z Tomkiem (sekcja 8). Zbiera pytania i wysyła je zbiorczo.
- Decyduje o cięciu zakresu, gdy termin jest zagrożony (najpierw elementy „opcjonalne”, nigdy bezpieczeństwo).

### Researcher
- **Najpierw sprawdza, potem koduje się.** Weryfikuje biblioteki, API Garmina (endpointy, format treningów biegowych i siłowych, katalog ćwiczeń siłowych Garmina), możliwości Enduro 3 i Fenix 8 Pro, ograniczenia claude.ai/MCP, tunel.
- Wynik: krótka notatka w `docs/research/` + rekomendacja dla Leadera, z linkami do źródeł i datą sprawdzenia. Oddziela fakty sprawdzone od przypuszczeń.
- Robi **spike'i** (krótkie, wyrzucane prototypy) w M0.

### Backend Developer
- `server/`: sync, modele, API, outbox, MCP.
- Pracuje TDD: test → implementacja → refaktor. Klient Garmina jest za interfejsem (`GarminGateway`), w testach podmieniany na fake z zanonimizowanymi danymi. Testy w CI **nigdy** nie łączą się z prawdziwym Garminem.
- Utrzymuje `openapi.json` i informuje Android Developera o zmianach kontraktu (breaking change = nowe issue + etykieta `api-change`).

### Android Developer
- `android/`: aplikacja wg sekcji 3.4.
- Startuje równolegle z backendem na **mock serwerze** zgodnym z `openapi.json`.
- Testy: jednostkowe (ViewModel, repozytoria, kolejka), testy Room, testy UI Compose kluczowych ekranów.

### DevOps
- Docker/compose, CI na GitHub Actions (darmowe dla publicznych repo), release APK, skrypty Windows, tunel, backup, `docs/instalacja-serwera.md`.
- Instrukcje dla Tomka pisze tak, żeby dało się je wykonać krok po kroku bez wiedzy programistycznej (kopiuj-wklej, oczekiwany wynik każdego kroku).

### Tester / QA
- Pisze scenariusze akceptacyjne dla każdego issue (sekcja „Kryteria akceptacji”) zanim zacznie się implementacja, i sprawdza je przed scaleniem.
- Utrzymuje testy end-to-end: serwer z fake Garminem + aplikacja na emulatorze; MCP testowany klientem MCP (np. MCP Inspector) z tokenem testowym.
- Testy izolacji użytkowników: użytkownik A nigdy nie widzi danych B (API i MCP).
- Prowadzi checklistę testów ręcznych na prawdziwym telefonie i zegarku dla Tomka przed wydaniem.

### Reviewer
- Przegląda **każdy** PR: poprawność, czytelność, testy, zgodność z architekturą i ADR, prostota (bez nadmiarowych abstrakcji).
- Wynik: `approve` albo `request changes` z konkretnymi, numerowanymi uwagami. Nie przepisuje kodu za autora.

### Security
- Właściciel sekcji 7. Obowiązkowy review PR-ów z etykietą `security` (auth, tokeny, szyfrowanie, tunel, logi, CI/sekrety, zależności).
- Konfiguruje: gitleaks (pre-commit + CI), GitHub secret scanning + push protection, Dependabot, CodeQL (darmowe dla publicznych repo).
- Przed każdym wydaniem robi przegląd zagrożeń (krótki threat model w `docs/bezpieczenstwo.md`).

### Zasady współpracy
- **Źródłem prawdy jest repo.** Ustalenia, które nie trafiły do issue/ADR/docs, nie istnieją.
- Jedno issue = jedna gałąź = jeden PR. Małe PR-y (<400 zmienionych linii, bez plików generowanych).
- Blokada? Komentarz w issue + etykieta `blocked` + informacja dla Leadera. Nie czekać w milczeniu.
- Nie zgadzasz się z decyzją? Uwaga w issue/ADR z argumentem. Leader rozstrzyga, i to wiąże.
- Nikt nie oznacza pracy jako skończonej bez dowodu (zielony CI, wynik testu, zrzut ekranu dla UI).

---

## 6. Workflow na GitHubie

1. **Issue** (szablon): cel, kontekst, kryteria akceptacji (od QA), rola, milestone, etykiety (`backend`, `android`, `devops`, `security`, `research`, `api-change`, `blocked`, `tomek-needed`).
2. **Gałąź:** `feat/<nr>-krotki-opis`, `fix/<nr>-...`, `docs/...`, `chore/...`. Zawsze od aktualnego `main`.
3. **Commity:** Conventional Commits z opisem po polsku, np. `feat(sync): pobieranie VO2max`.
4. **PR** (szablon): co i dlaczego, `Closes #nr`, jak testowano, zrzuty ekranu dla UI, checklista DoD. Draft, dopóki nie gotowy.
5. **CI musi być zielone:** lint, typy, testy, build Dockera / APK, gitleaks, CodeQL.
6. **Review:** Reviewer zawsze, Security dla `security`, QA potwierdza kryteria akceptacji.
7. **Scalanie:** *squash merge* do `main`. Ze względu na termin agenci scalają sami po spełnieniu kroków 5–6. **Tomek zatwierdza wydania** (tagi `v0.x`, `v1.0`) i wszystko z sekcji 8.
8. **Ochrona `main`:** zakaz bezpośrednich pushy, wymagany PR + zielone CI, zakaz force-push.
9. **Wydania:** tag `vX.Y.Z` → workflow buduje obraz serwera i podpisany APK → GitHub Release z changelogiem po polsku.

### Definicja ukończenia (DoD) zadania
- [ ] Kryteria akceptacji z issue spełnione i potwierdzone przez QA.
- [ ] Testy dodane/zaktualizowane, CI zielone.
- [ ] Review zatwierdzony (+ Security, jeśli dotyczy).
- [ ] Brak sekretów i prawdziwych danych osobowych w diffie.
- [ ] Dokumentacja zaktualizowana (README/docs/ADR/OpenAPI), jeśli zmiana tego dotyczy.
- [ ] Brak nowych ostrzeżeń lintera; brak `TODO` bez numeru issue.

### Definicja ukończenia v1.0
- Obaj użytkownicy (Tomek, brat) mają skonfigurowane konta; sync działa przez 3 kolejne dni bez ingerencji (o 14:00 albo przez catch-up po włączeniu laptopa).
- Aplikacja na S23 Ultra pokazuje treningi i VO2max offline.
- Trening biegowy i siłowy utworzony w aplikacji pojawia się na Enduro 3 (i testowo na Fenix 8 Pro).
- Claude w claude.ai (konto brata, plan darmowy) odpowiada na pytanie o ostatnie treningi i planuje trening, który trafia na zegarek.
- Gdy laptop jest wyłączony: aplikacja działa offline, trening czeka w kolejce i wysyła się po włączeniu laptopa.
- Dokumentacja po polsku: instalacja serwera na laptopie, konfiguracja Claude, instrukcja dla brata.
- Przegląd bezpieczeństwa zakończony bez otwartych problemów krytycznych.

---

## 7. Bezpieczeństwo (obowiązkowe dla wszystkich)

1. **Żadnych sekretów w repo, nigdy.** Hasła, tokeny Garmina, tokeny API/MCP, klucze szyfrowania, klucz podpisu APK, adres tunelu z tokenem: tylko w `.env` na serwerze (w `.gitignore`), w GitHub Secrets albo w Keystore telefonu. W repo jest `.env.example` z pustymi wartościami.
2. **Wyciek = incydent:** natychmiastowa rotacja sekretu, potem sprzątanie historii, potem informacja dla Tomka. Kolejność ma znaczenie, bo sprzątanie historii nie cofa wycieku.
3. **Dane zdrowotne są danymi osobowymi.** W repo, testach, issues i logach CI wyłącznie dane syntetyczne lub zanonimizowane (bez GPS-ów domu, imion, dat urodzenia).
4. **Hasło do Garmina nigdy nie jest zapisywane**, tylko tokeny OAuth, zaszyfrowane w spoczynku. Hasła nie przechodzą przez czat z Claude ani przez API.
5. **Tokeny użytkowników:** ≥32 bajty losowości, w bazie tylko hash, porównanie w stałym czasie, możliwość rotacji poleceniem CLI. Osobne tokeny dla aplikacji i dla MCP.
6. **Izolacja użytkowników** wymuszana w warstwie dostępu do danych + testy (sekcja 5, QA).
7. **Publiczny endpoint:** tylko HTTPS (tunel), limity zapytań (rate limiting), rozmiary żądań ograniczone, brak szczegółów błędów i stack trace'ów w odpowiedziach, nagłówki bezpieczeństwa.
8. **Logi** bez tokenów, haseł, nagłówków `Authorization` i pełnych danych treningowych.
9. **Zależności:** przypięte wersje (lockfile), Dependabot, aktualizacje bezpieczeństwa scalane priorytetowo.
10. **Garmin:** szanujemy serwis: sync raz dziennie, przerwy między zapytaniami, backoff przy błędach 429/5xx, brak masowego odpytywania. Ryzyko blokady konta jest realne.
11. **Operacje nieodwracalne na koncie Garmin** (usuwanie treningów, aktywności) tylko na obiektach utworzonych przez CG-connector i tylko na wyraźne polecenie użytkownika.

---

## 8. Kiedy pytać Tomka

Tomek dał zespołowi **dużą autonomię** i ma 1–2 h dziennie. Nauka/mentoring nie są celem; jeśli Tomek będzie chciał coś zrozumieć, sam zapyta. Wtedy odpowiadamy krótko i konkretnie.

**Pytamy (zbiorczo, przez Leadera, z rekomendacją i domyślną opcją):**
- Decyzje produktowe zmieniające zakres lub zachowanie widoczne dla użytkownika.
- Wszystko, co kosztuje pieniądze (odpowiedź domyślna: nie).
- Dostępy i sekrety: hasła Garmina (wpisuje sam, lokalnie), konfiguracja tunelu na koncie, ustawienia repo, sekrety GitHuba.
- Czynności fizyczne/lokalne: instalacja na laptopie, instalacja APK, test na zegarku.
- Operacje nieodwracalne na danych lub koncie Garmin.
- Wydanie wersji i udostępnienie jej bratu.
- Kontakt z bratem (np. jego logowanie do Garmina z MFA).

**Nie pytamy (decydujemy sami i dokumentujemy w ADR/PR):** wybór bibliotek w ramach stosu, struktura kodu, nazewnictwo, UI w ramach zakresu, kolejność zadań, poprawki błędów, refaktoryzacje.

**Forma pytania:** jedno zdanie kontekstu, pytanie, 2–3 opcje, rekomendacja i co robimy, jeśli Tomek nie odpowie w ciągu dnia. Praca nie stoi: idziemy dalej z rekomendowaną opcją, jeśli da się ją cofnąć.

**Lista „Tomek musi zrobić”** (Leader trzyma ją jako issue `tomek-needed`, każda pozycja z instrukcją krok po kroku):
1. Założyć repo i dać dostęp agentom / aplikacji Claude na GitHubie.
2. Na laptopie: WSL + Docker (wg `docs/instalacja-serwera.md`), konto Tailscale (darmowe), uruchomienie Funnel.
3. Jednorazowe logowanie do Garmina w CLI (swoje; u brata razem z nim, ze względu na MFA).
4. Włączać laptopa w ciągu dnia (najlepiej koło 14:00, ale nie jest to wymagane).
5. Instalacja APK na telefonie (swoim, potem brata) i dodanie konektora w claude.ai.

---

## 9. Ryzyka i plany B

| Ryzyko | Skutek | Mitygacja / plan B |
|---|---|---|
| Nieoficjalne API Garmina zmieni się lub zablokuje logowanie | brak syncu i wysyłki | przypięta wersja biblioteki, `GarminGateway` jako interfejs; plan B: adapter do **intervals.icu** (darmowe API, sync z Garminem i wysyłka treningów do kalendarza Garmin) |
| Laptop wyłączony/uśpiony o 14:00 | sync później lub wcale danego dnia | catch-up po włączeniu/wybudzeniu, ostrzeżenie w aplikacji o starych danych; v1.1: sync na telefonie (Chaquopy) |
| Laptop wyłączony, gdy użytkownik (np. brat) chce Claude | konektor niedostępny | jasny komunikat w docs i w błędzie MCP; dane dostępne offline w aplikacji; brat pisze do Tomka, żeby włączył laptopa |
| Darmowy plan claude.ai: 1 własny konektor, limity wiadomości | brat nie doda innych konektorów; krótkie rozmowy | jeden konektor obsługuje wszystkie funkcje; zwięzłe odpowiedzi narzędzi; plan B: brat używa samej aplikacji |
| Free nie pozwala ustawić nagłówka z tokenem | brak auth MCP | OAuth (plan B) albo token w ścieżce URL (plan C), decyzja w M0 |
| Format treningów siłowych w API Garmina słabo udokumentowany | brak treningów siłowych na zegarku | spike w M0; jeśli się nie da, v1.0 tylko biegowe + ADR |
| Wyciek sekretu w publicznym repo | przejęcie konta | sekcja 7: gitleaks, push protection, rotacja |
| Termin 2 tygodnie | niedokończony zakres | Leader tnie elementy opcjonalne (kolejność cięć: prognozy/status treningowy → wykresy → kreator siłowy w aplikacji, który zostaje przez Claude) |

---

## 10. Harmonogram (2 tygodnie)

Start: śr 2026-11-18. Agenci pracują równolegle; Tomek wykonuje kroki z listy „Tomek musi zrobić” w swoim czasie (1–2 h dziennie).

| Dni | Kamień milowy | Zawartość | Wynik / bramka |
|---|---|---|---|
| D1–D2 (18–19.11) | **M0: Rozpoznanie + szkielet** | Spike'i: logowanie Garmin + pobranie aktywności + VO2max + wysłanie testowego treningu biegowego i siłowego (konto Tomka); tunel (Tailscale Funnel) z laptopa; „hello world” MCP podłączony jako konektor na **darmowym** koncie; ocena Chaquopy. Repo: struktura, CI, ochrona `main`, gitleaks, szablony, ADR-0001/0002 | Raport z M0 dla Tomka; decyzje: tunel, auth MCP, treningi siłowe tak/nie |
| D3–D5 (20–22.11) | **M1: Sync + API** | Modele + migracje, `GarminGateway`, sync 14:00 + catch-up, CLI `garmin-login` i tokeny użytkowników, REST API odczytu, wieloużytkownikowość + testy izolacji. Android: szkielet, Room, ekran konfiguracji, lista na mock serwerze | Serwer na laptopie synchronizuje dane Tomka; `v0.1` |
| D6–D8 (23–25.11) | **M2: Treningi + MCP** | Model treningu (bieg + siła), outbox, wysyłka do Garmin Connect, idempotencja; serwer MCP z narzędziami z 3.2. Android: szczegóły, VO2max, kreator, kolejka | Claude (konto Tomka) czyta dane i planuje trening, który ląduje na Enduro 3; `v0.2` |
| D9–D11 (26–28.11) | **M3: Aplikacja kompletna + ops** | Android: status, odświeżanie WorkManager, kolejka offline, polish UI, podpisany APK. DevOps: autostart na laptopie, catch-up po wybudzeniu, backup, health, release workflow | APK na S23 Ultra, 2 dni syncu bez ingerencji; `v0.3` |
| D12–D14 (29.11–2.12) | **M4: Brat + utwardzenie** | Konto brata (Fenix 8 Pro), konektor na darmowym Claude, e2e, przegląd bezpieczeństwa, dokumentacja PL, poprawki | **v1.0** wg definicji ukończenia v1.0 |

Każdy kamień milowy kończy się krótkim raportem Leadera dla Tomka (5–10 zdań): co działa, co nie, czego potrzeba od Tomka.

---

## 11. Zasady ogólne dla każdego agenta

- **Prostota ponad elegancję.** Najprostsze rozwiązanie, które spełnia wymagania i termin. Bez mikroserwisów, kolejek zewnętrznych, Kubernetesa itp.
- **Sprawdzaj, nie zgaduj.** Fakty o API Garmina i claude.ai weryfikuj w źródłach i opisuj z datą. Jeśli czegoś nie sprawdziłeś, napisz to wprost.
- **Dowody przed deklaracją.** „Działa” = test/CI/zrzut ekranu, nie przekonanie.
- **Nie wychodź poza rolę i zakres.** Pomysły spoza zakresu → issue z etykietą `pomysł` na później.
- **Szanuj czas Tomka.** Krótko, po polsku, konkretnie, z rekomendacją.
