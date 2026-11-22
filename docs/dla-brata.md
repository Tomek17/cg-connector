# CG-connector: instrukcja dla brata

Ta instrukcja jest dla Ciebie, jeśli masz zegarek Garmin (np. Fenix 8 Pro), własne konto Garmin Connect i darmowe konto Claude. Nie musisz znać się na programowaniu.

## Co to robi

- Raz dziennie pobiera Twoje treningi i VO2max z Garmin Connect.
- Pokazuje je w aplikacji **CG-connector** na Twoim telefonie. Działa też bez internetu.
- Pozwala rozmawiać o treningach z **Claude** (claude.ai, darmowy plan wystarczy).
- Pozwala ułożyć trening (w aplikacji albo z Claude) i wysłać go na zegarek.

Wszystko działa przez **serwer na laptopie Tomka**.

## Co musisz wiedzieć na start

- Dane pobierają się codziennie **o 14:00**. Jeśli laptop Tomka był wtedy wyłączony, to zaraz po jego włączeniu.
- **Claude i wysyłanie treningów działają tylko wtedy, gdy laptop Tomka jest włączony.**
- Gdy laptop jest wyłączony, aplikacja nadal pokazuje Twoje dane. Trening czeka w kolejce i wyśle się sam, gdy laptop się włączy.
- Trening trafia najpierw do **kalendarza Garmin Connect**. Na zegarek trafia, gdy zegarek zsynchronizuje się z aplikacją Garmin Connect na telefonie.

## Krok 1. Spotkanie z Tomkiem (konto i logowanie do Garmina)

To robicie razem, przy laptopie Tomka. Zajmuje ok. 15 minut.

1. Tomek zakłada Ci konto na serwerze (polecenie `user-add`).
2. Tomek uruchamia logowanie do Garmina (polecenie `garmin-login`).
3. **Ty sam** wpisujesz na laptopie Tomka swój e-mail i hasło do Garmin Connect. Hasła nie widać na ekranie. Jeśli Garmin przyśle kod (MFA), wpisujesz go też Ty.
4. Hasło **nie jest nigdzie zapisywane**. Serwer zapisuje tylko zaszyfrowany „klucz dostępu” do Garmina.
5. Tomek uruchamia pierwsze pobranie danych. Może potrwać kilka minut.
6. Tomek generuje dla Ciebie dwa sekrety:
   - **token aplikacji**, zaczyna się od `cga_` (do telefonu),
   - **kod CG**, zaczyna się od `cgc_` (do Claude).

Tomek poda Ci też **adres serwera**, np. `https://cg-server.twoj-tailnet.ts.net` albo `https://<nazwa>.ngrok-free.dev`.

> **Nigdy nie wysyłaj hasła do Garmina** SMS-em, mailem, na Messengerze ani w czacie z Claude. Nikt z projektu o nie nie poprosi. Hasło wpisujesz tylko sam, przy laptopie Tomka.

Token i kod przekaż sobie najlepiej od razu na miejscu (np. wpisz je od razu w telefonie). Jeśli trzeba je przesłać, zrób to prywatnie i usuń wiadomość po użyciu. Są ważne tylko do czasu, aż Tomek wygeneruje nowe.

Szczegóły dla Tomka (polecenia): [konfiguracja-claude.md](konfiguracja-claude.md#przygotowanie-użytkownika-na-serwerze-robi-tomek). Token aplikacji generuje się poleceniem `cg-server app-token --user <nazwa>`, kod CG poleceniem `cg-server user-code --user <nazwa>`.

## Krok 2. Instalacja aplikacji na telefonie

Aplikacji nie ma w Sklepie Google Play. Instalujesz ją z pliku APK.

1. Na telefonie otwórz stronę <https://github.com/Tomek17/cg-connector/releases>.
2. Przy najnowszej wersji rozwiń **Assets** i stuknij plik kończący się na **`.apk`**.
3. Gdy telefon zapyta, pozwól przeglądarce **instalować nieznane aplikacje** (Ustawienia → Aplikacje → Twoja przeglądarka → *Instaluj nieznane aplikacje* → zezwól). Nazwy w ustawieniach mogą się trochę różnić zależnie od telefonu.
4. Otwórz pobrany plik i stuknij **Zainstaluj**.
5. Jeśli pojawi się ostrzeżenie Google Play Protect, wybierz *Zainstaluj mimo to*. To normalne dla aplikacji spoza sklepu.
6. Po instalacji możesz z powrotem wyłączyć „instaluj nieznane aplikacje” dla przeglądarki.

Tomek opisuje wydania w [release.md](release.md).

## Krok 3. Połączenie aplikacji z serwerem

1. Otwórz aplikację **CG-connector**.
2. Wejdź w zakładkę **Ustawienia** (na dole ekranu).
3. W polu **Adres serwera** wpisz adres od Tomka, np. `https://cg-server.twoj-tailnet.ts.net`. Musi zaczynać się od `https://`.
4. W polu **Token aplikacji** wklej token `cga_…`.
5. Stuknij **Zapisz**.
6. Wejdź w zakładkę **Status** i stuknij **Sprawdź teraz**. Powinno być: Serwer **Online**, Konto Garmin **Połączone**.
7. W zakładce **Treningi** pojawią się Twoje aktywności.

Token jest zapisany w telefonie w zaszyfrowanej formie. Po zapisaniu pole tokenu zostaje puste. To normalne.

## Krok 4. Podłączenie Claude (darmowe konto)

1. Na komputerze albo w przeglądarce telefonu otwórz <https://claude.ai> i zaloguj się na swoje konto.
2. Wejdź na <https://claude.ai/customize/connectors> (**Customize → Connectors**, nie Ustawienia), kliknij **+ Add → Add custom connector**.
3. Nazwa: `CG-connector`. Adres: adres serwera z dopiskiem **`/mcp`**, np. `https://cg-server.twoj-tailnet.ts.net/mcp`.
4. Jeśli pojawią się opcje: **Authentication** → **Sign in now**, **OAuth client** → **Register automatically**. Pola „Client ID” i „Client Secret” zostaw puste. Gdy pojawi się strona ostrzeżenia ngrok, kliknij **Visit Site**.
5. Kliknij **Dodaj**, potem **Połącz**.
6. Otworzy się strona CG-connector. Wklej **kod CG** (`cgc_…`) i kliknij **Połącz z Claude**.
7. W nowej rozmowie włącz konektor: **„+”** → **Konektory** → **CG-connector**.
8. Zapytaj: „Jak wyglądał mój ostatni tydzień?”.

Nazwy przycisków w claude.ai mogą być trochę inne. Dokładniej: [konfiguracja-claude.md](konfiguracja-claude.md).

Darmowe Claude pozwala mieć **tylko jeden** własny konektor. CG-connector go zajmuje.

Przykładowe pytania:

- „Jak wyglądał mój ostatni tydzień?”
- „Jak zmienia się mój VO2max?”
- „Zaplanuj mi interwały na czwartek.”
- „Co mam zaplanowane na ten tydzień?”

## Codzienne używanie

- Aplikacja sama pobiera nowe dane około 14:20. Możesz też przeciągnąć listę w dół, żeby odświeżyć.
- Nowy trening: przycisk **Nowy trening** → bieg albo siłowy → wybierz datę → zapisz. Status zobaczysz w zakładce **Plan**.
- Status „Oczekuje na wysłanie” oznacza, że laptop Tomka jest wyłączony. Trening wyśle się sam.
- Status „Wysłany do Garmin Connect”: zsynchronizuj zegarek z aplikacją Garmin Connect i sprawdź kalendarz.

## Co zrobić, gdy dane są stare

W zakładce **Status** widzisz, kiedy serwer ostatnio pobrał dane z Garmina. Jeśli pojawi się ostrzeżenie „Dane mogą być nieaktualne”:

1. Stuknij **Sprawdź teraz**.
2. Jeśli Serwer jest **Offline**: laptop Tomka jest wyłączony albo śpi. Napisz do Tomka, żeby go włączył. Dane pobiorą się same po włączeniu.
3. Jeśli Konto Garmin to **„Wymaga ponownego logowania na serwerze”**: umów się z Tomkiem i powtórzcie Krok 1, punkty 2–4 (Ty wpisujesz hasło na jego laptopie).
4. Jeśli Konto Garmin to **„Garmin ogranicza zapytania”**: nic nie rób. Serwer spróbuje sam później, zwykle następnego dnia.
5. Jeśli wynik odświeżenia to **„Token odrzucony”**: token przestał działać (np. Tomek wygenerował nowy). Poproś Tomka o nowy token i wklej go w **Ustawieniach**.
6. Jeśli aplikacja prosi o ponowne wklejenie tokenu (np. po zmianie blokady ekranu): poproś Tomka o nowy token.

Gdy Claude nie działa:

- „Nie można połączyć z konektorem”: laptop Tomka jest wyłączony. Spróbuj później.
- „Nieprawidłowy kod” przy logowaniu: sprawdź, czy wklejasz kod `cgc_…`, a nie token `cga_…`.

## Prywatność (uczciwie)

- Twoje dane treningowe leżą **na laptopie Tomka** (i w kopii zapasowej na tym samym laptopie) oraz w Twoim telefonie. Nie trafiają do żadnej zewnętrznej usługi poza Garminem i (gdy pytasz) Claude.
- Tomek nie widzi Twoich danych w swojej aplikacji ani w swoim Claude. Każdy ma osobny token i kod.
- **Ale Tomek jest administratorem serwera, więc technicznie może zajrzeć do Twoich danych treningowych** w bazie na swoim laptopie. Nie da się tego wykluczyć przy serwerze na jego komputerze.
- Tomek **nie zna Twojego hasła do Garmina**. Serwer go nie zapisuje.
- Zaszyfrowany klucz dostępu do Twojego konta Garmin leży na laptopie Tomka. Pozwala serwerowi czytać Twoje treningi i dodawać treningi do kalendarza. Usuwa tylko treningi utworzone przez CG-connector.
- Gdy rozmawiasz z Claude o treningach, Claude widzi dane, o które pytasz (jak każdą treść rozmowy).
- Chcesz zakończyć? Powiedz Tomkowi, żeby usunął Twoje konto i dane z serwera. W wersji 1.0 nie ma do tego gotowego polecenia, więc Tomek robi to ręcznie. Sam możesz: usunąć konektor CG-connector w claude.ai, odinstalować aplikację i zmienić hasło do Garmin Connect. Czy sama zmiana hasła unieważnia zapisany klucz dostępu, jest do potwierdzenia w teście.
