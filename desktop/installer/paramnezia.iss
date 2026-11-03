; Paramnezia for Windows: installer (Inno Setup 6). Built by CI (.github/workflows/build.yml, release.yml):
;   iscc /DAppVersion=2.6.0 /DAppDir=<jpackage app image> /DServiceDir=<paramnezia-service.exe + wintun.dll> paramnezia.iss
;
; What it does, and nothing else (docs/WINDOWS.md, "Defender and SmartScreen"):
;   * copies the app (with its own Java runtime) to Program Files;
;   * registers the ParamneziaTunnel service, which only starts a tunnel when the app asks for one;
;   * adds Start menu shortcuts and, if the user ticks it, the .vpn file association;
;   * allows incoming connections to Paramnezia.exe in Windows Firewall, so devices in the same network
;     can reach the share the user opens («Раздать по Wi-Fi», docs/LAN-SHARE.md). Nothing listens otherwise.
; The app never installs or updates itself: a new version is this installer, downloaded by the user.

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#ifndef AppDir
  #define AppDir "..\build\compose\binaries\main\app\Paramnezia"
#endif
#ifndef ServiceDir
  #define ServiceDir "..\build\service"
#endif
#ifndef OutputDir
  #define OutputDir "..\build\installer"
#endif

[Setup]
AppId={{6F0D6C6E-7061-726D-6E65-7A6961000001}
AppName=Paramnezia
AppVersion={#AppVersion}
AppVerName=Paramnezia {#AppVersion}
AppPublisher=Paramnezia
AppPublisherURL=https://github.com/zazzool/paramnezia
AppSupportURL=https://github.com/zazzool/paramnezia/issues
AppUpdatesURL=https://github.com/zazzool/paramnezia/releases
DefaultDirName={autopf}\Paramnezia
DefaultGroupName=Paramnezia
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir={#OutputDir}
OutputBaseFilename=Paramnezia-Setup-{#AppVersion}
SetupIconFile=..\icons\paramnezia.ico
UninstallDisplayIcon={app}\Paramnezia.exe
UninstallDisplayName=Paramnezia
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=force
RestartApplications=no
ChangesAssociations=yes
VersionInfoVersion={#AppVersion}
VersionInfoProductName=Paramnezia
VersionInfoDescription=Paramnezia VPN setup

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "vpnfiles"; Description: "Открывать файлы .vpn в Paramnezia"; GroupDescription: "Файлы:"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#ServiceDir}\paramnezia-service.exe"; DestDir: "{app}\service"; Flags: ignoreversion
Source: "{#ServiceDir}\wintun.dll"; DestDir: "{app}\service"; Flags: ignoreversion

[Icons]
Name: "{group}\Paramnezia"; Filename: "{app}\Paramnezia.exe"
Name: "{group}\{cm:UninstallProgram,Paramnezia}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Paramnezia"; Filename: "{app}\Paramnezia.exe"; Tasks: desktopicon

[Registry]
Root: HKA; Subkey: "Software\Classes\.vpn"; ValueType: string; ValueName: ""; ValueData: "Paramnezia.Key"; Flags: uninsdeletevalue; Tasks: vpnfiles
Root: HKA; Subkey: "Software\Classes\Paramnezia.Key"; ValueType: string; ValueName: ""; ValueData: "Ключ Paramnezia"; Flags: uninsdeletekey; Tasks: vpnfiles
Root: HKA; Subkey: "Software\Classes\Paramnezia.Key\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\Paramnezia.exe,0"; Tasks: vpnfiles
Root: HKA; Subkey: "Software\Classes\Paramnezia.Key\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Paramnezia.exe"" ""%1"""; Tasks: vpnfiles

[Run]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Paramnezia LAN"""; StatusMsg: "Настройка брандмауэра…"; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Paramnezia LAN"" dir=in action=allow program=""{app}\Paramnezia.exe"" enable=yes profile=any description=""Paramnezia: sharing the app and settings in the local network"""; StatusMsg: "Настройка брандмауэра…"; Flags: runhidden waituntilterminated
Filename: "{app}\service\paramnezia-service.exe"; Parameters: "install"; StatusMsg: "Регистрация службы Paramnezia…"; Flags: runhidden waituntilterminated
Filename: "{app}\Paramnezia.exe"; Description: "{cm:LaunchProgram,Paramnezia}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Paramnezia LAN"""; RunOnceId: "RemoveFirewallRule"; Flags: runhidden waituntilterminated
Filename: "{app}\service\paramnezia-service.exe"; Parameters: "uninstall"; RunOnceId: "RemoveService"; Flags: runhidden waituntilterminated

[UninstallDelete]
Type: filesandordirs; Name: "{commonappdata}\Paramnezia"

[Code]
// An upgrade replaces the service binary, so the running service is stopped first
// (installService then points the same service at the new file and starts it again).
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\sc.exe'), 'stop ParamneziaTunnel', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Sleep(1500);
  Result := '';
end;

// Autostart is a per-user value the app writes (Settings → "Запускать при входе в Windows").
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Paramnezia');
end;
