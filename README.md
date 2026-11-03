# Portal

[![Release](https://img.shields.io/github/v/release/plecos/portaltv?label=release)](https://github.com/plecos/portaltv/releases/latest)
[![Release build](https://img.shields.io/github/actions/workflow/status/plecos/portaltv/release.yml?label=build)](https://github.com/plecos/portaltv/actions/workflows/release.yml)
[![Tests](https://img.shields.io/github/actions/workflow/status/plecos/portaltv/ci.yml?label=tests)](https://github.com/plecos/portaltv/actions/workflows/ci.yml)
[![License: PolyForm Noncommercial](https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue)](LICENSE)
[![Android 14+](https://img.shields.io/badge/Android-14%2B-3DDC84?logo=android&logoColor=white)](#quick-install)
[![Android TV and Google TV](https://img.shields.io/badge/platform-Android%20TV%20%7C%20Google%20TV-4285F4?logo=googletv&logoColor=white)](#quick-install)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)

<a href="https://buymeacoffee.com/portal.tv"><img src="https://img.buymeacoffee.com/button-api/?text=Buy%20me%20a%20coffee&emoji=%E2%98%95&slug=portal.tv&button_colour=FFDD00&font_colour=000000&font_family=Inter&outline_colour=000000&coffee_colour=ffffff" alt="Buy me a coffee" height="48"></a>

A home-screen launcher for Android TV and Google TV. It replaces the stock launcher with a clean home screen that has a live video background, the time and weather, your apps, per-person profiles, parental controls, a screen saver, and a search box that can find apps, chat with your own AI, and search the web.

Android 14 or newer (`minSdk 34`). Package `com.portal.tv`. Website: [getportaltv.net](https://getportaltv.net). If Portal is useful to you, you can [buy me a coffee](https://buymeacoffee.com/portal.tv).

> **Source-available, not open source.** Portal is free for personal and other noncommercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE). Commercial use needs a separate license: write to license@getportaltv.net. If you share it, modified or not, keep the notices in [NOTICE](NOTICE). See also [CONTRIBUTING](CONTRIBUTING.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

## Quick install

You need `adb` (part of the Android SDK platform-tools) and a way to reach your TV. Turn on Developer options on the TV (Settings, Device Preferences, About, press Build several times), then enable USB debugging and network debugging.

### From the TV itself

Once Portal is installed (for example from the release APK), its first-run setup can do the rest without a computer. It walks you through turning on Developer options and Wireless debugging, then connects to the TV's own debug service (the TV asks you once to allow it: choose Always allow) and does what the script below does: turns off the TV's own home screen, makes Portal the home app, allows alerts over other apps and voice search, and turns off the TV's ambient screen saver. You can turn Wireless debugging off afterwards. Settings, System, Restore the TV's own home screen undoes the launcher and screen saver changes. If your TV doesn't offer Wireless debugging (it needs Wi-Fi on some TVs), the setup falls back to asking for each permission, and the script below still does everything.

Basic TV mode: tested on a TCL Google TV (Android 14) that was factory reset and set up as a **Basic TV** instead of a Google TV. Developer options, Wireless debugging and installing Portal with `adb` all still worked, the setup above finished normally, and installing other apps and creating profiles worked afterwards. Other TVs may differ, so if Developer options or Wireless debugging is missing in Basic TV mode, switch the TV to Google TV mode, or use the script below from a computer.

### One script

[`scripts/install.sh`](scripts/install.sh) (macOS, Linux) and [`scripts/install.ps1`](scripts/install.ps1) (Windows PowerShell) do every step below in one go: connect, install, make Portal the home screen, turn off the TV's ambient screen saver, and start Portal. They are safe to run again.

```bash
scripts/install.sh 192.168.1.20
```

```powershell
.\scripts\install.ps1 192.168.1.20
```

With no APK given they use a `portal*.apk` in the current folder, then your release build, then download the latest GitHub release (this needs the [GitHub CLI](https://cli.github.com) installed and signed in with `gh auth login`). Options:

| Bash | PowerShell | |
|---|---|---|
| `--apk FILE` | `-Apk FILE` | install this APK |
| `--keep-launcher` | `-KeepLauncher` | leave the stock launcher alone |
| `--keep-screensaver` | `-KeepScreensaver` | leave the TV's screen saver alone |
| `--restore` | `-Restore` | undo the launcher and screen saver changes |

### By hand

**1. Connect to the TV.**

```bash
adb connect <tv-ip>:5555
```

If the connection fails on a recent adb, set `ADB_MDNS_OPENSCREEN=0` first.

**2. Install the APK.** Either use a prebuilt one or build it (see [Build](#build)):

```bash
adb install -r portal-1.0.0-build1-release.apk
```

**3. Make it the home screen.** Press Home on the remote and choose Portal if the TV asks. If the stock launcher still wins, list what answers Home and turn those off for your user:

```bash
adb shell cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell pm disable-user --user 0 <stock-launcher-package>
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.portal.tv
```

Repeat the `pm disable-user` for each package the first command lists, except `com.portal.tv` and `com.android.tv.settings` (its `FallbackHome` has to stay). Disabling one launcher can reveal another, so run the list again until only those two are left. On Google TV devices that is usually `com.google.android.apps.tv.launcherx` and `com.google.android.tungsten.setupwraith`.

To undo that later: `adb shell pm enable --user 0 <package>` for each one you disabled.

**4. Turn off the TV's own screen saver.** Google TV's ambient mode (`com.google.android.apps.tv.dreamx`) draws over Portal. Turn it off in the TV's settings (Settings, System, Ambient mode, start time Never; older Android TV: Settings, Device Preferences, Screen saver), or from adb:

```bash
adb shell settings put secure screensaver_enabled 0
adb shell settings put secure screensaver_activate_on_sleep 0
adb shell settings put secure screensaver_activate_on_dock 0
```

To turn it back on, set `screensaver_enabled` to `1`. Portal has its own screen saver (Settings, Screen saver) that plays your videos, and it can also be chosen as the TV's system screen saver.

**5. First run.** Portal asks you to create a profile (a plain Portal profile, or one linked to a Google account). That's all the setup it needs. Everything else is optional and lives in Settings.

> Updating: install a newer build with `adb install -r` (or run the script again). Your profiles and settings are kept as long as the new APK is signed with the same key.
>
> Switching between a debug build and a release build is different. They are signed with different keys, so Android refuses the install until you run `adb uninstall com.portal.tv`, and that erases the app's data.

## What it does

- **Home screen:** time and weather, your apps in tiles, icons, or a list, grouped into Frequently Used and All Apps. Hold select (or press Menu) on an app to hide it, move it, open its app info, or uninstall it. A fresh start of Portal, and choosing a profile, opens with a ring that draws itself and breathes while the home screen is built out of sight, then grows to reveal it with its background already playing (any key skips it). Opening an app flies its icon to the middle of the screen while the home screen dims and the name appears (it follows Android's animation setting).
- **Backgrounds:** video, an animated color shader (11 styles in 16 colors, with an optional slow color-cycling mode), or off. A speed setting slows or quickens the video and the shader. Pick one video, or Random for a different one each day. Special holiday videos play the day before and the day of Halloween, Thanksgiving, Christmas and New Year's (with several for one holiday, Portal rotates them or you pick one), and a birthday video plays for birthdays you add.
- **Profiles:** each person has their own apps view, usage history, background, screen saver, weather units and search settings. Profiles can be linked to a Google account (sign in with a QR code).
- **Kid profiles:** only the apps a parent allows, a small Settings with just the background, app layout and weather units, a parent PIN to leave the profile, a daily time limit and a bedtime, the time left shown over apps only now and then (when an app opens, each hour, at 30, 15 and 5 minutes, then a second-by-second countdown for the last minute), colored green over 30 minutes, yellow from 30 minutes to 1 minute, and red for the countdown, and a full-screen lock when time is up. The PIN is entered on a remote-friendly picker that doesn't show which digits you press.
- **Screen saver:** your videos fade through (or, with a shader background, that shader plays) while the time and weather drift around the screen. It starts after the idle time you choose, or you can pick Portal as the TV's system screen saver. It can dim or go dark at night.
- **Search:** find installed apps as you type, speak to it, or ask an AI. With tool calling the AI can open apps and search the web for you. The AI's replies can be read aloud with the TV's own voice: after a question you spoke (the default), always, or never. Each profile chooses, in Settings, Search.
- **Weather:** current conditions, 12 hours and 7 days, plus sunrise and sunset, UV, wind gusts, pressure, visibility, rain and air quality, and the moon (phase, how much is lit, next full and new moon, moonrise and moonset). The page sits on a weather video if you add one, or a drawn animated sky (sun, stars, clouds, rain, snow, fog, lightning) if not. Location is your city or a rough one from your connection. In the US, active National Weather Service alerts show as a colored badge on the home screen and as cards on the weather page (select one for the full text); turn them off in Settings, Weather. A severe alert (a Severe or Extreme warning such as Tornado, Severe Thunderstorm or Flash Flood, or a Tornado Watch) also shows as a red banner on the home screen and, when a new one is issued, as a red banner with a tone over whatever app is open for about 30 seconds (it checks every two minutes and needs the Display over other apps permission; Settings, Weather has a switch). A radar card loops recent rain and snow over a dark map, with buttons to zoom (to street level; the rain is stretched past its source's detail), pause and play, switch the map (dark, light, satellite, streets, topo), move the map with the arrow keys, and open it full screen (Back returns). Move the map to your house and press the pin button to save that spot as the location for the forecast, radar and alerts (it applies to every profile; Settings, Weather can go back to automatic); in the lower 48 it uses the National Weather Service's radar mosaic, elsewhere RainViewer. OK on the map also pauses and plays.

## Search and the AI

Search has three levels per profile: Off, Apps only, and Apps + AI. Kid profiles start Off and only a parent (with the PIN) can change it. Kids on Apps + AI are told the user is a child and to keep answers age appropriate, and they never get web search.

**The AI server** (Settings, Search) is any OpenAI-compatible chat server: a hosted service, or llama.cpp, Ollama, LM Studio and similar on your network.

- Enter the server address, for example `http://192.168.1.50:8080/v1`. Plain `http://` is accepted only for addresses on your own network; anything else must be `https://`.
- The model name is optional. Tap it to pick from the server's own list, or leave it on "Server default".
- The API key is optional, and is stored encrypted.
- Set **Context size** to what your model really supports. Portal summarizes older messages before the chat fills it.
- Press **Test connection** to check the setup.

**Opening apps and web search need tool calling.** With llama.cpp, start `llama-server` with `--jinja` and use a model that supports tools. If a server refuses tools, Portal falls back to a plain chat and says so; pressing Test connection tries tools again.

**Web search** (Settings, Search, Web search for the AI) is off until you pick one of these. DuckDuckGo isn't offered: it has no search API and turns automated searches away.

| Service | Setup |
|---|---|
| SearXNG | Your own server's address. JSON output must be on: in `settings.yml`, list `json` under `search: formats:`. |
| Brave Search | An API key from Brave. |

## Get the source

```bash
git clone https://github.com/plecos/portaltv.git
```

## Build

Requirements: Android Studio (or the Android SDK) and any JDK 17 or newer to start Gradle. The project pins Gradle's own Java to version 25 (`gradle/gradle-daemon-jvm.properties`), which Gradle downloads on first use. The app itself compiles for Java 11.

```bash
./gradlew assembleDebug installDebug     # debug build onto a connected device
./gradlew assembleRelease                # signed release build
./gradlew testDebugUnitTest              # unit tests
```

The release APK is written to `app/build/outputs/apk/release/app-release.apk`.

### Local configuration

Create or edit `local.properties` in the project root. It is git-ignored, so secrets stay out of version control.

```properties
# Optional: bake your own Google sign-in client into a private build (see "Google sign-in" below).
GOOGLE_TV_CLIENT_ID=...
GOOGLE_TV_CLIENT_SECRET=...

# Release signing. Without these the release build comes out unsigned and can't be installed.
RELEASE_STORE_FILE=/Users/you/.portal-signing/portal-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=portal
RELEASE_KEY_PASSWORD=...
```

Create a release key once, outside the project, and **back it up**. Losing it means you can never update an installed release in place:

```bash
keytool -genkeypair -keystore ~/.portal-signing/portal-release.jks -storetype PKCS12 \
  -alias portal -keyalg RSA -keysize 4096 -validity 10000
```

### Google sign-in

Linked profiles need an OAuth client of type "TVs and Limited Input devices" (Google Cloud Console, APIs and Services, Credentials). Published releases don't include one, since an APK can't keep a secret. Either:

- enter the client ID and secret on the TV: Add profile, Google account, and Portal asks for them once and keeps them encrypted; or
- put them in `local.properties` as `GOOGLE_TV_CLIENT_ID` and `GOOGLE_TV_CLIENT_SECRET` and build for yourself. That APK contains the secret, so don't share it.

Without a client, Portal profiles still work.

## Versions, CI and releases

The version is automatic. `versionName` is `VERSION_MAJOR_MINOR` from `gradle.properties` (change it by hand for a bigger release) followed by the number of commits after the first, so the first commit is `1.0.0` and a later one is `1.0.42`. `versionCode` is the commit count plus `VERSION_CODE_OFFSET` (also in `gradle.properties`), which keeps it above builds made before the history was squashed. Every commit is therefore a newer build that installs over the last.

GitHub Actions (`.github/workflows`):

- **CI** runs the unit tests and lint on every pull request and on pushes to any branch except `main`.
- **Release** runs on every push to `main` (except Markdown-only changes). It runs the tests and lint, builds and signs the release APK, and publishes it as a GitHub release tagged `v<version>`.

The release workflow needs these repository secrets (Settings, Secrets and variables, Actions): `RELEASE_KEYSTORE_B64` (the keystore, base64), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. Releases carry no Google sign-in credentials, because an APK can't keep a secret.

## Getting more videos

Settings, **Videos**, **Search/Manage Videos** searches for videos and downloads the ones you pick:

- **NASA** needs no key. It searches NASA's Image and Video Library, whose media is generally in the public domain (space, Earth from orbit, aurora and more). Some clips are interviews or news, so searches like "aurora" or "earth from space" work best.
- **Pixabay** is free but needs a free key that you paste in once (pixabay.com/api/docs). The key stays on your TV, stored encrypted, and Portal ships without one. Searches use Pixabay's safe search.
- Pexels is built in but switched off while it isn't issuing API keys.

When you pick a video you also choose what it is for: the **regular background**, **birthdays**, or one of **Halloween, Thanksgiving, Christmas or New Year's**. A holiday video plays the day before and the day of its holiday, and a birthday video plays on someone's birthday. With more than one for the same holiday or for birthdays, Portal rotates them by day, or you pick one in Settings, Videos. The Downloaded tab lets you change what a video is for, or delete it.

- Each download is a 1080p mp4 (4K is skipped), saved on the TV. The size is shown before you confirm, and anything over 400 MB is refused.
- Downloaded background videos appear in the video list and the screen saver, and the Downloaded tab shows the creator of each.
- Kid profiles can't search for or download videos. A parent can let a kid choose their own everyday video from the ones on the TV (Settings, Videos, with the parent PIN); a kid never sees the holiday or birthday videos.
- Pixabay asks that its videos are credited: the creator and service are shown with every download.

## Adding videos

Drop `.mp4` files into `app/src/main/assets/backgrounds/` and rebuild. Each one appears in Settings, Videos, and in the screen saver's list, named from its file name (`northern_lights.mp4` becomes "Northern Lights"). `default.mp4` is listed first.

- `birthday.mp4` is reserved for birthdays and never offered in the lists.
- `backgrounds/special/` holds holiday videos, matched by the start of the file name: `halloween*`, `thanksgiving*`, `christmas*`, `new-years*` (also `new-year`, `new_year`). They are never offered in the lists or the screen saver.
- `backgrounds/weather/` holds videos for the weather page, matched by the start of the file name: `clear*` (or `sunny*`), `partly*`, `cloud*` or `overcast*`, `fog*`, `rain*`, `snow*`, `storm*` (or `thunder*`). Add `night` to the name (`rain_night.mp4`) for an after-dark version. Without a matching file the page draws its own sky. They are never offered in the lists. Turn the weather background off in Settings, Weather.
- Prefer 1080p, and HEVC (H.265) for big files: it can be a fraction of the size of H.264 with no visible difference on a dimmed background. Android TV chips decode it in hardware.
- Videos are bundled into the app, so they add to its size, and each plays from a copy extracted on the TV.

## Project layout

```
app/src/main/java/com/portal/tv/
  ui/            home, settings, profile picker, weather, PIN picker
  search/        search panel, AI client, tools, web search, chat history
  kids/          the service that watches a kid's time and shows the reminders and countdown
  screensaver/   the screen saver view and system screen saver service
  background/    video playback and the daily/holiday picks
  repo/          profiles, kid schedules, apps, weather, usage
  settings/      per-profile settings, birthdays
  auth/          Google sign-in (device flow)
app/src/main/assets/backgrounds/   background videos
scripts/                           install.sh and install.ps1
app/src/test/                      unit tests
```

## Known limits

- Kid time limits count only apps opened from Portal. Opening an app by voice through the TV's assistant bypasses them, and Portal can't force-close an app that is already running; it sends the child back to Portal and asks the app to pause.
- Voice input needs a speech recognizer on the TV; the mic button only appears if one exists.
- Portal can't see Google accounts or switch the TV's own Google TV profile; its profiles are separate from the TV's.

## License

Copyright 2026 Portal TV. PolyForm Noncommercial License 1.0.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). The background videos come from Pixabay and Pexels and keep their own licenses.
