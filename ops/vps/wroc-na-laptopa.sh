#!/usr/bin/env bash
# Powrót serwera CG-connector z VPS na laptopa (WSL). Uruchamiać na LAPTOPIE (WSL).
#
#   ~/cg-connector/ops/vps/wroc-na-laptopa.sh opc@92.5.126.13
#       zatrzymuje serwer na VPS i przenosi jego AKTUALNE dane na laptopa (zalecane:
#       nic nie ginie, a tokeny Garmina są te najświeższe),
#   ~/cg-connector/ops/vps/wroc-na-laptopa.sh opc@92.5.126.13 --bez-danych-z-vps
#       gdy VPS nie działa albo jego dane są zepsute: laptop wraca do stanu sprzed
#       przeprowadzki (zmiany z czasu pracy na VPS przepadają; jeśli Garmin unieważnił
#       stare tokeny, potrzebne będzie ponowne logowanie do Garmina).
#
# Dane, które były na laptopie, nie są kasowane: trafiają do <folder danych>/backups/przed-powrotem-<data>/.
# Adres publiczny się nie zmienia, więc konektor w claude.ai i aplikacje działają bez zmian.
set -euo pipefail
umask 077
VPS="${1:-}"
MODE="${2:-}"
# shellcheck source=ops/vps/wspolne.sh
. "$(dirname "${BASH_SOURCE[0]}")/wspolne.sh"

[[ -z "${MODE}" || "${MODE}" == "--bez-danych-z-vps" ]] || fail "nieznana opcja '${MODE}'"
[[ -f "${SERVER_DIR}/.env" ]] || fail "brak ${SERVER_DIR}/.env"
DATA_DIR="$(laptop_data_dir)"
PUBLIC_HOST="$(env_value CG_PUBLIC_HOST)"
[[ -n "${VPS}" ]] || fail "podaj adres VPS, np. opc@92.5.126.13"

step "Sprawdzenie"
if [[ -f "${LOCK_FILE}" ]] && ! grep -q "^${LOCK_MARK}" "${LOCK_FILE}"; then
  fail "${LOCK_FILE} nie pochodzi od skryptu przeprowadzki – sprawdź go ręcznie"
fi
[[ "${VPS}" =~ ^[A-Za-z0-9._-]+@[A-Za-z0-9.:-]+$ ]] || fail "zły adres '${VPS}' (oczekiwano użytkownik@IP)"
vps_ok=0
if [[ -f "${VPS_KEY}" ]] && vps_ssh true 2>/dev/null; then vps_ok=1; fi
if [[ ${vps_ok} -eq 0 && "${MODE}" != "--bez-danych-z-vps" ]]; then
  fail "VPS ${VPS} nie odpowiada przez SSH. Wróć bez jego danych: $0 ${VPS} --bez-danych-z-vps"
fi
if [[ "${MODE}" == "--bez-danych-z-vps" ]]; then
  echo "Tryb: laptop wraca do SWOICH danych (sprzed przeprowadzki)."
else
  echo "Tryb: aktualne dane z VPS ${VPS} wracają na laptopa."
fi
confirm "Wrócić z serwerem na laptopa?"
sudo true
STAMP="$(date +%Y-%m-%d_%H%M%S)"

step "1/4 Zatrzymanie serwera na VPS"
if [[ ${vps_ok} -eq 1 ]]; then
  vps_ssh 'cd ~/cg-connector/server && sudo docker compose down &&
           { sudo systemctl disable --now cg-backup.timer 2>/dev/null || true; }'
else
  echo "UWAGA: VPS nie odpowiada. Jeśli kiedyś wróci do życia, zatrzymaj go (Oracle: Instance → Stop),"
  echo "inaczej będzie walczył z laptopem o domenę ngrok i tokeny Garmina."
fi

if [[ "${MODE}" != "--bez-danych-z-vps" ]]; then
  step "2/4 Dane z VPS → laptop"
  KEEP="${DATA_DIR}/backups/przed-powrotem-${STAMP}"
  sudo mkdir -p "${KEEP}"
  # Obecne dane laptopa (sprzed przeprowadzki) odkładamy do kopii, nic nie kasujemy.
  sudo find "${DATA_DIR}" -mindepth 1 -maxdepth 1 ! -name backups -exec mv -t "${KEEP}" {} +
  vps_ssh 'sudo tar czf - -C ~/cg-data --exclude=./backups .' | sudo tar xzf - -C "${DATA_DIR}"
  sudo chown -R 10001:10001 "${DATA_DIR}"
  sudo chmod 700 "${DATA_DIR}"
  sudo test -f "${DATA_DIR}/cg.sqlite" || fail "nie przyszła baza z VPS; stare dane laptopa są w ${KEEP}"
  echo "Poprzednie dane laptopa: ${KEEP}"
else
  step "2/4 Dane: zostają te z laptopa (sprzed przeprowadzki)"
fi

step "3/4 Uruchomienie na laptopie"
rm -f "${LOCK_FILE}"
laptop_compose up -d
wait_http_ok "http://127.0.0.1:8000/health" 120 || fail "serwer na laptopie nie odpowiada (docker compose logs cg-server)"
if [[ -n "${PUBLIC_HOST}" ]]; then
  wait_http_ok "https://${PUBLIC_HOST}/health" 120 \
    || echo "UWAGA: adres https://${PUBLIC_HOST} jeszcze nie odpowiada (docker compose logs ngrok)."
fi

step "4/4 Autostart na laptopie"
run_windows_script install-autostart.ps1 \
  || echo "UWAGA: włącz autostart ręcznie w PowerShell: ops/windows/install-autostart.ps1"

echo
echo "GOTOWE. Serwer działa znowu na laptopie: https://${PUBLIC_HOST}"
echo "Maszyna na VPS jest zatrzymana (nie usunięta); dane na VPS zostały w ~/cg-data."
