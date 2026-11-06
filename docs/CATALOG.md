# The Portal extensions catalog (format 1, draft)

The catalog is a list of extensions that people have submitted for Portal to show under **Settings, Extensions, Browse**.
It is a review by pull request, **not an audit**: being listed means someone read the entry and checked the source link,
not that the extension is safe. Portal says so wherever it shows the list.

Portal reads one file, `catalog.json`, over https. The catalog repository (planned: `plecos/portaltv-extensions`) keeps
one file per extension and builds `catalog.json` from them; Portal never reads the individual files and never hosts an APK.
Authors host their own APKs. The catalog holds a link and a checksum.

This page is the format and how Portal shows it. The checks that run before anything is installed come in a later piece.

## catalog.json

```json
{
  "format": 1,
  "updated": "2026-11-06",
  "extensions": [ { ... } ],
  "revoked":    [ { ... } ]
}
```

| Field | Meaning |
|---|---|
| `format` | Whole number. Portal reads the formats it knows and ignores a file with a higher one. |
| `updated` | Free text date, shown to the person. Optional. |
| `extensions` | Up to 500 entries. |
| `revoked` | Up to 1000 lines. Optional. |

The file is at most 2,000,000 characters.

## An extension entry

| Field | Required | Rules |
|---|---|---|
| `package` | yes | The Android package name. Listed once. |
| `name` | yes | Up to 40 characters, plain text. |
| `description` | yes | Up to 200 characters. |
| `author` | yes | Up to 60 characters. |
| `source` | yes | https link to the source. A listing needs a source link. |
| `license` | no | Up to 40 characters. Shown, not judged. |
| `capabilities` | yes | What it asks to be allowed to do, from the [contract](EXTENSIONS.md#capabilities). An empty list is fine. An unknown one makes the entry unusable. |
| `tags` | no | Up to 5 short words for finding it: lowercase letters, digits and dashes, at most 20 characters each (`home`, `kids`, `weather`). |
| `icon` | no | A `.png` path next to `catalog.json`, like `icons/garage.png`, at most 200 KB, square, 128 to 256 pixels. It is always read from the catalog's own host, so browsing never contacts the author's server. A full address isn't allowed. |
| `signer` | yes | SHA-256 of the signing certificate, 64 hex characters. For an app with several signers, the fingerprints joined with `+`. |
| `screenshots` | no | Up to 4 https links. |
| `versions` | yes | 1 to 20 versions (below). |

Anything beyond these fields is ignored, so the format can grow.

### A version

| Field | Rules |
|---|---|
| `version_code` | The APK's `versionCode`, 1 or more. Not repeated. |
| `version_name` | Up to 40 characters. |
| `apk` | https link to the APK file, hosted by the author. |
| `sha256` | SHA-256 of the APK file, 64 hex characters. |
| `size` | Size of the APK in bytes, up to 100 MB. |
| `contract` | The extension contract number it needs. A version needing a newer contract than Portal speaks is never offered. |

All links are at most 300 characters, with a host and no name or password in them.

## The revoked list

A line switches an extension off in Portal wherever it is installed. A line names a package, a signer, or both, and may
add a version. What a line leaves out matches anything.

```json
{ "package": "net.example.panel", "version_code": 3, "reason": "Sends reminders to a server it shouldn't." }
{ "package": "net.example.panel", "reason": "Withdrawn by its author." }
{ "signer": "aa11...", "reason": "The signing key was stolen." }
```

- A line needs a `package` or a `signer`, and a `reason` (up to 200 characters, shown to the person).
- A `version_code` needs a `package`.
- An extension doesn't have to be listed to be revoked.
- A revoked version is never offered; Portal offers the newest version that isn't revoked and that it can use.

## One bad entry

Portal leaves out an entry that breaks these rules and keeps the rest, so one mistake can't hide the catalog. The
catalog repository's checks are stricter: any problem at all fails the pull request. They run the very parser Portal uses:

```bash
scripts/validate-catalog.sh path/to/catalog.json
```

`docs/catalog/sample-catalog.json` is an example (its signer and checksum are placeholders) and is checked on every test run.

## Browsing it in Portal

Settings, Extensions has a **Browse extensions** button (a kid's Settings never shows Extensions).

- The list opens at once from the copy kept on the TV, and is fetched again when that copy is more than a day old, or when
  you choose **Refresh**. With no connection it keeps showing the last copy and says it couldn't update.
- A search box finds extensions by words in the title, the description or the tags: every word must match somewhere, capitals
  don't matter, and title matches come first. A **Tag** button limits the list to one tag.
- Entries with nothing Portal could offer (every version revoked, or needing a newer contract) are left out.
- Each row says whether the extension is **Installed**, has an **Update available**, or is installed but **signed by someone
  else** (then it isn't the listed one).
- A detail page shows the description, author, licence, what it asks to be allowed to do in plain words, its signer,
  version and size, and the source link. 
- On an installed extension's page, Portal says **Listed in the catalog**, **In the catalog, but signed by someone else**,
  or **Not in the catalog** (once the catalog has been loaded at least once).
- The request carries nothing about the TV: no account, no id, no list of installed apps.
- The kept copy and when it was fetched are left out of backups and device transfers.

The real catalog isn't online yet, so a debug build shows "The catalog isn't online yet" (a 404). To try Browse now, run
`scripts/test-catalog.sh`: it serves the test catalog in `docs/catalog/test` from your computer and points a debug build of
Portal at it (`HOST=<your computer's address>` for a real TV, `ANDROID_SERIAL=<device>` to pick one device,
`scripts/test-catalog.sh --off` to undo). Underneath, a debug build reads the address in the `url` preference of
`catalog_debug` (http is allowed there only).

## Installing from it

An extension's card has **Install** (or **Update**, when the catalog has a newer version than the one on the TV). It is not
shown for an app that is signed by someone other than the catalog's signer, or when nothing is offerable. Portal never
installs another app silently: Android's own confirmation always comes last.

In order:

1. **Before anything is downloaded:** the extension isn't withdrawn, there is a version this Portal can use, the app on
   the TV (if any) is signed by the catalog's signer, and it is older than the version offered.
2. **Install unknown apps:** if Android hasn't allowed Portal to install apps, Portal says so and opens that setting.
3. **Parent PIN:** asked when one is set, before the download starts.
4. **Download:** https only (http only in debug builds). The size is the catalog's size, and a file that keeps going past it
   is stopped.
5. **Checks on the file**, all of which must pass or the file is deleted and the reason shown:
   - its size and SHA-256 are the catalog's;
   - its package name and version code are the listed ones;
   - its signer is the catalog's signer (and, for an update, the installed app's signer);
   - that version hasn't been withdrawn;
   - it is newer than what is installed;
   - it is a Portal extension, and asks for no more than the catalog lists (nothing unknown, nothing extra).
6. **Android's confirmation screen**, then Android installs it.
7. **Review:** Portal says it is installed and that nothing is allowed yet. **Review** opens the extension's page, which
   says "Listed in the catalog", where an adult allows each capability as for any other extension.

The catalog says what a file should be, and the file is checked to be that. A listing can't be reviewed for one thing and
ship another.

To try it before the real catalog is online, `scripts/test-catalog.sh` serves the sample extension's real debug APK with its
real checksum, size and signer (build it first with `./gradlew :sample-extension:assembleDebug`, and uninstall the sample
from the TV). It also lists two entries that must be refused: a wrong checksum and a wrong package.
