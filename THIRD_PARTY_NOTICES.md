# Third-party notices

Portal's own code is under the PolyForm Noncommercial License 1.0.0 (see `LICENSE`). The
following are not Portal's work and keep their own terms.

## Background videos

The videos in `app/src/main/assets/backgrounds/` were downloaded from Pixabay and Pexels, whose
licenses allow free use without attribution. They are **not** covered by Portal's license, and you
may not redistribute them on their own (for example as a stock video collection). Credits:

| File | Title | Creator | Source |
|---|---|---|---|
| `aquarium1.mp4` | Aquarium | DavidClode | [Pixabay](https://pixabay.com/users/davidclode-43394210/) |
| `aurora2.mp4` | Aurora | urantiabg | [Pixabay](https://pixabay.com/users/urantiabg-44895266/) |
| `space2.mp4` | Space | u_l0oiolyzfi | [Pixabay](https://pixabay.com/users/u_l0oiolyzfi-46727768/) |
| `birthday.mp4` | Birthday | apricott | [Pixabay](https://pixabay.com/users/apricott-17638596/) |
| `special/christmas1.mp4` | Christmas | placidplace | [Pixabay](https://pixabay.com/users/placidplace-25572496/) |
| `special/newyears1.mp4` | New Year's | kmeel_com | [Pixabay](https://pixabay.com/users/kmeel_com-5075826/) |
| `special/thanksgiving1.mp4` | Thanksgiving | joe_hackney | [Pixabay](https://pixabay.com/users/joe_hackney-15024843/) |
| `special/halloween1.mp4` | Halloween | victorsteep | [Pixabay](https://pixabay.com/users/victorsteep-9526460/) |

## Libraries

Linked into the app and used under the Apache License 2.0
(<https://www.apache.org/licenses/LICENSE-2.0>):

- AndroidX (appcompat, core, lifecycle, leanback), Copyright The Android Open Source Project
- Material Components for Android, Copyright Google LLC
- Kotlin Coroutines, Copyright JetBrains s.r.o.
- ZXing core, Copyright ZXing authors (used to draw the sign-in QR code)
- libadb-android, Copyright 2021 Muntashir Al-Islam, used under the Apache License 2.0 (it is also offered under
  GPL-3.0-or-later) (<https://github.com/MuntashirAkon/libadb-android>). It lets Portal talk to the TV's own
  debug service to finish setup.
- Conscrypt, Copyright The Android Open Source Project and Google, used under the Apache License 2.0
  (<https://github.com/google/conscrypt>)

Linked into the app under other licenses:

- sun-security-android, used under the GNU General Public License version 2 with the "Classpath" exception, which
  allows linking it into an independent program without that program becoming GPL
  (<https://github.com/MuntashirAkon/sun-security-android>). It makes the certificate adb pairing needs.
- Bouncy Castle (bcprov), Copyright The Legion of the Bouncy Castle Inc., used under the Bouncy Castle License, an
  MIT-style license (<https://www.bouncycastle.org/licence.html>). libadb-android uses a few of its routines (AES-GCM,
  HMAC and HKDF) for adb pairing; the rest is removed from the release build.

## Font

Inter, Copyright The Inter Project Authors (<https://github.com/rsms/inter>), used under the SIL
Open Font License 1.1 (<https://openfontlicense.org>).

## Moon picture

The photo of the full moon on the weather page's night sky (`app/src/main/res/drawable-nodpi/moon_full.jpg`)
is a frame from NASA's Scientific Visualization Studio, "Moon Phase and Libration, 2024"
(<https://svs.gsfc.nasa.gov/5187>), by Ernie Wright (USRA) with scientist Noah Petro (NASA/GSFC), built
from Lunar Reconnaissance Orbiter data. NASA media are generally free to use under
<https://www.nasa.gov/nasa-brand-center/images-and-media/>; NASA does not endorse Portal.

## Radar
The weather page's radar loop shows rain and snow over one of Esri's maps (Canvas / World Dark Gray, the
default, or Light Gray, World Imagery, World Street Map or World Topo Map) and, for the grey ones,
its place names (data from Esri and its providers: HERE, Garmin, Maxar, Earthstar Geographics, USGS,
NGA and OpenStreetMap contributors, © OpenStreetMap contributors, ODbL). The map in use is credited
on the radar itself. In the contiguous United States the rain comes from the National Weather
Service's national radar mosaic (MRMS, a US government work, via opengeo.ncep.noaa.gov); everywhere
else, and if that is unavailable, from [RainViewer](https://www.rainviewer.com/)'s free public API.
The source in use is credited on the radar itself. None of these services endorses Portal.

## Downloaded videos

Videos you download in Portal (Settings, Videos, Search/Manage Videos) come from Pixabay or from
NASA's Image and Video Library, and keep their own licenses. Portal records each one's creator and the
page it came from. NASA media is generally in the public domain, and NASA does not endorse Portal.

## Google logo

The Google "G" beside a profile's name marks a profile linked to a Google account. It is a trademark
of Google LLC, used only to show that link. Portal is not made or endorsed by Google.

## Look-alike characters

Portal's check on extension names uses the Unicode Consortium's list of confusable characters (confusables.txt,
Unicode Security Mechanisms, UTS #39), kept in `app/src/main/resources/confusables.txt` and made by
`scripts/gen-confusables.py`. Copyright Unicode, Inc., used under the Unicode License
(https://www.unicode.org/terms_of_use.html).
