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
| `serial` | Whole number from 1, up by at least one with every published catalog. Required for a signed catalog (all of them). See [Signing](#signing). |
| `expires` | A date like `2027-01-31`. After the end of that day Portal pauses installing from this catalog until a newer one is published; listing and withdrawals still work. Optional. |
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

Settings, Extensions has a **Browse extensions** button (a kid's Settings never shows Extensions). It opens a full-screen
browser: a grid of cards, three across, that scrolls and recycles its cards, so it holds a catalog of hundreds. Back returns to
Settings, Extensions. Selecting a card opens its details.

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

## When the catalog withdraws an extension

A line in the [revoked list](#the-revoked-list) reaches extensions that are already installed.

- **Checked against what is installed:** each installed extension is matched by its package, its installed version code and
  its signer. A line that names only a signer withdraws everything that person signed.
- **Switched off:** a withdrawn extension is **Withdrawn**. Nothing is granted to it (tiles, items, background, reminders,
  cards, host calls), what was allowed is forgotten, and it can't be allowed again while it is withdrawn. Its page shows the
  reason and **Uninstall**. If the line is later removed, it comes back as "Not allowed": an adult has to allow it again.
- **Kept fresh without browsing:** when the home screen comes up and Portal's copy of the catalog is over a day old, Portal
  fetches it once, but only while at least one extension is installed. A failed try waits an hour. With no extensions installed
  Portal never contacts the catalog unless you open Browse. Browsing's Refresh also updates it.
- **Easy on the host:** each fetch asks "has it changed?" (the file's `ETag` or `Last-Modified`), and when it hasn't the host
  answers with a few hundred bytes. Each TV also waits its own random extra 0 to 6 hours beyond the day, picked again after
  every fetch, so TVs that started together drift apart. If the host answers 429 or 503, Portal stays away for as long as
  `Retry-After` says (an hour if it doesn't say; never less than a minute or more than a day). The catalog host should send
  `ETag` or `Last-Modified`; GitHub Pages and most CDNs do.
- **Offline:** the copy on the TV is used, so a withdrawal that was already fetched stays in force with no connection.

### Extensions that aren't in the catalog

Once the catalog has been loaded, an installed extension that isn't in it (or is in it but signed by someone else) says so on
its page, with a stronger warning. When something new is being allowed for it, Portal asks once more, plainly, before saving.
Before the catalog has ever been loaded, its page says it hasn't been checked yet.

To see these on the emulator: `REVOKE_SAMPLE=1 scripts/test-catalog.sh` (the catalog withdraws the sample), and
`UNLIST_SAMPLE=1 scripts/test-catalog.sh` (the sample isn't listed). Then press Refresh in Browse.

## Signing

The catalog is a file on a web host, so Portal does not take its word for it. Portal reads a catalog only if it carries a
valid **Ed25519 signature** from a key built into Portal. Whoever controls the host or the repository can't make a TV install
an app, or withdraw one, without the private key, which is kept offline.

### What is published

Two files side by side: `catalog.json` and `catalog.json.sig`, the signature over the exact bytes of the first:

```json
{"key_id": "portal-2026-1", "signature": "<base64>"}
```

Portal fetches both, checks the signature before reading anything in the catalog, and keeps them together in one file. A
catalog that is unsigned, signed by an unknown key, or altered by a single byte is refused, and the copy already on the TV
stays in use. Checking works the same on every Android version Portal runs on (the check is written out in
`Ed25519Verify.kt` rather than relying on the platform, and is tested against the RFC 8032 vectors and the JDK).

### Old copies and stale copies

A signed catalog is still only a snapshot, so two more rules close the gaps a signature alone leaves:

- **`serial` never goes down.** Portal remembers the highest serial it has accepted and refuses a catalog with a lower one,
  so an old signed copy (from before a withdrawal) can't be put back in front of it.
- **`expires` limits how long a frozen catalog helps an attacker.** Someone who can block the real host and serve a
  perfectly valid but old catalog is limited by the date in it. Past it, installing is paused (with a message), and
  withdrawals already known stay in force. Choose it so there is time to publish again (say 60 to 90 days ahead) and move it
  with every publication.

### Setting up the key (once)

```bash
java scripts/CatalogSign.java keygen portal-catalog
```

This writes `portal-catalog.private` and `portal-catalog.public`. Keep the private file offline (an encrypted drive or a
password manager; **not** in a repository and not in CI): it is the only thing that can publish a catalog Portal accepts.
Paste the one line in `portal-catalog.public` into `CatalogTrust.RELEASE_KEYS` in `CatalogTrust.kt`, with an id such as
`portal-2026-1`, and ship that in a release. **Until a key is added, a release build refuses every catalog** (it has nothing to
check against); debug builds also trust a test key whose private half is in `docs/catalog/test`, and nothing else.

### Publishing

1. Build `catalog.json` from the entries (the catalog repository's job), with a `serial` one higher than the last and a new
   `expires`.
2. Check it: `scripts/validate-catalog.sh catalog.json`.
3. Sign it, on the machine that holds the key:
   `java scripts/CatalogSign.java sign portal-catalog.private portal-2026-1 catalog.json` (writes `catalog.json.sig`).
4. Publish both files together. (`java scripts/CatalogSign.java verify portal-catalog.public catalog.json` checks a pair.)

### Changing the key

`CatalogTrust.RELEASE_KEYS` is a list. To rotate, add the new public key in a Portal release, wait until most TVs have it,
then sign with the new key; the old key can be removed in a later release. If the private key is ever lost or stolen, TVs can
only be protected by a Portal update that stops trusting that key, so keep it somewhere you would be sorry to lose.

### Trying it

`scripts/test-catalog.sh` signs the test catalog with the test key. Options show each refusal on the emulator:
`SERIAL=n` (serve a lower serial than the last accepted to see it refused), `BAD_SIGNATURE=1`, `UNSIGNED=1` and
`EXPIRES=2020-01-01`. With `KEEP=1` it only restarts the server, leaving Portal and its kept catalog alone, so the next
Refresh shows what happens to a TV that already has a copy.
