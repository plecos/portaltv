#!/usr/bin/env bash
# Checks an extension's package name and names against the rules Portal applies, with the same code Portal runs, so you
# find out before installing it on a TV whether Portal would refuse it ("Can't be used").
#
# Usage: scripts/check-extension.sh <package.name> <path/to/res/xml/portal_extension.xml> [path/to/res/values/strings.xml]
#   Pass strings.xml when the extension file uses @string/... for its name or labels, so the real words are checked.
# Exit status 0: Portal would accept the names. 1: it would refuse them, and each reason is printed.
set -uo pipefail
{ [ $# -ge 2 ] && [ -f "$2" ] && { [ $# -lt 3 ] || [ -f "$3" ]; }; } || { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
abs() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
# Full paths are worked out before moving to the repository folder, so a relative path means what the caller meant.
XML_PATH="$(abs "$2")"
STRINGS_PATH=""; [ $# -ge 3 ] && STRINGS_PATH="$(abs "$3")"
cd "$(dirname "$0")/.."
OUT="$(mktemp)"
ENVS=(PORTAL_CHECK_PACKAGE="$1" PORTAL_CHECK_XML="$XML_PATH" PORTAL_CHECK_OUT="$OUT")
[ $# -ge 3 ] && ENVS+=(PORTAL_CHECK_STRINGS="$STRINGS_PATH")
LOG="$(mktemp)"
env "${ENVS[@]}" ./gradlew :app:testDebugUnitTest --tests 'com.portal.tv.extensions.ExtensionFileTest.checksTheExtensionNamedByTheEnvironment' --rerun -q >"$LOG" 2>&1
STATUS=$?
if [ -s "$OUT" ]; then
  echo "Portal would refuse this extension:"; sed 's/^/  - /' "$OUT"; rm -f "$OUT"; exit 1
fi
rm -f "$OUT"
# A failure with nothing reported means the check itself didn't run (a build error), which is not "ok".
if [ $STATUS -ne 0 ]; then echo "The check could not run:"; tail -20 "$LOG"; rm -f "$LOG"; exit 3; fi
rm -f "$LOG"
echo "ok: Portal would accept the package name and names of $1"
