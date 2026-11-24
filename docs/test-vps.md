# Test: czy Garmin działa z zewnętrznego serwera (VPS)

Zanim przeniesiemy serwer z laptopa na VPS, sprawdzamy jedną rzecz: **czy Garmin wpuszcza
logowanie i zapytania z adresu IP serwerowni**. Garmin bywa podejrzliwy wobec ruchu z chmury
(blokady, HTTP 429, sprawdzanie „odcisku” połączenia), a laptop w domu tego problemu nie ma.
Nic nie przenosimy, dopóki ten test nie przejdzie.

Skrypt [`ops/vps-test/test_garmin_vps.py`](../ops/vps-test/test_garmin_vps.py) robi dokładnie to,
co serwer CG-connector robi na co dzień, tą samą biblioteką i wersją (`garminconnect 0.3.20`):

| Krok | Co sprawdza | Po co serwerowi |
|---|---|---|
| 1 | logowanie hasłem + kod MFA | nowa osoba loguje się z aplikacji (logowanie idzie przez serwer) |
| 2 | wznowienie sesji z samych tokenów | codzienna synchronizacja o 14:00 |
| 3a, 3b | aktywności z 14 dni, VO2max z 30 dni | dane dla Claude i aplikacji |
| 4a–4c | wysłanie treningu, zaplanowanie za 30 dni, usunięcie | treningi na zegarek |

Bezpieczeństwo: hasło wpisujesz ręcznie (nie widać go), skrypt **niczego nie zapisuje na dysku**
(ani hasła, ani tokenów). Robi jedno logowanie i nie ponawia. Testowy trening nazywa się
„CG TEST VPS (usuń)” i znika na końcu (przez chwilę może być widoczny w Garmin Connect).

Czas: ok. 30 min (głównie założenie konta u dostawcy). Koszt: 0 zł na Oracle.

---

## Gdzie testować: Oracle Cloud Always Free (polecane)

Testujemy tam, gdzie chcemy potem trzymać serwer, bo Garmin ocenia konkretne adresy IP.
Oracle Always Free kosztuje 0 zł na stałe (zgodnie z budżetem projektu), a darmowa maszyna
(1 GB RAM) wystarczy dla CG-connector.

Jeśli Oracle nie zadziała (nie da się założyć konta, brak wolnych maszyn albo test nie przejdzie),
plan B to **Hetzner Cloud CX22** z rozliczeniem godzinowym: test trwa kilkanaście minut i kosztuje
grosze, a po teście usuwasz serwer. Na stałe to ok. 4,5 €/mies.

### 1. Konto Oracle (15 min)

1. Wejdź na https://signup.cloud.oracle.com i załóż konto **Free Tier**.
2. **Region domowy** (Home Region) wybierz **Germany Central (Frankfurt)** albo najbliższy w Europie.
   Uwaga: regionu domowego nie da się później zmienić, a darmowe maszyny są tylko w nim.
3. Oracle poprosi o kartę płatniczą — to tylko weryfikacja. Konto Free Tier niczego nie pobiera,
   dopóki sam nie przejdziesz na płatny plan („Upgrade”). Nie klikaj „Upgrade”.

### 2. Maszyna wirtualna (5 min)

W konsoli Oracle: **☰ → Compute → Instances → Create instance**.

1. **Name**: `cg-test`.
2. **Image**: kliknij *Change image* → **Canonical Ubuntu 24.04**.
3. **Shape**: *Change shape* → **Specialty and previous generation** → **VM.Standard.E2.1.Micro**
   (oznaczony „Always Free-eligible”).
   Jeśli go nie ma albo tworzenie kończy się błędem „Out of capacity”: **Ampere** →
   **VM.Standard.A1.Flex**, 1 OCPU, 6 GB RAM (też darmowy).
4. **Networking**: zostaw domyślne, zaznaczone ma być *Assign a public IPv4 address*.
5. **Add SSH keys**: *Generate a key pair for me* → **Save private key** (zapisze się plik
   `ssh-key-….key` w Pobranych).
6. **Create**. Po 1–2 min status zmieni się na *Running*; skopiuj **Public IP address**.

### 3. Połączenie z maszyną (2 min)

W Ubuntu (WSL) na laptopie (w ostatniej linii podmień `ADRES_IP`):

```bash
mkdir -p ~/.ssh
cp /mnt/c/Users/*/Downloads/ssh-key-*.key ~/.ssh/oracle-cg.key
chmod 600 ~/.ssh/oracle-cg.key
ssh -i ~/.ssh/oracle-cg.key ubuntu@ADRES_IP
```

Przy pierwszym połączeniu wpisz `yes`. Gdy zobaczysz `ubuntu@cg-test:~$`, jesteś na serwerze.

### 4. Test (5 min)

Na serwerze wklej po kolei:

```bash
curl -4 -s ifconfig.me; echo
curl -LsSf https://astral.sh/uv/install.sh | sh
source ~/.local/bin/env
curl -LO https://raw.githubusercontent.com/Tomek17/cg-connector/main/ops/vps-test/test_garmin_vps.py
uv run test_garmin_vps.py
```

Pierwsza linia pokazuje publiczny adres serwera (przyda się do raportu). `uv run` sam pobierze
Pythona i bibliotekę Garmina (ok. 30 s). Potem skrypt zapyta o e-mail, hasło i kod MFA z Garmina.

**Tylko jedna próba.** Jeśli coś nie przejdzie, nie powtarzaj — wklej Claude całe wyjście
(hasła tam nie ma). Po komunikacie o HTTP 429 nie uruchamiaj niczego z Garminem przez 24 h,
także na laptopie, bo Garmin może zablokować konto.

### 5. Wynik

| Co widzisz | Znaczenie | Dalej |
|---|---|---|
| `WYNIK: ZALICZONY` | Garmin działa z tej serwerowni | wklej podsumowanie Claude → etap 2 (plan przeprowadzki). Maszynę zostaw, posłuży jako serwer |
| ✗ w kroku 1, a 403 / „TLS” / błąd połączenia | Garmin blokuje logowanie z tego IP | wklej wynik; spróbujemy Hetzner albo wariantu „logowanie w domu, serwer w chmurze” |
| ✗ w kroku 1 „odmowa logowania” | zwykle zły e-mail, hasło lub kod MFA | odczekaj kilka godzin i spróbuj raz jeszcze |
| HTTP 429 | Garmin ograniczył zapytania | 24 h przerwy, potem decydujemy razem |
| ✓ 1–3, ✗ w 4 | dane się pobierają, treningi nie | wklej wynik; sprawdź w Garmin Connect, czy nie został „CG TEST VPS (usuń)” |

Po teście wyloguj się z serwera: `exit`. Maszyny Oracle nie trzeba usuwać (jest darmowa).
Jeśli testowałeś na Hetznerze i test nie przeszedł, usuń serwer w panelu Hetznera (Delete),
żeby nie naliczał opłat.

---

## Plan B: Hetzner Cloud (gdyby Oracle nie zadziałał)

1. https://console.hetzner.cloud → załóż konto (może poprosić o weryfikację dokumentem lub
   przedpłatę 20 €, zależnie od oceny konta).
2. **New project** → **Add server**: lokalizacja **Falkenstein** lub **Nuremberg**,
   obraz **Ubuntu 24.04**, typ **CX22** (Shared vCPU, x86), **SSH key**: dodaj klucz publiczny
   (w WSL: `ssh-keygen -t ed25519 -f ~/.ssh/hetzner-cg -N ""`, potem
   `cat ~/.ssh/hetzner-cg.pub` i wklej). **Create & Buy now**.
3. Połącz się: `ssh -i ~/.ssh/hetzner-cg root@ADRES_IP` i wykonaj krok 4 z instrukcji wyżej.
4. Opłata jest godzinowa; po teście usuń serwer albo zostaw go, jeśli to on ma zostać serwerem.
