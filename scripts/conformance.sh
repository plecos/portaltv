#!/usr/bin/env bash
# Conformance test for Portal's extension host API, on a running emulator or TV (adb).
#
# It installs Portal and a deliberately misbehaving extension (conformance-extension), sets up what an adult would have
# allowed, and checks that every call it shouldn't be able to make is refused, from the point of view of a real caller
# going through Android's own IPC: other apps' reminders, Portal's own buttons, spoofed names, bad and oversized values,
# flooding, a kid profile, a changed signer, and cleanup on uninstall.
#
#   ./gradlew assembleDebug :conformance-extension:assembleDebug :sample-extension:assembleDebug
#   scripts/conformance.sh [portal.apk [extension.apk [sample.apk]]]
#
# Needs: adb, and apksigner (Android build-tools) to read the extension's signer. Uses ANDROID_SERIAL if several devices are
# attached. It wipes Portal's data on that device (pm clear), so use an emulator. Exits non-zero if anything fails.
set -u

PORTAL_APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
ROGUE_APK="${2:-conformance-extension/build/outputs/apk/debug/conformance-extension-debug.apk}"
SAMPLE_APK="${3:-sample-extension/build/outputs/apk/debug/sample-extension-debug.apk}"
ADB="${ADB:-adb}"
PORTAL=com.portal.tv
ROGUE=net.getportaltv.rogue
SAMPLE=net.getportaltv.sample   # the extension whose reminders the misbehaving one must not touch
TAG=PortalConformance
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILS=0
CHECKS=0

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
APKSIGNER="$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
[ -x "$APKSIGNER" ] || { echo "apksigner not found under $SDK/build-tools"; exit 2; }
[ -f "$PORTAL_APK" ] && [ -f "$ROGUE_APK" ] && [ -f "$SAMPLE_APK" ] || { echo "Build the apks first (see the top of this file)."; exit 2; }

adb_() { "$ADB" "$@"; }
signer_of() { "$APKSIGNER" verify --print-certs "$1" 2>/dev/null | grep -i 'certificate SHA-256 digest' | head -1 | awk '{print $NF}' | tr 'A-F' 'a-f'; }
SIGNER="$(signer_of "$ROGUE_APK")"
SAMPLE_SIGNER="$(signer_of "$SAMPLE_APK")"
[ ${#SIGNER} -eq 64 ] && [ ${#SAMPLE_SIGNER} -eq 64 ] || { echo "couldn't read the extensions' signers"; exit 2; }

fail() { echo "  FAIL  $*"; FAILS=$((FAILS + 1)); }
pass() { echo "  pass  $*"; }

# ---------- what an adult would have set up ----------

AT=$(( $(date +%s) * 1000 + 86400000 ))

put_prefs() {   # name, xml body
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n%s\n</map>\n" "$2" > "$WORK/$1.xml"
  adb_ push "$WORK/$1.xml" "/data/local/tmp/$1.xml" >/dev/null
  adb_ shell run-as $PORTAL cp "/data/local/tmp/$1.xml" "shared_prefs/$1.xml"
}

seed() {   # active profile id, signer to record, capabilities (comma separated, or empty for none allowed)
  local profile="$1" signer="$2" caps="$3"
  adb_ shell run-as $PORTAL mkdir -p shared_prefs
  # Android keeps a .bak next to a preferences file while writing it and puts it back over the file if it finds one at
  # start-up, so they go too or the old state would come back.
  adb_ shell "run-as $PORTAL sh -c 'rm -f shared_prefs/portal_extensions.xml* shared_prefs/portal_extension_items.xml* shared_prefs/portal_reminders.xml* shared_prefs/portal_profiles.xml*'"
  put_prefs portal_profiles '<int name="active_profile_id" value="'"$profile"'" />
<string name="profiles">[{&quot;id&quot;:1,&quot;name&quot;:&quot;Adult&quot;,&quot;color&quot;:0,&quot;type&quot;:&quot;app&quot;,&quot;email&quot;:&quot;&quot;,&quot;photo&quot;:&quot;&quot;,&quot;avatar&quot;:&quot;sun&quot;,&quot;kids&quot;:false,&quot;allowed&quot;:[]},{&quot;id&quot;:2,&quot;name&quot;:&quot;Kid&quot;,&quot;color&quot;:1,&quot;type&quot;:&quot;app&quot;,&quot;email&quot;:&quot;&quot;,&quot;photo&quot;:&quot;&quot;,&quot;avatar&quot;:&quot;turtle&quot;,&quot;kids&quot;:true,&quot;allowed&quot;:[]}]</string>
<int name="next_id" value="3" />'
  # 900 is Portal'"'"'s own reminder and 901 was made by the sample extension (which is allowed reminders, or Portal would
  # tidy it away): the misbehaving extension must not be able to touch either.
  put_prefs portal_reminders '<string name="reminders">[{&quot;id&quot;:900,&quot;profile&quot;:1,&quot;title&quot;:&quot;Portal own&quot;,&quot;at&quot;:'"$AT"',&quot;repeat&quot;:&quot;NONE&quot;},{&quot;id&quot;:901,&quot;profile&quot;:1,&quot;title&quot;:&quot;Other extension&quot;,&quot;at&quot;:'"$AT"',&quot;repeat&quot;:&quot;NONE&quot;,&quot;source&quot;:&quot;'"$SAMPLE"'&quot;}]</string>
<int name="next_id" value="1000" />'
  if [ -n "$caps" ]; then
    local list; list="$(echo "$caps" | sed 's/\([a-z]*\)/\&quot;\1\&quot;/g')"
    put_prefs portal_extensions '<string name="allowed">[{&quot;package&quot;:&quot;'$ROGUE'&quot;,&quot;signer&quot;:&quot;'"$signer"'&quot;,&quot;capabilities&quot;:['"$list"']},{&quot;package&quot;:&quot;'$SAMPLE'&quot;,&quot;signer&quot;:&quot;'"$SAMPLE_SIGNER"'&quot;,&quot;capabilities&quot;:[&quot;reminders&quot;]}]</string>'
  fi
  # Preferences are read once when Portal starts, so it is restarted to read these. It is the home app, so Android starts
  # it again by itself a moment after it is stopped: the files had to be written first.
  adb_ shell am force-stop $PORTAL
  local i
  for i in $(seq 1 20); do [ -n "$(adb_ shell pidof $PORTAL)" ] && break; sleep 1; done
  sleep 3
}

# ---------- running one suite of the misbehaving extension ----------

run_suite() {   # suite name, minimum number of checks it must report
  local suite="$1" min="$2"
  [ -n "${CONFORMANCE_VERBOSE:-}" ] && { echo "  (allowed before $suite:) $(adb_ shell run-as $PORTAL cat shared_prefs/portal_extensions.xml 2>&1 | grep allowed)"; }
  adb_ logcat -c
  adb_ shell am start -n $ROGUE/.RunActivity --es suite "$suite" >/dev/null
  local i
  for i in $(seq 1 60); do
    adb_ logcat -d -s $TAG:I | grep -q "CONFORMANCE DONE $suite" && break
    sleep 1
  done
  adb_ logcat -d -s $TAG:I | grep "CONFORMANCE" | grep -v "DONE" | sed 's/.*CONFORMANCE //' > "$WORK/out.txt"
  local n=0 line
  while IFS= read -r line; do
    n=$((n + 1)); CHECKS=$((CHECKS + 1))
    case "$line" in
      *" PASS"*) pass "$line" ;;
      *) fail "$line" ;;
    esac
  done < "$WORK/out.txt"
  if [ "$n" -lt "$min" ]; then fail "suite $suite reported $n checks, expected at least $min (did it crash or time out?)"; fi
}

prefs_of() { adb_ shell run-as $PORTAL cat "shared_prefs/$1.xml" 2>/dev/null; }

# ---------- go ----------

echo "== installing"
adb_ wait-for-device
adb_ shell pm clear $PORTAL >/dev/null 2>&1
adb_ install -r -g "$PORTAL_APK" >/dev/null || { echo "couldn't install Portal"; exit 2; }
adb_ uninstall $ROGUE >/dev/null 2>&1
adb_ install -r "$SAMPLE_APK" >/dev/null || { echo "couldn't install the sample extension"; exit 2; }
adb_ install -r "$ROGUE_APK" >/dev/null || { echo "couldn't install the extension"; exit 2; }

echo "== nothing allowed: every call is refused"
seed 1 "$SIGNER" ""
run_suite refused 7

echo "== an app that isn't an extension (the shell) can't call at all"
for method in host.info reminders.add card.show items.set; do
  CHECKS=$((CHECKS + 1))
  out="$(adb_ shell content call --uri content://net.getportaltv.host --method $method --extra title:s:x --extra at_millis:l:$AT 2>&1)"
  case "$out" in *not_allowed*) pass "shell: $method refused" ;; *) fail "shell: $method got: $out" ;; esac
done

echo "== allowed, but signed by someone else now: every call is refused"
seed 1 "0000000000000000000000000000000000000000000000000000000000000000" "reminders,cards,bar,clock"
run_suite refused 7

echo "== allowed, but a kid profile is in use"
seed 2 "$SIGNER" "reminders,cards,bar,clock"
run_suite kid 5

echo "== allowed: reminders"
seed 1 "$SIGNER" "reminders,cards,bar,clock"
run_suite reminders 14
state="$(prefs_of portal_reminders)"
CHECKS=$((CHECKS + 3))
case "$state" in *"Portal own"*) pass "Portal's own reminder is untouched" ;; *) fail "Portal's own reminder changed or went" ;; esac
case "$state" in *"Other extension"*) pass "another extension's reminder is untouched" ;; *) fail "another extension's reminder changed or went" ;; esac
# The spoof attempt claimed to be the sample extension: it must not have gained a reminder of its own.
theirs="$(echo "$state" | grep -o "source&quot;:&quot;$SAMPLE" | wc -l | tr -d ' ')"
CHECKS=$((CHECKS + 1))
[ "$theirs" = "1" ] && pass "the sample extension still has only its own reminder (no spoofed source)" || fail "the sample extension has $theirs reminders, expected 1: a caller named its own source"
others="$(echo "$state" | grep -o 'source&quot;:&quot;[^&]*' | sort -u | grep -v "$ROGUE" | grep -v "$SAMPLE")"
[ -z "$others" ] && pass "every reminder it made is its own (no spoofed source)" || fail "a reminder has a source it shouldn't: $others"

echo "== allowed: bar and clock items"
seed 1 "$SIGNER" "reminders,cards,bar,clock"
run_suite items 14

echo "== allowed: cards"
seed 1 "$SIGNER" "reminders,cards,bar,clock"
run_suite cards 3

echo "== allowed: a flood of changes"
seed 1 "$SIGNER" "reminders,cards,bar,clock"
run_suite flood 2

echo "== uninstalling the extension removes what it made and what was allowed for it"
seed 1 "$SIGNER" "reminders,cards,bar,clock"
run_suite reminders 14 >/dev/null
adb_ uninstall $ROGUE >/dev/null
sleep 4
CHECKS=$((CHECKS + 2))
prefs_of portal_reminders | grep -q "$ROGUE" && fail "its reminders are still there after uninstall" || pass "its reminders are gone after uninstall"
prefs_of portal_extensions | grep -q "$ROGUE" && fail "what was allowed for it is still there after uninstall" || pass "what was allowed for it is gone after uninstall"

echo
echo "$CHECKS checks, $FAILS failed"
# A failed run keeps the device's log next to where it was run from, for working out why.
[ "$FAILS" -eq 0 ] || adb_ logcat -d > conformance-logcat.txt 2>/dev/null
[ "$FAILS" -eq 0 ]
