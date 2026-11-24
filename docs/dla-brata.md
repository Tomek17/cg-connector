# CG-connector: instrukcja dla brata (i każdej kolejnej osoby)

Ta instrukcja jest dla Ciebie, jeśli masz zegarek Garmin (np. Fenix 8 Pro), własne konto Garmin Connect i telefon z Androidem (np. Samsung). Nie musisz znać się na programowaniu ani spotykać z Tomkiem: wszystko robisz sam w aplikacji, która prowadzi Cię krok po kroku. Ta instrukcja to tylko ściąga. Konto Claude (darmowe wystarczy) jest potrzebne tylko do rozmów z Claude; sama aplikacja działa bez niego.

Każda osoba ma własne konto na serwerze, sama loguje się do swojego Garmina i ma własny kod do Claude. Nikt nie widzi danych drugiej osoby w aplikacji ani w Claude.

## Co to robi

- Raz dziennie pobiera Twoje treningi i VO2max z Garmin Connect.
- Pokazuje je w aplikacji **CG-connector** na Twoim telefonie. Działa też bez internetu.
- Pozwala rozmawiać o treningach z **Claude** (claude.ai, darmowy plan wystarczy).
- Pozwala ułożyć trening (w aplikacji albo z Claude) i wysłać go na zegarek.

Wszystko działa przez **serwer na laptopie Tomka**.

## Co musisz wiedzieć na start

- Dane pobierają się codziennie **o 14:00**. Jeśli laptop Tomka był wtedy wyłączony, to zaraz po jego włączeniu.
- **Claude, logowanie do Garmina i wysyłanie treningów działają tylko wtedy, gdy laptop Tomka jest włączony.**
- Gdy laptop jest wyłączony, aplikacja nadal pokazuje Twoje dane. Trening czeka w kolejce i wyśle się sam, gdy laptop się włączy.
- Trening trafia najpierw do **kalendarza Garmin Connect**. Na zegarek trafia, gdy zegarek zsynchronizuje się z aplikacją Garmin Connect na telefonie.

## Krok 1. Link zaproszenia od Tomka

Tomek wysyła Ci (SMS-em albo w komunikatorze) **link zaproszenia**, np. `https://crisply-idly-precise.ngrok-free.dev/join?code=cgi_…`. Link jest **jednorazowy** i ważny **7 dni**. Wygasł albo zgubiony? Poproś Tomka o nowy. Nie przesyłaj go dalej.

## Krok 2. Instalacja aplikacji

1. Otwórz link na telefonie. Jeśli pojawi się ostrzeżenie ngrok, stuknij **Visit Site**.
2. Stuknij **Pobierz aplikację**: od razu pobierze się najnowszy plik `.apk`.
3. Gdy telefon zapyta, pozwól przeglądarce **instalować nieznane aplikacje** (na Samsungu nazwy mogą się trochę różnić). Otwórz pobrany plik i stuknij **Zainstaluj**. Ostrzeżenie Google Play Protect: *Zainstaluj mimo to* (to normalne dla aplikacji spoza sklepu).

Kolejne wersje aplikacja pokaże sama: w **Ustawieniach** pojawi się „Dostępna nowa wersja” z przyciskiem **Pobierz aktualizację**. Instalujesz ją na obecną aplikację, dane zostają.

## Krok 3. Resztę prowadzi aplikacja

Wróć do linku zaproszenia i stuknij **Otwórz w aplikacji**. Aplikacja otworzy się z kreatorem, który krok po kroku przeprowadzi Cię przez:

1. **Połączenie z serwerem**: adres i kod są już wpisane z linku, wystarczy stuknąć **Połącz**.
2. **Konto Garmin**: e-mail i hasło do Garmin Connect, a jeśli Garmin wyśle kod weryfikacyjny (e-mail albo SMS), wpisujesz go w ciągu 10 minut.
3. **Claude** (opcjonalnie): aplikacja przygotuje kod, otworzy claude.ai w przeglądarce i powie, co gdzie wkleić. Możesz to pominąć i wrócić później w **Ustawieniach → Claude**.

Jeśli w trakcie wyjdziesz z aplikacji (np. do poczty po kod z Garmina), po powrocie kreator będzie w tym samym miejscu.

> **Sprawdź hasło przed wysłaniem.** Po kilku błędnych próbach Garmin blokuje logowanie na około dobę. Aplikacja pozwala na 2 nieudane próby na godzinę, potem każe odczekać.

Hasło jest szyfrowane w telefonie kluczem serwera, więc po drodze (tunel ngrok) nikt go nie odczyta. Serwer używa go jeden raz do zalogowania i **nigdzie go nie zapisuje**; trzyma tylko zaszyfrowany „klucz dostępu” do Garmina. **Nigdy nie wysyłaj hasła** SMS-em, mailem, na Messengerze ani w czacie z Claude.

Darmowe Claude pozwala mieć **tylko jeden** własny konektor. CG-connector go zajmuje. Nowy kod z aplikacji unieważnia poprzedni i rozłącza Claude, więc generuj go tylko przy łączeniu.

Przykładowe pytania: „Jak zmienia się mój VO2max?”, „Zaplanuj mi interwały na czwartek.”, „Co mam zaplanowane na ten tydzień?”.

## Dla Tomka: zaproszenie nowej osoby

Najprościej z telefonu: **Ustawienia → Zaproś osobę** (widzi to tylko konto administratora, czyli najstarsze konto na serwerze albo to z `CG_ADMIN_USER` w `.env`). Wpisz nazwę (małe litery, np. `wojtek`), stuknij **Utwórz link zaproszenia**, potem **Wyślij link** (SMS, Messenger…). Ta sama nazwa drugi raz = nowy link dla tej osoby (poprzedni przestaje działać).

Instrukcję podłączenia Claude ze zrzutami możesz wysłać razem z linkiem: [podlaczenie-claude.pdf](podlaczenie-claude.pdf).

To samo z laptopa (serwer włączony), w Ubuntu, jedna osoba = jedno polecenie (nazwa małymi literami, np. `brat`, `ola`):

```bash
cd ~/cg-connector/server && docker compose exec cg-server cg-server invite --name brat
```

Polecenie zakłada konto (jeśli go nie ma) i wypisuje **link zaproszenia** (`https://…/join?code=cgi_…`). Wyślij go tej osobie prywatnie; resztę poprowadzi ją aplikacja (ta instrukcja nie jest potrzebna, to tylko ściąga). Link wygasł albo zginął? Uruchom polecenie jeszcze raz; poprzedni przestaje działać.

Stara droga (wszystko na laptopie, osoba wpisuje hasło przy Tobie) nadal działa: `~/cg-connector/ops/wsl/dodaj-osobe.sh brat`, szczegóły w [konfiguracja-claude.md](konfiguracja-claude.md#przygotowanie-użytkownika-na-serwerze-robi-tomek).

## Codzienne używanie

- Aplikacja sama pobiera nowe dane około 14:20. Możesz też przeciągnąć listę w dół, żeby odświeżyć.
- Nowy trening: przycisk **Nowy trening** → bieg albo siłowy → wybierz datę → zapisz. Status zobaczysz w zakładce **Plan**.
- Status „Oczekuje na wysłanie” oznacza, że laptop Tomka jest wyłączony. Trening wyśle się sam.
- Status „Wysłany do Garmin Connect”: zsynchronizuj zegarek z aplikacją Garmin Connect i sprawdź kalendarz.

## Co zrobić, gdy dane są stare

Na dole zakładki **Dziś** widzisz, z kiedy są dane; szczegóły są w **Ustawieniach**, w sekcji **Połączenie i synchronizacja**. Jeśli pojawi się ostrzeżenie „Dane mogą być nieaktualne”:

1. Stuknij **Sprawdź teraz**.
2. Jeśli Serwer jest **Offline**: laptop Tomka jest wyłączony albo śpi. Napisz do Tomka, żeby go włączył. Dane pobiorą się same po włączeniu.
3. Jeśli Konto Garmin to **„Wymaga ponownego logowania na serwerze”**: w **Ustawieniach**, w sekcji **Konto Garmin**, stuknij **Zaloguj ponownie** i zaloguj się jak w kreatorze.
4. Jeśli Konto Garmin to **„Garmin ogranicza zapytania”**: nic nie rób. Serwer spróbuje sam później, zwykle następnego dnia.
5. Jeśli wynik odświeżenia to **„Token odrzucony”** albo aplikacja prosi o ponowne wklejenie tokenu (np. po zmianie blokady ekranu): poproś Tomka o nowy link zaproszenia i powtórz Krok 3 (możesz też wkleić token `cga_…` od Tomka w polu „Token aplikacji”).

Gdy Claude nie działa:

- „Nie można połączyć z konektorem”: laptop Tomka jest wyłączony. Spróbuj później.
- „Nieprawidłowy kod” przy logowaniu: sprawdź, czy wklejasz kod `cgc_…`, a nie `cgi_…` czy `cga_…`. Kod mógł też zostać zastąpiony nowszym: wygeneruj nowy w aplikacji.

## Prywatność (uczciwie)

- Twoje dane treningowe leżą **na laptopie Tomka** (i w kopii zapasowej na tym samym laptopie) oraz w Twoim telefonie. Nie trafiają do żadnej zewnętrznej usługi poza Garminem i (gdy pytasz) Claude.
- Tomek nie widzi Twoich danych w swojej aplikacji ani w swoim Claude. Każdy ma osobny token i kod.
- **Ale Tomek jest administratorem serwera, więc technicznie może zajrzeć do Twoich danych treningowych** w bazie na swoim laptopie. Nie da się tego wykluczyć przy serwerze na jego komputerze.
- Tomek **nie zna Twojego hasła do Garmina**. Aplikacja szyfruje je kluczem serwera, serwer używa go raz do zalogowania i nie zapisuje ani nie loguje.
- Zaszyfrowany klucz dostępu do Twojego konta Garmin leży na laptopie Tomka. Pozwala serwerowi czytać Twoje treningi i dodawać treningi do kalendarza. Usuwa tylko treningi utworzone przez CG-connector.
- Gdy rozmawiasz z Claude o treningach, Claude widzi dane, o które pytasz (jak każdą treść rozmowy).
- Chcesz zakończyć? Powiedz Tomkowi, żeby usunął Twoje konto i dane z serwera. W wersji 1.0 nie ma do tego gotowego polecenia, więc Tomek robi to ręcznie. Sam możesz: usunąć konektor CG-connector w claude.ai, odinstalować aplikację i zmienić hasło do Garmin Connect. Czy sama zmiana hasła unieważnia zapisany klucz dostępu, jest do potwierdzenia w teście.
