#!/usr/bin/env bash
# Przygotowanie świeżego VPS pod serwer CG-connector (Oracle Linux 9 / RHEL 9 albo Ubuntu).
# Uruchamiany NA VPS. Najprościej z laptopa (WSL), bez kopiowania pliku:
#
#   ssh -i ~/.ssh/oracle-cg.key opc@<IP> 'bash -s' < ~/cg-connector/ops/vps/przygotuj-vps.sh
#
# Co robi (można uruchamiać wiele razy):
#   1. instaluje Dockera z pluginem compose i git,
#   2. włącza automatyczne poprawki bezpieczeństwa systemu,
#   3. pobiera kod do ~/cg-connector (albo aktualizuje: git pull),
#   4. tworzy pusty folder danych ~/cg-data (właściciel 10001 = użytkownik serwera).
# Nie uruchamia serwera i nie dotyka żadnych danych: to robi ops/vps/przenies-na-vps.sh.
# Nie otwiera żadnych portów: ruch z internetu idzie przez tunel ngrok.
set -euo pipefail

REPO_URL="${CG_REPO_URL:-https://github.com/Tomek17/cg-connector.git}"
fail() { echo "BŁĄD: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

[[ ${EUID} -ne 0 ]] || fail "uruchom jako zwykły użytkownik (np. opc), nie root; skrypt sam używa sudo"
sudo -n true 2>/dev/null || fail "użytkownik $(id -un) musi mieć sudo bez hasła (na Oracle Cloud opc ma)"
# shellcheck source=/dev/null
. /etc/os-release

step "1/4 Docker i git (${PRETTY_NAME})"
if command -v docker >/dev/null 2>&1 && sudo docker compose version >/dev/null 2>&1; then
  echo "Docker już jest: $(sudo docker --version)"
else
  case "${ID}" in
    ol|rhel|centos|rocky|almalinux)
      sudo dnf install -y dnf-plugins-core git
      # Repozytorium Dockera dla CentOS/RHEL 9 działa też na Oracle Linux (x86_64 i aarch64).
      sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
      # --allowerasing: obraz Oracle może mieć podmana/runc, które blokują instalację.
      sudo dnf install -y --allowerasing docker-ce docker-ce-cli containerd.io docker-compose-plugin
      ;;
    ubuntu|debian)
      sudo apt-get update
      sudo apt-get install -y ca-certificates curl git
      curl -fsSL https://get.docker.com | sudo sh
      ;;
    *) fail "nieobsługiwany system: ${PRETTY_NAME}" ;;
  esac
fi
command -v git >/dev/null 2>&1 || fail "brak git"
sudo systemctl enable --now docker
sudo usermod -aG docker "$(id -un)"
sudo docker compose version

step "2/4 Automatyczne poprawki bezpieczeństwa"
case "${ID}" in
  ol|rhel|centos|rocky|almalinux)
    sudo dnf install -y dnf-automatic
    sudo sed -i 's/^upgrade_type.*/upgrade_type = security/; s/^apply_updates.*/apply_updates = yes/' \
      /etc/dnf/automatic.conf
    sudo systemctl enable --now dnf-automatic.timer
    ;;
  ubuntu|debian)
    echo "Ubuntu: unattended-upgrades jest włączone domyślnie."
    ;;
esac

step "3/4 Kod w ~/cg-connector"
if [[ -d "${HOME}/cg-connector/.git" ]]; then
  git -C "${HOME}/cg-connector" pull --ff-only
else
  git clone "${REPO_URL}" "${HOME}/cg-connector"
fi
git -C "${HOME}/cg-connector" log --oneline -1

step "4/4 Folder danych ~/cg-data"
mkdir -p "${HOME}/cg-data"
if sudo test -e "${HOME}/cg-data/cg.sqlite"; then
  echo "UWAGA: w ~/cg-data jest już baza (cg.sqlite) – nie ruszam jej."
fi
sudo chown 10001:10001 "${HOME}/cg-data"
sudo chmod 700 "${HOME}/cg-data"
# SELinux (Oracle Linux): kontener musi móc czytać i pisać zamontowane foldery.
if command -v selinuxenabled >/dev/null 2>&1 && selinuxenabled; then
  sudo chcon -R -t container_file_t "${HOME}/cg-data" "${HOME}/cg-connector/ops/ngrok"
fi
sudo ls -ld "${HOME}/cg-data"

echo
echo "GOTOWE. VPS przygotowany; serwer jeszcze nie działa."
echo "Dalej na laptopie: ~/cg-connector/ops/vps/przenies-na-vps.sh $(id -un)@<IP>"
