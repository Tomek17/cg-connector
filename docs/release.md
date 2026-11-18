# Wydanie aplikacji Android (podpisany APK)

Aplikację instalujemy z pliku APK pobranego z [GitHub Releases](https://github.com/Tomek17/cg-connector/releases) (bez Google Play). Wydanie powstaje samo, gdy na GitHubie pojawi się tag `vX.Y.Z` — workflow `.github/workflows/release-android.yml` buduje APK, podpisuje go Twoim kluczem i publikuje razem z sumą SHA-256.

Klucz podpisu jest **tylko u Ciebie i w GitHub Secrets**. Nigdy w repozytorium, czacie, mailu ani na zrzucie ekranu.

Jednorazowo: kroki 1–3. Przy każdym wydaniu: krok 4.

## Krok 1. Wygenerowanie klucza (keystore)

W oknie **Ubuntu** (WSL). `keytool` jest w pakiecie Javy:

```bash
sudo apt-get install -y openjdk-21-jre-headless && mkdir -p ~/cg-klucz && chmod 700 ~/cg-klucz && keytool -genkeypair -keystore ~/cg-klucz/cg-release.keystore -storetype PKCS12 -alias cg-release -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=CG-connector"
```

`keytool` dwa razy zapyta o hasło (litery się nie wyświetlają). Wymyśl długie hasło (min. 16 znaków) i **od razu zapisz je w menedżerze haseł**. To samo hasło jest hasłem magazynu i hasłem klucza (format PKCS12 ma jedno hasło).

Oczekiwany wynik: linia zaczynająca się od `Generating 4,096 bit RSA key pair` i brak błędu; plik `~/cg-klucz/cg-release.keystore` istnieje (`ls -l ~/cg-klucz`).

Klucz jest ważny ok. 27 lat (`-validity 10000` dni).

## Krok 2. Kopia klucza — obowiązkowo

> **⚠️ Utrata klucza albo hasła = brak aktualizacji.** Android instaluje aktualizację tylko wtedy, gdy jest podpisana **tym samym** kluczem. Bez niego trzeba odinstalować aplikację na każdym telefonie (przepadnie lokalna kopia danych i kolejka niewysłanych treningów; dane na serwerze zostają) i zainstalować wersję z nowym kluczem. Klucza nie da się odzyskać ani wygenerować ponownie.

Trzymaj **dwie kopie** pliku `cg-release.keystore` w różnych miejscach, np.:

1. w menedżerze haseł (Bitwarden / 1Password / KeePass: wpis „CG-connector klucz APK” z hasłem, aliasem `cg-release` i plikiem jako załącznikiem),
2. na pendrivie trzymanym w domu.

Skopiowanie na pendrive (Windows widzi go np. jako `E:`; w Ubuntu to `/mnt/e`):

```bash
cp ~/cg-klucz/cg-release.keystore /mnt/e/ && ls -l /mnt/e/cg-release.keystore
```

Nie wrzucaj klucza do folderów synchronizowanych z chmurą bez szyfrowania ani do repozytorium (`.gitignore` blokuje `*.keystore` i `*.jks`, ale nie polegaj na tym).

## Krok 3. Sekrety w GitHubie

Na stronie repozytorium: **Settings → Secrets and variables → Actions → New repository secret**. Dodaj cztery sekrety (nazwa dokładnie jak niżej):

| Nazwa | Wartość |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | plik klucza zamieniony na tekst (polecenie niżej) |
| `ANDROID_KEYSTORE_PASSWORD` | hasło z kroku 1 |
| `ANDROID_KEY_ALIAS` | `cg-release` |
| `ANDROID_KEY_PASSWORD` | to samo hasło z kroku 1 |

Wartość `ANDROID_KEYSTORE_BASE64` skopiuj do schowka Windows (nie pojawi się na ekranie):

```bash
base64 -w0 ~/cg-klucz/cg-release.keystore | clip.exe && echo "Skopiowane do schowka"
```

Oczekiwany wynik: `Skopiowane do schowka`. Wklej (Ctrl+V) w pole **Secret** i kliknij **Add secret**. Potem wyczyść schowek, kopiując cokolwiek innego.

Oczekiwany wynik całego kroku: na liście **Repository secrets** są cztery nazwy. GitHub nigdy nie pokaże już ich wartości (to normalne) — można je tylko nadpisać.

## Krok 4. Wydanie nowej wersji (za każdym razem)

1. **Podbij wersję** w `android/app/build.gradle.kts` (przez PR, jak każdą zmianę): `versionName = "0.2.0"` oraz `versionCode` o 1 większy niż ostatnio. Telefon odrzuci aktualizację z `versionCode` nie większym niż zainstalowany. Scal PR do `main`.
2. **Utwórz tag** z tą samą wersją, z literą `v` na początku. W Ubuntu, w katalogu repozytorium:

   ```bash
   cd ~/cg-connector && git checkout main && git pull && git tag -a v0.2.0 -m "CG-connector v0.2.0" && git push origin v0.2.0
   ```

   Oczekiwany wynik: `* [new tag] v0.2.0 -> v0.2.0`.
3. Otwórz zakładkę **Actions → release-android**. Po ok. 5–10 minutach przebieg jest zielony, a w **Releases** pojawia się wydanie `CG-connector v0.2.0` z plikami `cg-connector-v0.2.0.apk` i `cg-connector-v0.2.0.apk.sha256`.

Wersje testowe: tag z myślnikiem (np. `v0.2.0-rc1`, wtedy też `versionName = "0.2.0-rc1"`) tworzy wydanie oznaczone jako *Pre-release*.

### Gdy przebieg jest czerwony

- **„Brak sekretów podpisu APK”** — brakuje sekretu wymienionego w komunikacie (krok 3). Po dodaniu: w przebiegu **Re-run jobs**.
- **„Tag … nie pasuje do versionName”** — tag różni się od `versionName`. Usuń błędny tag (`git push --delete origin v0.2.0 && git tag -d v0.2.0`), popraw wersję przez PR i utwórz tag ponownie.
- **„nie jest poprawnym base64”** albo błąd `apksigner` o haśle/aliasie — wpisz sekrety ponownie (krok 3).

## Instalacja na telefonie

Na telefonie otwórz stronę wydania, pobierz `cg-connector-vX.Y.Z.apk` i otwórz plik. Przy pierwszej instalacji Android zapyta o zgodę na instalowanie aplikacji z przeglądarki — zezwól. Aktualizacja instaluje się na poprzednią wersję i zachowuje dane.

Opis wydania zawiera **SHA-256 certyfikatu podpisu**. Jest taki sam w każdym wydaniu — jeśli się zmieni, a Ty nie zmieniałeś klucza, nie instaluj pliku i zgłoś to.

## Gdy klucz wycieknie

1. Usuń sekrety z GitHuba (Settings → Secrets) i sprawdź, kto ma dostęp do repozytorium.
2. Wygeneruj nowy klucz (kroki 1–3) i wydaj nową wersję; na telefonach trzeba będzie odinstalować starą aplikację (patrz ostrzeżenie w kroku 2).
