#!/usr/bin/env bash
# shellcheck disable=SC2016  # $… w poleceniach dla VPS ma się rozwinąć dopiero na VPS
# Przeniesienie serwera CG-connector z laptopa (WSL) na VPS. Uruchamiać na LAPTOPIE (WSL).
#
#   ~/cg-connector/ops/vps/przenies-na-vps.sh opc@92.5.126.13
#
# Zasada: adres publiczny się NIE zmienia (ta sama stała domena ngrok), więc konektor
# w claude.ai i aplikacje wszystkich osób działają dalej bez żadnych zmian.
#
# Bezpieczeństwo i powrót (ops/vps/wroc-na-laptopa.sh):
#   - folder danych na laptopie zostaje nietknięty; dodatkowo powstaje punkt powrotu
#     ~/cg-przed-vps-<data>/ (dane.tgz + kopia .env),
#   - laptop zostaje zablokowany (server/compose.override.yaml + wyłączony autostart),
#     żeby serwer nigdy nie działał w dwóch miejscach naraz: dwa serwery odświeżałyby
#     te same tokeny Garmina i walczyły o domenę ngrok,
#   - w razie błędu, zanim serwer ruszy na VPS, skrypt sam włącza z powrotem laptop.
#
# Wymaga: VPS przygotowany przez ops/vps/przygotuj-vps.sh, klucz SSH (CG_VPS_KEY,
# domyślnie ~/.ssh/oracle-cg.key), tunel ngrok w .env (COMPOSE_PROFILES=ngrok).
set -euo pipefail
umask 077
VPS="${1:-}"
# shellcheck source=ops/vps/wspolne.sh
. "$(dirname "${BASH_SOURCE[0]}")/wspolne.sh"

step "Sprawdzenie przed przeprowadzką"
[[ -f "${SERVER_DIR}/.env" ]] || fail "brak ${SERVER_DIR}/.env – uruchamiaj na laptopie, na którym działa serwer"
[[ ! -f "${LOCK_FILE}" ]] || fail "serwer jest już przeniesiony (istnieje ${LOCK_FILE})"
check_vps_arg
PUBLIC_HOST="$(env_value CG_PUBLIC_HOST)"
[[ -n "${PUBLIC_HOST}" ]] || fail "brak CG_PUBLIC_HOST w .env"
[[ "$(env_value COMPOSE_PROFILES)" == *ngrok* ]] \
  || fail "ten skrypt przenosi serwer z tunelem ngrok (w .env brak COMPOSE_PROFILES=ngrok)"
[[ -n "$(env_value NGROK_AUTHTOKEN)" ]] || fail "brak NGROK_AUTHTOKEN w .env"
DATA_DIR="$(laptop_data_dir)"
sudo test -f "${DATA_DIR}/cg.sqlite" || fail "nie znaleziono bazy ${DATA_DIR}/cg.sqlite"
vps_ssh 'sudo docker compose version >/dev/null && test -f ~/cg-connector/server/compose.yaml && sudo test -d ~/cg-data' \
  || fail "VPS nieprzygotowany – najpierw ops/vps/przygotuj-vps.sh (instrukcja: docs/przeprowadzka-vps.md)"
if vps_ssh 'sudo test -e ~/cg-data/cg.sqlite'; then
  fail "na VPS jest już baza ~/cg-data/cg.sqlite – nie nadpisuję. Jeśli to stara próba, usuń ją na VPS: sudo rm -rf ~/cg-data/* "
fi
LAPTOP_REV="$(git -C "${REPO_DIR}" rev-parse --short HEAD)"
VPS_REV="$(vps_ssh 'git -C ~/cg-connector rev-parse --short HEAD')"
if [[ "${LAPTOP_REV}" != "${VPS_REV}" ]]; then
  echo "UWAGA: inna wersja kodu: laptop ${LAPTOP_REV}, VPS ${VPS_REV}."
  echo "Najlepiej przed przeprowadzką zrobić 'git pull' na laptopie i ponownie przygotuj-vps.sh."
fi
echo "Laptop: dane ${DATA_DIR}, adres https://${PUBLIC_HOST}"
echo "VPS:    ${VPS}"
echo
echo "Serwer na laptopie zostanie zatrzymany na ok. 5–10 minut (budowa obrazu na VPS)."
confirm "Przenieść serwer na VPS?"

STAMP="$(date +%Y-%m-%d_%H%M%S)"
ROLLBACK_DIR="${HOME}/cg-przed-vps-${STAMP}"
laptop_stopped=0
started_on_vps=0
on_exit() {
  local rc=$?
  # Błąd po zatrzymaniu laptopa, a zanim serwer ruszył na VPS: przywróć laptop do pracy.
  [[ ${rc} -ne 0 && ${laptop_stopped} -eq 1 && ${started_on_vps} -eq 0 ]] || return 0
  echo
  echo "!!! Coś poszło nie tak – włączam z powrotem serwer na laptopie."
  # Nieudaną kopię na VPS odkładamy na bok (nie kasujemy), żeby kolejna próba miała czysty folder.
  vps_ssh "cd ~/cg-connector/server && sudo docker compose down;
           sudo test -e ~/cg-data/cg.sqlite && sudo mv ~/cg-data ~/cg-data-nieudana-${STAMP} &&
           mkdir -p ~/cg-data && sudo chown 10001:10001 ~/cg-data && sudo chmod 700 ~/cg-data" \
    >/dev/null 2>&1 || true
  rm -f "${LOCK_FILE}"
  laptop_compose up -d || true
  run_windows_script install-autostart.ps1 >/dev/null 2>&1 || true
  echo "Laptop działa jak przedtem. Wklej Claude cały wynik tego skryptu."
}
trap on_exit EXIT

step "1/7 Zatrzymanie serwera na laptopie"
laptop_stopped=1
laptop_compose down
if autostart_installed; then
  run_windows_script uninstall-autostart.ps1 \
    || fail "nie udało się wyłączyć autostartu (PowerShell: ops/windows/uninstall-autostart.ps1)"
fi

step "2/7 Punkt powrotu: ${ROLLBACK_DIR}"
mkdir -p "${ROLLBACK_DIR}"
sudo tar czf "${ROLLBACK_DIR}/dane.tgz" -C "${DATA_DIR}" --exclude=./backups .
sudo chown "$(id -u):$(id -g)" "${ROLLBACK_DIR}/dane.tgz"
cp "${SERVER_DIR}/.env" "${ROLLBACK_DIR}/env"
ls -lh "${ROLLBACK_DIR}"

step "3/7 Kopiowanie danych na VPS (bez starych kopii zapasowych)"
sudo tar czf - -C "${DATA_DIR}" --exclude=./backups . \
  | vps_ssh 'sudo tar xzf - -C ~/cg-data && sudo chown -R 10001:10001 ~/cg-data && sudo chmod 700 ~/cg-data &&
             if command -v selinuxenabled >/dev/null && selinuxenabled; then sudo chcon -R -t container_file_t ~/cg-data; fi &&
             sudo ls -l ~/cg-data'

step "4/7 Kopiowanie konfiguracji (.env) na VPS"
# .env idzie tylko szyfrowanym SSH, nigdzie indziej. Na VPS: własny folder danych.
vps_ssh 'umask 077; cat > ~/cg-connector/server/.env' < "${SERVER_DIR}/.env"
vps_ssh 'sed -i "s|^CG_HOST_DATA_DIR=.*|CG_HOST_DATA_DIR=$HOME/cg-data|" ~/cg-connector/server/.env &&
         grep -q "^CG_HOST_DATA_DIR=" ~/cg-connector/server/.env ||
         echo "CG_HOST_DATA_DIR=$HOME/cg-data" >> ~/cg-connector/server/.env'

step "5/7 Uruchomienie na VPS (budowa obrazu, kilka minut)"
# Tylko serwer + ngrok; Tailscale zostaje na laptopie.
vps_ssh 'cd ~/cg-connector/server && sudo docker compose up -d --build cg-server ngrok'
echo "Czekam na serwer na VPS…"
vps_ssh 'for i in $(seq 1 40); do curl -sf -m 5 http://127.0.0.1:8000/health >/dev/null && exit 0; sleep 3; done; exit 1' \
  || { vps_ssh 'cd ~/cg-connector/server && sudo docker compose logs --tail 40 cg-server' || true
       fail "serwer na VPS nie odpowiada"; }
echo "Czekam na adres publiczny https://${PUBLIC_HOST}/health …"
if ! wait_http_ok "https://${PUBLIC_HOST}/health" 120; then
  vps_ssh 'cd ~/cg-connector/server && sudo docker compose logs --tail 30 ngrok' || true
  fail "adres publiczny nie odpowiada (ngrok na VPS)"
fi
started_on_vps=1

step "6/7 Blokada serwera na laptopie"
cat > "${LOCK_FILE}" <<EOF
${LOCK_MARK} (${STAMP}, ${VPS}).
# Dopóki ten plik istnieje, "docker compose up" na laptopie NIE uruchomi serwera ani ngroka,
# żeby nie działały w dwóch miejscach naraz. Powrót: ops/vps/wroc-na-laptopa.sh
services:
  cg-server:
    restart: "no"
    command: ["sh", "-c", "echo 'Serwer CG-connector działa na VPS (${VPS}). Powrót: ops/vps/wroc-na-laptopa.sh'; exit 1"]
  ngrok:
    restart: "no"
    entrypoint: ["sh", "-c", "echo 'ngrok działa na VPS'; exit 0"]
EOF
echo "Zapisano ${LOCK_FILE}"

step "7/7 Codzienna kopia zapasowa na VPS (14:30, 14 dni)"
vps_ssh 'sudo tee /etc/systemd/system/cg-backup.service >/dev/null <<EOF
[Unit]
Description=CG-connector: kopia zapasowa SQLite
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
Environment=CG_BACKUP_RETENTION_DAYS=14
# /bin/bash: SELinux (Oracle Linux) nie pozwala systemd uruchamiać skryptów z katalogu domowego.
ExecStart=/bin/bash $HOME/cg-connector/ops/wsl/backup.sh
EOF
sudo tee /etc/systemd/system/cg-backup.timer >/dev/null <<EOF
[Unit]
Description=CG-connector: codzienna kopia zapasowa

[Timer]
OnCalendar=*-*-* 14:30:00 Europe/Warsaw
Persistent=true

[Install]
WantedBy=timers.target
EOF
sudo systemctl daemon-reload && sudo systemctl enable --now cg-backup.timer && sudo systemctl start cg-backup.service &&
sudo ls ~/cg-data/backups' \
  || echo "UWAGA: kopia zapasowa na VPS nie zadziałała – serwer działa, ale wklej Claude ten wynik."

echo
echo "GOTOWE. Serwer działa na VPS pod tym samym adresem: https://${PUBLIC_HOST}"
echo "Sprawdź: aplikacja (Ustawienia → Status: Online) i w claude.ai zapytanie o profil."
echo "Punkt powrotu na laptopie: ${ROLLBACK_DIR}"
echo "Powrót na laptopa w razie problemów: ${SCRIPT_DIR}/wroc-na-laptopa.sh ${VPS}"
