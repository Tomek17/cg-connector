#!/usr/bin/env bash
# Instaluje codzienną kopię zapasową CG-connector jako timer systemd w WSL:
# codziennie o 14:30 (po synchronizacji o 14:00); Persistent=true nadrabia kopię,
# gdy laptop był wtedy wyłączony. Kopia trafia do ~/cg-data/backups/ oraz na dysk
# Windows (domyślnie C:\Users\<nazwa>\CG-kopie). Rotacja: 14 dni w obu miejscach.
#
# Użycie:  sudo ~/cg-connector/ops/wsl/install-backup-timer.sh [folder_w_WSL]
#   np.    sudo ~/cg-connector/ops/wsl/install-backup-timer.sh /mnt/c/Users/Tomek/Kopie/CG
# Folder kopii musi leżeć w profilu użytkownika Windows (C:\Users\<nazwa>\...): tam
# dostęp ogranicza NTFS; chmod na /mnt/c nie działa, więc umask/0600 nic nie dają.
#
# Uwaga (świadomie zaakceptowane ryzyko): usługa działa jako root i uruchamia skrypt
# z repozytorium, które może edytować zwykły użytkownik WSL. Przy jednym użytkowniku,
# który i tak ma sudo, nie daje to nowych uprawnień. Na komputerze z wieloma kontami
# WSL repozytorium powinno należeć do roota.
#
# Ponowne uruchomienie nadpisuje ustawienia. Usunięcie:
#   sudo systemctl disable --now cg-backup.timer
set -euo pipefail

fail() { echo "BŁĄD: $*" >&2; exit 1; }

[[ ${EUID} -eq 0 ]] || fail "uruchom przez sudo: sudo $0 [folder_w_WSL]"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WIN_DIR="${1:-}"

if [[ -z "${WIN_DIR}" ]]; then
  # Profil użytkownika Windows (np. C:\Users\Tomek) przez interop WSL.
  profile="$(cd /mnt/c && /mnt/c/Windows/System32/cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')" || true
  [[ "${profile}" == [A-Za-z]:\\* ]] \
    || fail "nie udało się ustalić folderu użytkownika Windows; podaj folder jako argument, np. /mnt/c/Users/Tomek/CG-kopie"
  WIN_DIR="$(wslpath -u "${profile}")/CG-kopie"
fi
[[ "${WIN_DIR}" == /* ]] || fail "podaj pełną ścieżkę (zaczynającą się od /)"
mkdir -p "${WIN_DIR}"

cat > /etc/systemd/system/cg-backup.service <<EOF
[Unit]
Description=CG-connector: kopia zapasowa SQLite
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
Environment="CG_BACKUP_WINDOWS_DIR=${WIN_DIR}"
Environment=CG_BACKUP_RETENTION_DAYS=14
ExecStart="${SCRIPT_DIR}/backup.sh"
EOF

cat > /etc/systemd/system/cg-backup.timer <<'EOF'
[Unit]
Description=CG-connector: codzienna kopia zapasowa

[Timer]
OnCalendar=*-*-* 14:30:00
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now cg-backup.timer
echo "Kopie na dysku Windows: ${WIN_DIR}"
systemctl list-timers cg-backup.timer --no-pager
