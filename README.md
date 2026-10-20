# Cascade Launcher

A minimal, open-source Android home-screen launcher inspired by the *one-handed, list-based*
style of launchers like Niagara. Written from scratch in plain Java with only Android framework
APIs: no AndroidX, no Gradle, no tracking, no network permission. The APK is about 40 KB.

## Features

- **Home screen**: big clock and date at the top (tap for alarms or calendar), and your favourite
  apps as a text list placed low on the screen for thumb reach.
- **Alphabet wave scroller**: drag along the letters on the right edge. Letters bulge outwards
  around your finger with haptic ticks, and the app list jumps to that letter.
- **App drawer + search**: swipe up. Matches are ranked by prefix, then word prefix, then substring
  (accent-insensitive). Press *Go* to launch the top hit, or search the web if nothing matches.
- **Swipe down** on the home screen to pull down the notification shade.
- **Long-press an app**: app shortcuts (when Cascade is your default launcher), add to or remove
  from home, move up/down, hide, app info, uninstall.
- **Long-press empty space**: settings (wallpaper, show/hide home icons, notification dots,
  hidden apps, set as default launcher).
- **Notification dots** (optional): grant notification access from settings.
- **Work profile** support (badged icons), and live updates when apps are installed or removed.

## Install

1. Copy `CascadeLauncher.apk` to your phone and open it (allow "install unknown apps" for your
   file manager or browser).
2. Open Cascade. It asks to become your home app. You can also go to
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
src/app/cascade/launcher/
  MainActivity.java          home screen, drawer, gestures, menus, settings
  AlphabetScrollerView.java  the A–Z wave strip
  AppRepository.java         loads apps for all profiles via LauncherApps, watches changes
  AppInfo.java               app model (label, icon, section letter, search key)
  Prefs.java                 favourites, hidden apps, options
  NotificationDotService.java  optional notification listener for dots
```

## License

MIT. See `LICENSE`.
