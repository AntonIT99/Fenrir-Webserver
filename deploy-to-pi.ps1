$ConfigPath = Join-Path $PSScriptRoot "deploy-to-pi.local.ps1"

if (-not (Test-Path $ConfigPath)) {
    Write-Error "Missing deploy-to-pi.local.ps1. Create it from deploy-to-pi.local.example.ps1."
    exit 1
}

. $ConfigPath

function Escape-EnvValue([string] $Value) {
    return $Value.Replace("\", "\\").Replace("`"", "\`"")
}

function Use-DefaultVariable([string] $Name, $Value) {
    if (-not (Get-Variable -Name $Name -Scope Script -ErrorAction SilentlyContinue)) {
        Set-Variable -Name $Name -Value $Value -Scope Script
    }
}

Use-DefaultVariable "ServiceName" "webserver"
Use-DefaultVariable "ServerPort" 8080
Use-DefaultVariable "InstallSystemdService" $true
Use-DefaultVariable "RestartSystemdService" $true

if (-not $DisplayName) {
    Write-Error "DisplayName must be set in deploy-to-pi.local.ps1."
    exit 1
}

if (-not $BootstrapAdminUsername -or -not $BootstrapAdminPassword) {
    Write-Error "BootstrapAdminUsername and BootstrapAdminPassword must be set in deploy-to-pi.local.ps1."
    exit 1
}

$RemoteDirectory = $TargetPath -replace "/[^/]+$", ""
$RemoteEnvPath = "$RemoteDirectory/webserver.env"
$RemoteJarTmpPath = "$TargetPath.uploading"
$RemoteEnvTmpPath = "$RemoteEnvPath.uploading"
$LocalEnvPath = Join-Path $env:TEMP "webserver.env"
$LocalServicePath = Join-Path $env:TEMP "$ServiceName.service"
$RemoteServiceTmpPath = "/tmp/$ServiceName.service"
$RemoteServicePath = "/etc/systemd/system/$ServiceName.service"

Write-Host "Building Spring Boot JAR..."
.\gradlew.bat clean bootJar

if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed."
    exit 1
}

$Jar = Get-ChildItem ".\build\libs\*.jar" |
        Where-Object { $_.Name -notlike "*plain*.jar" } |
        Select-Object -First 1

if (-not $Jar) {
    Write-Error "No boot JAR found."
    exit 1
}

Write-Host "Ensuring remote directory exists..."
ssh -i $SshKey "${PiUser}@${PiHost}" "mkdir -p '$RemoteDirectory'"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Could not create remote directory $RemoteDirectory."
    exit 1
}

if ($RestartSystemdService) {
    Write-Host "Stopping systemd service $ServiceName before upload..."
    ssh -i $SshKey "${PiUser}@${PiHost}" "sudo systemctl stop '$ServiceName' 2>/dev/null || true"

    if ($LASTEXITCODE -ne 0) {
        Write-Error "Could not stop systemd service $ServiceName."
        exit 1
    }

    Write-Host "Waiting for $ServiceName to stop..."
    ssh -i $SshKey "${PiUser}@${PiHost}" "for i in 1 2 3 4 5 6 7 8 9 10; do sudo systemctl is-active --quiet '$ServiceName' || exit 0; sleep 1; done; sudo systemctl status '$ServiceName' --no-pager; exit 1"

    if ($LASTEXITCODE -ne 0) {
        Write-Error "Service $ServiceName did not stop cleanly."
        exit 1
    }

    Write-Host "Checking whether port $ServerPort is free..."
    ssh -i $SshKey "${PiUser}@${PiHost}" "if command -v ss >/dev/null 2>&1; then if ss -ltnp 2>/dev/null | grep -q ':$ServerPort '; then ss -ltnp 2>/dev/null | grep ':$ServerPort '; exit 1; fi; fi"

    if ($LASTEXITCODE -ne 0) {
        Write-Error "Port $ServerPort is still in use on the Raspberry Pi. Stop the process shown above before deploying."
        exit 1
    }
}

Write-Host "Uploading $($Jar.Name) to Raspberry Pi..."
scp -i $SshKey $Jar.FullName "${PiUser}@${PiHost}:${RemoteJarTmpPath}"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Upload failed."
    exit 1
}

ssh -i $SshKey "${PiUser}@${PiHost}" "mv '$RemoteJarTmpPath' '$TargetPath'"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Could not replace remote JAR atomically."
    exit 1
}

@(
    "APP_DISPLAY_NAME=""$(Escape-EnvValue $DisplayName)"""
    "BOOTSTRAP_ADMIN_USERNAME=""$(Escape-EnvValue $BootstrapAdminUsername)"""
    "BOOTSTRAP_ADMIN_PASSWORD=""$(Escape-EnvValue $BootstrapAdminPassword)"""
) | Set-Content -Path $LocalEnvPath -Encoding ascii

Write-Host "Using display name: $DisplayName"
Write-Host "Uploading environment file to Raspberry Pi..."
scp -i $SshKey $LocalEnvPath "${PiUser}@${PiHost}:${RemoteEnvTmpPath}"

if ($LASTEXITCODE -ne 0) {
    Remove-Item $LocalEnvPath -ErrorAction SilentlyContinue
    Write-Error "Environment upload failed."
    exit 1
}

ssh -i $SshKey "${PiUser}@${PiHost}" "mv '$RemoteEnvTmpPath' '$RemoteEnvPath' && chmod 600 '$RemoteEnvPath'"

if ($LASTEXITCODE -ne 0) {
    Remove-Item $LocalEnvPath -ErrorAction SilentlyContinue
    Write-Error "Could not set permissions on remote environment file."
    exit 1
}

Write-Host "Remote environment file:"
ssh -i $SshKey "${PiUser}@${PiHost}" "grep '^APP_DISPLAY_NAME=' '$RemoteEnvPath'"

if ($LASTEXITCODE -ne 0) {
    Remove-Item $LocalEnvPath -ErrorAction SilentlyContinue
    Write-Error "Could not verify APP_DISPLAY_NAME in remote environment file."
    exit 1
}

Remove-Item $LocalEnvPath -ErrorAction SilentlyContinue

if ($InstallSystemdService) {
    @"
[Unit]
Description=$DisplayName Webserver
After=network.target

[Service]
WorkingDirectory=$RemoteDirectory
EnvironmentFile=$RemoteEnvPath
EnvironmentFile=-$RemoteDirectory/mod-catalogue.env
ExecStart=/usr/bin/java -jar $TargetPath --spring.profiles.active=prod
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
"@ | Set-Content -Path $LocalServicePath -Encoding ascii

    Write-Host "Uploading systemd service $ServiceName..."
    scp -i $SshKey $LocalServicePath "${PiUser}@${PiHost}:${RemoteServiceTmpPath}"

    if ($LASTEXITCODE -ne 0) {
        Remove-Item $LocalServicePath -ErrorAction SilentlyContinue
        Write-Error "Systemd service upload failed."
        exit 1
    }

    ssh -i $SshKey "${PiUser}@${PiHost}" "sudo mv '$RemoteServiceTmpPath' '$RemoteServicePath' && sudo systemctl daemon-reload && sudo systemctl enable '$ServiceName'"

    if ($LASTEXITCODE -ne 0) {
        Remove-Item $LocalServicePath -ErrorAction SilentlyContinue
        Write-Error "Could not install or enable systemd service. Check sudo permissions on the Raspberry Pi."
        exit 1
    }

    Remove-Item $LocalServicePath -ErrorAction SilentlyContinue
}

if ($RestartSystemdService) {
    Write-Host "Restarting systemd service $ServiceName..."
    ssh -i $SshKey "${PiUser}@${PiHost}" "sudo systemctl restart '$ServiceName' && sudo systemctl status '$ServiceName' --no-pager"

    if ($LASTEXITCODE -ne 0) {
        Write-Error "Service restart failed. Check logs with: journalctl -u $ServiceName -f"
        exit 1
    }
}

Write-Host "Done."
