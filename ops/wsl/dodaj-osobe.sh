#!/usr/bin/env bash
# Dodaje nową osobę do serwera CG-connector w jednym przebiegu:
# konto -> logowanie do Garmina (wpisuje TA osoba) -> pierwszy sync -> token aplikacji -> kod CG.
# Użycie (w Ubuntu/WSL, serwer musi działać):  ~/cg-connector/ops/wsl/dodaj-osobe.sh brat
set -euo pipefail

NAME="${1:-}"
if [[ ! "$NAME" =~ ^[a-z0-9][a-z0-9_-]{0,31}$ ]]; then
  echo "Użycie: $0 <nazwa>   (małe litery, cyfry, - lub _, np. brat, ola)" >&2
  exit 2
fi

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../server" && pwd)"
cd "$SERVER_DIR"
cg() { docker compose exec cg-server cg-server "$@"; }

if ! docker compose ps --status running --services | grep -qx cg-server; then
  echo "BŁĄD: serwer nie działa. Uruchom go: cd $SERVER_DIR && docker compose up -d" >&2
  exit 1
fi

echo "== 1/5 Konto \"$NAME\" na serwerze"
if cg sync-status | grep -q "^${NAME}:"; then
  echo "Konto \"$NAME\" już istnieje, pomijam."
else
  cg user-add --name "$NAME"
fi

echo
echo "== 2/5 Logowanie do Garmina"
echo "Teraz klawiaturę przejmuje właściciel konta Garmin (\"$NAME\")."
echo "TYLKO JEDNA PRÓBA: jeśli pojawi się błąd, nie powtarzaj (Garmin blokuje logowanie na ok. dobę)."
read -rp "Enter, gdy osoba jest gotowa... " _
if ! cg garmin-login --user "$NAME"; then
  echo
  echo "Logowanie nieudane. NIE powtarzaj teraz. Skopiuj komunikat powyżej i wklej go Claude." >&2
  exit 1
fi

echo
echo "== 3/5 Pierwsze pobranie danych (może potrwać kilka minut)"
cg sync --user "$NAME" || echo "Uwaga: pierwszy sync się nie udał. Serwer spróbuje sam o 14:00; idę dalej."

echo
echo "== 4/5 Token aplikacji (do telefonu, zaczyna się od cga_)"
cg app-token --user "$NAME"
read -rp "Wpiszcie go teraz w telefonie (Ustawienia → Token aplikacji). Enter, gdy gotowe... " _
clear || true

echo "== 5/5 Kod CG (do claude.ai, zaczyna się od cgc_)"
cg user-code --user "$NAME"
read -rp "Wklejcie go przy łączeniu konektora w claude.ai. Enter, gdy gotowe... " _
clear || true

echo "Gotowe: \"$NAME\" jest skonfigurowany. Stan wszystkich kont:"
cg sync-status
