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
- **Releases** are manual (Actions → Release → Run workflow on `main`; merging never releases) and the weather extension has its own workflow (`release-weather.yml`, tags `weather-v*`). Catalog entries link to specific release APKs with checksums, so never delete a release a catalog entry points to (currently `v2.1.135` and `weather-v2.1.158`). Portal's updater likely reads the latest release.

## Working notes

- There is no installed base yet, so skip migrations and backward-compatibility work.
- Never install debug builds on the user's real TV; use the emulator.
