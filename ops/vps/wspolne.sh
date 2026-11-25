# shellcheck shell=bash
# shellcheck disable=SC2034  # zmienne używane przez skrypty, które dołączają ten plik
# Wspólne funkcje skryptów ops/vps/ (dołączane przez `source`, nie uruchamiać).
#
# Zmienne, które można nadpisać:
#   CG_VPS_KEY   klucz SSH do VPS (domyślnie ~/.ssh/oracle-cg.key)
#   CG_SERVER_DIR  katalog server/ repozytorium na laptopie

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[1]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SERVER_DIR="${CG_SERVER_DIR:-${REPO_DIR}/server}"
VPS_KEY="${CG_VPS_KEY:-${HOME}/.ssh/oracle-cg.key}"
# Plik, który blokuje uruchomienie serwera na laptopie, gdy działa on na VPS.
LOCK_FILE="${SERVER_DIR}/compose.override.yaml"
LOCK_MARK="# CG-connector: serwer przeniesiony na VPS"
AUTOSTART_TASK="CG-connector autostart"

fail() { echo "BŁĄD: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

# vps_ssh <polecenie...>: polecenie na VPS (bez pytań o hasło; klucz z VPS_KEY).
vps_ssh() {
  ssh -i "${VPS_KEY}" -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 \
    "${VPS}" "$@"
}

check_vps_arg() {
  [[ -n "${VPS:-}" ]] || fail "podaj adres serwera, np. opc@92.5.126.13"
  [[ "${VPS}" =~ ^[A-Za-z0-9._-]+@[A-Za-z0-9.:-]+$ ]] || fail "zły adres '${VPS}' (oczekiwano użytkownik@IP)"
  [[ -f "${VPS_KEY}" ]] || fail "brak klucza SSH ${VPS_KEY} (ustaw CG_VPS_KEY=ścieżka)"
  vps_ssh true || fail "nie mogę połączyć się z ${VPS} przez SSH (sprawdź IP i klucz)"
}

# env_value <klucz>: wartość z server/.env (ostatnia, bez cudzysłowów i \r).
env_value() {
  local v
  v="$(sed -n "s/^[[:space:]]*$1[[:space:]]*=//p" "${SERVER_DIR}/.env" | tail -n1)"
  v="${v%$'\r'}"
  if [[ "${v}" =~ ^\"(.*)\"$ || "${v}" =~ ^\'(.*)\'$ ]]; then
    v="${BASH_REMATCH[1]}"
  fi
  printf '%s' "${v}"
}

# Folder danych na laptopie, tak jak w compose: ${CG_HOST_DATA_DIR:-./data} względem server/.
laptop_data_dir() {
  local d
  d="$(env_value CG_HOST_DATA_DIR)"
  d="${d:-./data}"
  [[ "${d}" == /* ]] || d="${SERVER_DIR}/${d#./}"
  printf '%s' "${d}"
}

laptop_compose() { docker compose --project-directory "${SERVER_DIR}" "$@"; }

# Czeka, aż adres odpowie HTTP 200 (maks. $2 sekund).
wait_http_ok() {
  local url="$1" limit="${2:-120}" i code
  for ((i = 0; i < limit; i += 5)); do
    code="$(curl -s -o /dev/null -m 10 -w '%{http_code}' "${url}" || true)"
    [[ "${code}" == 200 ]] && return 0
    sleep 5
  done
  return 1
}

# Autostart na laptopie (Harmonogram zadań Windows), przez interop WSL.
autostart_installed() {
  command -v schtasks.exe >/dev/null 2>&1 || return 1
  schtasks.exe /Query /TN "${AUTOSTART_TASK}" >/dev/null 2>&1
}

run_windows_script() {
  local script="${REPO_DIR}/ops/windows/$1"
  command -v powershell.exe >/dev/null 2>&1 || return 1
  (cd /mnt/c && powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(wslpath -w "${script}")")
}

confirm() {
  local answer
  read -rp "$1 [t/N] " answer
  [[ "${answer}" == [tTyY] ]] || fail "przerwano, nic nie zostało zmienione"
}
