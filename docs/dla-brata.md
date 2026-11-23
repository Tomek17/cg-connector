# CG-connector: instrukcja dla brata (i każdej kolejnej osoby)

Ta instrukcja jest dla Ciebie, jeśli masz zegarek Garmin (np. Fenix 8 Pro), własne konto Garmin Connect i telefon z Androidem (np. Samsung). Nie musisz znać się na programowaniu ani spotykać z Tomkiem: wszystko robisz sam w aplikacji. Konto Claude (darmowe wystarczy) jest potrzebne tylko do rozmów z Claude; sama aplikacja działa bez niego.

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
2. Stuknij **Pobierz aplikację**. Na stronie wydania rozwiń **Assets** i stuknij plik kończący się na **`.apk`**.
3. Gdy telefon zapyta, pozwól przeglądarce **instalować nieznane aplikacje** (na Samsungu nazwy mogą się trochę różnić). Otwórz pobrany plik i stuknij **Zainstaluj**. Ostrzeżenie Google Play Protect: *Zainstaluj mimo to* (to normalne dla aplikacji spoza sklepu).

Kolejne wersje aplikacja pokaże sama: w **Ustawieniach** pojawi się „Dostępna nowa wersja” z przyciskiem **Pobierz aktualizację**. Instalujesz ją na obecną aplikację, dane zostają.

## Krok 3. Połączenie z serwerem

1. Wróć do linku zaproszenia i stuknij **Otwórz w aplikacji**. Aplikacja otworzy się z wpisanym adresem i kodem.
2. Stuknij **Połącz z serwerem**. Kod zaproszenia przestaje działać, a aplikacja zapisuje swój token (zaszyfrowany w telefonie).

Przycisk nie zadziałał? W aplikacji: **Ustawienia → Mam kod zaproszenia** i przepisz adres oraz kod pokazane na dole strony zaproszenia.

## Krok 4. Połączenie z Garminem (w aplikacji)

1. W **Ustawieniach** przewiń do sekcji **Konto Garmin**.
2. Wpisz e-mail i hasło do **Garmin Connect** i stuknij **Połącz z Garminem**.
3. Jeśli Garmin wyśle kod weryfikacyjny (e-mail albo SMS), wpisz go w ciągu 10 minut i stuknij **Potwierdź**.
4. Gotowe: „Połączono z Garminem”. Pierwsze pobranie danych trwa kilka minut. Potem w zakładce **Treningi** zobaczysz swoje aktywności, a na **Dziś** podsumowanie tygodnia i VO2max.

> **Sprawdź hasło przed wysłaniem.** Po kilku błędnych próbach Garmin blokuje logowanie na około dobę. Aplikacja pozwala na 2 nieudane próby na godzinę, potem każe odczekać.

Hasło jest szyfrowane w telefonie kluczem serwera, więc po drodze (tunel ngrok) nikt go nie odczyta. Serwer używa go jeden raz do zalogowania i **nigdzie go nie zapisuje**; trzyma tylko zaszyfrowany „klucz dostępu” do Garmina. **Nigdy nie wysyłaj hasła** SMS-em, mailem, na Messengerze ani w czacie z Claude.

## Krok 5. Podłączenie Claude (darmowe konto, opcjonalnie)

Nie masz konta Claude? Załóż darmowe na <https://claude.ai>. Nie chcesz używać Claude? Pomiń ten krok.

1. W aplikacji, w **Ustawieniach**, sekcja **Claude**: stuknij **Wygeneruj kod do Claude**.
2. Stuknij **Kopiuj adres i otwórz claude.ai**. Otworzy się strona konektorów w claude.ai: kliknij **+ Add → Add custom connector**, nazwa `CG-connector`, w polu adresu wklej skopiowany adres.
3. Jeśli pojawią się opcje: **Authentication** → **Sign in now**, **OAuth client** → **Register automatically** (pola „Client ID” i „Client Secret” puste). Strona ostrzeżenia ngrok: **Visit Site**.
4. Kliknij **Dodaj**, potem **Połącz**. Otworzy się strona CG-connector.
5. Wróć do aplikacji, stuknij **Kopiuj kod**, wróć do przeglądarki, wklej kod `cgc_…` i kliknij **Połącz z Claude**.
6. W nowej rozmowie włącz konektor: **„+”** → **Konektory** → **CG-connector** i zapytaj: „Jak wyglądał mój ostatni tydzień?”.

Darmowe Claude pozwala mieć **tylko jeden** własny konektor. CG-connector go zajmuje. Nowy kod z aplikacji unieważnia poprzedni i rozłącza Claude, więc generuj go tylko przy łączeniu.

Przykładowe pytania: „Jak zmienia się mój VO2max?”, „Zaplanuj mi interwały na czwartek.”, „Co mam zaplanowane na ten tydzień?”.

## Dla Tomka: zaproszenie nowej osoby

Na laptopie (serwer włączony), w Ubuntu, jedna osoba = jedno polecenie (nazwa małymi literami, np. `brat`, `ola`):

```bash
cd ~/cg-connector/server && docker compose exec cg-server cg-server invite --name brat
```

Polecenie zakłada konto (jeśli go nie ma) i wypisuje **link zaproszenia** (`https://…/join?code=cgi_…`). Wyślij go tej osobie prywatnie; resztę zrobi sama (Kroki 2–5). Link wygasł albo zginął? Uruchom polecenie jeszcze raz; poprzedni przestaje działać.

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
3. Jeśli Konto Garmin to **„Wymaga ponownego logowania na serwerze”**: w **Ustawieniach**, w sekcji **Konto Garmin**, stuknij **Zaloguj ponownie** i powtórz Krok 4.
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
