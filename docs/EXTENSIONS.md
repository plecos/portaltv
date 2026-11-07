# Portal extensions (contract, draft 1)

An extension is an ordinary Android app that tells Portal what it offers. Portal never loads or runs an extension's code:
it reads a small XML file the extension names in its manifest, and opens the extension's own screens like any other app.
Nothing an extension asks for happens until an adult allows it, one capability at a time.

> **Status: draft.** This version of Portal finds extensions, reads and checks their declaration, lists them under
> Settings, Extensions where an adult allows each capability, adds the tiles of allowed extensions to the app grid,
> opens an extension's settings screen from Settings, and draws the buttons and text an extension declares in the top bar and
> under the clock. It also answers the calls an extension makes into Portal: reminders, cards over other apps, and changing its own home screen
> items while running (see *Calling Portal*), and can show a background an extension draws (see *Drawing the home screen
> background*), and put a badge and a line on its own tiles. Contract version **1**.

## Writing one

You need no SDK and no library. Any Android app can be an extension, written in any language. The
[`sample-extension`](../sample-extension) module is a complete example.

**1. Point at the declaration from your manifest**

```xml
<application ...>
    <meta-data
        android:name="net.getportaltv.extension"
        android:resource="@xml/portal_extension" />
</application>
```

**2. Write the declaration**, `res/xml/portal_extension.xml`:

```xml
<portal-extension
    contract="1"
    id="home-panel"
    label="@string/ext_name"
    description="@string/ext_description">

    <uses capability="reminders" />
    <uses capability="cards" />

    <tile id="lights"  label="@string/tile_lights"  icon="@drawable/ic_lights" activity=".LightsActivity" />
    <tile id="cameras" label="@string/tile_cameras" icon="@drawable/ic_cameras" activity=".CamerasActivity" />

    <settings activity=".SettingsActivity" />

    <bar-button   id="lights" label="@string/bar_lights" icon="@drawable/ic_lights" activity=".LightsActivity" />
    <bar-text     id="mode"   text="@string/bar_mode" />
    <clock-text   id="door"   text="@string/clock_door" />
    <clock-button id="cams"   label="@string/clock_cams" activity=".CamerasActivity" />

    <background service=".BackgroundService" />
</portal-extension>
```

**3. Export the screens Portal will open.** Every activity named in a `<tile>` or `<settings>` element must be in your
manifest with `android:exported="true"` (and enabled). A tile or settings screen that isn't is left out, and Portal says so.

An extension does not need a launcher entry. One without it appears in Portal only through the tiles it declares.

## The file

All attributes are plain (no namespace). Text can be a literal or a `@string/...` reference. Values are trimmed.

| Element | Attribute | Required | Notes |
| --- | --- | --- | --- |
| `<portal-extension>` | `contract` | yes | A whole number, 1 or more. The version of this contract you wrote for. |
| | `id` | yes | Lowercase letters, digits and dashes, starting with a letter or digit, at most 40 characters. |
| | `label` | yes | The name shown to people, at most 40 characters. |
| | `description` | no | One or two sentences, at most 200 characters. |
| | `icon` | no | A drawable resource that stands for the extension in Portal's lists. If it is missing, your app's icon is used. |
| `<uses>` | `capability` | one per capability | `reminders` or `cards`. See below. |
| `<tile>` | `id` | yes | Same rules as the extension id; unique within the file. |
| | `label` | yes | At most 24 characters. |
| | `activity` | yes | The class to open. `.Foo` and `Foo` mean your own package; a name with a package is used as written. |
| | `icon` | no | A drawable resource. |
| `<settings>` | `activity` | yes | Your settings screen, which Portal opens from its own Settings. At most one. |

| `<bar-button>`, `<clock-button>` | `id` | yes | Same rules as the extension id; unique among the items for that area. |
| | `label` | yes | At most 24 characters, plain text. |
| | `activity` | yes | The exported screen it opens. |
| | `icon` | no | A drawable resource. |
| `<bar-text>`, `<clock-text>` | `id` | yes | As above. |
| | `text` | yes | At most 40 characters, plain text. A line of text can't be pressed, so it has no activity or icon (the remote can still stop on it, and a long press says whose it is). |

| `<background>` | `service` | yes | A service of yours that draws the home screen background. At most one. |

At most 8 tiles, and at most 2 items for each area (the top bar and the clock area), in any mix of buttons and text. Elements Portal doesn't know are ignored, so a file can carry things for later contract versions.
Anything wrong in the parts it does know is reported to the person looking at the extension, not guessed at.

## Capabilities

| Capability | Asked for by | What allowing it means |
| --- | --- | --- |
| `tiles` | declaring a `<tile>` | Show your tiles in the person's apps. |
| `settings` | declaring `<settings>` | Add a settings page for you. |
| `bar` | declaring a `<bar-...>` item | Add buttons or text to the top bar, next to the profile, search and settings buttons. |
| `clock` | declaring a `<clock-...>` item | Add buttons or text under the time and weather. |
| `background` | declaring `<background>` | Draw the home screen background, when an adult chooses "Extension" under Settings, Background. |
| `reminders` | `<uses capability="reminders"/>` | Add and change reminders that belong to you. |
| `cards` | `<uses capability="cards"/>` | Show cards over other apps. |
| `weather` | `<uses capability="weather"/>` | Read the current weather and where the TV roughly is (to within about 10 km). |

A capability name Portal doesn't know is never granted. You can list one for a newer Portal and it is ignored by older ones.

## How Portal treats an extension

- **Found is not allowed.** A new extension starts as *Not allowed*. An adult looks at what it asks for and turns each
  capability on or off. Only what you both asked for and was allowed is granted. A capability you add in an update stays
  off until it is turned on.
- **The signer is pinned.** When an adult allows an extension, Portal records the SHA-256 fingerprint of the certificate
  that signed it. If the app later turns up signed by someone else, nothing is granted until an adult allows it again.
  (An app that changes its signing key the way Android allows, with a proof of rotation signed by the old key, keeps
  what it was allowed.) Keep your signing key safe, and don't change it.
- **Uninstalling forgets.** What was allowed for an app is forgotten once it is uninstalled. A disabled app keeps it.
- **Newer contract.** If `contract` is higher than the version Portal speaks (currently 1), Portal can't use the
  extension and says it needs a newer Portal. It doesn't try to read the rest of the file.
- **Kid profiles.** A kid's Settings never shows Extensions. An extension's tile appears on a kid's home screen only if a
  parent has allowed that tile for them (Manage apps, in the profile's menu). A kid's bedtime and daily limit are checked before a
  tile opens, as for any app.
- **Home screen items.** The top bar is the panel at the top right with the profile, search and settings buttons; the
  clock area is under the time and weather. Portal draws every item itself, in its own style, from the words and icon
  you give; you never supply a view. An extension can only have its own items: the profile, search, settings and update
  buttons, the weather and any alert or kid time-left text can't be removed, hidden or changed by an extension, and
  never move out of place. Items are limited (2 per extension per area, and about 4 in the bar and 3 under the clock over
  all extensions; the extras are not shown, in extension name order), and a button opens its screen the same way a tile
  does, checked again on each press. Long-pressing an item says whose it is. Kid profiles don't show extension items.
- **Third-party apps.** Extensions are not part of Portal and are not covered by its no-ads, no-tracking promise.
  What an extension does with your data is between you and its author.

## What it looks like in Portal

- **Settings, Extensions** lists what is installed, with where each one stands (*Not allowed*, *Allowed: tiles, cards*, and so
  on). Selecting one shows its description, package, signer and what it asks for. An adult turns each capability on or off
  and saves; if a parent PIN is set it is asked for before anything changes. *Settings* appears when the settings
  page is allowed.
- **The app grid** gets one tile per `<tile>` of an allowed extension, with the tile's icon and label, among the other
  apps. They can be hidden, moved, and appear under Frequently Used like any app, and the search box finds them.
- **The detail page lists the items** the extension would put on the home screen, so an adult sees exactly what turning
  on `bar` or `clock` will show.
- **Opening a tile, a button or the settings screen is checked again each time**: the extension must still be installed, still
  signed by who was allowed, and still allowed that capability. If not, Portal says so and takes the tile away.
- Tiles open one of your own exported activities in your own task. Back returns to Portal.

## Versioning

The `contract` number only goes up, and an existing attribute or capability never changes meaning. A new Portal can read
an older contract. An older Portal won't use an extension written for a newer one.

## Calling Portal

Portal exposes one provider, `content://net.getportaltv.host`, and you call it with `ContentResolver.call(uri, method, null, extras)`.
There is nothing to bind to and no library: the answer is a `Bundle`.

**Android 11 and later: ask to see it**, or the system hides Portal's provider from your app:

```xml
<queries><provider android:authorities="net.getportaltv.host" /></queries>
```

```kotlin
val reply = contentResolver.call(Uri.parse("content://net.getportaltv.host"), "reminders.add", null, Bundle().apply {
    putString("title", "Take the bins out")
    putLong("at_millis", System.currentTimeMillis() + 60 * 60_000L)
})
if (reply?.getBoolean("ok") == true) { val id = reply.getInt("id") } else { val why = reply?.getString("error") }
```

Every answer has `ok`. When it is `false`, `error` says why and nothing was changed:

| `error` | Meaning |
| --- | --- |
| `not_allowed` | You aren't an installed extension, an adult hasn't allowed you the capability the call needs, or you are no longer signed by who was allowed. |
| `kid_profile` | A kid's profile is in use. Nothing is done for it. |
| `bad_argument` | A value is missing, too long, in the past, not plain text, or not one of the allowed choices. |
| `limit` | You are at a limit (reminders you may keep, items you may show, changes you may keep). |
| `rate_limited` | Too many calls lately (20 changes a minute, 1 card a minute). Try later. |
| `not_found` | There is no such reminder or item of yours. |
| `no_such_method` | Portal doesn't have that method. |
| `unavailable` | Portal couldn't do it right now. |

Portal works out who is calling from Android itself, never from anything you send, so you can't act for another app and
nobody can act for you.

| Method | Needs | Arguments | Answer |
| --- | --- | --- | --- |
| `host.info` | nothing | none | `contract`, `portal_version_code`, `granted` (the capabilities you have now), `max_reminders` |
| `reminders.add` | `reminders` | `title` (up to 80 characters), `at_millis` (in the future, within 2 years); optional `repeat` (`none`, `daily`, `weekly`), `tone` (`none`, `beeps`, `chirp`, `prompt`, `alert`), `manual` (stays until dismissed), `snooze_minutes` (0, 5, 10, 15 or 30) | `id` |
| `reminders.list` | `reminders` | none | `reminders`: a list of Bundles with `id`, `title`, `at_millis`, `repeat`, `tone`, `manual`, `snooze_minutes` |
| `reminders.update` | `reminders` | `id`, and any of the add arguments to change | |
| `reminders.delete` | `reminders` | `id` | |
| `card.show` | `cards` | `title` (up to 60), optional `text` (up to 140) and `seconds` (default 10, at most 30) | |
| `tile.update` | `tiles` | `id` (one of your declared tile ids), and `badge` (up to 6 characters) and/or `subtitle` (up to 24); an empty text clears that part | |
| `items.set` | `bar` or `clock` | `area` (`bar` or `clock`), `id`, `text` | |
| `items.remove` | `bar` or `clock` | `area`, `id` | |
| `items.list` | `bar` or `clock` | `area` | `items`: a list of Bundles with `id`, `text`, `button` |
| `weather.get` | `weather` | none | The weather as numbers; see below |

**Weather** (`weather.get`) is what Portal already holds, from the forecast service it uses, as plain numbers, so you need no weather
code of your own. It never touches the network, so asking is cheap and counts as a read; Portal keeps the weather up to date. The
answer has: `as_of_millis` (when it was fetched) and `stale` (more than three hours old); `condition` (`clear`, `partly_cloudy`, `cloudy`,
`fog`, `drizzle`, `rain`, `snow` or `thunderstorm`); `cloud`, `rain`, `snow`, `thunder` and `fog`, each from 0 to 1 (doubles), and
`code` (the WMO weather code, for anyone who wants it); `wind_kmh`, `wind_dir` (degrees, where the wind comes from), `gust_kmh`
when known, `temp_c`, `visibility_km` when known and `is_day`; `utc_offset_seconds`; `sunrise_millis` and `sunset_millis` for today
when known; and `latitude` and `longitude` (doubles) when the TV's place is known. The place is rounded to a tenth of a degree
(about 10 km): enough to place the sun and moon, never a street. Portal gives no city name or address, and an adult is told what
the capability allows before turning it on. If there is no weather yet the answer is `unavailable`. Kid profiles get `kid_profile`
like every other call.

**Reminders** are yours: `list`, `update` and `delete` only see the ones you made, and they belong to the profile that was in
use when you added them. Portal's own reminders and other extensions' are out of reach. They are marked "from extension <your name>" on
the card and in Settings, you may keep 50, and they are removed when an adult turns reminders off for you or you are
uninstalled. A reminder whose extension is no longer allowed is dropped rather than shown. You can pick only the built-in tones.

**Cards** use the same card as reminders, over whatever app is open, headed "from extension <your name>" so they can't pass for
Portal. They have no buttons and no sound and go away by themselves. Without the "Display over other apps" permission Portal
shows a notification instead.

**Items** you declared can change their words, be taken away with `items.remove` (they come back with `items.set`), and you
can add lines of text beyond the declared ones, always within two items per area. A button's label changes, but the screen it
opens is always one you declared. The profile, search, settings and update buttons, the weather and any alert are not yours:
there is no way to name them, and a call that tries gets `not_found`. If the home screen is showing, it redraws at once.

**Tiles** can show a small badge on the icon (a count, say) and a line under the name, set with `tile.update` and changed whenever
you like (limited like other changes). Only your own declared tiles can be decorated; the badge shows in every layout (tiles, icons,
list) and the line in the tiles and list layouts. Kid profiles don't see either, since it is text from another app. They are
removed when `tiles` is turned off for you or you are uninstalled.

## Drawing the home screen background

An extension with `background` allowed can be chosen as the background in Settings, Background (a fourth option beside Off, Shader
and Video, set per profile). You draw in your own process and Portal shows the result; none of your code runs in Portal.

1. Declare `<background service=".BackgroundService"/>` and export that service (`android:exported="true"`). Portal binds it
   with the action `net.getportaltv.BACKGROUND`, and you answer on a `Messenger`.
2. Portal sends `MSG_START` (1) with `width`, `height`, `host_token` (a binder), `display_id` and `max_fps` in the message data,
   and its reply `Messenger` in `replyTo`. Make a `SurfaceControlViewHost(context, display, hostToken)`, call `setView(view, width,
   height)`, and reply with `MSG_READY` (2) whose data holds `surface_package` (the host's `surfacePackage`).
3. When you get `MSG_STOP` (3), or Portal unbinds, `release()` the host and stop drawing.

The [sample](../sample-extension/src/main/java/net/getportaltv/sample/BackgroundService.kt) is about 80 lines.

What Portal does with it:
- Your picture sits behind everything on the home screen, under the same dark layer Portal puts over its own backgrounds, so
  text stays readable. It can't take the remote or a touch. It is asked for at most 1920 by 1080 and 30 frames a second; the
  frame rate is a request (Portal can't count your frames), so draw sparingly.
- Until your first picture arrives the screen shows Portal's own gradient, so it is never blank.
- If you don't answer within 5 seconds, your process stops, you are no longer allowed, or you are signed by someone else now,
  Portal goes back to its own gradient and keeps the choice, trying you again the next time the home screen opens.
- You are asked to stop whenever the home screen isn't showing (another app, the screen saver), and started again when it is back.
- Kid profiles never use it.

## How Portal tests this

Everything above that says "refused" is tested, because the host API is open to every app on the TV:

- **Rules, on the JVM.** [`HostConformanceTest`](../app/src/test/java/com/portal/tv/extensions/HostConformanceTest.kt) runs the real
  rules ([`HostHandler`](../app/src/main/java/com/portal/tv/extensions/HostHandler.kt)) against a fake TV: an unknown caller,
  nothing allowed, each capability opening only its own calls, a kid profile, one extension reaching for another's or Portal's
  reminders, names claimed in the arguments, every kind of bad value, the 50-reminder and 2-item limits, the rate limits, Portal's
  own buttons under every name, and 3000 random calls that must never throw. Every refusal is also checked to have changed nothing.
- **Real IPC, on an emulator.** [`scripts/conformance.sh`](../scripts/conformance.sh) installs Portal, the
  [sample](../sample-extension) and [`conformance-extension`](../conformance-extension), a misbehaving extension. It checks what
  only Android can show: the shell (not an extension) can't call, an extension signed by someone else than the one allowed is
  refused, a kid profile is refused, Portal's own and another extension's reminders can't be touched, a spoofed `source` is
  ignored, wrong types and a 200 KB value are refused, a flood is limited, and uninstalling removes what the extension made and
  what was allowed for it. It exits non-zero on any failure. CI runs it on an emulator for every pull request.

Run it yourself on an emulator (it wipes Portal's data there):

```
./gradlew assembleDebug :sample-extension:assembleDebug :conformance-extension:assembleDebug
scripts/conformance.sh
```

## Housekeeping

- What was allowed, and what an extension changed about its items, is not part of Android's backup or a device-to-device
  transfer: it belongs to the apps installed on this TV.
- A change to what an extension may do takes effect on its next call. Turning a capability off also removes what the
  extension made with it, and uninstalling an extension removes everything Portal kept for it at once.

## Checking your names before you install

Portal refuses an extension whose names could pass for part of Portal or Android. It shows "Can't be used" and a reason on the
extension's page. Check yours first, on your computer, with the same code Portal runs:

```bash
scripts/check-extension.sh com.yourname.garage app/src/main/res/xml/portal_extension.xml app/src/main/res/values/strings.xml
```

The first argument is your package name, the second is your `portal-extension` file, and the third (optional) is the
`strings.xml` that holds the words behind any `@string/...` names. It prints each problem and the reason, and exits 0 when
Portal would accept the names. It needs a JDK and this repository. A catalog listing is checked by the same rules in
`scripts/validate-catalog.sh`, so a pull request that adds a refused name fails with the reason.

### The rules, exactly

A name is your extension's name, a tile's name, a button's words, and a catalog listing's name. Text lines (`bar-text`,
`clock-text`) are not buttons and may say anything plain.

| Rule | Refused | Fine |
| --- | --- | --- |
| A whole word that is one of: portal, settings, setting, android, google, system, parent, parents, pin, profile, profiles, update, search, launcher, parental, password, passcode | "Update", "Parent PIN", "My Settings page" | "Updates for my pets", "Searchlight", "Pinball" |
| Those longer names anywhere once spaces and punctuation are ignored: portal, settings, android, google, launcher, parentpin, parental, passcode | "S e t t i n g s", "Portal-TV", "Google TV" | "Garage door" |
| Look-alike letters are read as the Latin letter: accents are dropped, full-width letters are folded, and every character Unicode's own confusables list says is drawn like a Latin letter or digit (about 1,800, from Cyrillic, Greek, Lisu, Cherokee, Latin extensions and more) is mapped to it. On top of that, i, l and 1 count as the same letter, and so do q and g, and the digits 0 and 5; a bar or exclamation mark inside a word is read as l or i too (a name is checked both ways, so "Pin|Search" is still two words) | "Sεttings", "Pörtal", "Sett1ngs", "Settlngs", "Settinqs", "p0rtal", "Porta|", "Sett!ngs" | |
| Letters from an alphabet that has many letters drawn like Latin ones in Unicode's list (Lisu, Cherokee, Canadian syllabics and a few more). Everyday alphabets, and alphabets with next to no look-alikes such as Odia, Thaana (Dhivehi) and Mongolian, are fine; the everyday ones (Latin, Cyrillic, Greek, Arabic, Hebrew, Armenian, Georgian, the Indic and South-East Asian ones, Ethiopic, Han, Hiragana, Katakana, Hangul) are fine | "ꓢꓰꓔꓔꓲꓠꓖꓢ" (Lisu), "ᏢᎾᎡᎢᎪᏞ" (Cherokee) | "Игра", "ゲーム" |
| A word that mixes Latin letters with any other alphabet, except Han, Hiragana, Katakana, Hangul and Bopomofo | "Pօrtal", "ᑭortal", "Раrent" | "TVアプリ", "Игра" (all one alphabet) |
| Hidden or confusing characters: control characters, zero-width and direction-changing characters, private-use or unassigned characters, more than 3 combining marks in a row | | |
| A package name with a part that is portal, portaltv, getportal or getportaltv (split at dots, underscores and dashes, digits removed), or that has getportal or portaltv anywhere in it, or has portal together with settings, parental, parentpin, passcode, launcher, android or google | `tv.portal.settings`, `com.portal_tv.x`, `net.getportaltv2.app`, `com.myportal.settings`, `net.getportaltv.anything` | `net.example.garage`, `com.acme.portalclock` |

A reserved package name is used only if the app is signed with Portal's own key. Everyone else should choose a package name of
their own. The look-alike list is refreshed with `python3 scripts/gen-confusables.py confusables.txt` (the script says where to get the file). It only knows what Unicode lists, so names in rarely used alphabets are refused as well. A name that is refused makes the whole extension unusable, so fix it and install again.

## Names, text and limits

- An extension's name, its tiles' names and its buttons' words can't pass for Portal or Android ("Portal", "Settings",
  "Parent PIN", "Update", and so on); an extension that does is unusable, and its own app label is used only if it is clean,
  otherwise its package name. Text can't contain control, invisible or direction-changing characters. See
  [CATALOG.md](CATALOG.md#names-that-pass-for-portal). A word that mixes Latin letters with Cyrillic, Greek, Armenian or similar
  look-alikes is refused, and i, l and 1 (and q and g) are read as the same letter. A package name with a part that is
  Portal's own name (`portal`, `portaltv`, `getportal`, `getportaltv`) is used only if the app is signed like Portal, so
  don't build an extension under those names.
- Every host call counts against 120 calls a minute per extension (reads too), before anything else happens; changes also have
  their own lower limit. A caller that isn't allowed anything is refused before its arguments are read.
- Pictures an extension supplies (icons) must be no larger than 512 pixels a side; a bigger one, or one that can't be read, is
  replaced by a generic icon.
- Changing what an extension may do, or installing one, asks for the parent PIN, and asks you to set one first if there is none.

## A withdrawn extension

The catalog ([CATALOG.md](CATALOG.md#when-the-catalog-withdraws-an-extension)) can withdraw an extension or everything a signer
made. Portal then switches it off: every call to the host API is refused as `not_allowed`, its tiles, items and background
are gone, and what was allowed is forgotten.

## Coming in later pieces (not available yet)

- Finding and installing extensions from a catalog inside Portal. The catalog's file format is written down in [CATALOG.md](CATALOG.md); Portal can browse it and install from it. The catalog is signed; see [CATALOG.md](CATALOG.md#signing).
