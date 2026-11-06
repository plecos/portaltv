# Portal extensions (contract, draft 1)

An extension is an ordinary Android app that tells Portal what it offers. Portal never loads or runs an extension's code:
it reads a small XML file the extension names in its manifest, and opens the extension's own screens like any other app.
Nothing an extension asks for happens until an adult allows it, one capability at a time.

> **Status: draft.** This version of Portal finds extensions, reads and checks their declaration, lists them under
> Settings, Extensions where an adult allows each capability, adds the tiles of allowed extensions to the app grid,
> opens an extension's settings screen from Settings, and draws the buttons and text an extension declares in the top bar and
> under the clock. It also answers the calls an extension makes into Portal: reminders, cards over other apps, and changing its own home screen
> items while running (see *Calling Portal*). Badges on tiles are not there yet. Contract version **1**.

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
| | `text` | yes | At most 40 characters, plain text. A line of text isn't pressed, so it has no activity or icon. |

At most 8 tiles, and at most 2 items for each area (the top bar and the clock area), in any mix of buttons and text. Elements Portal doesn't know are ignored, so a file can carry things for later contract versions.
Anything wrong in the parts it does know is reported to the person looking at the extension, not guessed at.

## Capabilities

| Capability | Asked for by | What allowing it means |
| --- | --- | --- |
| `tiles` | declaring a `<tile>` | Show your tiles in the person's apps. |
| `settings` | declaring `<settings>` | Add a settings page for you. |
| `bar` | declaring a `<bar-...>` item | Add buttons or text to the top bar, next to the profile, search and settings buttons. |
| `clock` | declaring a `<clock-...>` item | Add buttons or text under the time and weather. |
| `reminders` | `<uses capability="reminders"/>` | Add and change reminders that belong to you. |
| `cards` | `<uses capability="cards"/>` | Show cards over other apps. |

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
  and saves; if a parent PIN is set it is asked for before anything changes. *Open its settings* appears when the settings
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
| `items.set` | `bar` or `clock` | `area` (`bar` or `clock`), `id`, `text` | |
| `items.remove` | `bar` or `clock` | `area`, `id` | |
| `items.list` | `bar` or `clock` | `area` | `items`: a list of Bundles with `id`, `text`, `button` |

**Reminders** are yours: `list`, `update` and `delete` only see the ones you made, and they belong to the profile that was in
use when you added them. Portal's own reminders and other extensions' are out of reach. They are marked "via <your name>" on
the card and in Settings, you may keep 50, and they are removed when an adult turns reminders off for you or you are
uninstalled. A reminder whose extension is no longer allowed is dropped rather than shown. You can pick only the built-in tones.

**Cards** use the same card as reminders, over whatever app is open, headed "via <your name>" so they can't pass for
Portal. They have no buttons and no sound and go away by themselves. Without the "Display over other apps" permission Portal
shows a notification instead.

**Items** you declared can change their words, be taken away with `items.remove` (they come back with `items.set`), and you
can add lines of text beyond the declared ones, always within two items per area. A button's label changes, but the screen it
opens is always one you declared. The profile, search, settings and update buttons, the weather and any alert are not yours:
there is no way to name them, and a call that tries gets `not_found`. If the home screen is showing, it redraws at once.

## Housekeeping

- What was allowed, and what an extension changed about its items, is not part of Android's backup or a device-to-device
  transfer: it belongs to the apps installed on this TV.
- A change to what an extension may do takes effect on its next call. Turning a capability off also removes what the
  extension made with it.

## Coming in later pieces (not available yet)

- **Badges on tiles:** a short badge or subtitle on a tile in the app grid.
- Conformance tests that install a misbehaving extension, and a catalog of extensions.
