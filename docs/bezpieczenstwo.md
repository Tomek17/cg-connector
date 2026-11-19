# Bezpieczeństwo CG-connector

Model zagrożeń, zabezpieczenia, znane ryzyka i zasady zgłaszania problemów. Dokument prowadzi rola Security (MASTER_PROMPT §5, §7). Aktualizujemy go przed każdym wydaniem.

Stan na: 2026-11-18, przegląd bezpieczeństwa `main` @ `150423d`. Poprawki z tego przeglądu są w PR #29 (limity, OAuth, Host) i PR #30 (adnotacje MCP, kontener, proxy). Pozycje oznaczone „(#29)” albo „(#30)” obowiązują po ich scaleniu.

## 1. Co chronimy

| Zasób | Gdzie jest | Skutek wycieku lub utraty |
|---|---|---|
| Tokeny Garmin Connect (DI OAuth) | SQLite na laptopie, zaszyfrowane Fernetem | pełny dostęp do konta Garmin (dane zdrowotne, kalendarz, usuwanie) |
| Klucz Fernet `CG_ENCRYPTION_KEY` | `server/.env` (poza repo, `chmod 600`) | odszyfrowanie tokenów Garmina z kopii bazy |
| Kod CG (logowanie konektora claude.ai) | w bazie tylko SHA-256; plaintext tylko u użytkownika | podłączenie własnego Claude do cudzych danych |
| Token aplikacji Android | w bazie SHA-256; w telefonie AES-GCM z kluczem w Android Keystore | odczyt danych i planowanie treningów przez REST |
| Tokeny OAuth (access 1 h, refresh 60 dni) | w bazie SHA-256; u Anthropic (konektor) | jak wyżej, przez MCP |
| Dane treningowe (aktywności, tętno, VO2max) | SQLite i kopie zapasowe na laptopie; Room w telefonie | dane zdrowotne = dane osobowe |
| Klucz Tailscale i stan węzła | `~/cg-tailscale` (osobny folder, serwer go nie widzi) | podszycie się pod adres `cg-server.<tailnet>.ts.net` |
| Klucz podpisu APK | tylko u Tomka i w GitHub Secrets | podpisanie złośliwej aktualizacji aplikacji |

Hasło do Garmina **nie jest nigdzie zapisywane**. Podaje się je tylko w CLI `cg-server garmin-login` na laptopie.

## 2. Granice zaufania i aktorzy

```
Internet ──HTTPS──▶ Tailscale Funnel ──▶ kontener tailscale ──HTTP (sieć compose)──▶ cg-server ──▶ SQLite (/data)
   ▲                      (allowlista ścieżek, serve.json)                         │
   │                                                                               └──HTTPS──▶ Garmin Connect
claude.ai (konektor MCP, OAuth)        Aplikacja Android (token aplikacji)
```

- **Niezaufane:** cały ruch z internetu, w tym każdy, kto zna adres `*.ts.net`. Adres jest publiczny (Certificate Transparency), więc nie jest sekretem.
- **Częściowo zaufane:**
  - claude.ai: działa w imieniu użytkownika, ale model może zostać zmanipulowany treścią (prompt injection);
  - telefon użytkownika.
- **Zaufane:** laptop Tomka (WSL, Docker), administrator serwera (CLI).
- **Repozytorium jest publiczne.** Wszystko w repo, issues, PR-ach i logach CI jest jawne.

## 3. Model zagrożeń

| # | Zagrożenie | Scenariusz | Zabezpieczenia | Ryzyko rezydualne |
|---|---|---|---|---|
| T1 | Przejęcie konektora (kradzież tokenu) | Atakujący zdobywa kod CG, kod autoryzacyjny albo token OAuth | kod CG i tokeny mają 256 bitów; w bazie tylko SHA-256; PKCE S256 wymuszone; `redirect_uri` dokładne, tylko claude.ai i claude.com; kod jednorazowy z TTL 5 min; rotacja refresh tokenu z wykrywaniem powtórzenia; ponowne użycie kodu unieważnia wydane z niego tokeny (#29); rotacja kodu CG w CLI odłącza wszystkie połączenia | niskie |
| T2 | Zgadywanie kodu CG | Masowe próby na `/authorize/login` | entropia 256 bitów; 5 prób na jedno logowanie; rate limit 30/min/IP; blokada IP po 10 błędach w 15 min (#29); porównanie hashy | pomijalne |
| T3 | Dostęp do cudzych danych (IDOR) | Użytkownik A podaje id aktywności lub treningu użytkownika B | użytkownik zawsze z tokenu, nigdy z parametru; każde zapytanie filtruje po `user_id`; cudzy obiekt = „nie znaleziono”; testy izolacji (API i MCP) | niskie |
| T4 | Nadużycie konta Garmin (blokada, masowe zmiany) | Prompt injection albo błąd sprawia, że Claude planuje setki treningów | maks. 30 nadchodzących wpisów na użytkownika i maks. 10 wpisów na przebieg outboxu (#29); przerwa 2 s między zapytaniami; po HTTP 429 natychmiastowe zatrzymanie i 24 h pauzy; sync raz dziennie z limitem zapytań | niskie |
| T5 | Nieodwracalne operacje w Garmin | Usunięcie treningów lub aktywności użytkownika | usuwane są tylko `garmin_workout_id` i `garmin_schedule_id` z naszych tabel (obiekty CG); aktywności nigdy; narzędzie `delete_scheduled_workout` ma `destructiveHint` (#30) | niskie |
| T6 | Odmowa usługi | Zalew `/register`, `/authorize`, dużych żądań | rate limit na endpointach OAuth; limit body 1 MiB (4 KiB dla `/register`); maks. 100 klientów DCR, nieużywani usuwani po 1 h, przy pełnej tabeli wypierany najstarszy nieużywany (#29); maks. 1000 oczekujących logowań | średnie: serwer i tak bywa wyłączony; limity w pamięci zerują się po restarcie |
| T7 | Ekspozycja panelu lub dokumentacji | `/docs`, `/openapi.json`, `/admin` z internetu | Funnel przepuszcza tylko ścieżki z `ops/tailscale/serve.json`; serwer ma własną allowlistę (404); OpenAPI wyłączone w `production` | niskie |
| T8 | DNS rebinding i obcy Host | Złośliwa strona w przeglądarce na laptopie strzela do `127.0.0.1:8000` | port tylko na `127.0.0.1`; `TrustedHostMiddleware` (#29); `allowed_hosts` i `allowed_origins` w MCP; wszystko poza `/health` wymaga tokenu | niskie |
| T9 | Clickjacking, XSS, CSRF na stronie logowania | Osadzenie formularza w ramce, wstrzyknięcie HTML przez nazwę klienta DCR | `X-Frame-Options: DENY`, CSP `frame-ancestors 'none'`, `form-action` tylko do `'self'` i Claude; strona bez JS; `html.escape` wszystkich wartości; nieodgadywalny identyfikator logowania wiąże formularz z `/authorize` | niskie |
| T10 | Wyciek sekretów do repo lub logów | Commit `.env`, token w logu, stack trace z danymi | `.gitignore`; gitleaks w CI (suma kontrolna binarki); push protection; filtr logów maskuje `Bearer`, `cgc_`/`cga_` i parametry `code`/`state`/`token`; brak stack trace'ów w odpowiedziach; testy na danych syntetycznych | niskie |
| T11 | Kradzież danych z laptopa lub telefonu | Utrata sprzętu, kopia folderu danych | tokeny Garmina zaszyfrowane; `chmod 700` na folder danych, kopie z `umask 077`; telefon: Keystore, `allowBackup=false`, wykluczenia z kopii i transferu | **średnie:** baza SQLite z danymi treningowymi nie jest szyfrowana; zalecane szyfrowanie dysku (BitLocker) |
| T12 | Łańcuch dostaw | Złośliwa zależność lub obraz | `uv.lock`; przypięte `garminconnect` i `mcp`; obrazy przypięte digestem (#30); Dependabot; `pip-audit` w CI; CodeQL (Python) | niskie |
| T13 | Ucieczka z kontenera, eskalacja | Podatność w serwerze daje RCE | uid 10001, `no-new-privileges`, `cap_drop: [ALL]`, `read_only` (#30); stan Tailscale niewidoczny dla serwera; `TS_AUTHKEY` wyczyszczony w środowisku serwera | niskie |
| T14 | Podrobiona aplikacja Android | Złośliwy APK podszywa się pod aktualizację | podpis kluczem tylko u Tomka i w GitHub Secrets; dystrybucja przez GitHub Releases | niskie |

## 4. Zabezpieczenia: gdzie w kodzie

- **OAuth 2.1 i logowanie kodem CG:** `server/src/cg_server/oauth.py`, `login_page.py`. Metadane, PKCE i walidację żądań zapewnia SDK `mcp` 2.3.
- **Utwardzenie publicznego endpointu:** `server/src/cg_server/security.py`. Zawiera allowlistę ścieżek, nagłówki (CSP, HSTS, `nosniff`, `no-store`, `no-referrer`), limit rozmiaru i rate limit.
- **Sekrety użytkowników:** `user_secrets.py` (SHA-256, rotacja) i `crypto.py` (Fernet).
- **Izolacja użytkowników:** `queries.py` (obowiązkowy `user_id`), `workouts.py`, `mcp_server.py` (`current_user` z tokenu). Testy w `tests/test_api_read.py`, `test_mcp_read.py`, `test_mcp_workouts.py`.
- **Garmin:** `garmin/gateway.py`, `sync.py`, `outbox.py` (429, pauzy, limity).
- **Logi:** `logging_filter.py`.
- **Kontener:** `server/Dockerfile`, `server/compose.yaml`. **Tunel:** `ops/tailscale/serve.json`.
- **Android:**
  - `KeystoreTokenCipher.kt`;
  - `AndroidManifest.xml` (`allowBackup=false`, cleartext wyłączony);
  - `res/xml/network_security_config.xml`, `data_extraction_rules.xml`;
  - R8 w buildzie release.
- **CI:** `.github/workflows/security.yml` (gitleaks, pip-audit, CodeQL) i `.github/dependabot.yml`.

## 5. Znane ryzyka i świadome decyzje

1. **Alert CodeQL „clear-text logging of sensitive information” w `server/src/cg_server/cli.py`.** Jest zamierzony. Polecenia `cg-server user-code` i `cg-server app-token` muszą raz pokazać nowy kod administratorowi w terminalu (tak jak `gen-key` pokazuje klucz). Wypisanie idzie na stdout polecenia uruchomionego ręcznie przez `docker compose run --rm`, nie do logów serwera. W bazie zostaje tylko hash. Alert zamykamy jako „won't fix” z odnośnikiem do tego punktu. Nie dotyczy to logów serwera, które filtruje `logging_filter.py`.
2. **IP klienta za Tailscale Funnel.** Rate limit i blokada IP opierają się na `X-Forwarded-For`. Tailscale Serve go **nadpisuje** adresem klienta (`ipn/ipnlocal/serve.go`). uvicorn ufa nagłówkom tylko od adresów lokalnych i prywatnych (sieć compose) i bierze skrajnie prawy niezaufany adres (#30).
   - Do sprawdzenia przy instalacji: w logu `cg-server` przy żądaniu przez Funnel widać publiczny adres klienta, a nie `172.x`.
   - Gdyby Funnel nie przekazywał prawdziwego IP, limity byłyby wspólne dla wszystkich. Atakujący mógłby wtedy na 15 min zablokować logowanie konektora (ale nie dostęp do danych).
3. **Plan B: ngrok (profil `ngrok`, domyślnie wyłączony).** Inaczej niż Funnel, ngrok kończy TLS na swoich serwerach, więc firma ngrok technicznie widzi ruch w postaci jawnej: tokeny konektora (`cga_`, tokeny OAuth) i dane treningowe zwracane przez `/mcp` i `/api/v1/`. Hasło Garmina przez tunel nie idzie (logowanie do Garmina jest tylko lokalne). Używamy ngrok tylko, gdy Funnel nie działa, i wracamy na Funnel, gdy zacznie. ngrok dopisuje adres klienta na końcu `X-Forwarded-For`, więc reguła „skrajnie prawy niezaufany adres” nadal daje prawdziwe IP. Ścieżki ogranicza `ops/ngrok/policy.yml` (te same co `serve.json`).
4. **Otwarta rejestracja klientów (DCR).** Wymaga jej claude.ai. Rejestracja niczego nie daje bez kodu CG, a ograniczają ją limity z T6.
5. **Limity w pamięci procesu** (rate limit, blokada IP, oczekujące logowania) zerują się po restarcie kontenera. To akceptowalne przy jednym procesie i jednym serwerze.
6. **Kontener `tailscale`** działa jako root w trybie userspace, bez `NET_ADMIN`. Nie ma `read_only` ani `cap_drop`, bo nie było to testowane, a awaria oznacza brak tunelu. Do ponownej oceny po teście na laptopie.
7. **Wykrycie ponownego użycia refresh tokenu** odwołuje cały grant, także przy legalnym retry claude.ai po zerwanym połączeniu. Objaw: Claude prosi o ponowne połączenie konektora. Naprawa: połączyć ponownie.
8. **Baza danych nie jest szyfrowana w spoczynku** (poza tokenami Garmina). Zalecenie dla Tomka: BitLocker na laptopie (bezpłatny w Windows Pro; w Windows Home „Szyfrowanie urządzenia”, jeśli sprzęt je obsługuje).
9. **`/health` jest publiczne** i zwraca wersję serwera. Wersja jest i tak jawna w publicznym repo.
10. **CodeQL obejmuje tylko Pythona.** Analiza Kotlina wymaga builda Androida w CI. Do rozważenia po ustabilizowaniu `ci-android`. Do tego czasu Android ma lint i review.
11. **Akcje GitHub przypięte tagiem** (`@v4`), nie SHA. Ryzyko ograniczają minimalne `permissions` i brak `pull_request_target`. Dependabot aktualizuje akcje.

## 6. Incydent: co robić

Zgodnie z MASTER_PROMPT §7.2, w tej kolejności:

1. **Rotacja sekretu, natychmiast.**
   - Kod CG: `cg-server user-code --user <nazwa>`; odłącza też wszystkie połączenia Claude.
   - Token aplikacji: `cg-server app-token --user <nazwa>`.
   - Tokeny Garmina: zmiana hasła w Garmin Connect, potem `cg-server garmin-login`.
   - Klucz Fernet: nowy `gen-key`, potem ponowne `garmin-login` każdego użytkownika.
   - Klucz Tailscale: unieważnienie w panelu Tailscale.
2. **Sprzątanie** (historia gita, logi CI, issue). Sprzątanie nie cofa wycieku, dlatego jest dopiero drugie.
3. **Informacja dla Tomka:** co wyciekło, od kiedy, co zrobiono.

## 7. Jak zgłaszać problemy bezpieczeństwa

- **Nie zakładaj publicznego issue ze szczegółami podatności.** Repo jest publiczne.
- Zgłoś przez **GitHub → Security → Report a vulnerability** (prywatne zgłoszenie, *private vulnerability reporting*) albo bezpośrednio Tomkowi (właściciel repo).
  - Na 2026-11-18 prywatne zgłoszenia są w repo **wyłączone**. Włącza je Tomek: Settings → Code security → Private vulnerability reporting → Enable. Do tego czasu zgłaszaj bezpośrednio Tomkowi.
- W zgłoszeniu podaj: czego dotyczy (serwer, MCP, Android, CI), jak odtworzyć i jaki jest skutek. Nie dołączaj prawdziwych tokenów ani danych treningowych.
- Agenci zespołu zgłaszają problem w issue z etykietą `security` **bez** szczegółów umożliwiających atak. Szczegóły trafiają do zgłoszenia prywatnego lub do Leadera.
- Pilność:
  - krytyczne (wyciek sekretu, dostęp do cudzych danych): od razu, rotacja wg sekcji 6;
  - pozostałe: w najbliższym PR z etykietą `security`.

## 8. Lista kontrolna przed wydaniem

- [ ] `uv run pytest -q`, CI zielone (w tym gitleaks, pip-audit, CodeQL).
- [ ] Brak otwartych alertów Dependabot i CodeQL poza opisanymi w sekcji 5.
- [ ] `CG_ENV=production`, `CG_OAUTH_ALLOW_LOCALHOST=false`, `.env` z `chmod 600`.
- [ ] Przez Funnel: `/docs`, `/openapi.json` i `/admin` zwracają 404; `/health` zwraca 200.
- [ ] IP klienta w logu serwera jest publicznym adresem (sekcja 5, pkt 2).
- [ ] APK podpisany kluczem release; klucz nie trafił do repo ani do logów.
- [ ] Ten dokument zaktualizowany (data, nowe ryzyka).
