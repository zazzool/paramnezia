# End-to-end check of the Windows build on a windows-latest runner (administrator):
# installs the real installer silently, talks to the service over its pipe the way the app does,
# brings a tunnel up (Wintun adapter, address, route) and down, uses the "direct" proxy,
# then uninstalls and checks that the service is gone.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$setup = Get-ChildItem "$root\desktop\build\installer\Paramnezia-Setup-*.exe" | Select-Object -First 1
if (-not $setup) { throw "no installer" }

function Ask($req) {
    $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', 'paramnezia', [System.IO.Pipes.PipeDirection]::InOut)
    $pipe.Connect(10000)
    $w = New-Object System.IO.StreamWriter($pipe); $w.AutoFlush = $true
    $r = New-Object System.IO.StreamReader($pipe)
    $w.WriteLine(($req | ConvertTo-Json -Compress -Depth 5))
    $line = $r.ReadLine()
    $pipe.Dispose()
    $resp = $line | ConvertFrom-Json
    Write-Host "$($req.op) -> $line"
    return $resp
}
function Hex32 { -join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) }) }

Write-Host "== install $($setup.Name)"
$p = Start-Process $setup.FullName -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/TASKS=vpnfiles', '/LOG=install.log' -Wait -PassThru
if ($p.ExitCode) { Get-Content install.log -Tail 40; throw "setup exit code $($p.ExitCode)" }
$app = "${env:ProgramFiles}\Paramnezia"
foreach ($f in 'Paramnezia.exe', 'service\paramnezia-service.exe', 'service\wintun.dll') {
    if (-not (Test-Path "$app\$f")) { throw "missing $app\$f" }
}
if ((Get-ItemProperty 'Registry::HKEY_CLASSES_ROOT\.vpn').'(default)' -ne 'Paramnezia.Key') { throw ".vpn is not associated" }
$svc = Get-Service ParamneziaTunnel
for ($i = 0; $i -lt 30 -and $svc.Status -ne 'Running'; $i++) { Start-Sleep 1; $svc.Refresh() }
if ($svc.Status -ne 'Running') { throw "service is $($svc.Status)" }

Write-Host "== the packaged release app runs (ProGuard kept what it needs)"
$shots = "$env:RUNNER_TEMP\pn-shots"
$p = Start-Process "$app\Paramnezia.exe" -ArgumentList '--screenshots', $shots -PassThru
if (-not $p.WaitForExit(200000)) { $p.Kill(); throw "the app did not finish its screenshots" }
$pngs = @(Get-ChildItem "$shots\*.png" -ErrorAction SilentlyContinue)
if ($p.ExitCode -ne 0 -or $pngs.Count -lt 3) {
    Get-Content "$shots\error.txt" -ErrorAction SilentlyContinue | Select-Object -First 120
    throw "the packaged app rendered $($pngs.Count) screens (exit code $($p.ExitCode))"
}
Write-Host "rendered $($pngs.Count) screens"
# The Windows calls (java.lang.foreign, Win32.kt) in the packaged runtime: DPAPI and the registry.
$st = "$env:RUNNER_TEMP\pn-selftest.txt"
$p = Start-Process "$app\Paramnezia.exe" -ArgumentList '--selftest', $st -PassThru
if (-not $p.WaitForExit(60000)) { $p.Kill(); throw "the self-test hung" }
if ($p.ExitCode -ne 0) { Get-Content $st -ErrorAction SilentlyContinue; throw "Windows self-test failed (code $($p.ExitCode))" }
Write-Host "self-test: $(Get-Content $st)"
# A real start: state file (DPAPI), tray, service pipe. It must still run after 20 s.
$p = Start-Process "$app\Paramnezia.exe" -ArgumentList '--minimized' -PassThru
Start-Sleep 20
$log = "$env:LOCALAPPDATA\Paramnezia\paramnezia.log"
if ($p.HasExited) { Get-Content $log -Tail 40 -ErrorAction SilentlyContinue; throw "the app exited on start (code $($p.ExitCode))" }
if (Select-String -Path $log -Pattern 'ClassNotFound|NoSuchMethod|NoClassDefFound|NoSuchField' -Quiet -ErrorAction SilentlyContinue) {
    Get-Content $log -Tail 40; throw "classes missing in the release app"
}
Stop-Process -Id $p.Id -Force

$v = Ask @{ op = 'version' }
if (-not $v.ok -or -not $v.version) { throw "version failed" }

Write-Host "== tunnel up (dummy peer, only 10.123.0.0/16 routed, the runner keeps its network)"
$uapi = "private_key=$(Hex32)`nreplace_peers=true`npublic_key=$(Hex32)`nendpoint=192.0.2.1:51820`npersistent_keepalive_interval=25`nallowed_ip=0.0.0.0/0`n"
$up = Ask @{ op = 'up'; up = @{ name = 'ci'; uapi = $uapi; addresses = @('10.123.0.2/32'); routes = @('10.123.0.0/16'); dnsServers = @() } }
if (-not $up.ok) { throw "up failed: $($up.error)" }
$st = Ask @{ op = 'status' }
if (-not $st.up -or $st.name -ne 'ci') { throw "status does not show the tunnel" }
if ($st.stats -match 'private_key') { throw "status gives out the private key" }
$nic = Get-NetAdapter -Name Paramnezia
Write-Host "adapter: $($nic.InterfaceDescription) $($nic.Status)"
$addr = Get-NetIPAddress -InterfaceAlias Paramnezia -AddressFamily IPv4 | Where-Object IPAddress -eq '10.123.0.2'
if (-not $addr) { throw "address not set" }
$route = Get-NetRoute -InterfaceAlias Paramnezia -DestinationPrefix '10.123.0.0/16' -ErrorAction SilentlyContinue
if (-not $route) { throw "route not set" }
# Traffic into the tunnel is handled by amneziawg-go: a ping makes it try a handshake (tx grows).
ping -n 2 -w 500 10.123.0.1 | Out-Null
$st = Ask @{ op = 'status' }
if ($st.stats -notmatch 'tx_bytes=[1-9]') { throw "no traffic went into the tunnel: $($st.stats)" }

Write-Host "== watchdog in the service: a dead server is never 'connected', the service switches by itself"
# The peer (192.0.2.1) never answers. The probe is pinned to the tunnel adapter, so even though only
# 10.123.0.0/16 is routed into it, the runner's own internet must not make the tunnel look alive.
$w = (Ask @{ op = 'status' }).watch
if (-not $w) { throw "status has no watchdog part" }
if ($w.verified) { throw "a tunnel to a dead server was reported as verified" }
if ((Ask @{ op = 'rebind' }).ok -ne $true) { throw "rebind failed" }
if ((Ask @{ op = 'recheck' }).ok -ne $true) { throw "recheck failed" }
$b = @{ name = 'ci-b'; uapi = $uapi; addresses = @('10.123.0.3/32'); routes = @('10.123.0.0/16'); dnsServers = @() }
$up = Ask @{ op = 'up'; up = @{ name = 'ci'; uapi = $uapi; addresses = @('10.123.0.2/32'); routes = @('10.123.0.0/16'); dnsServers = @() }; alternates = @($b) }
if (-not $up.ok) { throw "up with a fallback server failed: $($up.error)" }
# Two failed builds of 'ci' (20 s of probes each), then the next server: about 50 s.
$switched = $false
for ($i = 0; $i -lt 50; $i++) {
    Start-Sleep 2
    $st = Ask @{ op = 'status' }
    if ($st.watch.active -eq 1 -and $st.watch.server -eq 'ci-b') { $switched = $true; break }
}
if (-not $switched) {
    Get-Content "$env:ProgramData\Paramnezia\service.log" -Tail 30 -ErrorAction SilentlyContinue
    throw "the service did not switch to the fallback server"
}
if (-not $st.up -or $st.watch.verified) { throw "after the switch: up=$($st.up) verified=$($st.watch.verified)" }
if (-not (Select-String -Path "$env:ProgramData\Paramnezia\service.log" -Pattern 'rebuild "ci-b"' -Quiet)) { throw "service.log does not show the switch" }

Write-Host "== direct proxy (the app's way around the tunnel)"
$d = Ask @{ op = 'direct' }
if (-not $d.ok -or -not $d.socks) { throw "direct failed" }
$ip = curl.exe -sS --max-time 20 --socks5-hostname "$($d.socksUser):$($d.socksPass)@$($d.socks)" https://api.ipify.org
if ($LASTEXITCODE -or -not $ip) { throw "no answer through the direct proxy" }
Write-Host "direct address: $ip"
$bad = curl.exe -s --max-time 10 --socks5-hostname "x:y@$($d.socks)" https://api.ipify.org
if ($LASTEXITCODE -eq 0) { throw "the direct proxy accepted wrong credentials" }

Write-Host "== programs past the VPN (1.1.1.1 routed into a tunnel that goes nowhere)"
$probeDir = "$env:RUNNER_TEMP\splitprobe"
New-Item -ItemType Directory -Force $probeDir | Out-Null
Push-Location "$root\tunnel\src\main\go"
$env:CGO_ENABLED = '0'
go build -o "$probeDir\pn-direct-probe.exe" ./windows/splitprobe
if ($LASTEXITCODE) { throw "probe build failed" }
Pop-Location
Copy-Item "$probeDir\pn-direct-probe.exe" "$probeDir\pn-tunnel-probe.exe"
$up = Ask @{ op = 'up'; up = @{ name = 'ci-split'; uapi = $uapi; addresses = @('10.123.0.2/32'); routes = @('10.123.0.0/16', '1.1.1.1/32'); dnsServers = @(); excludedApps = @('pn-direct-probe.exe') } }
if (-not $up.ok) { throw "up with excluded programs failed: $($up.error)" }
$inTunnel = & "$probeDir\pn-tunnel-probe.exe" 1.1.1.1 | ConvertFrom-Json
Write-Host "program not on the list: tcp=$($inTunnel.tcp) udp=$($inTunnel.udp)"
if ($inTunnel.tcp -or $inTunnel.udp) { throw "a program not on the list got past the tunnel" }
$direct = & "$probeDir\pn-direct-probe.exe" 1.1.1.1 | ConvertFrom-Json
Write-Host "program on the list: tcp=$($direct.tcp) udp=$($direct.udp)"
if (-not $direct.tcp -or -not $direct.udp) {
    Get-Content "$env:ProgramData\Paramnezia\service.log" -Tail 30 -ErrorAction SilentlyContinue
    throw "the program on the list did not get past the VPN"
}
if (-not (Select-String -Path "$env:ProgramData\Paramnezia\service.log" -Pattern 'pn-direct-probe.exe goes past the VPN' -Quiet)) { throw "service.log does not mention the excluded program" }

Write-Host "== tunnel down"
$down = Ask @{ op = 'down' }
if (-not $down.ok) { throw "down failed" }
Start-Sleep 2
if (Get-NetAdapter -Name Paramnezia -ErrorAction SilentlyContinue) { throw "adapter still present after down" }

Write-Host "== uninstall"
$p = Start-Process "$app\unins000.exe" -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
Start-Sleep 3
if (Get-Service ParamneziaTunnel -ErrorAction SilentlyContinue) { throw "service still installed" }
if (Test-Path "$app\service\paramnezia-service.exe") { throw "files left behind" }
Write-Host "OK"
exit 0
