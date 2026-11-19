<#
.SYNOPSIS
    Sprawdza, czy serwer CG-connector dziala: WSL, Docker, kontenery, /health, tunel.

.DESCRIPTION
    Tylko odczyt - niczego nie zmienia. Kazdy punkt konczy sie [OK] albo [BLAD]
    z podpowiedzia. Kod wyjscia 0 = wszystko OK, 1 = cos nie dziala.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\status.ps1
#>
[CmdletBinding()]
param(
    [string]$Distro = "",
    [string]$RepoPath = "~/cg-connector",
    [string]$TaskName = "CG-connector autostart",
    [string]$HealthUrl = "http://127.0.0.1:8000/health"
)

$env:WSL_UTF8 = "1"

# RepoPath trafia do polecenia bash w WSL - odrzucamy spacje, cudzyslowy i znaki specjalne.
if ($RepoPath -notmatch '^[A-Za-z0-9_./~-]+\z') {
    Write-Host "BLAD: niedozwolona sciezka -RepoPath '$RepoPath'." -ForegroundColor Red
    Write-Host "Sciezka nie moze zawierac spacji, cudzyslowow ani znakow specjalnych." -ForegroundColor Red
    Write-Host "Przenies repozytorium np. do ~/cg-connector i podaj -RepoPath ~/cg-connector." -ForegroundColor Red
    exit 2
}
$script:failed = $false

function Write-Check([bool]$ok, [string]$name, [string]$detail) {
    if ($ok) {
        Write-Host "[OK]   $name" -ForegroundColor Green
    } else {
        Write-Host "[BLAD] $name" -ForegroundColor Red
        $script:failed = $true
    }
    if ($detail) { Write-Host "       $detail" }
}

# 1. WSL
$distros = @(& wsl.exe --list --quiet 2>$null | ForEach-Object { ($_ -replace "`0", "").Trim() } | Where-Object { $_ })
if (-not $Distro) {
    $Distro = @("Ubuntu-24.04", "Ubuntu") | Where-Object { $distros -contains $_ } | Select-Object -First 1
}
$wslOk = [bool]$Distro -and ($distros -contains $Distro)
Write-Check $wslOk "WSL: dystrybucja '$Distro'" $(if (-not $wslOk) { "Dostepne: $($distros -join ', '). Podaj -Distro <nazwa>." })
if (-not $wslOk) { exit 1 }

# 2. Docker w WSL (uruchamia WSL, jesli byl wylaczony)
$dockerVer = & wsl.exe -d $Distro --exec bash -lc "docker info --format '{{.ServerVersion}}' 2>/dev/null"
Write-Check ($LASTEXITCODE -eq 0) "Docker w WSL" $(if ($LASTEXITCODE -eq 0) { "wersja $dockerVer" } else { "W WSL: sudo systemctl status docker" })

# 3. Kontenery
$serverDir = "$RepoPath/server"
$running = & wsl.exe -d $Distro --exec bash -lc "cd $serverDir && docker compose ps --status running --services 2>/dev/null"
$running = @($running | Where-Object { $_ })
foreach ($svc in @("cg-server", "tailscale")) {
    Write-Check ($running -contains $svc) "Kontener $svc" $(if ($running -notcontains $svc) { "W WSL: cd $serverDir && docker compose up -d && docker compose logs $svc" })
}

# 4. /health lokalnie
$health = & curl.exe --silent --show-error --max-time 5 $HealthUrl 2>&1
$healthOk = ($LASTEXITCODE -eq 0) -and ("$health" -match '"status"\s*:\s*"ok"')
Write-Check $healthOk "Serwer: $HealthUrl" "$health"

# 5. Tunel: ngrok (plan B, profil "ngrok") albo Tailscale Funnel
if ($running -contains "ngrok") {
    $publicHost = & wsl.exe -d $Distro --exec bash -lc "cd $serverDir && grep -E '^CG_PUBLIC_HOST=' .env | cut -d= -f2-"
    $publicHost = "$publicHost".Trim()
    $publicUrl = "https://$publicHost/health"
    $pub = & curl.exe --silent --show-error --max-time 15 $publicUrl 2>&1
    $pubOk = ($LASTEXITCODE -eq 0) -and ("$pub" -match '"status"\s*:\s*"ok"')
    Write-Check $pubOk "Tunel ngrok: $publicUrl" $(if (-not $pubOk) { "$pub | W WSL: cd $serverDir && docker compose logs ngrok" })
} else {
    $funnel = & wsl.exe -d $Distro --exec bash -lc "cd $serverDir && docker compose exec -T tailscale tailscale funnel status 2>&1"
    $funnelOk = ($LASTEXITCODE -eq 0) -and ("$funnel" -match "Funnel on")
    Write-Check $funnelOk "Tunel Tailscale Funnel" (($funnel | Select-Object -First 3) -join " | ")
}

# 6. Autostart
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -eq $task) {
    Write-Check $false "Autostart '$TaskName'" "Uruchom install-autostart.ps1"
} else {
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    Write-Check ($task.State -ne "Disabled") "Autostart '$TaskName'" "Stan: $($task.State), ostatnie uruchomienie: $($info.LastRunTime), wynik: $($info.LastTaskResult)"
}

if ($script:failed) { exit 1 } else { Write-Host "Wszystko dziala."; exit 0 }
