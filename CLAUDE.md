# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Portal is a home-screen launcher for Android TV / Google TV (package `com.portal.tv`, minSdk 34, Kotlin). It is a multi-module Gradle project: `:app` (Portal itself), `:weather-core` and `:weather-extension` (weather, built as an extension), `:sample-extension` (a complete example) and `:conformance-extension` (a deliberately misbehaving extension that Portal must refuse). `README.md` covers install and user-facing features; the docs below cover extensions and the catalog in depth.

## Commands

```bash
./gradlew assembleDebug installDebug                       # debug build onto a connected device
./gradlew testDebugUnitTest lintDebug                      # what a PR must pass (CI also lints :sample-extension and :conformance-extension)
./gradlew :app:testDebugUnitTest --tests '*CatalogTest*'  # one test class (filters matching nothing in a module are errors, so name the module)
./gradlew :weather-core:test :weather-extension:testDebugUnitTest :weather-extension:lintDebug   # weather modules
scripts/conformance.sh                                     # extension-host conformance on an emulator/TV via adb (wipes Portal's data: emulator only)
scripts/validate-catalog.sh path/to/catalog.json           # check a catalog with the same parser Portal uses
```

- Gradle's own JVM is pinned to Java 25 (`gradle/gradle-daemon-jvm.properties`, auto-downloaded); the app compiles for Java 11.
- `local.properties` (git-ignored) holds release signing and optional Google sign-in credentials; without signing values the release build is unsigned.
- Commits need a `Signed-off-by:` line (`git commit -s`) per CONTRIBUTING.md.
- Version is automatic: `VERSION_MAJOR_MINOR` in `gradle.properties` + commit count; `versionCode` = commit count + `VERSION_CODE_OFFSET`. Don't hand-edit per release.

## Architecture

- **Extensions are the big subsystem** (`app/src/main/java/com/portal/tv/extensions`). An extension is a separate APK that declares itself in an XML manifest and talks to Portal through one `ContentProvider`; Portal never loads its code, draws everything itself, and nothing is allowed until an adult grants each capability, tied to the app's signing certificate. Read `docs/EXTENSIONS-ARCHITECTURE.md` before changing trust, signing or the host API; the author-facing contract is `docs/EXTENSIONS.md`.
- **Weather is itself an extension** (`net.getportaltv.weather`, "Portal TV Weather"), bundled in Portal's assets and installed at first run, with `weather-core` holding the model/protocol shared with any substitute source or page. There is no built-in weather fallback.
- **Catalog**: Portal reads a single `catalog.json` + `catalog.json.sig` (Ed25519) from `getportaltv.net/extensions/`. Entries come from the separate repo `plecos/portaltv-extensions` (one file per extension); this repo only hosts the signed output in `site/extensions/`. Format and rules: `docs/CATALOG.md`.
- Other packages under `com.portal.tv`: `ui`, `search` (AI + web search), `kids` (time limits/reminders), `screensaver`, `background` (video), `repo`, `settings`, `reminders`, `auth` (Google device-flow sign-in), `adb` (Portal configuring the TV through its own debug service), `update`.

## Catalog and signing gotchas

- **Publishing a catalog** is manual and offline; the private key (`~/portal-catalog-keys/`) never goes in CI or a repo. Flow, from the `portaltv-extensions` clone: `python3 scripts/build-catalog.py build/catalog.json`, `java ../portaltv/scripts/CatalogSign.java stamp ... --serial <live+1>`, `validate-catalog.sh`, `CatalogSign.java sign <private> portal-catalog-1 ...`, then copy `catalog.json` and `.sig` into `site/extensions/` and open a PR here. It expires after 180 days (max 365); republish before then.
- **After publishing a new serial, raise `CatalogPolicy.MIN_SERIAL`** (and the number in `docs/CATALOG.md`) to the live serial before releasing Portal; the release workflow refuses to run while it is below the live catalog's serial.
- **Reserved names**: package names/names that could pass for Portal's own (`net.getportaltv.*`, "Portal …") are refused unless signed by Portal's key. There are two separate allow-lists that must agree: `docs/catalog/first-party-signers.txt` (only affects catalog PR checks) and `CatalogPolicy.FIRST_PARTY_SIGNERS` (the on-TV check in `ExtensionRegistry.describe`; a test keeps them in step). A first-party extension signed by a non-Portal key (Scenic Travel, signed by Google Play) needs both, and a Portal release before it works on a TV.
- **Releases** are manual (Actions → Release → Run workflow on `main`; merging never releases) and the weather extension has its own workflow (`release-weather.yml`, tags `weather-v*`). Catalog entries link to specific release APKs with checksums, so never delete a release a catalog entry points to (currently `v2.1.135` and every `weather-v*` the catalog lists, e.g. `weather-v2.1.184`). Portal's updater likely reads the latest release.

## Publishing a weather extension update (the full sequence)

Do these in order; each step waits on the one before. Releases, the signing key and merges are the user's call: ask before running a workflow or signing.

1. **Merge the code to `main`** (PR here). Merging releases nothing.
2. **Release the weather extension first**: Actions → "Release weather extension" → Run workflow on `main` (`gh workflow run release-weather.yml` only if the user says so). It tests, lints, signs with Portal's key and publishes tag `weather-v2.1.N` with `portal-weather-2.1.N.apk` (N = commit count; versionCode is a separate larger number). Release the weather *before* Portal so Portal's release stays the newest ("latest").
3. **Verify the APK** (download it into the scratchpad, never the repo): `gh release download weather-vX -p '*.apk'`, then `shasum -a 256`, `stat -f %z`, `~/Library/Android/sdk/build-tools/36.0.0/aapt2 dump badging` (versionCode/versionName) and `apksigner verify --print-certs` (signer SHA-256 must be `5362c877…51ca`, the `signer` in the catalog entry).
4. **Add the version to the catalog entry** in the `portaltv-extensions` clone (`~/Source/portaltv-extensions`): append an item to `versions` in `entries/net.getportaltv.weather.json` (`version_code`, `version_name`, `apk` URL, `sha256`, `size`, `contract: 1`; keep old versions). Branch, PR, user merges, then `git pull` on `main`.
5. **Build, stamp, validate, sign** from that clone (see the catalog flow above; serial = live + 1; get the live serial from `site/extensions/catalog.json` here). Signing uses `~/portal-catalog-keys/portal-catalog.private` with key id `portal-catalog-1`: ask the user first.
6. **PR in this repo** with `catalog.json` + `catalog.json.sig` copied into `site/extensions/`, `CatalogPolicy.MIN_SERIAL` raised to the new serial, and the serial number in `docs/CATALOG.md` ("it is N now") updated. `SignersAndPolicyTest` takes its serial from `MIN_SERIAL`, so it needs no edit. Run `./gradlew testDebugUnitTest lintDebug`. The user merges it.
7. **Wait for the "Website" workflow** (`gh run list --workflow pages.yml`), then check `https://getportaltv.net/extensions/catalog.json` serves the new serial and equals `site/extensions/` on `main` (`curl` into the scratchpad, `cmp`, `scripts/validate-catalog.sh`).
8. **Release Portal last** (Actions → Release → Run workflow on `main`). It bundles the current weather extension for new installs and refuses to run while `MIN_SERIAL` is below the live serial. Existing TVs get the weather update from the catalog, not from the Portal release.

Testing on the real TV (`192.168.10.11:5555`): `adb connect` first; if it says "No route to host" while `nc -z 192.168.10.11 5555` works, `adb kill-server` and retry from the same shell. Install the signed release APK with `adb install -r` (never uninstall: it wipes data), then confirm `pm path com.portal.tv` still resolves. Give it a minute after an install before rebooting: once a reboot soon after an install left Portal uninstalled (cause unknown), and the first boot after an install is slow.

## Working notes

- There is no installed base yet, so skip migrations and backward-compatibility work.
- Never install debug builds on the user's real TV; use the emulator.
- Real TV ip address is 192.168.10.11 with adb port 5555
