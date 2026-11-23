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

## Krok 1. Kod zaproszenia od Tomka

Tomek wpisuje na swoim laptopie jedno polecenie (szczegóły niżej) i wysyła Ci:

- **adres serwera**: teraz `https://crisply-idly-precise.ngrok-free.dev` (sam adres nie jest sekretem),
- **kod zaproszenia**, zaczyna się od `cgi_`. Jest **jednorazowy** i ważny **7 dni**. Wygasł albo zgubiony? Poproś Tomka o nowy.

## Krok 2. Instalacja aplikacji na telefonie

Aplikacji nie ma w Sklepie Google Play. Instalujesz ją z pliku APK.

1. Na telefonie otwórz stronę <https://github.com/Tomek17/cg-connector/releases>.
2. Przy najnowszej wersji (0.9.0 albo nowszej) rozwiń **Assets** i stuknij plik kończący się na **`.apk`**.
3. Gdy telefon zapyta, pozwól przeglądarce **instalować nieznane aplikacje** (Ustawienia → Aplikacje → Twoja przeglądarka → *Instaluj nieznane aplikacje* → zezwól). Na Samsungu nazwy mogą się trochę różnić.
4. Otwórz pobrany plik i stuknij **Zainstaluj**.
5. Jeśli pojawi się ostrzeżenie Google Play Protect, wybierz *Zainstaluj mimo to*. To normalne dla aplikacji spoza sklepu.
6. Po instalacji możesz z powrotem wyłączyć „instaluj nieznane aplikacje” dla przeglądarki.

## Krok 3. Połączenie z serwerem (kod zaproszenia)

1. Otwórz aplikację **CG-connector** i wejdź w zakładkę **Ustawienia** (na dole ekranu).
2. W sekcji **Mam kod zaproszenia** wpisz **adres serwera** i **kod zaproszenia** `cgi_…`.
3. Stuknij **Połącz z serwerem**. Aplikacja sama zapisze swój token (zaszyfrowany w telefonie). Kod zaproszenia przestaje działać.

## Krok 4. Połączenie z Garminem (w aplikacji)

1. W **Ustawieniach** przewiń do sekcji **Konto Garmin**.
2. Wpisz e-mail i hasło do **Garmin Connect** i stuknij **Połącz z Garminem**.
3. Jeśli Garmin wyśle kod weryfikacyjny (e-mail albo SMS), wpisz go w ciągu 10 minut i stuknij **Potwierdź**.
4. Gotowe: „Połączono z Garminem”. Pierwsze pobranie danych trwa kilka minut. Potem w zakładce **Treningi** zobaczysz swoje aktywności, a na **Dziś** podsumowanie tygodnia i VO2max.

> **Sprawdź hasło przed wysłaniem.** Po kilku błędnych próbach Garmin blokuje logowanie na około dobę. Aplikacja pozwala na 2 nieudane próby na godzinę, potem każe odczekać.

Hasło jest szyfrowane w telefonie kluczem serwera, więc po drodze (tunel ngrok) nikt go nie odczyta. Serwer używa go jeden raz do zalogowania i **nigdzie go nie zapisuje**; trzyma tylko zaszyfrowany „klucz dostępu” do Garmina. **Nigdy nie wysyłaj hasła** SMS-em, mailem, na Messengerze ani w czacie z Claude.

## Krok 5. Podłączenie Claude (darmowe konto, opcjonalnie)

Nie masz konta Claude? Załóż darmowe na <https://claude.ai>. Nie chcesz używać Claude? Pomiń ten krok.

1. W aplikacji, w **Ustawieniach**, sekcja **Claude**: stuknij **Wygeneruj kod do Claude**. Pokaże się kod `cgc_…` (tylko ten jeden raz) i przyciski **Kopiuj kod** i **Kopiuj adres**.
2. Na <https://claude.ai/customize/connectors> (**Customize → Connectors**, nie Ustawienia) kliknij **+ Add → Add custom connector**.
3. Nazwa: `CG-connector`. Adres: skopiowany adres (adres serwera z **`/mcp`** na końcu).
4. Jeśli pojawią się opcje: **Authentication** → **Sign in now**, **OAuth client** → **Register automatically**. Pola „Client ID” i „Client Secret” zostaw puste. Gdy pojawi się strona ostrzeżenia ngrok, kliknij **Visit Site**.
5. Kliknij **Dodaj**, potem **Połącz**. Na stronie CG-connector wklej **kod** `cgc_…` i kliknij **Połącz z Claude**.
6. W nowej rozmowie włącz konektor: **„+”** → **Konektory** → **CG-connector** i zapytaj: „Jak wyglądał mój ostatni tydzień?”.

Darmowe Claude pozwala mieć **tylko jeden** własny konektor. CG-connector go zajmuje. Nowy kod z aplikacji unieważnia poprzedni i rozłącza Claude, więc generuj go tylko przy łączeniu.

Przykładowe pytania: „Jak zmienia się mój VO2max?”, „Zaplanuj mi interwały na czwartek.”, „Co mam zaplanowane na ten tydzień?”.

## Dla Tomka: zaproszenie nowej osoby

Na laptopie (serwer włączony), w Ubuntu, jedna osoba = jedno polecenie (nazwa małymi literami, np. `brat`, `ola`):

```bash
cd ~/cg-connector/server && docker compose exec cg-server cg-server invite --name brat
```

Polecenie zakłada konto (jeśli go nie ma) i wypisuje kod `cgi_…`. Wyślij go tej osobie prywatnie razem z adresem serwera; resztę zrobi sama w aplikacji (Kroki 2–5). Zaproszenie wygasło albo zginęło? Uruchom polecenie jeszcze raz; poprzedni kod przestaje działać.

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
5. Jeśli wynik odświeżenia to **„Token odrzucony”** albo aplikacja prosi o ponowne wklejenie tokenu (np. po zmianie blokady ekranu): poproś Tomka o nowy kod zaproszenia i powtórz Krok 3 (możesz też wkleić token `cga_…` od Tomka w polu „Token aplikacji”).

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
