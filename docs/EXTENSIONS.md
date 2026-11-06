# Portal extensions (contract, draft 1)

An extension is an ordinary Android app that tells Portal what it offers. Portal never loads or runs an extension's code:
it reads a small XML file the extension names in its manifest, and opens the extension's own screens like any other app.
Nothing an extension asks for happens until an adult allows it, one capability at a time.

> **Status: draft.** This version of Portal finds extensions, reads and checks their declaration, lists them under
> Settings, Extensions where an adult allows each capability, adds the tiles of allowed extensions to the app grid, and
> opens an extension's settings screen from Settings. The calls an extension can make into Portal (reminders, cards, tile
> badges) come in a later piece and are listed at the end. Contract version **1**.

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

At most 8 tiles. Elements Portal doesn't know are ignored, so a file can carry things for later contract versions.
Anything wrong in the parts it does know is reported to the person looking at the extension, not guessed at.

## Capabilities

| Capability | Asked for by | What allowing it means |
| --- | --- | --- |
| `tiles` | declaring a `<tile>` | Show your tiles in the person's apps. |
| `settings` | declaring `<settings>` | Add a settings page for you. |
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
- **Third-party apps.** Extensions are not part of Portal and are not covered by its no-ads, no-tracking promise.
  What an extension does with your data is between you and its author.

## What it looks like in Portal

- **Settings, Extensions** lists what is installed, with where each one stands (*Not allowed*, *Allowed: tiles, cards*, and so
  on). Selecting one shows its description, package, signer and what it asks for. An adult turns each capability on or off
  and saves; if a parent PIN is set it is asked for before anything changes. *Open its settings* appears when the settings
  page is allowed.
- **The app grid** gets one tile per `<tile>` of an allowed extension, with the tile's icon and label, among the other
  apps. They can be hidden, moved, and appear under Frequently Used like any app, and the search box finds them.
- **Opening a tile or the settings screen is checked again each time**: the extension must still be installed, still
  signed by who was allowed, and still allowed that capability. If not, Portal says so and takes the tile away.
- Tiles open one of your own exported activities in your own task. Back returns to Portal.

## Versioning

The `contract` number only goes up, and an existing attribute or capability never changes meaning. A new Portal can read
an older contract. An older Portal won't use an extension written for a newer one.

## Coming in later pieces (not available yet)

- **Calls into Portal:** a provider Portal exposes so an extension can add its own reminders, show a card over other
  apps, and update a tile's badge. Every call will be checked against what was allowed, with limits on how often.
- **Later still:** tools the AI search can use, and a catalog to find and install extensions.

Nothing here is a promise about dates, and details may change until the first release that includes extensions.
