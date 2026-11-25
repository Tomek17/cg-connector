# Przeprowadzka serwera z laptopa na VPS (Oracle Cloud)

Po tej przeprowadzce serwer CG-connector działa całą dobę na darmowej maszynie Oracle, a laptop nie musi być włączony.
Warunek był jeden: [test Garmina z VPS](test-vps.md) musiał przejść. Przeszedł 25.11.2026 (7/7).

## Jak to jest zabezpieczone

- **Ten sam adres.** Serwer na VPS używa tej samej stałej domeny ngrok co laptop. Konektor w claude.ai, aplikacja Tomka, Wojtka i pozostałych osób działają dalej **bez żadnych zmian**.
- **Dane laptopa zostają nietknięte.** Skrypt tylko je kopiuje. Dodatkowo robi punkt powrotu `~/cg-przed-vps-<data>/`, czyli archiwum danych i kopię `.env`.
- **Serwer nigdy nie działa w dwóch miejscach naraz.** Dwa serwery odświeżałyby te same tokeny Garmina i walczyły o domenę ngrok. Dlatego skrypt wyłącza autostart na laptopie i zostawia plik `server/compose.override.yaml`, który blokuje start serwera na laptopie.
- **Automatyczne wycofanie.** Jeśli cokolwiek pójdzie nie tak, zanim serwer ruszy na VPS, skrypt sam włącza z powrotem laptopa. Nieudaną kopię na VPS odkłada na bok jako `~/cg-data-nieudana-<data>`, niczego nie kasuje.
- **Powrót jednym poleceniem.** Służy do tego `ops/vps/wroc-na-laptopa.sh`, opisany niżej. Domyślnie zabiera najnowsze dane z VPS, więc nic nie ginie.
- **Sekrety nie wychodzą poza Twoje maszyny.** Plik `.env` (klucz szyfrowania, token ngrok) idzie tylko szyfrowanym SSH z laptopa na VPS. VPS nie ma otwartych portów poza SSH, a ruch z internetu wchodzi tunelem ngrok, tak jak na laptopie.

Czas: ok. 20–30 minut, w tym przerwa w działaniu serwera na ok. 5–10 minut.
Laptop musi być włączony, a Ubuntu (WSL) otwarte.

---

## Krok 1. Najnowszy kod na laptopie (1 min)

W Ubuntu (WSL):

```bash
cd ~/cg-connector && git pull && ls ops/vps
```

Oczekiwany wynik: lista z `przenies-na-vps.sh`, `przygotuj-vps.sh`, `wroc-na-laptopa.sh` i `pobierz-kopie-z-vps.sh`.

## Krok 2. Przygotowanie VPS (5 min)

Z laptopa, bez logowania na VPS:

```bash
ssh -i ~/.ssh/oracle-cg.key opc@92.5.126.13 'bash -s' < ~/cg-connector/ops/vps/przygotuj-vps.sh
```

Skrypt instaluje na VPS Dockera i git oraz włącza automatyczne poprawki bezpieczeństwa. Pobiera też kod i tworzy pusty folder danych `~/cg-data`. Niczego nie uruchamia. Można go powtarzać.

Oczekiwany wynik na końcu: `GOTOWE. VPS przygotowany; serwer jeszcze nie działa.`

## Krok 3 (zalecany). Zabezpieczenie przed „odzyskaniem” maszyny przez Oracle (5 min)

Według [dokumentacji Oracle](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm) darmowa maszyna może zostać „odzyskana”, jeśli przez 7 dni jednocześnie zużywa poniżej 20% procesora, sieci i (przy A1) pamięci. Nasz serwer jest lekki, więc to realne ryzyko. Dokumentacja nie mówi, czy „odzyskana” znaczy zatrzymana czy usunięta.

Dokumentacja nie obiecuje też wprost, że konto płatne chroni przed tą regułą. Z doświadczeń użytkowników wynika jednak, że Oracle odzyskuje bezczynne maszyny tylko na kontach darmowych, a nie na **Pay As You Go**. Na takim koncie za zasoby z limitu Always Free dalej się nie płaci; Oracle pisze to wprost.

1. Konsola Oracle → ☰ → **Billing & Cost Management** → **Upgrade and Manage Payment** → **Upgrade to Pay As You Go**. Oracle może na chwilę zablokować drobną kwotę na karcie.
2. Zabezpieczenie przed przypadkowym kosztem: ☰ → **Billing & Cost Management** → **Budgets** → **Create Budget**. Kwota: 1 (w walucie konta), alert e-mail przy 1% kwoty. Dostaniesz mail, gdyby cokolwiek zaczęło kosztować.

Niezależnie od tego raz w tygodniu pobieraj kopię bazy na laptopa (`pobierz-kopie-z-vps.sh`, opisane niżej). Nawet gdyby Oracle usunął maszynę, z tej kopii odtworzymy serwer na laptopie albo na nowej maszynie.

## Krok 4. Przeprowadzka (10 min)

W Ubuntu (WSL) na laptopie:

```bash
~/cg-connector/ops/vps/przenies-na-vps.sh opc@92.5.126.13
```

1. Skrypt najpierw wszystko sprawdza, a potem pyta `Przenieść serwer na VPS? [t/N]`. Wpisz `t`.
2. Gdy poprosi o hasło (`[sudo] password`), podaj hasło do Ubuntu. Litery się nie wyświetlają.
3. Kolejne etapy pojawiają się jako `==> 1/7 … 7/7`. Najdłużej trwa krok 5, czyli budowa obrazu na VPS (kilka minut).

Oczekiwany wynik na końcu: `GOTOWE. Serwer działa na VPS pod tym samym adresem: https://…`

Jeśli zamiast tego zobaczysz `!!! Coś poszło nie tak – włączam z powrotem serwer na laptopie`, laptop działa jak przedtem. Wklej Claude cały wynik.

## Krok 5. Sprawdzenie (5 min)

1. **Aplikacja:** Ustawienia → Status ma być **Online**. Potem „Pobierz teraz” (najwyżej raz na godzinę).
2. **Claude:** w nowej rozmowie zapytaj np. „Jaki mam VO2max?”. Konektor powinien odpowiedzieć bez ponownego łączenia.
3. **Laptop możesz wyłączyć** i sprawdzić z telefonu na danych komórkowych, że wszystko dalej działa.

Codzienna synchronizacja o 14:00 i kopia bazy o 14:30 działają teraz na VPS.

---

## Powrót na laptopa

Gdyby coś było nie tak (np. Garmin zaczął blokować, Oracle zatrzymał maszynę), w Ubuntu (WSL) na laptopie:

```bash
~/cg-connector/ops/vps/wroc-na-laptopa.sh opc@92.5.126.13
```

Skrypt zatrzymuje serwer na VPS i przenosi jego **aktualne** dane na laptopa. Dzięki temu nic nie ginie, a tokeny Garmina są najświeższe. Poprzednie dane laptopa odkłada do `~/cg-data/backups/przed-powrotem-<data>/`. Potem uruchamia serwer na laptopie i z powrotem włącza autostart.

Gdy VPS w ogóle nie odpowiada:

```bash
~/cg-connector/ops/vps/wroc-na-laptopa.sh opc@92.5.126.13 --bez-danych-z-vps
```

Laptop wraca wtedy do stanu sprzed przeprowadzki. Zmiany z czasu pracy na VPS (tagi, cel, zaplanowane treningi) przepadają, a nowe aktywności pobiorą się z Garmina same. Jeśli Garmin w międzyczasie unieważnił stare tokeny, trzeba raz zalogować się do Garmina od nowa (`cg-server garmin-login`, a reszta osób przez aplikację).

Po powrocie nic nie trzeba zmieniać ani w claude.ai, ani w aplikacjach, bo adres jest ten sam.

## Na co dzień, gdy serwer jest na VPS

Wszystkie polecenia wykonujesz z Ubuntu (WSL) na laptopie.

- **Druga kopia bazy poza chmurą** (np. raz w tygodniu): `~/cg-connector/ops/vps/pobierz-kopie-z-vps.sh opc@92.5.126.13`. Pobiera najnowszą kopię do `C:\Users\<nazwa>\CG-kopie\vps` i trzyma 8 ostatnich.
- **Logowanie na VPS:** `ssh -i ~/.ssh/oracle-cg.key opc@92.5.126.13`.
- **Aktualizacja serwera** (zamiast tej z laptopa):

  ```bash
  ssh -i ~/.ssh/oracle-cg.key opc@92.5.126.13 'cd ~/cg-connector && git pull && cd server && sudo docker compose up -d --build cg-server ngrok'
  ```

  Przed każdą zmianą schematu bazy serwer sam robi kopię do `~/cg-data/backups/przed-aktualizacja-*`.
- **Zaproszenie osoby:** najprościej z aplikacji (Ustawienia → Zaproś osobę). Z wiersza poleceń:

  ```bash
  ssh -i ~/.ssh/oracle-cg.key opc@92.5.126.13 'cd ~/cg-connector/server && sudo docker compose exec cg-server cg-server invite --name ola'
  ```

- **Stan serwera:** `ssh -i ~/.ssh/oracle-cg.key opc@92.5.126.13 'cd ~/cg-connector/server && sudo docker compose ps && sudo docker compose logs --tail 20 cg-server'`.
- **Na laptopie** `docker compose up` celowo nie uruchomi serwera (blokada `server/compose.override.yaml`). Nie usuwaj tego pliku ręcznie, tylko użyj `wroc-na-laptopa.sh`.

## Później (osobny krok, opcjonalnie)

Własny adres HTTPS bez ngroka (np. darmowa subdomena i certyfikat Let’s Encrypt na VPS) usunąłby limit ngroka (20 000 żądań/mies.). Wymaga jednak zmiany adresu w konektorze claude.ai i w aplikacjach wszystkich osób, więc robimy to dopiero, gdy VPS przez jakiś czas działa stabilnie.
