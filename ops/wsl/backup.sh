#!/usr/bin/env bash
# Kopia zapasowa baz SQLite CG-connector (uruchamiać w WSL, nie w kontenerze).
#
# 1. Spójna kopia "online" każdej bazy (*.sqlite, *.sqlite3, *.db) z folderu danych
#    (${CG_HOST_DATA_DIR}) do ${CG_HOST_DATA_DIR}/backups/<data_godzina>/ — to samo co
#    `sqlite3 baza ".backup plik"`, działa przy pracującym serwerze; kopia przechodzi
#    PRAGMA integrity_check. Robi ją jednorazowy kontener z obrazu cg-server (ten sam
#    użytkownik co serwer), więc nie trzeba instalować sqlite3.
# 2. Opcjonalnie (CG_BACKUP_WINDOWS_DIR, np. /mnt/c/Users/Tomek/CG-kopie) kopiuje ten
#    katalog na dysk Windows. Pliki kopii należą do użytkownika serwera (10001), dlatego
#    ten krok wymaga uruchomienia przez sudo (timer systemd działa jako root).
# 3. Usuwa kopie starsze niż CG_BACKUP_RETENTION_DAYS dni (domyślnie 14) w obu miejscach.
#
# Logika kopii: ops/wsl/cg_backup.py (testy: ops/tests/).
# Użycie:  sudo ~/cg-connector/ops/wsl/backup.sh
# Harmonogram: ops/wsl/install-backup-timer.sh, opis w docs/instalacja-serwera.md (krok 10).
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${CG_SERVER_DIR:-$(cd "${SCRIPT_DIR}/../../server" && pwd)}"
RETENTION_DAYS="${CG_BACKUP_RETENTION_DAYS:-14}"
WINDOWS_DIR="${CG_BACKUP_WINDOWS_DIR:-}"

fail() { echo "BŁĄD: $*" >&2; exit 1; }

[[ -f "${SERVER_DIR}/compose.yaml" ]] || fail "nie znaleziono ${SERVER_DIR}/compose.yaml"
[[ -f "${SERVER_DIR}/.env" ]] || fail "brak pliku ${SERVER_DIR}/.env"
if ! [[ "${RETENTION_DAYS}" =~ ^[0-9]+$ ]] || (( 10#${RETENTION_DAYS} < 1 )); then
  fail "CG_BACKUP_RETENTION_DAYS musi być liczbą całkowitą >= 1"
fi
RETENTION_DAYS=$((10#${RETENTION_DAYS}))

if [[ -n "${WINDOWS_DIR}" ]]; then
  [[ -d "$(dirname "${WINDOWS_DIR}")" ]] || fail "nie istnieje folder nadrzędny ${WINDOWS_DIR} (czy dysk Windows jest widoczny w WSL?)"
  # Folder danych z .env, tak jak w compose: ${CG_HOST_DATA_DIR:-./data} względem server/.
  DATA_DIR="$(sed -n 's/^[[:space:]]*CG_HOST_DATA_DIR[[:space:]]*=//p' "${SERVER_DIR}/.env" | tail -n1)"
  DATA_DIR="${DATA_DIR%$'\r'}"
  if [[ "${DATA_DIR}" =~ ^\"(.*)\"$ || "${DATA_DIR}" =~ ^\'(.*)\'$ ]]; then
    DATA_DIR="${BASH_REMATCH[1]}"
  fi
  DATA_DIR="${DATA_DIR:-./data}"
  [[ "${DATA_DIR}" == /* ]] || DATA_DIR="${SERVER_DIR}/${DATA_DIR#./}"
  [[ -r "${DATA_DIR}" && -x "${DATA_DIR}" ]] \
    || fail "brak dostępu do ${DATA_DIR} - kopię na dysk Windows uruchamiaj przez sudo"
fi

STAMP="$(date +%Y-%m-%d_%H%M%S)"
echo "$(date -Iseconds) start kopii ${STAMP}"

# Wewnątrz kontenera folder danych to zawsze /data; skrypt idzie przez stdin.
docker compose --project-directory "${SERVER_DIR}" -f "${SERVER_DIR}/compose.yaml" \
  run --rm --no-deps -T cg-server \
  python - snapshot --data /data --stamp "${STAMP}" --keep-days "${RETENTION_DAYS}" \
  < "${SCRIPT_DIR}/cg_backup.py"

if [[ -n "${WINDOWS_DIR}" ]]; then
  if [[ ! -d "${DATA_DIR}/backups/${STAMP}" ]]; then
    echo "Brak nowej kopii - pomijam kopiowanie na dysk Windows."
  else
    python3 "${SCRIPT_DIR}/cg_backup.py" mirror --src "${DATA_DIR}/backups" \
      --dest "${WINDOWS_DIR}" --stamp "${STAMP}" --keep-days "${RETENTION_DAYS}"
  fi
fi

echo "$(date -Iseconds) koniec kopii ${STAMP}"
