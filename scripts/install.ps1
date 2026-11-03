<#
.SYNOPSIS
Installs Portal on an Android TV / Google TV over adb and sets the TV up to use it.

.DESCRIPTION
  1. connects to the TV
  2. installs the APK (yours, or the latest GitHub release)
  3. makes Portal the home screen (turns off the stock launcher, gives Portal the HOME role)
  4. makes Portal the TV's screen saver in place of its ambient mode, so it also starts over other apps
  5. starts Portal

Every step is safe to repeat. Needs adb (Android SDK platform-tools) on your PATH.

.PARAMETER Tv
The TV's address, with or without a port (default 5555): 192.168.1.20 or 192.168.1.20:5555.

.PARAMETER Apk
APK to install. Default: portal*.apk in the current folder, then the release build in
app\build\outputs\apk\release, then the latest GitHub release (needs gh).

.PARAMETER KeepLauncher
Leave the stock launcher alone.

.PARAMETER KeepScreensaver
Leave the TV's screen saver settings alone.

.PARAMETER Restore
Undo steps 3 and 4: re-enable the stock launcher and give back the TV's own screen saver.

.EXAMPLE
.\scripts\install.ps1 192.168.1.20
.\scripts\install.ps1 -Apk .\portal-1.0.42.apk 192.168.1.20
.\scripts\install.ps1 -Restore 192.168.1.20
#>
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Tv,
    [string]$Apk = "",
    [switch]$KeepLauncher,
    [switch]$KeepScreensaver,
    [switch]$Restore
)

$ErrorActionPreference = "Stop"

function Step($text) { Write-Host ""; Write-Host "==> $text" }
function Fail($text) { Write-Host "error: $text" -ForegroundColor Red; exit 1 }

if ($Tv -notmatch ":") { $Tv = "${Tv}:5555" }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Fail "adb not found. Install the Android SDK platform-tools and put them on your PATH."
}

# Newer adb builds can fail to connect through mDNS; use the classic path.
$env:ADB_MDNS_OPENSCREEN = "0"

# Runs a command on the TV and returns its output lines. adb's own errors don't stop the script.
function Tv-Shell {
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { (& adb -s $Tv shell @args 2>&1 | ForEach-Object { "$_".TrimEnd() }) } finally { $ErrorActionPreference = $old }
}

# Launchers that must stay enabled: Portal, and the Settings app's fallback home.
$keepPackages = @("com.portal.tv", "com.android.tv.settings", "android")
# Where the stock launchers have been seen, for -Restore.
$knownLaunchers = @(
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tungsten.setupwraith",
    "com.google.android.tvlauncher",
    "com.google.android.leanbacklauncher"
)

function Home-Packages {
    Tv-Shell cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME |
        ForEach-Object { if ($_ -match '^\s+([A-Za-z0-9_.]+)/') { $Matches[1] } } |
        Sort-Object -Unique
}

Step "Connecting to $Tv"
& adb connect $Tv | Select-Object -Last 1
& adb -s $Tv get-state *> $null
if ($LASTEXITCODE -ne 0) {
    Fail "can't reach the TV. Turn on USB debugging and network debugging in its Developer options, accept the prompt on the TV, and check the address."
}

if ($Restore) {
    Step "Re-enabling the stock launcher"
    $disabled = Tv-Shell pm list packages -d
    foreach ($p in $knownLaunchers) {
        if ($disabled -contains "package:$p") {
            Tv-Shell pm enable --user 0 $p | Out-Null
            Write-Host "enabled $p"
        }
    }
    Step "Giving the TV's own screen saver back"
    Tv-Shell settings delete secure screensaver_components | Out-Null
    Tv-Shell settings delete secure screensaver_activate_on_sleep | Out-Null
    Tv-Shell settings put secure screensaver_enabled 1 | Out-Null
    Write-Host "screen saver on"
    Write-Host ""; Write-Host "Done. Press Home on the remote and pick the launcher you want."
    exit 0
}

if (-not $Apk) {
    $candidates = @(Get-ChildItem -Path . -Filter "portal*.apk" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }) +
        @("app\build\outputs\apk\release\app-release.apk")
    foreach ($f in $candidates) { if (Test-Path $f) { $Apk = $f; break } }
}
if (-not $Apk) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Fail "no APK found. Pass one with -Apk, build one (.\gradlew.bat assembleRelease), or install the GitHub CLI (gh) to fetch the latest release."
    }
    Step "Downloading the latest release"
    $dir = Join-Path ([System.IO.Path]::GetTempPath()) ("portal-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $dir | Out-Null
    & gh release download --repo plecos/portaltv --pattern "portal-*.apk" --dir $dir
    if ($LASTEXITCODE -ne 0) { Fail "download failed (is gh signed in?)" }
    $Apk = (Get-ChildItem -Path $dir -Filter "portal-*.apk" | Select-Object -First 1).FullName
}
if (-not (Test-Path $Apk)) { Fail "APK not found: $Apk" }

Step "Installing $Apk"
$old = $ErrorActionPreference; $ErrorActionPreference = "Continue"
$out = (& adb -s $Tv install -r $Apk 2>&1 | ForEach-Object { "$_" }) -join "`n"
$ErrorActionPreference = $old
Write-Host $out
if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
    Fail "this APK is signed with a different key than the Portal on the TV (debug vs release). Run 'adb -s $Tv uninstall com.portal.tv' (this erases Portal's data) and try again."
}
if ($out -notmatch "Success") { Fail "install failed" }

if (-not $KeepLauncher) {
    Step "Making Portal the home screen"
    # Disabling one launcher can reveal another that also answers Home, so go round until none are left.
    for ($i = 0; $i -lt 5; $i++) {
        $todo = @(Home-Packages | Where-Object { $keepPackages -notcontains $_ })
        if ($todo.Count -eq 0) { break }
        foreach ($p in $todo) {
            Tv-Shell pm disable-user --user 0 $p | Out-Null
            Write-Host "disabled $p (undo: scripts\install.ps1 -Restore $Tv)"
        }
    }
    Tv-Shell cmd role add-role-holder --user 0 android.app.role.HOME com.portal.tv | Out-Null
    Write-Host ("home app: " + (Tv-Shell cmd role get-role-holders --user 0 android.app.role.HOME))
}

if (-not $KeepScreensaver) {
    # The TV's own ambient mode would draw over Portal's screen saver, so Portal takes its place. The TV then starts
    # it over any app that has been idle for the TV's own screen saver time.
    Step "Making Portal the TV's screen saver"
    Tv-Shell settings put secure screensaver_components com.portal.tv/com.portal.tv.screensaver.ScreensaverService | Out-Null
    Tv-Shell settings put secure screensaver_enabled 1 | Out-Null
    Tv-Shell settings put secure screensaver_activate_on_sleep 1 | Out-Null
    Write-Host ("screen saver: " + (Tv-Shell settings get secure screensaver_components))
    Write-Host "(Settings, Screen saver, Also over other apps turns this on or off.)"
}

Step "Starting Portal"
Tv-Shell am start -n com.portal.tv/.PortalActivity | Out-Null
Write-Host "started"

Write-Host ""; Write-Host "Done. Portal asks you to create a profile the first time it runs."
