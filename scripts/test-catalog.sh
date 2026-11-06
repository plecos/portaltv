#!/usr/bin/env bash
# Serves a test catalog (docs/catalog/test) from this computer and points a debug build of Portal at it, so Settings,
# Extensions, Browse has something to show before the real catalog is online. Ctrl-C stops the server.
#
# The sample extension's entry points at its real debug APK, served from here with its real checksum, size and signer,
# so Install can be tried end to end (uninstall the sample first: adb uninstall net.getportaltv.sample). Two more
# entries point at the same file with a wrong checksum and a wrong package, which Portal must refuse.
# Build the APK first with: ./gradlew :sample-extension:assembleDebug
#
#   scripts/test-catalog.sh                 # an emulator (it reaches this computer at 10.0.2.2)
#   HOST=192.168.1.20 scripts/test-catalog.sh   # a real TV: this computer's address on your network
#   ANDROID_SERIAL=emulator-5600 scripts/test-catalog.sh   # pick a device when several are connected
#   MANY=200 scripts/test-catalog.sh        # also list 200 made-up extensions, to see a big catalog
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
adb shell "run-as com.portal.tv rm -f shared_prefs/extension_catalog.xml files/extension_catalog.json"   # no stale copy from another catalog
adb shell am force-stop com.portal.tv   # Portal is the home app, so it comes straight back and reads the new address
echo "Portal now reads http://$HOST:$PORT/catalog.json (debug builds only)."
echo "Open Settings, Extensions, Browse extensions. Ctrl-C stops the server."
SERVE="$(mktemp -d)"
cp -R docs/catalog/test/. "$SERVE/"
APK=sample-extension/build/outputs/apk/debug/sample-extension-debug.apk
if [ -f "$APK" ]; then
  cp "$APK" "$SERVE/sample.apk"
  SDK="$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)"
  AAPT2="$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | tail -1)"
  CODE="$("$AAPT2" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
  NAME="$("$AAPT2" dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
  APKSIGNER="$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | tail -1)"
  SIGNER="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: *//p' | head -1)"
  python3 - "$SERVE" "$HOST:$PORT" "${CODE:-1}" "${NAME:-1.0}" "$SIGNER" <<'PY'
import json, sys, hashlib, os, copy
d, hostport, code, name, signer = sys.argv[1:6]
apk = os.path.join(d, "sample.apk")
data = open(apk, "rb").read()
version = {"version_code": int(code), "version_name": name, "apk": f"http://{hostport}/sample.apk",
           "sha256": hashlib.sha256(data).hexdigest(), "size": len(data), "contract": 1}
cat = json.load(open(os.path.join(d, "catalog.json")))
sample = next(e for e in cat["extensions"] if e["package"] == "net.getportaltv.sample")
sample["signer"] = signer
sample["versions"] = [version]
for pkg, label, change in (("net.example.wrongsum", "Wrong checksum", {"sha256": "ee" * 32}), ("net.example.wrongpkg", "Wrong package", {})):
    e = copy.deepcopy(sample)
    e.update({"package": pkg, "name": label, "description": "Points at the sample file but must be refused.", "icon": "icons/garage.png"})
    e["versions"] = [dict(version, **change)]
    cat["extensions"].append(e)
json.dump(cat, open(os.path.join(d, "catalog.json"), "w"))
PY
  echo "The sample extension can be installed from Browse (version ${CODE:-1}, signer ${SIGNER:0:16}...)."
else
  echo "No sample APK yet, so Install can't be tried. Build it: ./gradlew :sample-extension:assembleDebug"
fi
if [ -n "${MANY:-}" ]; then
  python3 - "$SERVE" "$MANY" <<'PY'
import json, sys, os, random
d, n = sys.argv[1], int(sys.argv[2])
random.seed(7)
cat = json.load(open(os.path.join(d, "catalog.json")))
tags = ["home", "kids", "weather", "photos", "music", "sports", "news", "reminders", "background", "family", "garage", "lights", "calendar", "clock"]
words = ["Garden", "Clock", "Radar", "Lights", "Doorbell", "Photo", "Calendar", "Chores", "Recipes", "Trivia", "Sports", "Traffic", "Stocks", "Moon", "Tides", "Pollen", "Plants", "Pets", "Cameras", "Thermostat"]
icons = ["icons/garage.png", "icons/photos.png", "icons/portal-sample.png"]
for i in range(n):
    name = f"{random.choice(words)} {random.choice(['Plus', 'Lite', 'Pro', 'Board', 'Panel', 'Widget'])} {i + 1}"
    cat["extensions"].append({
        "package": f"net.example.fake{i:03d}", "name": name, "description": f"{name} for your TV. A made-up extension to fill the list.",
        "author": random.choice(["Example Co", "A. Person", "Home Labs", "Jo Smith"]), "source": "https://github.com/example/fake",
        "license": "MIT", "capabilities": random.sample(["tiles", "bar", "reminders", "background"], 2), "signer": "ab" * 32,
        "tags": random.sample(tags, 3), "icon": random.choice(icons),
        "versions": [{"version_code": 1, "version_name": "1.0", "apk": "https://example.com/fake.apk", "sha256": "cd" * 32, "size": 1000000, "contract": 1}]})
json.dump(cat, open(os.path.join(d, "catalog.json"), "w"))
PY
  echo "Added $MANY made-up extensions."
fi
cd "$SERVE" && exec python3 -m http.server "$PORT" --bind 0.0.0.0
