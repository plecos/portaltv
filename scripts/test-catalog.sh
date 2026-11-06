#!/usr/bin/env bash
# Serves a test catalog (docs/catalog/test) from this computer and points a debug build of Portal at it, so Settings,
# Extensions, Browse has something to show before the real catalog is online. Ctrl-C stops the server.
#
#   scripts/test-catalog.sh                 # an emulator (it reaches this computer at 10.0.2.2)
#   HOST=192.168.1.20 scripts/test-catalog.sh   # a real TV: this computer's address on your network
#   ANDROID_SERIAL=emulator-5600 scripts/test-catalog.sh   # pick a device when several are connected
#   scripts/test-catalog.sh --off           # forget the test address
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${PORT:-8765}"
HOST="${HOST:-10.0.2.2}"
PREFS=shared_prefs/catalog_debug.xml

if [ "${1:-}" = "--off" ]; then
  adb shell "run-as com.portal.tv rm -f $PREFS shared_prefs/extension_catalog.xml files/extension_catalog.json"
  adb shell am force-stop com.portal.tv
  echo "Test address removed."; exit 0
fi

adb shell "run-as com.portal.tv sh -c 'mkdir -p shared_prefs && cat > $PREFS'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="url">http://$HOST:$PORT/catalog.json</string></map>
XML
adb shell am force-stop com.portal.tv   # Portal is the home app, so it comes straight back and reads the new address
echo "Portal now reads http://$HOST:$PORT/catalog.json (debug builds only)."
echo "Open Settings, Extensions, Browse extensions. Serving docs/catalog/test; Ctrl-C to stop."
cd docs/catalog/test && exec python3 -m http.server "$PORT" --bind 0.0.0.0
