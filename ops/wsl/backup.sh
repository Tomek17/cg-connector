#!/usr/bin/env bash
# Kopia zapasowa baz SQLite CG-connector (uruchamiać w WSL, nie w kontenerze).
#
# - Kopiuje każdą bazę (*.sqlite, *.sqlite3, *.db) z folderu danych
#   (${CG_HOST_DATA_DIR}) do ${CG_HOST_DATA_DIR}/backups/<data_godzina>/.
# - Używa wbudowanego w SQLite mechanizmu kopii "online" (to samo co
#   `sqlite3 baza ".backup plik"`), więc działa także przy pracującym serwerze.
# - Kopię robi jednorazowy kontener z obrazu cg-server (ten sam użytkownik co
#   serwer), dzięki czemu nie trzeba instalować sqlite3 ani używać sudo.
# - Usuwa kopie starsze niż CG_BACKUP_RETENTION_DAYS dni (domyślnie 14).
#
# Użycie:  ~/cg-connector/ops/wsl/backup.sh
# Harmonogram (systemd timer / cron): patrz docs/instalacja-serwera.md.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${CG_SERVER_DIR:-$(cd "${SCRIPT_DIR}/../../server" && pwd)}"
RETENTION_DAYS="${CG_BACKUP_RETENTION_DAYS:-14}"

if [[ ! -f "${SERVER_DIR}/compose.yaml" ]]; then
  echo "BŁĄD: nie znaleziono ${SERVER_DIR}/compose.yaml" >&2
  exit 1
fi
if [[ ! -f "${SERVER_DIR}/.env" ]]; then
  echo "BŁĄD: brak pliku ${SERVER_DIR}/.env" >&2
  exit 1
fi
if ! [[ "${RETENTION_DAYS}" =~ ^[0-9]+$ ]] || (( 10#${RETENTION_DAYS} < 1 )); then
  echo "BŁĄD: CG_BACKUP_RETENTION_DAYS musi być liczbą całkowitą >= 1" >&2
  exit 1
fi
RETENTION_DAYS=$((10#${RETENTION_DAYS}))

STAMP="$(date +%Y-%m-%d_%H%M%S)"
echo "$(date -Iseconds) start kopii ${STAMP}"

# Wewnątrz kontenera folder danych to zawsze /data.
docker compose --project-directory "${SERVER_DIR}" -f "${SERVER_DIR}/compose.yaml" \
  run --rm --no-deps -T \
  -e "BACKUP_STAMP=${STAMP}" \
  -e "BACKUP_RETENTION_DAYS=${RETENTION_DAYS}" \
  cg-server python - <<'PY'
import os
import re
import shutil
import sqlite3
import time
from pathlib import Path

# Kopie (katalogi i pliki) dostępne tylko dla właściciela.
os.umask(0o077)

data = Path("/data")
root = data / "backups"
target = root / os.environ["BACKUP_STAMP"]
retention_days = max(1, int(os.environ["BACKUP_RETENTION_DAYS"]))
# Rotacja usuwa wyłącznie katalogi utworzone przez ten skrypt (RRRR-MM-DD_GGMMSS).
stamp_re = re.compile(r"^\d{4}-\d{2}-\d{2}_\d{6}$")

dbs = sorted(
    p for p in data.iterdir()
    if p.is_file() and p.suffix in {".sqlite", ".sqlite3", ".db"}
)
if not dbs:
    print("Brak baz SQLite w folderze danych - nic do skopiowania.")
else:
    target.mkdir(parents=True, exist_ok=True)
    for db in dbs:
        src = sqlite3.connect(db)
        dst = sqlite3.connect(target / db.name)
        with dst:
            src.backup(dst)
        dst.close()
        src.close()
        print(f"OK: {db.name} -> backups/{target.name}/{db.name}")

if root.is_dir():
    cutoff = time.time() - retention_days * 86400
    for old in root.iterdir():
        if (
            stamp_re.fullmatch(old.name)
            and old.is_dir()
            and not old.is_symlink()
            and old.stat().st_mtime < cutoff
        ):
            shutil.rmtree(old)
            print(f"Usunięto starą kopię: backups/{old.name}")
PY

echo "$(date -Iseconds) koniec kopii ${STAMP}"
