<#
.SYNOPSIS
    Usuwa zadanie autostartu CG-connector z Harmonogramu zadan.

.DESCRIPTION
    Nie zatrzymuje dzialajacych kontenerow i nie usuwa zadnych danych.
    Aby zatrzymac serwer, w WSL: cd ~/cg-connector/server && docker compose down
    Mozna uruchamiac wielokrotnie.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\uninstall-autostart.ps1
#>
[CmdletBinding()]
param(
    [string]$TaskName = "CG-connector autostart"
)

$ErrorActionPreference = "Stop"

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -eq $task) {
    Write-Host "Zadanie '$TaskName' nie istnieje - nic do zrobienia."
    exit 0
}
Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
Write-Host "Usunieto zadanie '$TaskName'. Kontenery i dane pozostaly bez zmian."
