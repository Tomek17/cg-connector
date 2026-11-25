#!/usr/bin/env bash
# Pobiera najnowszą kopię zapasową bazy z VPS na laptopa (druga kopia poza chmurą).
# Uruchamiać na LAPTOPIE (WSL), np. raz w tygodniu:
#
#   ~/cg-connector/ops/vps/pobierz-kopie-z-vps.sh opc@92.5.126.13 [folder]
#
# Domyślny folder: C:\Users\<nazwa>\CG-kopie\vps (ten sam profil Windows co kopie z laptopa).
# Kopia zawiera zaszyfrowane tokeny Garmina; do ich odczytu potrzebny jest klucz z .env.
set -euo pipefail
umask 077
VPS="${1:-}"
TARGET="${2:-}"
# shellcheck source=ops/vps/wspolne.sh
. "$(dirname "${BASH_SOURCE[0]}")/wspolne.sh"

check_vps_arg
if [[ -z "${TARGET}" ]]; then
  profile="$(cd /mnt/c && /mnt/c/Windows/System32/cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')" || true
  [[ "${profile}" == [A-Za-z]:\\* ]] || fail "nie znam folderu Windows; podaj folder jako drugi argument"
  TARGET="$(wslpath -u "${profile}")/CG-kopie/vps"
fi
mkdir -p "${TARGET}"

LATEST="$(vps_ssh 'sudo ls -1 ~/cg-data/backups | grep -E "^[0-9]{4}-" | sort | tail -n1')"
[[ -n "${LATEST}" ]] || fail "na VPS nie ma jeszcze żadnej kopii (powstaje codziennie o 14:30)"
[[ "${LATEST}" =~ ^[0-9_-]+$ ]] || fail "nieoczekiwana nazwa kopii: ${LATEST}"
if [[ -e "${TARGET}/${LATEST}" ]]; then
  echo "Najnowsza kopia ${LATEST} jest już pobrana: ${TARGET}/${LATEST}"
  exit 0
fi
mkdir -p "${TARGET}/${LATEST}"
vps_ssh "sudo tar czf - -C ~/cg-data/backups '${LATEST}'" | tar xzf - -C "${TARGET}"
echo "Pobrano kopię ${LATEST} do ${TARGET}/${LATEST}"
ls -l "${TARGET}/${LATEST}"
# Zostaw 8 najnowszych kopii z VPS.
find "${TARGET}" -mindepth 1 -maxdepth 1 -type d -name '20*' | sort | head -n -8 | xargs -r rm -rf
