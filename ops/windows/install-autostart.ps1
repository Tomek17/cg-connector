<#
.SYNOPSIS
    Rejestruje autostart serwera CG-connector w Harmonogramie zadan Windows.

.DESCRIPTION
    Tworzy (albo nadpisuje - skrypt mozna uruchamiac wiele razy) zadanie
    "CG-connector autostart", ktore:
      - uruchamia sie przy logowaniu do Windows i przy odblokowaniu komputera,
      - bez widocznego okna uruchamia WSL (Ubuntu) i w katalogu repozytorium
        wykonuje "docker compose up -d" (serwer + tunel Tailscale).
    Nie wymaga uprawnien administratora.

.PARAMETER Distro
    Nazwa dystrybucji WSL (np. Ubuntu albo Ubuntu-24.04). Domyslnie wykrywana.

.PARAMETER RepoPath
    Sciezka repozytorium W WSL. Domyslnie ~/cg-connector.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\install-autostart.ps1
#>
[CmdletBinding()]
param(
    [string]$Distro = "",
    [string]$RepoPath = "~/cg-connector",
    [string]$TaskName = "CG-connector autostart"
)

$ErrorActionPreference = "Stop"

# RepoPath trafia do polecenia bash w WSL - odrzucamy spacje, cudzyslowy i znaki specjalne.
if ($RepoPath -notmatch '^[A-Za-z0-9_./~-]+\z') {
    Write-Host "BLAD: niedozwolona sciezka -RepoPath '$RepoPath'." -ForegroundColor Red
    Write-Host "Sciezka nie moze zawierac spacji, cudzyslowow ani znakow specjalnych." -ForegroundColor Red
    Write-Host "Przenies repozytorium np. do ~/cg-connector i podaj -RepoPath ~/cg-connector." -ForegroundColor Red
    exit 2
}
# wsl.exe domyslnie wypisuje UTF-16; to wymusza zwykly UTF-8.
$env:WSL_UTF8 = "1"

function Get-WslDistros {
    # PowerShell 5.1: "Stop" + przekierowany stderr programu = wyjatek; tu nie chcemy.
    $ErrorActionPreference = "Continue"
    $out = & wsl.exe --list --quiet 2>$null
    if ($LASTEXITCODE -ne 0) { return @() }
    return @($out | ForEach-Object { ($_ -replace "`0", "").Trim() } | Where-Object { $_ })
}

# 1. WSL i dystrybucja
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "Nie znaleziono wsl.exe. Zainstaluj WSL (wsl --install) i sprobuj ponownie."
}
$distros = Get-WslDistros
if (-not $Distro) {
    $Distro = @("Ubuntu-24.04", "Ubuntu") | Where-Object { $distros -contains $_ } | Select-Object -First 1
}
if (-not $Distro -or ($distros -notcontains $Distro)) {
    throw "Nie znaleziono dystrybucji WSL '$Distro'. Dostepne: $($distros -join ', '). Podaj -Distro <nazwa>."
}
Write-Host "Dystrybucja WSL: $Distro"

# 2. Repozytorium i compose.yaml w WSL
$serverDir = "$RepoPath/server"
& wsl.exe -d $Distro --exec bash -lc "test -f $serverDir/compose.yaml && test -f $serverDir/.env"
if ($LASTEXITCODE -ne 0) {
    throw "W WSL brakuje $serverDir/compose.yaml albo $serverDir/.env. Najpierw wykonaj kroki z docs/instalacja-serwera.md."
}
Write-Host "Repozytorium w WSL: $serverDir (OK)"

# 3. Akcja: conhost --headless = bez okna konsoli.
#    W WSL: czekamy (max ~2 min), az Docker wystartuje, potem "docker compose up -d".
#    Pojedyncze cudzyslowy w PowerShell = znaki $ trafiaja do bash bez zmian.
$wslCommand = 'n=0; until docker info >/dev/null 2>&1 || [ $n -ge 60 ]; do n=$((n+1)); sleep 2; done; ' +
              "cd $serverDir && docker compose up -d"
$action = New-ScheduledTaskAction -Execute "$env:WINDIR\System32\conhost.exe" `
    -Argument "--headless `"$env:WINDIR\System32\wsl.exe`" -d $Distro --exec bash -lc `"$wslCommand`""

# 4. Wyzwalacze: logowanie + odblokowanie stacji roboczej
$user = "$env:USERDOMAIN\$env:USERNAME"
$atLogon = New-ScheduledTaskTrigger -AtLogOn -User $user
$atLogon.Delay = "PT30S"   # chwila na start sieci po zalogowaniu

$stateClass = Get-CimClass -Namespace "ROOT\Microsoft\Windows\TaskScheduler" `
    -ClassName "MSFT_TaskSessionStateChangeTrigger"
$atUnlock = New-CimInstance -CimClass $stateClass -ClientOnly
$atUnlock.StateChange = 8   # 8 = TASK_SESSION_UNLOCK
$atUnlock.UserId = $user
$atUnlock.Enabled = $true

# 5. Ustawienia: dziala tez na baterii, nie dubluje sie, limit 10 min
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 10)

$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

# 6. Rejestracja (-Force = nadpisz, jesli juz istnieje -> idempotentnie)
Register-ScheduledTask -TaskName $TaskName `
    -Description "Uruchamia WSL ($Distro) i 'docker compose up -d' dla CG-connector." `
    -Action $action -Trigger @($atLogon, $atUnlock) -Settings $settings -Principal $principal `
    -Force | Out-Null
Write-Host "Zadanie '$TaskName' zarejestrowane (logowanie + odblokowanie)."

# 7. Ostrzezenie, jesli .wslconfig nie wylacza usypiania WSL
$wslConfig = Join-Path $env:USERPROFILE ".wslconfig"
if (-not (Test-Path $wslConfig) -or -not (Select-String -Path $wslConfig -Pattern "instanceIdleTimeout" -Quiet)) {
    Write-Warning "Brak instanceIdleTimeout w $wslConfig - WSL moze sie sam zamykac. Zobacz ops/windows/wslconfig.example."
}

# 8. Uruchom od razu
Start-ScheduledTask -TaskName $TaskName
Write-Host "Zadanie uruchomione. Za ok. 30 s sprawdz: status.ps1"
