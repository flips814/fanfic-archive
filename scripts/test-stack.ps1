# Local test stack helper (Docker Engine in WSL).
#   .\scripts\test-stack.ps1 up      build + start app, worker, fake SSO, Mailpit, ntfy
#   .\scripts\test-stack.ps1 test    run the automated test suite in a container
#   .\scripts\test-stack.ps1 logs    follow web + worker logs
#   .\scripts\test-stack.ps1 down    stop everything (keeps data)
#   .\scripts\test-stack.ps1 reset   stop everything and delete the test database
param(
    [ValidateSet("up", "test", "logs", "down", "reset", "ps")][string]$Action = "up",
    [string]$Distro = "Ubuntu"
)

Set-Location (Split-Path $PSScriptRoot -Parent)
$compose = @("docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.test.yml", "--profile", "flaresolverr")
$keepAliveTask = "FanficArchive-WSL-KeepAlive"

# WSL stops the distro (and Docker with it) once no wsl.exe process is attached.
# A scheduled task holds one open, detached from whichever terminal ran this script.
function Start-KeepAlive {
    if (-not (Get-ScheduledTask -TaskName $keepAliveTask -ErrorAction SilentlyContinue)) {
        $action = New-ScheduledTaskAction -Execute "conhost.exe" `
            -Argument "--headless wsl.exe -d $Distro --exec sleep infinity"
        $taskSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
            -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        Register-ScheduledTask -TaskName $keepAliveTask -Action $action -Settings $taskSettings `
            -Description "Keeps WSL ($Distro) running for the fanfic-archive Docker test stack" | Out-Null
    }
    if ((Get-ScheduledTask -TaskName $keepAliveTask).State -ne "Running") {
        Start-ScheduledTask -TaskName $keepAliveTask
    }
}

function Stop-KeepAlive {
    if (Get-ScheduledTask -TaskName $keepAliveTask -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $keepAliveTask
    }
}

switch ($Action) {
    "up" {
        Start-KeepAlive
        wsl -d $Distro @compose up -d --build
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Host ""
        Write-Host "App        http://localhost:8000"
        Write-Host "Fake SSO   http://localhost:8080  (any username; optional claims JSON, e.g."
        Write-Host '           {"name":"Alice","preferred_username":"alice","email":"alice@test.local","groups":["admins"]})'
        Write-Host "Mailpit    http://localhost:8025"
        Write-Host "ntfy       http://localhost:8090/fanfic-all"
        Write-Host "(WSL is kept running in the background until '.\scripts\test-stack.ps1 down')"
    }
    "test"  { wsl -d $Distro @compose run --rm --build tests }
    "logs"  { wsl -d $Distro @compose logs -f web worker }
    "ps"    { wsl -d $Distro @compose ps }
    "down"  { wsl -d $Distro @compose down; Stop-KeepAlive }
    "reset" { wsl -d $Distro @compose down -v; Stop-KeepAlive }
}
exit $LASTEXITCODE
