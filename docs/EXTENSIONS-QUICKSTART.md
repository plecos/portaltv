# Your first Portal extension in ten minutes

An extension is an ordinary Android app. You need Android Studio (or Gradle), an Android TV emulator or a TV, and Portal built from this repository. No SDK is needed.

1. **Build and install Portal and the sample.** From a clone of this repository:

   ```bash
   ./gradlew :app:installDebug :sample-extension:installDebug
   ```
   If more than one device is connected, set `ANDROID_SERIAL` to the one you mean. Never install a debug build over the Portal on your own TV: its signature differs and Android will refuse.

2. **Find it in Portal.** Open Settings, Extensions. The sample appears as "Needs your permission". Select it, tick what you want to try (tiles, settings page, top bar, clock area, reminders, cards, weather), and select Save. If a parent PIN is set it asks for it; if not, it asks you to set one first.

3. **See what it does.** Go back to the home screen. The sample adds two tiles to your apps, a button and text to the top bar, and a button and text under the clock, each with a small extension badge. Open the sample's settings from Settings, Extensions and press its buttons: each one calls Portal (add a reminder in two minutes, show a card, read the weather, list reminders).

4. **Make your own.** Copy `sample-extension`, change its package, and edit `res/xml/portal_extension.xml` to say what you offer. The format, the limits and every host call are in [EXTENSIONS.md](EXTENSIONS.md). Every activity Portal opens must be exported.

5. **Check it behaves.** Try it with a kid profile (an extension's items are hidden and host calls are refused as `kid_profile`), and try declining what it asks for (calls are refused as `not_allowed`). `conformance-extension` is a deliberately badly behaved extension that Portal must refuse; its tests show what is checked.

6. **Sign it with your own key and keep that key.** Portal remembers who signed an extension. If the signer changes, nothing is allowed until an adult allows it again.

7. **Share it.** Anyone can install your APK like any other app, and it shows up as "Needs your permission" with your signer's fingerprint. To be listed for Browse extensions, follow [CATALOG.md](CATALOG.md): an entry with your APK's link, checksum and signer, added by pull request.

Extensions are not covered by Portal's no-ads, no-tracking promise, and Portal says so on the Extensions page. If your extension collects anything, say what, on your own page and in your own app.
