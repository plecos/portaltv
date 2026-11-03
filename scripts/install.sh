#!/usr/bin/env bash
# Installs Portal on an Android TV / Google TV over adb and sets the TV up to use it:
#   1. connects to the TV
#   2. installs the APK (yours, or the latest GitHub release)
#   3. makes Portal the home screen (turns off the stock launcher, gives Portal the HOME role)
#   4. makes Portal the TV's screen saver in place of its ambient mode, so it also starts over other apps
#   5. starts Portal
#
# Usage: scripts/install.sh [options] <tv-ip>[:port]
#   --apk FILE           APK to install. Default: portal*.apk in the current folder, then the release
#                        build in app/build/outputs/apk/release, then the latest GitHub release (needs gh).
#   --keep-launcher      leave the stock launcher alone
#   --keep-screensaver   leave the TV's screen saver settings alone
#   --restore            undo steps 3 and 4: re-enable the stock launcher and give back the TV's own screen saver
#   -h, --help           show this help
#
# Every step is safe to repeat. Needs adb (Android SDK platform-tools) on your PATH.

set -u

PORT=5555
APK=""
KEEP_LAUNCHER=0
KEEP_SCREENSAVER=0
RESTORE=0
TV=""

usage() { sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "error: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) [ $# -ge 2 ] || die "--apk needs a file"; APK="$2"; shift 2 ;;
    --keep-launcher) KEEP_LAUNCHER=1; shift ;;
    --keep-screensaver) KEEP_SCREENSAVER=1; shift ;;
    --restore) RESTORE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown option $1 (try --help)" ;;
    *) TV="$1"; shift ;;
  esac
done

[ -n "$TV" ] || { usage; exit 1; }
case "$TV" in *:*) ;; *) TV="$TV:$PORT" ;; esac

command -v adb >/dev/null 2>&1 || die "adb not found. Install the Android SDK platform-tools and put them on your PATH."

# Newer adb builds can fail to connect through mDNS; use the classic path.
export ADB_MDNS_OPENSCREEN=0

# Runs a command on the TV; keeps adb's exit status and strips its carriage returns.
tv() {
  local out rc
  out="$(adb -s "$TV" shell "$@")"; rc=$?
  [ -z "$out" ] || printf '%s\n' "$out" | tr -d '\r'
  return $rc
}

# Launchers that must stay enabled: Portal, and the Settings app's fallback home.
KEEP_PKGS="com.portal.tv com.android.tv.settings android"
# Where the stock launchers have been seen, for --restore.
KNOWN_LAUNCHERS="com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.google.android.tvlauncher com.google.android.leanbacklauncher"

home_packages() {
  tv cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME \
    | awk -F'[ /]+' '/^ +[a-zA-Z0-9_.]+\// { print $2 }' | sort -u
}

step "Connecting to $TV"
adb connect "$TV" | tail -1
adb -s "$TV" get-state >/dev/null 2>&1 || die "can't reach the TV. Turn on USB debugging and network debugging in its Developer options, accept the prompt on the TV, and check the address."

if [ "$RESTORE" -eq 1 ]; then
  step "Re-enabling the stock launcher"
  for p in $KNOWN_LAUNCHERS; do
    if tv pm list packages -d | grep -q "^package:$p\$"; then
      tv pm enable --user 0 "$p" >/dev/null && echo "enabled $p"
    fi
  done
  step "Giving the TV's own screen saver back"
  tv settings delete secure screensaver_components >/dev/null
  tv settings delete secure screensaver_activate_on_sleep >/dev/null
  tv settings put secure screensaver_enabled 1
  echo "screen saver on"
  echo; echo "Done. Press Home on the remote and pick the launcher you want."
  exit 0
fi

if [ -z "$APK" ]; then
  for f in ./portal*.apk app/build/outputs/apk/release/app-release.apk; do
    [ -f "$f" ] && { APK="$f"; break; }
  done
fi
if [ -z "$APK" ]; then
  command -v gh >/dev/null 2>&1 || die "no APK found. Pass one with --apk, build one (./gradlew assembleRelease), or install the GitHub CLI (gh) to fetch the latest release."
  step "Downloading the latest release"
  dir="$(mktemp -d)"
  gh release download --repo plecos/portaltv --pattern 'portal-*.apk' --dir "$dir" || die "download failed (is gh signed in?)"
  APK="$(ls "$dir"/portal-*.apk | head -1)"
fi
[ -f "$APK" ] || die "APK not found: $APK"

step "Installing $APK"
out="$(adb -s "$TV" install -r "$APK" 2>&1)"; echo "$out" | tail -3
case "$out" in
  *Success*) ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    die "this APK is signed with a different key than the Portal on the TV (debug vs release). Run 'adb -s $TV uninstall com.portal.tv' (this erases Portal's data) and try again." ;;
  *) die "install failed" ;;
esac

if [ "$KEEP_LAUNCHER" -eq 0 ]; then
  step "Making Portal the home screen"
  # Disabling one launcher can reveal another that also answers Home, so go round until none are left.
  for _ in 1 2 3 4 5; do
    todo=""
    for p in $(home_packages); do
      case " $KEEP_PKGS " in *" $p "*) ;; *) todo="$todo $p" ;; esac
    done
    [ -n "$todo" ] || break
    for p in $todo; do
      tv pm disable-user --user 0 "$p" >/dev/null && echo "disabled $p (undo: scripts/install.sh --restore $TV)"
    done
  done
  tv cmd role add-role-holder --user 0 android.app.role.HOME com.portal.tv >/dev/null 2>&1
  echo "home app: $(tv cmd role get-role-holders --user 0 android.app.role.HOME)"
fi

if [ "$KEEP_SCREENSAVER" -eq 0 ]; then
  # The TV's own ambient mode would draw over Portal's screen saver, so Portal takes its place. The TV then starts it
  # over any app that has been idle for the TV's own screen saver time.
  step "Making Portal the TV's screen saver"
  tv settings put secure screensaver_components com.portal.tv/com.portal.tv.screensaver.ScreensaverService
  tv settings put secure screensaver_enabled 1
  tv settings put secure screensaver_activate_on_sleep 1
  echo "screen saver: $(tv settings get secure screensaver_components)"
  echo "(Settings, Screen saver, Also over other apps turns this on or off.)"
fi

step "Starting Portal"
tv am start -n com.portal.tv/.PortalActivity >/dev/null && echo "started"

echo; echo "Done. Portal asks you to create a profile the first time it runs."
