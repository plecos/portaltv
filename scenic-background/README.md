# Scenic Travel

An animated background for Portal, made as a Portal extension: a side-scrolling journey through the countryside that follows
the real sky, sun, moon and weather. It is a plain Android app with no libraries; Portal binds its background service and
shows the picture it draws (see [docs/EXTENSIONS.md](../docs/EXTENSIONS.md)).

## What it does

- **Travels.** The world scrolls right to left, each layer at its own speed: mountains, hills, trees, farms, fences, the road and
  its traffic, poles and grass. Everything is worked out from the position and the time, so there is nothing stored and the
  same place always looks the same.
- **Follows the sky.** The sun and moon are placed from the real date, time and the TV's place (from Portal's `weather.get`).
  Sunrise and sunset colour the sky, clouds and light together. The moon shows the real phase and which side is lit.
- **Follows the weather.** Cloud, rain, snow, fog and storms (with lightning) come from `weather.get`. Every part eases toward
  what it should be, so a change of weather fades in over a minute or so instead of jumping. Snow settles on the ground.
- **Looks like an old computer.** The scene is drawn at 320 by 180 pixels, dithered and limited to a small palette, then scaled
  up whole, so every pixel is a clean square on the TV. Only one style so far; more can be added as other renderers.
- **Looks after a slow TV.** It aims for 30 frames a second and steps down to 20, 15 or 10 (and drops the dithering and some
  of the rain) if drawing is taking too long.

## Settings

Under Settings, Extensions, Scenic Travel (with a live preview): follow the real weather or always show one, follow the real
time or always show an hour, scrolling on or off, and the place and drawing style (one of each for now).

## Trying it

```
./gradlew :scenic-background:assembleDebug
adb install -r scenic-background/build/outputs/apk/debug/scenic-background-debug.apk
```

Then allow it under Settings, Extensions (it asks for a settings page, the background and the weather) and choose
"Extension" under Settings, Background.

The debug build also has a **Scenic preview** screen on its own (started with adb, as below): full screen, with keys to scrub through the day and the weather
(left and right move the time, up and down change the weather, OK starts and stops the journey, play/pause turns on a
time-lapse). Intent extras set where it starts, for taking pictures:

```
adb shell am start -n net.getportaltv.scenic/.PreviewActivity --ef hour 18.5 --es weather Rain --ez hide_readout true
```

(`--ei days 9` looks at another day, which is how to check the moon's phases.)

## Where things are

| File | What it does |
| --- | --- |
| `Astronomy.kt` | Sun and moon positions and phase, tested against known dates |
| `Look.kt` | The light: sky, tint, clouds and fog from the sun's height and the weather |
| `Conditions.kt` | The weather as numbers, and how it eases from one to another |
| `Director.kt` | Moves the scene on a frame: time, journey, weather, sun and moon, lightning |
| `PixelScene.kt` | Draws everything and applies the palette |
| `Sprites.kt` | The small pictures, drawn as text |
| `ScenicView.kt`, `ScenicService.kt` | Showing it, and answering Portal |
| `SettingsActivity.kt`, `Settings.kt` | The settings page and what it saves |
| `PortalWeather.kt` | Asking Portal for the weather every minute |
