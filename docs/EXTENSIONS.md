# Portal extensions (contract, draft 1)

An extension is an ordinary Android app that tells Portal what it offers. Portal never loads or runs an extension's code:
it reads a small XML file the extension names in its manifest, and opens the extension's own screens like any other app.
Nothing an extension asks for happens until an adult allows it, one capability at a time.

> **Status: draft.** This version of Portal finds extensions, reads and checks their declaration, and keeps track of
> what has been allowed and who signed the app. Tiles in the app grid, the Settings page, and the calls an extension can
> make into Portal (reminders, cards) come in later pieces and are listed at the end. Contract version **1**.

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
- **Kid profiles (planned).** Extensions will be off for kid profiles unless a parent allows them.
- **Third-party apps.** Extensions are not part of Portal and are not covered by its no-ads, no-tracking promise.
  What an extension does with your data is between you and its author.

## Versioning

The `contract` number only goes up, and an existing attribute or capability never changes meaning. A new Portal can read
an older contract. An older Portal won't use an extension written for a newer one.

## Coming in later pieces (not available yet)

- **Tiles and Settings:** your tiles in Portal's app grid (with an optional badge or subtitle), and an Extensions page in
  Portal's Settings where an adult allows each capability and opens your settings screen.
- **Calls into Portal:** a provider Portal exposes so an extension can add its own reminders, show a card over other
  apps, and update a tile's badge. Every call will be checked against what was allowed, with limits on how often.
- **Later still:** tools the AI search can use, and a catalog to find and install extensions.

Nothing here is a promise about dates, and details may change until the first release that includes extensions.
