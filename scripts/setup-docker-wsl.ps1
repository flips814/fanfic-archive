# Installs Docker Engine + Compose inside a WSL 2 distro (no Docker Desktop needed).
# Afterwards, run docker from Windows as:  wsl docker compose up -d
param([string]$Distro = "Ubuntu")

$ErrorActionPreference = "Stop"

function Invoke-Root([string]$cmd) {
    # base64 so quotes/redirects survive Windows PowerShell's native-arg quoting
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($cmd -replace "`r", "")))
    wsl -d $Distro -u root -- bash -c "echo $b64 | base64 -d | bash"
    if ($LASTEXITCODE -ne 0) { throw "Command failed in WSL ($LASTEXITCODE): $cmd" }
}

Write-Host "==> Checking WSL 2 can start $Distro"
wsl -d $Distro -u root -- true
if ($LASTEXITCODE -ne 0) {
    throw "WSL can't start $Distro. Enable 'Virtual Machine Platform' (wsl --install --no-distribution, as admin) and restart Windows."
}

Write-Host "==> Enabling systemd in /etc/wsl.conf"
Invoke-Root @'
touch /etc/wsl.conf
if ! grep -q '^\[boot\]' /etc/wsl.conf; then printf '\n[boot]\nsystemd=true\n' >> /etc/wsl.conf
elif ! grep -q '^systemd=true' /etc/wsl.conf; then sed -i '/^\[boot\]/a systemd=true' /etc/wsl.conf; fi
'@

Write-Host "==> Installing Docker Engine (get.docker.com)"
Invoke-Root "command -v docker >/dev/null || (apt-get update -qq && apt-get install -y -qq curl ca-certificates >/dev/null && curl -fsSL https://get.docker.com | sh)"

$user = (wsl -d $Distro -- whoami).Trim()
if ($user -and $user -ne "root") {
    Write-Host "==> Adding '$user' to the docker group"
    Invoke-Root "usermod -aG docker $user"
}

Write-Host "==> Restarting WSL so systemd starts"
wsl --shutdown
Start-Sleep -Seconds 3

Invoke-Root 'systemctl enable --now docker >/dev/null 2>&1; for i in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 1; done'

Write-Host "==> Verifying"
wsl -d $Distro -- docker version --format "Docker {{.Server.Version}}"
wsl -d $Distro -- docker compose version
wsl -d $Distro -- docker run --rm hello-world | Select-String "Hello from Docker"
Write-Host "Done. From the project folder run:  wsl docker compose up -d --build"
