# Konfiguracja Claude (konektor MCP, także na darmowym planie)

Instrukcja podłączenia CG-connector do Claude jako **własnego konektora MCP**. Po podłączeniu możesz w zwykłym czacie claude.ai pytać o swoje treningi i VO2max oraz planować treningi, które trafiają na zegarek.

Każda osoba podłącza konektor **na swoim koncie Claude** i loguje się **swoim** kodem CG. Serwer rozpoznaje użytkownika wyłącznie po tym kodzie, więc Claude brata widzi tylko dane brata, a Claude Tomka tylko dane Tomka.

> **Oznaczenie „do potwierdzenia w teście”:** nazwy menu i okien claude.ai poniżej pochodzą z dokumentacji Anthropic (sprawdzonej 2026-11-18, patrz [research](research/mcp-tunel.md)), a nie z przejścia całego procesu na prawdziwym koncie. Interfejs claude.ai się zmienia i może mieć inne nazwy po polsku i po angielsku. Takie miejsca są oznaczone ⚠️ *do potwierdzenia w teście*.

## Co jest potrzebne

1. **Działający serwer** z publicznym adresem `https://<nazwa>.ts.net` (np. `https://cg-server.twoj-tailnet.ts.net`) albo, przy planie B, `https://<domena>.ngrok-free.dev`. Instalacja: [instalacja-serwera.md](instalacja-serwera.md).
2. **Użytkownik na serwerze**, zalogowany do Garmin Connect i z co najmniej jedną synchronizacją (patrz [niżej](#przygotowanie-użytkownika-na-serwerze-robi-tomek)).
3. **Osobisty kod CG** (zaczyna się od `cgc_`). Generuje go Tomek poleceniem `cg-server user-code`.
4. **Konto Claude** (wystarczy darmowe) i przeglądarka na komputerze lub telefonie.

Konektor dodaje się na claude.ai w przeglądarce. Potem działa też w aplikacji Claude na telefonie (po ponownym zalogowaniu). ⚠️ *do potwierdzenia w teście.*

## Przygotowanie użytkownika na serwerze (robi Tomek)

Polecenia wpisuje się w oknie **Ubuntu** na laptopie-serwerze, w folderze serwera. W przykładach użytkownik nazywa się `brat`. Nazwę wybierasz sam, ale potem używasz tej samej w każdym poleceniu.

```bash
cd ~/cg-connector/server
docker compose exec cg-server cg-server user-add --name brat
docker compose exec cg-server cg-server garmin-login --user brat
docker compose exec cg-server cg-server sync --user brat
docker compose exec cg-server cg-server user-code --user brat
```

Co robią te polecenia:

| Polecenie | Co robi |
|---|---|
| `user-add --name brat` | Dodaje użytkownika. Oczekiwany wynik: `Dodano użytkownika „brat”.` |
| `garmin-login --user brat` | Jednorazowe logowanie do Garmin Connect. Pyta o e-mail, hasło (niewidoczne) i ewentualnie kod MFA. **Hasło wpisuje właściciel konta Garmin osobiście, na tym laptopie.** Hasło nie jest nigdzie zapisywane, tylko zaszyfrowane tokeny. |
| `sync --user brat` | Pierwsza synchronizacja od razu (zamiast czekać do 14:00). Pierwszy sync pobiera historię i może potrwać kilka minut. |
| `user-code --user brat` | Generuje **osobisty kod CG** do logowania konektora w Claude. |
| `app-token --user brat` | (Opcjonalnie, dla aplikacji Android) generuje **token aplikacji** `cga_…`. Patrz [dla-brata.md](dla-brata.md). |

Wynik `user-code` wygląda tak (kod jest losowy, ten jest tylko przykładem):

```
Osobisty kod CG-connector dla „brat” (pokazywany tylko raz, zapisz go w bezpiecznym miejscu):

    cgc_PRZYKLAD0000000000000000000000000000000

Wklej go na stronie logowania przy dodawaniu konektora w claude.ai.
Poprzedni kod/token (jeśli był) przestał działać.
Wcześniejsze połączenia claude.ai zostały rozłączone; połącz konektor ponownie.
```

Ważne:

- Kod jest pokazywany **tylko raz**. Serwer trzyma tylko jego skrót (hash), więc nie da się go później odczytać. Zgubiony kod: wygeneruj nowy tym samym poleceniem.
- **Nowy kod unieważnia stary i rozłącza wszystkie dotychczasowe połączenia Claude** tego użytkownika. Po wygenerowaniu nowego kodu trzeba ponownie połączyć konektor.
- Kod CG (`cgc_…`) i token aplikacji Android (`cga_…`) to **dwa różne sekrety**. Kod CG nie zadziała w aplikacji, a token aplikacji nie zadziała w Claude.
- Kod przekazuj osobiście albo bezpiecznym kanałem. Nie wklejaj go do czatu z Claude, do maila, do issue ani na zrzut ekranu.

Stan wszystkich użytkowników sprawdzisz poleceniem:

```bash
docker compose exec cg-server cg-server sync-status
```

## Dodanie konektora w claude.ai

1. Otwórz <https://claude.ai> i zaloguj się na **swoje** konto.
2. Wejdź na <https://claude.ai/customize/connectors> (w menu: **Customize → Connectors**; to **nie** jest w Ustawieniach). Kliknij **+ Add**, potem **Add custom connector**. (Sprawdzone na żywo 2026-11-21.)
3. Wypełnij:
   - **Nazwa:** `CG-connector`
   - **Adres URL:** `https://<adres serwera>/mcp`, np. `https://cg-server.twoj-tailnet.ts.net/mcp` albo przy planie B `https://<domena>.ngrok-free.dev/mcp`. Na końcu musi być **`/mcp`**.
   - Jeśli pojawią się opcje: **Authentication** → **Sign in now**, **OAuth client** → **Register automatically** (nie „Use Claude's published identity”), **Request headers** puste.
4. Kliknij **Add**, potem **Connect**. Przy planie B (ngrok) najpierw może pojawić się strona ostrzeżenia ngrok: kliknij **Visit Site**.
5. Otworzy się strona **CG-connector** z serwera z napisem „Aplikacja „…” chce połączyć się z Twoimi danymi z Garmin Connect” i adresem powrotu (`claude.ai` albo `claude.com`). Sprawdź, że adres powrotu to jeden z nich.
6. W polu **„Wklej swój kod CG-connector”** wklej kod `cgc_…` i kliknij **Połącz z Claude**.
7. Wrócisz do claude.ai, a konektor będzie oznaczony jako połączony.
8. W nowej rozmowie włącz konektor: przycisk **„+”** (lub ikona narzędzi) → **Konektory** → włącz **CG-connector**. ⚠️ *do potwierdzenia w teście.*
9. Sprawdzenie (działa, test na żywo 2026-11-21): zapytaj „Kim jestem w CG-connector i kiedy była ostatnia synchronizacja?”. Claude powinien wywołać `get_profile` i podać Twoją nazwę użytkownika oraz datę ostatniego syncu.

### Jak działa logowanie (dla ciekawych)

- Serwer jest małym serwerem OAuth 2.1 (dynamiczna rejestracja klienta + PKCE, [ADR-0003](adr/0003-uwierzytelnienie-mcp.md)). Przyjmuje tylko adresy powrotu claude.ai i claude.com.
- Link do strony logowania jest ważny **10 minut**. Po **5 błędnych kodach** link przestaje działać i trzeba zacząć od kroku 4.
- Po zalogowaniu Claude dostaje tokeny: dostępowy (ważny 1 h) i odświeżający (ważny 60 dni, wymieniany przy każdym odświeżeniu). Claude odnawia je sam. Jeśli nie używasz konektora przez ponad 60 dni, trzeba połączyć go ponownie.
- Twój kod CG nie trafia do Claude. Claude dostaje tylko własne tokeny, które serwer przechowuje jako skróty (hash).

## Narzędzia, które Claude dostaje

Claude sam wybiera narzędzie na podstawie pytania. Nie musisz znać nazw. Daty są w czasie polskim, jednostki: km, min/km, bpm.

### Odczyt danych

| Narzędzie | Co zwraca |
|---|---|
| `get_profile` | Nazwa użytkownika, stan połączenia z Garminem, czas ostatniej udanej synchronizacji, data najnowszej aktywności. |
| `list_activities` | Lista aktywności od najnowszej: data, typ, km, czas, tempo, średnie tętno. Domyślnie ostatnie 30 dni, do 20 pozycji (maks. 50). Filtr typu (bieg, siłownia, wspinaczka, inne). |
| `get_activity` | Szczegóły jednej aktywności: okrążenia, minuty w strefach tętna, serie siłowe, training effect i obciążenie. |
| `get_vo2max_trend` | Trend VO2max z biegu: ostatnia wartość, zmiana, min/max, punkty. Domyślnie ostatnie 180 dni. |
| `get_training_summary` | Sumy tygodniowe (pon–niedz) albo miesięczne per typ aktywności: liczba, km, czas, przewyższenie, tempo biegu. Domyślnie 8 tygodni albo 6 miesięcy. |

### Treningi na zegarek

| Narzędzie | Co robi |
|---|---|
| `create_workout` | Zapisuje **szkic** treningu (bieg strukturalny, siłowy albo „inne”/bouldering z samym opisem; Garmin nie ma typu treningu dla wspinaczki, więc bouldering trafia do kalendarza jako „Inne”) i zwraca podgląd. Jeszcze niczego nie wysyła. |
| `schedule_workout` | Wysyła trening do **kalendarza Garmin Connect** na wybrany dzień (dziś lub później) i zwraca status. |
| `list_scheduled_workouts` | Treningi zaplanowane przez CG-connector (domyślnie od dziś na 30 dni) ze statusem. |
| `delete_scheduled_workout` | Usuwa zaplanowany trening z kalendarza Garmin. Działa **tylko** dla treningów utworzonych przez CG-connector i tylko na Twoje wyraźne polecenie. |

Trening trafia do kalendarza Garmin Connect, a na zegarek **przy najbliższej synchronizacji zegarka z aplikacją Garmin Connect** na telefonie. CG-connector nie łączy się z zegarkiem bezpośrednio.

Biegi: rozgrzewka, interwały, przerwy, schłodzenie, powtórzenia; koniec kroku po czasie, dystansie albo przycisku okrążenia; cel tempa (min/km), strefy tętna albo zakresu tętna. Siłowe: ćwiczenia z katalogu Garmina (nazwy angielskie, np. „Barbell Back Squat”), serie, powtórzenia albo czas, ciężar, przerwa.

### Przykładowe pytania

- „Jak wyglądał mój ostatni tydzień?”
- „Porównaj kilometry biegowe z ostatnich 8 tygodni. Rośnie czy spada?”
- „Pokaż szczegóły mojego ostatniego biegu: okrążenia i strefy tętna.”
- „Jak zmieniał się mój VO2max przez ostatnie pół roku?”
- „Ile razy byłem na siłowni w tym miesiącu i co robiłem na ostatnim treningu?”
- „Zaplanuj mi interwały na czwartek: 10 min rozgrzewki, 5 × 1 km w tempie 4:30–4:15 z 90 s przerwy, schłodzenie.”
- „Ułóż trening siłowy na sobotę: przysiad 3 × 5 po 80 kg, martwy ciąg 3 × 5, plank 3 × 45 s.”
- „Co mam zaplanowane na ten tydzień?”
- „Usuń trening zaplanowany na piątek.”

Dobra praktyka: przy planowaniu poproś Claude, żeby **najpierw pokazał podgląd** (`create_workout`), a dopiero po Twoim „OK” wysłał trening (`schedule_workout`).

## Ograniczenia

- **Dane są z ostatniej synchronizacji serwera z Garminem**, a nie na żywo. Sync jest codziennie o **14:00**, a jeśli laptop był wtedy wyłączony, zaraz po jego włączeniu. Trening zrobiony rano zobaczysz w Claude po popołudniowym syncu. Gdy ostatni udany sync był ponad 24 h temu albo konto Garmin wymaga ponownego logowania, Claude dostaje w odpowiedzi notatkę o tym i powinien Ci to przekazać.
- **Konektor działa tylko, gdy laptop-serwer jest włączony** i ma internet. Gdy laptop śpi albo jest wyłączony, Claude pokaże błąd połączenia z konektorem. Dane nadal są w aplikacji na telefonie (offline).
- **Darmowy plan Claude pozwala dodać tylko 1 własny konektor.** CG-connector go zajmuje. Żeby dodać inny, trzeba usunąć ten.
- Darmowy plan ma limity wiadomości i długości rozmowy. Narzędzia zwracają krótkie odpowiedzi, ale długie analizy wielu miesięcy szybciej wyczerpują limit. Lepiej pytać o konkretny okres.
- Zakres danych: aktywności i VO2max. **Brak** snu, HRV, Body Battery, stresu i SpO2.
- Nie da się zmienić logowania istniejącego konektora. Przy problemach z logowaniem usuń konektor i dodaj go ponownie. ⚠️ *do potwierdzenia w teście.*

## Rozwiązywanie problemów

| Objaw | Co zrobić |
|---|---|
| Claude nie może połączyć się z konektorem / „serwer niedostępny” | Laptop-serwer jest wyłączony, uśpiony albo bez internetu. Włącz go (serwer startuje sam po zalogowaniu do Windows) i spróbuj za 1–2 minuty. Sprawdzenie: otwórz w przeglądarce `https://<nazwa>.ts.net/health`. Dalsze kroki: [instalacja-serwera.md](instalacja-serwera.md), sekcja „Rozwiązywanie problemów”. |
| Na stronie logowania: „Nieprawidłowy kod” | Sprawdź, czy wklejasz **kod CG** (`cgc_…`), a nie token aplikacji (`cga_…`), cały i bez spacji. Jeśli kod był wygenerowany ponownie, działa tylko najnowszy. |
| „Link logowania wygasł albo jest nieprawidłowy” | Minęło ponad 10 minut, było 5 błędnych prób albo serwer się zrestartował w trakcie. Wróć do Claude i połącz konektor ponownie. |
| Konektor nagle przestał działać po wygenerowaniu nowego kodu | To zamierzone: `user-code` rozłącza stare połączenia. Połącz konektor ponownie nowym kodem. |
| Claude odpowiada, że nie ma nowych treningów | Sync jeszcze nie przeszedł (przed 14:00 albo laptop był wyłączony). Zapytaj „kiedy była ostatnia synchronizacja?”. Tomek może wymusić sync: `docker compose exec cg-server cg-server sync --user <nazwa>`. |
| Claude pisze, że logowanie do Garmina wygasło | Tomek uruchamia `docker compose exec cg-server cg-server garmin-check --user <nazwa>`, a jeśli to nie pomoże, `garmin-login --user <nazwa>` (z właścicielem konta, który sam wpisuje hasło). Po błędzie 429 od Garmina odczekaj 24 h przed ponownym logowaniem. |
| Trening „wysłany”, ale nie ma go na zegarku | Zsynchronizuj zegarek z aplikacją Garmin Connect na telefonie i sprawdź kalendarz w Garmin Connect. |
| Trening ma status błędu | Zapytaj Claude „co jest zaplanowane i jaki ma status?”. Komunikat błędu jest w odpowiedzi `list_scheduled_workouts`. Najczęściej to zła nazwa ćwiczenia siłowego albo chwilowy problem z Garminem. |
| Konektor nie pojawia się w aplikacji Claude na telefonie | Wyloguj się i zaloguj ponownie w aplikacji. Konektor dodawaj na claude.ai w przeglądarce. ⚠️ *do potwierdzenia w teście.* |
| Nie da się dodać konektora: limit | Darmowy plan ma 1 własny konektor. Usuń inny konektor i dodaj CG-connector. |
