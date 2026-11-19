# Instalacja serwera CG-connector (laptop z Windows + WSL)

Instrukcja krok po kroku. Każdy krok to polecenie do skopiowania i wklejenia oraz opis tego, co powinieneś zobaczyć. Jeśli wynik jest inny, zajrzyj do [Rozwiązywania problemów](#rozwiązywanie-problemów).

Czas: ok. 30–45 minut (najdłużej trwa pierwsze budowanie obrazu).

## Co warto wiedzieć na start

- Serwer działa **tylko wtedy, gdy laptop jest włączony** i ma internet. Gdy laptop śpi albo jest wyłączony, Claude pokaże błąd „serwer niedostępny”, a aplikacja w telefonie poczeka z wysyłką treningów do następnego kontaktu z serwerem. To normalne.
- Po włączeniu i zalogowaniu do Windows serwer startuje sam (krok 9). Nie trzeba niczego klikać.
- Adres serwera w internecie będzie stały: `https://cg-server.<twój-tailnet>.ts.net`. Nie zmienia się przy zmianie sieci (dom, praca, hotspot).
- **Sekrety** (klucz Tailscale, klucz szyfrowania) trafiają tylko do pliku `server/.env` na laptopie. Nigdy do repozytorium, czatu, maila ani zrzutu ekranu.

## Zanim zaczniesz (powinno już być zrobione)

1. **WSL z Ubuntu 24.04** i **Docker Engine** z włączonym systemd. Sprawdzenie (w oknie Ubuntu):

   ```bash
   docker run --rm hello-world
   ```

   Oczekiwany wynik: między innymi linia `Hello from Docker!`. Jeśli widzisz `permission denied`, zobacz problem [P1](#p1-permission-denied-przy-poleceniu-docker).

   Sprawdź też dodatek Compose:

   ```bash
   docker compose version
   ```

   Oczekiwany wynik: `Docker Compose version v2.…`. Jeśli zamiast tego widzisz `unknown flag` albo `'compose' is not a docker command`, doinstaluj go:

   ```bash
   sudo apt-get update && (sudo apt-get install -y docker-compose-plugin || sudo apt-get install -y docker-compose-v2) && docker compose version
   ```

2. **Konto Tailscale** (plan Personal, darmowy) z włączonymi w panelu <https://login.tailscale.com/admin>:
   - **DNS → MagicDNS** oraz **HTTPS Certificates** (Enable HTTPS),
   - **Access controls → Funnel** dodany do polityki (przycisk *Add Funnel to policy*, potem *Save*).

Wszystkie polecenia `bash` poniżej wpisujesz w oknie **Ubuntu** (menu Start → „Ubuntu”). Polecenia PowerShell — w oknie **PowerShell** w Windows (menu Start → „PowerShell”, zwykłe, nie „jako administrator”).

---

## Krok 1. Pobranie kodu

```bash
cd ~ && git clone https://github.com/Tomek17/cg-connector ~/cg-connector && cd ~/cg-connector/server && ls
```

Oczekiwany wynik: lista plików, wśród nich `compose.yaml`, `Dockerfile`, `pyproject.toml`.

## Krok 2. Foldery z danymi

Cały stan leży poza kontenerami, w dwóch folderach:

- `~/cg-data` — dane serwera (baza, kopie zapasowe). Serwer w kontenerze działa jako użytkownik o numerze 10001, więc to on musi być właścicielem folderu.
- `~/cg-tailscale` — stan Tailscale (tożsamość maszyny `cg-server`). Osobno, żeby serwer nie miał do niego dostępu.

```bash
mkdir -p ~/cg-data ~/cg-tailscale && sudo chown 10001:10001 ~/cg-data && sudo chown root:root ~/cg-tailscale && sudo chmod 700 ~/cg-data ~/cg-tailscale && sudo ls -ld ~/cg-data ~/cg-tailscale
```

Ubuntu zapyta o Twoje hasło do Linuksa (litery się nie wyświetlają — to normalne). Oczekiwany wynik: dwie linie zaczynające się od `drwx------` (dostęp tylko dla właściciela); przy `cg-data` w środku `10001 10001`, przy `cg-tailscale` — `root root`. Dlatego do zaglądania do tych folderów potrzebne jest `sudo` (np. `sudo ls ~/cg-data`).

## Krok 3. Plik `.env` (ustawienia i sekrety)

```bash
cd ~/cg-connector/server && cp .env.example .env && chmod 600 .env && sed -i "s|^CG_HOST_DATA_DIR=.*|CG_HOST_DATA_DIR=$HOME/cg-data|;s|^CG_HOST_TS_DIR=.*|CG_HOST_TS_DIR=$HOME/cg-tailscale|" .env && grep CG_HOST_ .env
```

Oczekiwany wynik: dwie linie `CG_HOST_DATA_DIR=/home/<twoja-nazwa>/cg-data` i `CG_HOST_TS_DIR=/home/<twoja-nazwa>/cg-tailscale`.

## Krok 4. Klucz szyfrowania (Fernet)


Pierwsze uruchomienie zbuduje obraz serwera (kilka minut, dużo tekstu na ekranie — to normalne):

```bash
cd ~/cg-connector/server && KEY=$(docker compose run --rm -T cg-server cg-server gen-key | tail -n1) && [ ${#KEY} -eq 44 ] && sed -i "s|^CG_ENCRYPTION_KEY=.*|CG_ENCRYPTION_KEY=$KEY|" .env && echo "Klucz zapisany w .env" || echo "BLAD: klucz NIE zostal zapisany - zobacz komunikat powyzej"; unset KEY
```

Oczekiwany wynik: na końcu `Klucz zapisany w .env`. Klucz nie jest wyświetlany na ekranie i nie trafia do historii poleceń.

> Ten klucz jest potrzebny do odczytania zapisanych tokenów Garmina. **Nie zmieniaj go** po pierwszym użyciu i przenoś zawsze razem z folderem danych (patrz [Migracja](#migracja-na-inny-komputer)).

## Krok 5. Klucz Tailscale (auth key)

1. Otwórz <https://login.tailscale.com/admin/settings/keys> → **Generate auth key…**
2. Ustaw:
   - *Description*: `cg-server`
   - *Reusable*: **wyłączone**
   - *Ephemeral*: **wyłączone**
   - *Pre-approved*: **włączone**
   - *Expiration*: może zostać domyślne (klucz jest potrzebny tylko raz, przy pierwszym starcie)
3. **Generate key** → **Copy**. Klucz zaczyna się od `tskey-auth-`.
4. W Ubuntu wklej poniższe polecenie, naciśnij Enter, potem wklej klucz (prawy przycisk myszy) i znowu Enter. Klucz się nie wyświetli — to celowe.

```bash
cd ~/cg-connector/server && read -rsp "Wklej klucz Tailscale i nacisnij Enter: " K && echo && sed -i "s|^TS_AUTHKEY=.*|TS_AUTHKEY=$K|" .env && unset K && grep -c '^TS_AUTHKEY=tskey-auth-' .env
```

Oczekiwany wynik: `1`. Jeśli `0` — klucz się nie wkleił, powtórz polecenie.

### 5b. Publiczny adres serwera (`CG_PUBLIC_HOST`)

Serwer musi znać swój adres w internecie (potrzebny do logowania Claude). Adres to `cg-server.<nazwa-tailnetu>.ts.net`.

1. Otwórz <https://login.tailscale.com/admin/dns> i znajdź pole **Tailnet name** (np. `tail1234.ts.net`). Skopiuj je.
2. W Ubuntu wklej poniższe polecenie, naciśnij Enter, wklej nazwę tailnetu i znowu Enter:

```bash
cd ~/cg-connector/server && read -rp "Wklej Tailnet name (np. tail1234.ts.net) i nacisnij Enter: " T && T=$(echo "$T" | tr -d ' ') && T=${T%.ts.net} && sed -i "s|^CG_PUBLIC_HOST=.*|CG_PUBLIC_HOST=cg-server.$T.ts.net|" .env && unset T && grep CG_PUBLIC_HOST .env
```

Oczekiwany wynik: `CG_PUBLIC_HOST=cg-server.tail1234.ts.net` (z Twoją nazwą tailnetu, bez `https://`). Bez tego serwer nie wystartuje (w `docker compose logs cg-server` komunikat „Brak publicznego adresu serwera”).

## Krok 6. Uruchomienie serwera i tunelu

```bash
cd ~/cg-connector/server && docker compose up -d --build && sleep 20 && docker compose ps
```

Oczekiwany wynik: dwie linie z kontenerami `cg-connector-cg-server-1` i `cg-connector-tailscale-1`, w kolumnie STATUS `Up …` (przy serwerze może być `(healthy)` albo przez pierwszą minutę `(health: starting)`). Jeśli serwer ma `Restarting`, sprawdź krok 5b.

> **⚠️ ZRÓB TO TERAZ: wyłącz wygasanie klucza maszyny (Disable key expiry)**
>
> Od razu po pierwszym starcie, zanim przejdziesz dalej. Bez tego **po 180 dniach tunel przestanie działać** (Claude i aplikacja stracą połączenie) i trzeba będzie rejestrować maszynę od nowa.
>
> 1. Otwórz <https://login.tailscale.com/admin/machines>.
> 2. Przy maszynie **cg-server** kliknij **…** (trzy kropki) → **Disable key expiry**.
>
> Oczekiwany wynik: przy `cg-server` znika data wygaśnięcia (albo pojawia się napis *Expiry disabled*). Jeśli maszyny `cg-server` nie ma na liście, zobacz problem P4 w [Rozwiązywaniu problemów](#rozwiązywanie-problemów).

## Krok 7. Sprawdzenie serwera i tunelu

Sprawdzenie serwera na laptopie:

```bash
curl http://127.0.0.1:8000/health
```

Oczekiwany wynik: `{"status":"ok","version":"…"}`.

Sprawdzenie tunelu i odczytanie adresu:

```bash
cd ~/cg-connector/server && docker compose exec tailscale tailscale funnel status
```

Oczekiwany wynik (podobny; nazwa tailnetu będzie inna):

```
# Funnel on:
#     - https://cg-server.tail1234.ts.net

https://cg-server.tail1234.ts.net (Funnel on)
|-- /health                                  proxy http://cg-server:8000/health
|-- /api/v1/                                 proxy http://cg-server:8000/api/v1
|-- /mcp                                     proxy http://cg-server:8000/mcp
|-- …                                        (ścieżki logowania OAuth)
```

Zapisz ten adres — to adres serwera, którego użyjesz w Claude i w aplikacji. Musi być taki sam jak `CG_PUBLIC_HOST` w `.env` (krok 5b). Jeśli się różni (np. `cg-server-1…`, bo w tailnecie była już maszyna `cg-server`), popraw `CG_PUBLIC_HOST` w `.env` na ten adres (bez `https://`) i uruchom `docker compose up -d`.

> **Co jest widoczne z internetu.** Tunel przepuszcza tylko ścieżki wpisane w `ops/tailscale/serve.json`: `/health`, `/api/v1/…`, `/mcp` oraz ścieżki logowania OAuth (`/.well-known/oauth-authorization-server`, `/.well-known/oauth-protected-resource`, `/authorize` (razem ze stroną logowania `/authorize/login`), `/token`, `/register`, `/revoke`). Każdy inny adres (np. `/`, `/docs`) dostaje błąd 404 od Tailscale i w ogóle nie trafia do serwera.
>
> Dla programistów: Tailscale Serve obcina prefiks punktu montowania przed przekazaniem żądania (`http.StripPrefix`) i dokleja resztę do ścieżki z `Proxy`. Dlatego każdy handler ma w `Proxy` pełną ścieżkę, np. `"/mcp": {"Proxy": "http://cg-server:8000/mcp"}`. Według kodu Tailscale v1.104.7 (sprawdzone symulacją): `/mcp` → `/mcp`, `/mcp/` → `/mcp/`, `/api/v1/x?y=1` → `/api/v1/x?y=1`, `/.well-known/oauth-protected-resource/mcp` → ta sama ścieżka, `/mcpx` → 404. **Test `/mcp` i `/mcp/` przez prawdziwy Funnel (z claude.ai) jest częścią spike'u M0** — zwróć uwagę na przekierowania 307 między `/mcp` a `/mcp/` w FastAPI/Starlette.

Klucz Tailscale nie jest już potrzebny (maszyna jest zarejestrowana, stan leży w `~/cg-tailscale`). Możesz go usunąć z `.env`:

```bash
cd ~/cg-connector/server && sed -i "s|^TS_AUTHKEY=.*|TS_AUTHKEY=|" .env
```

## Krok 8. Test z telefonu (z internetu)

1. W telefonie **wyłącz Wi-Fi** (żeby ruch szedł przez dane komórkowe, czyli „z zewnątrz”).
2. W przeglądarce otwórz: `https://cg-server.<twój-tailnet>.ts.net/health` (adres z kroku 7).

Oczekiwany wynik: `{"status":"ok","version":"…"}` i kłódka przy adresie (poprawny certyfikat HTTPS). Za pierwszym razem może to potrwać do minuty, a nowy adres bywa widoczny w internecie dopiero po ok. 10 minutach.

## Krok 9. Autostart po zalogowaniu do Windows

### 9a. Ustawienie WSL, żeby się sam nie wyłączał

Domyślnie WSL wyłącza Ubuntu kilkanaście sekund po zamknięciu ostatniego okna terminala — razem z serwerem. W PowerShell:

```powershell
if (Test-Path "$env:USERPROFILE\.wslconfig") { notepad "$env:USERPROFILE\.wslconfig" } else { wsl.exe -d Ubuntu --exec bash -lc 'cat ~/cg-connector/ops/windows/wslconfig.example' | Set-Content -Encoding ascii "$env:USERPROFILE\.wslconfig"; Get-Content "$env:USERPROFILE\.wslconfig" | Select-String '=' }
```

Oczekiwany wynik: `instanceIdleTimeout=-1` i `vmIdleTimeout=-1`. Jeśli zamiast tego otworzył się Notatnik, masz już własny `.wslconfig` — dopisz w nim te dwie linie we właściwych sekcjach (wzór: `ops/windows/wslconfig.example`), zapisz i zamknij.

> Jeśli Twoja dystrybucja nazywa się inaczej niż `Ubuntu` (sprawdź: `wsl -l -v`), zamień `Ubuntu` w poleceniu na tę nazwę, np. `Ubuntu-24.04`.

Następnie (zamknie wszystkie okna Ubuntu i serwer na chwilę):

```powershell
wsl --shutdown
```

### 9b. Zadanie w Harmonogramie zadań

W **Ubuntu** wygeneruj gotowe polecenie z właściwą ścieżką:

```bash
echo "powershell -ExecutionPolicy Bypass -File \"$(wslpath -w ~/cg-connector/ops/windows/install-autostart.ps1)\""
```

Skopiuj wypisaną linię (zaczyna się od `powershell -ExecutionPolicy Bypass -File "\\wsl.localhost\…`) i wklej w **PowerShell** (zwykłym). Oczekiwany wynik:

```
Dystrybucja WSL: Ubuntu
Repozytorium w WSL: ~/cg-connector/server (OK)
Zadanie 'CG-connector autostart' zarejestrowane (logowanie + odblokowanie).
Zadanie uruchomione. Za ok. 30 s sprawdz: status.ps1
```

Skrypt można uruchamiać wiele razy (nadpisuje zadanie). Zadanie uruchamia się przy każdym logowaniu i odblokowaniu komputera (np. po otwarciu klapy), bez żadnego okna.

Jeśli po wybudzeniu Windows nie prosi o hasło/PIN (wznowienie bez blokady ekranu), wyzwalacz „przy odblokowaniu” się nie uruchomi. To nie szkodzi: uśpienie nie zatrzymuje WSL ani kontenerów, więc po wybudzeniu serwer i tunel działają dalej same (po kilkunastu sekundach na odnowienie połączenia).

### 9c. Sprawdzenie

Wygeneruj polecenie tak samo jak wyżej, ale dla `status.ps1`:

```bash
echo "powershell -ExecutionPolicy Bypass -File \"$(wslpath -w ~/cg-connector/ops/windows/status.ps1)\""
```

Wklej wypisaną linię w PowerShell. Oczekiwany wynik: same `[OK]` (WSL, Docker, kontenery `cg-server` i `tailscale`, `/health`, tunel, autostart) i na końcu `Wszystko dziala.`

Ostateczny test: uruchom ponownie Windows, zaloguj się, **nie otwieraj Ubuntu**, odczekaj ok. 2 minuty i sprawdź adres `/health` z telefonu (krok 8).

Aby wyłączyć autostart, uruchom w ten sam sposób `uninstall-autostart.ps1` (serwer i dane zostają nietknięte).

## Krok 10. Codzienna kopia zapasowa

Skrypt `ops/wsl/backup.sh`:

- robi spójną kopię bazy (`cg.sqlite`) do `~/cg-data/backups/<data_godzina>/` — bezpieczną kopię „online”, jak `sqlite3 .backup`, także przy działającym serwerze — i sprawdza ją (`integrity_check: ok`),
- kopiuje ten katalog na dysk Windows, domyślnie do `C:\Users\<nazwa>\CG-kopie` (gdyby WSL się zepsuł, kopia przetrwa),
- usuwa kopie starsze niż 14 dni (w obu miejscach; najnowsza zostaje zawsze).

Dane da się zawsze odtworzyć z Garmina, więc kopia to wygoda, a nie konieczność.

Włączenie codziennej kopii o 14:30 (po synchronizacji o 14:00). Gdy laptop był o tej porze wyłączony, kopia wykona się zaraz po jego włączeniu:

```bash
sudo ~/cg-connector/ops/wsl/install-backup-timer.sh
```

Oczekiwany wynik: linia `Kopie na dysku Windows: /mnt/c/Users/<nazwa>/CG-kopie` i tabela z jedną linią `cg-backup.timer` oraz datą następnego uruchomienia (`NEXT`) dziś albo jutro o 14:30. Inny folder podaj na końcu polecenia, np. `sudo ~/cg-connector/ops/wsl/install-backup-timer.sh /mnt/c/Users/<nazwa>/Kopie/CG`.

> Folder z kopiami zawiera dane treningowe. Wybieraj folder **wewnątrz swojego profilu Windows** (`C:\Users\<nazwa>\…`): tam dostęp innym kontom Windows blokują uprawnienia NTFS. Uprawnienia linuksowe (`chmod`) na `/mnt/c` nie działają, więc folder poza profilem (np. `C:\CG` albo inny dysk) mogą czytać wszyscy użytkownicy komputera. Nie wybieraj też folderu synchronizowanego z chmurą (OneDrive, „Dokumenty”, „Pulpit”), jeśli nie chcesz, żeby kopie tam trafiły.

Test od razu (nie trzeba czekać do 14:30):

```bash
sudo systemctl start cg-backup.service && journalctl -u cg-backup.service -n 10 --no-pager
```

Oczekiwany wynik: linie `start kopii …`, `OK: cg.sqlite -> backups/… (integrity_check: ok)`, `OK: kopia … -> /mnt/c/Users/<nazwa>/CG-kopie/…` i `koniec kopii …`. W Eksploratorze Windows w folderze `CG-kopie` pojawi się katalog z dzisiejszą datą. (Jeśli serwer jeszcze nic nie zapisał: `Brak baz SQLite…` — to normalne.)

**Przywracanie z kopii** (rzadko potrzebne). Najpierw zatrzymaj serwer i wybierz kopię (katalog z datą):

```bash
cd ~/cg-connector/server && docker compose stop cg-server && sudo ls ~/cg-data/backups
```

Wpisz wybraną nazwę katalogu (np. `2026-11-18_143000`) i przywróć bazę:

```bash
read -rp "Katalog kopii: " K && sudo rm -f ~/cg-data/cg.sqlite-wal ~/cg-data/cg.sqlite-shm && sudo install -o 10001 -g 10001 -m 600 ~/cg-data/backups/"$K"/cg.sqlite ~/cg-data/cg.sqlite && cd ~/cg-connector/server && docker compose start cg-server && unset K
```

Oczekiwany wynik: `Container cg-connector-cg-server-1 Started`, a po chwili `curl http://127.0.0.1:8000/health` odpowiada. Kopię z dysku Windows (np. po reinstalacji WSL) przywracasz tak samo, zamieniając `~/cg-data/backups/"$K"` na `/mnt/c/Users/<nazwa>/CG-kopie/"$K"`.

> Tokeny Garmina w bazie są zaszyfrowane kluczem z `server/.env` (`CG_ENCRYPTION_KEY`), którego kopia **nie zawiera** (to sekret). Przy przywracaniu na tym samym serwerze nic nie trzeba robić. Na nowej instalacji bez starego `.env` treningi i ustawienia wrócą, ale każdy użytkownik musi ponownie połączyć konto Garmin.

---

## Aktualizacja do nowej wersji

```bash
cd ~/cg-connector && git pull && cd server && docker compose up -d --build
```

Oczekiwany wynik: `Container … Started` / `Running`, a potem `curl http://127.0.0.1:8000/health` zwraca nową wersję.

## Migracja na inny komputer

Cały stan to: folder `~/cg-data` (baza, kopie), folder `~/cg-tailscale` (**stan Tailscale**) i plik `server/.env` (**klucz szyfrowania**). Przeniesienie wszystkich trzech zachowuje dane i **ten sam adres** `https://cg-server.<tailnet>.ts.net`, więc konektor w Claude i aplikacja działają dalej bez zmian.

Na **starym** komputerze (Ubuntu):

```bash
cd ~/cg-connector/server && docker compose down && sudo tar czf ~/cg-migracja.tgz -C ~ cg-data cg-tailscale cg-connector/server/.env && sudo chown $USER ~/cg-migracja.tgz && ls -lh ~/cg-migracja.tgz
```

Następnie w PowerShell wyłącz autostart (`uninstall-autostart.ps1`, jak w kroku 9). **Stary serwer nie może już działać** — dwie maszyny z tym samym stanem Tailscale gryzą się ze sobą.

Plik `cg-migracja.tgz` skopiuj na nowy komputer (pendrive; w Eksploratorze Windows Ubuntu jest widoczne jako „Linux” w panelu po lewej). Archiwum zawiera sekrety — nie wysyłaj go mailem ani przez komunikator i usuń z pendrive’a po migracji.

Na **nowym** komputerze (WSL Ubuntu + Docker jak w „Zanim zaczniesz”):

1. Krok 1 (pobranie kodu).
2. Skopiuj `cg-migracja.tgz` do katalogu domowego Ubuntu i rozpakuj:

   ```bash
   sudo tar xzf ~/cg-migracja.tgz -C ~ && sudo chown $USER ~/cg-connector/server/.env && sed -i "s|^CG_HOST_DATA_DIR=.*|CG_HOST_DATA_DIR=$HOME/cg-data|;s|^CG_HOST_TS_DIR=.*|CG_HOST_TS_DIR=$HOME/cg-tailscale|" ~/cg-connector/server/.env && ls -ld ~/cg-data ~/cg-tailscale ~/cg-connector/server/.env
   ```

   Oczekiwany wynik: trzy linie; `cg-data` z właścicielem `10001`, `cg-tailscale` z właścicielem `root`.
3. Kroki 6 i 7 (bez nowego klucza Tailscale; wygasanie klucza jest już wyłączone), 8, 9 i 10.

Oczekiwany wynik: `tailscale funnel status` pokazuje ten sam adres co wcześniej, a w panelu Tailscale jest nadal jedna maszyna `cg-server`.

---

## Rozwiązywanie problemów

Pierwszy krok zawsze: `status.ps1` (krok 9c) oraz logi:

```bash
cd ~/cg-connector/server && docker compose ps && docker compose logs --tail 50
```

### P1. `permission denied` przy poleceniu `docker`

Twój użytkownik nie jest w grupie `docker`:

```bash
sudo usermod -aG docker $USER
```

Zamknij wszystkie okna Ubuntu, w PowerShell wykonaj `wsl --shutdown`, otwórz Ubuntu ponownie i powtórz polecenie.

### P2. Kontener `cg-server` ciągle się restartuje (`Restarting`), w logach `Permission denied: '/data/…'`

Folder danych ma złego właściciela (np. został utworzony przez Dockera jako `root`):

```bash
sudo chown 10001:10001 ~/cg-data && sudo chmod 700 ~/cg-data && cd ~/cg-connector/server && docker compose restart cg-server
```

Sprawdź też, czy w `.env` jest pełna ścieżka: `grep CG_HOST_DATA_DIR ~/cg-connector/server/.env`.

### P3. Adres `https://cg-server…ts.net` nie działa z telefonu

1. `docker compose exec tailscale tailscale funnel status` — musi być `(Funnel on)`. Jeśli jest błąd o Funnel albo HTTPS: w panelu Tailscale włącz **HTTPS Certificates** (DNS) i dodaj **Funnel** w *Access controls* (patrz „Zanim zaczniesz”), potem `docker compose restart tailscale`.
2. Nowy adres bywa widoczny w internecie dopiero po ok. 10 minutach — odczekaj.
3. Upewnij się, że telefon nie jest w tej samej sieci Wi-Fi co laptop (test ma iść przez dane komórkowe).
4. Niektóre sieci firmowe/hotelowe blokują Tailscale — sprawdź z innej sieci (np. hotspot z telefonu dla laptopa).

### P4. W panelu Tailscale pojawia się `cg-server-1` albo kontener `tailscale` pisze o nieprawidłowym kluczu (`invalid key`, `expired`)

- `cg-server-1` = zarejestrowano drugą maszynę, bo stan Tailscale zginął (np. inny lub pusty `CG_HOST_TS_DIR`). W panelu **Machines** usuń starą maszynę `cg-server` (… → *Remove*), a nową przemianuj na `cg-server` (… → *Edit machine name*).
- Zły lub zużyty klucz: wygeneruj nowy (krok 5), wpisz go do `.env` i:

  ```bash
  cd ~/cg-connector/server && docker compose up -d --force-recreate tailscale
  ```

### P5. Serwer działa tylko, gdy okno Ubuntu jest otwarte

WSL usypia Ubuntu po zamknięciu okna. Sprawdź `.wslconfig` (krok 9a), wykonaj `wsl --shutdown` i uruchom zadanie ponownie: `Start-ScheduledTask -TaskName "CG-connector autostart"`. Jeśli `vmIdleTimeout=-1` nie działa, zamień `-1` na `2147483647`. Gdyby i to nie pomogło — planem zapasowym jest Docker Desktop (ADR-0002).

### P6. Po zalogowaniu do Windows serwer nie wstaje

1. Menu Start → **Harmonogram zadań** → *Biblioteka Harmonogramu zadań* → `CG-connector autostart` → kolumna *Wynik ostatniego uruchomienia* (powinno być `0x0`).
2. Uruchom ręcznie: `Start-ScheduledTask -TaskName "CG-connector autostart"`, odczekaj minutę, potem `status.ps1`.
3. Jeśli nazwa dystrybucji jest inna niż `Ubuntu`/`Ubuntu-24.04` albo repozytorium leży gdzie indziej, zainstaluj zadanie ponownie z parametrami, np. `… install-autostart.ps1 -Distro Ubuntu-24.04 -RepoPath ~/cg-connector`.
4. Sprawdź, czy Docker startuje razem z Ubuntu: `systemctl is-enabled docker` → `enabled` (jeśli nie: `sudo systemctl enable docker containerd`).
5. **Plan B dla ukrytego okna.** Zadanie uruchamia WSL przez `conhost.exe --headless` (bez okna). Na starszych wersjach Windows 10 ta opcja może nie działać (wynik zadania inny niż `0x0` albo miga czarne okno). Wtedy zamień akcję zadania na PowerShell z ukrytym oknem (w PowerShell, w jednej linii; zamień `Ubuntu`, jeśli Twoja dystrybucja nazywa się inaczej):

   ```powershell
   Set-ScheduledTask -TaskName "CG-connector autostart" -Action (New-ScheduledTaskAction -Execute "powershell.exe" -Argument '-NoProfile -WindowStyle Hidden -Command "wsl.exe -d Ubuntu --exec bash -lc ''cd ~/cg-connector/server && docker compose up -d''"')
   ```

   Oczekiwany wynik: opis zadania bez błędów. Przy starcie może na ułamek sekundy mignąć okno PowerShell — to normalne. Uwaga: ponowne uruchomienie `install-autostart.ps1` przywraca wariant z `conhost`. Gdyby żaden wariant nie działał, ostatecznym planem zapasowym jest Docker Desktop z opcją automatycznego startu (ADR-0002).

### P7. Port 8000 jest zajęty (`address already in use`)

Inny program na laptopie używa portu 8000. Zatrzymaj go albo zmień w `server/compose.yaml` linię `"127.0.0.1:8000:8000"` na np. `"127.0.0.1:8001:8000"` (tunel działa dalej bez zmian; `status.ps1` uruchom wtedy z `-HealthUrl http://127.0.0.1:8001/health`).
