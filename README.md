# Chinook Launcher

A minimal, open-source Android home-screen launcher inspired by the *one-handed, list-based*
style of launchers like Niagara. Written from scratch in plain Java with only Android framework
APIs: no AndroidX, no Gradle, no tracking, no network permission. The APK is about 220 KB, most of it the bundled font.

## Features

- **Top card slider**: the top of the home screen is a row of swipeable cards: the clock card
  (time, date, next alarm, world clocks), any widgets (weather, calendar, wallet…) and app-shortcut
  cards (up to 8 apps each). Add as many as you like; with 3+ cards it loops endlessly. Long-press a
  card to move, remove or add cards, choose its apps, or change the card height.
- **Home apps**: your favourites sit low on the screen as a big text list, within thumb reach.
- **Letter pop-out**: slide along the ★ / # / A–Z strip on the right. ★ shows your home apps. The letters ripple outward, and that
  letter's apps fan out beside your finger. Slide left onto an app and let go to open it, or let go
  on the strip to keep the list open and tap.
- **World clocks**: add any cities. Each shows its local time, with +1/−1 when it's a different day.
- **Lock screen designer**: generated minimal wallpapers (Aurora, Dusk, Ocean, Graphite, Midnight,
  Dark waves, Pure black, Mono waves, Horizon, Mono grain) with an optional note line, applied to
  the lock screen or both screens. Android still draws the lock-screen clock itself.
- **Edit mode**: long-press empty space → *Edit home screen*. Reorder (↑ ↓) or remove (✕) home
  apps, and add apps, top cards and clocks from one bar.
- **Themes**: *Colored* (wallpaper + blue accent), *Dark* (dimmed wallpaper, muted grey accent),
  *Black & White* (pure black AMOLED background, grayscale icons and widgets).
- **Search**: swipe up. Matches are ranked by prefix, then word prefix, then substring. Press *Go*
  to launch the top hit, or search the web if nothing matches.
- **Swipe down** for notifications. **Long-press an app** for app shortcuts, add/remove from home,
  hide, app info and uninstall.
- Optional notification dots, hidden apps, work-profile support, Manrope or system font.

## Install

1. Copy `ChinookLauncher.apk` to your phone and open it (allow "install unknown apps" for your
   file manager or browser).
2. Open Chinook. It asks to become your home app. You can also go to
   Settings → Apps → Default apps → Home app.

## Build

No Gradle needed. On Debian/Ubuntu:

```sh
sudo apt install openjdk-21-jdk aapt apksigner zipalign dalvik-exchange
./build.sh
```

`build.sh` downloads an API-34 `android.jar` if `sdk/android.jar` is missing, then compiles the
resources (aapt), compiles the Java (javac), dexes it (dx), and zipaligns and signs it (apksigner).
The first build creates a signing key in `keystore/`. Keep it: Android only installs updates
signed with the same key.

## Project layout

```
AndroidManifest.xml
res/                         theme, colours, adaptive icon
assets/fonts/                Manrope (SIL Open Font License)
src/app/chinook/launcher/
  MainActivity.java          home screen, letter pop-out, drawer, edit mode, menus, settings
  TopCards.java              top card slider: clock, widget and app cards; add/move/remove
  CardPager.java             endless horizontal pager
  WidgetBinder.java          widget host: pick, bind, configure
  LockScreenDesigner.java    lock-screen wallpaper styles
  WidgetHostView.java        widget view that catches long-press
  WorldClocks.java           time-zone list, names, day offsets
  Theme.java                 Colored / Dark / Black & White palettes
  Fonts.java                 bundled or system font
  AlphabetScrollerView.java  the A–Z wave strip
  AppRepository.java         loads apps for all profiles via LauncherApps, watches changes
  AppInfo.java               app model (label, icon, section letter, search key)
  Prefs.java                 favourites, hidden apps, options
  NotificationDotService.java  optional notification listener for dots
```

## License

MIT. See `LICENSE`. The Manrope font is under the SIL Open Font License (`assets/fonts/OFL.txt`).
