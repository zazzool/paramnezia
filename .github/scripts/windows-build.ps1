# Builds the Paramnezia for Windows installer on a windows-latest runner (docs/WINDOWS.md):
#   1. paramnezia-service.exe (Go) and wintun.dll (official signed build, checksum pinned);
#   2. the app image with its own Java runtime (Compose Desktop / jpackage);
#   3. Inno Setup → desktop/build/installer/Paramnezia-Setup-<version>.exe
# Version: $env:PN_VERSION_NAME (v2.6.0 or 2.6.0), $env:PN_VERSION_CODE.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$version = ($env:PN_VERSION_NAME, '0.1.0' | Where-Object { $_ } | Select-Object -First 1) -replace '^v', ''
$svc = "$root\desktop\build\service"
New-Item -ItemType Directory -Force $svc | Out-Null

# 1. The service.
Push-Location "$root\tunnel\src\main\go"
$env:CGO_ENABLED = '0'; $env:GOOS = 'windows'; $env:GOARCH = 'amd64'
go build -trimpath -ldflags="-s -w" -o "$svc\paramnezia-service.exe" ./windows
if ($LASTEXITCODE) { throw "go build failed" }
Pop-Location

# Wintun 0.14.1, signed by WireGuard LLC; the zip's checksum is pinned here.
$zip = "$env:RUNNER_TEMP\wintun.zip"
Invoke-WebRequest -Uri 'https://www.wintun.net/builds/wintun-0.14.1.zip' -OutFile $zip
$sha = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
if ($sha -ne '07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51') { throw "wintun.zip checksum mismatch: $sha" }
Expand-Archive $zip -DestinationPath "$env:RUNNER_TEMP\wintun" -Force
Copy-Item "$env:RUNNER_TEMP\wintun\wintun\bin\amd64\wintun.dll" "$svc\wintun.dll"

# 2. The app image (release: ProGuard-shrunk, desktop/proguard-rules.pro).
# The app is built on and ships JDK 25 (java.lang.foreign). Temurin 24+ comes without its jmods
# (JEP 493), but ProGuard reads the JDK's classes from them and jlink prefers them: fetch the
# jmods of exactly this Temurin build from Adoptium when they are missing.
$jdk = $env:JAVA_HOME_25_X64
if ($jdk -and -not (Test-Path "$jdk\jmods\java.base.jmod")) {
    $impl = (Select-String -Path "$jdk\release" -Pattern '^IMPLEMENTOR_VERSION="Temurin-(.+)"').Matches.Groups[1].Value
    if (-not $impl) { throw "not a Temurin JDK at $jdk, and it has no jmods" }
    $url = "https://api.adoptium.net/v3/binary/version/jdk-$([uri]::EscapeDataString($impl))/windows/x64/jmods/hotspot/normal/eclipse"
    Write-Host "jmods for Temurin $impl from $url"
    $jz = "$env:RUNNER_TEMP\jmods.zip"
    Invoke-WebRequest -Uri $url -OutFile $jz
    Expand-Archive $jz -DestinationPath "$env:RUNNER_TEMP\jmods" -Force
    New-Item -ItemType Directory -Force "$jdk\jmods" | Out-Null
    Get-ChildItem "$env:RUNNER_TEMP\jmods" -Recurse -Filter *.jmod | Copy-Item -Destination "$jdk\jmods"
    if (-not (Test-Path "$jdk\jmods\java.base.jmod")) { throw "no java.base.jmod in the Adoptium jmods archive" }
}
Push-Location $root
& .\gradlew.bat '-Pparamnezia.jvmOnly=true' --no-configuration-cache :desktop:createReleaseDistributable
if ($LASTEXITCODE) { throw "gradle failed" }
Pop-Location
$app = "$root\desktop\build\compose\binaries\main-release\app\Paramnezia"
if (-not (Test-Path "$app\Paramnezia.exe")) { throw "no app image at $app" }

# 3. The installer.
$iscc = "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe"
if (-not (Test-Path $iscc)) {
    choco install innosetup -y --no-progress | Out-Null
    if ($LASTEXITCODE) { throw "choco install innosetup failed" }
}
& $iscc /Qp "/DAppVersion=$version" "/DAppDir=$app" "/DServiceDir=$svc" "/DOutputDir=$root\desktop\build\installer" "$root\desktop\installer\paramnezia.iss"
if ($LASTEXITCODE) { throw "iscc failed" }
Get-ChildItem "$root\desktop\build\installer" | ForEach-Object { "{0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB) }
