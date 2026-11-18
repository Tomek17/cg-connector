#!/usr/bin/env bash
# Kopia zapasowa baz SQLite CG-connector (uruchamiać w WSL, nie w kontenerze).
#
# 1. Spójna kopia "online" każdej bazy (*.sqlite, *.sqlite3, *.db) z folderu danych
#    (${CG_HOST_DATA_DIR}) do ${CG_HOST_DATA_DIR}/backups/<data_godzina>/ — to samo co
#    `sqlite3 baza ".backup plik"`, działa przy pracującym serwerze; kopia przechodzi
#    PRAGMA integrity_check. Robi ją jednorazowy kontener z obrazu cg-server (ten sam
#    użytkownik co serwer), więc nie trzeba instalować sqlite3.
# 2. Usuwa kopie starsze niż CG_BACKUP_RETENTION_DAYS dni (domyślnie 14).
#
# Logika kopii: ops/wsl/cg_backup.py (testy: ops/tests/).
# Użycie:  ~/cg-connector/ops/wsl/backup.sh
# Harmonogram (systemd timer): docs/instalacja-serwera.md (krok 10).
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${CG_SERVER_DIR:-$(cd "${SCRIPT_DIR}/../../server" && pwd)}"
RETENTION_DAYS="${CG_BACKUP_RETENTION_DAYS:-14}"

fail() { echo "BŁĄD: $*" >&2; exit 1; }

[[ -f "${SERVER_DIR}/compose.yaml" ]] || fail "nie znaleziono ${SERVER_DIR}/compose.yaml"
[[ -f "${SERVER_DIR}/.env" ]] || fail "brak pliku ${SERVER_DIR}/.env"
if ! [[ "${RETENTION_DAYS}" =~ ^[0-9]+$ ]] || (( 10#${RETENTION_DAYS} < 1 )); then
  fail "CG_BACKUP_RETENTION_DAYS musi być liczbą całkowitą >= 1"
fi
RETENTION_DAYS=$((10#${RETENTION_DAYS}))

STAMP="$(date +%Y-%m-%d_%H%M%S)"
echo "$(date -Iseconds) start kopii ${STAMP}"

# Wewnątrz kontenera folder danych to zawsze /data; skrypt idzie przez stdin.
docker compose --project-directory "${SERVER_DIR}" -f "${SERVER_DIR}/compose.yaml" \
  run --rm --no-deps -T cg-server \
  python - snapshot --data /data --stamp "${STAMP}" --keep-days "${RETENTION_DAYS}" \
  < "${SCRIPT_DIR}/cg_backup.py"

echo "$(date -Iseconds) koniec kopii ${STAMP}"
