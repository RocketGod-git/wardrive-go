# Wardrive Go (Android)

A passive wardriving app for Android. It maps Wi-Fi, Bluetooth, and cellular networks on a live map, and can upload discoveries to [WiGLE](https://wigle.net) and
[WDGWars](https://wdgwars.pl).

## Features

- Live radar and map (dark/light) of nearby Wi-Fi, Bluetooth, and cellular networks
- WiGLE and WDGWars upload (streamed CSV), plus CSV import/export
- Privacy controls: exclusion zones, per-device blacklist, and `_nomap`/`_optout` honoring
- High-gain USB (OTG) monitor-mode adapter support for long-range Wi-Fi capture
- Channel and spectrum analyzers (2.4 / 5 / 6 GHz)
- Android Auto map (navigation-category, renders the app's own map on the car screen)
- Wear OS companion with a live-stats tile and complication
- Also detects offensive devices such as Flock Cameras and Axon Body Cameras

## Modules

- `app/` - the phone application (`com.rocketgod.warble`)
- `wear/` - the Wear OS companion

## Building

Requirements:

- JDK 17
- Android SDK (compileSdk 36)
- Gradle 8.x (or open the project in Android Studio, which manages the Gradle wrapper for you)

Build from the command line:

```bash
gradle :app:assembleDebug        # phone, debug
gradle :wear:assembleDebug       # wear companion, debug
```

Release builds are debug-signed by default so the project builds without any secrets. To sign a
release with your own keystore, set these environment variables before building:

```
ANDROID_KEYSTORE_PATH        # path to your .keystore
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

## Configuration

WiGLE and WDGWars API credentials are entered in the app (Settings | API keys) and stored only on
the device. No keys are bundled with the source.

## Note

I first made the iOS app WarBLE Go, and ported the Bluetooth and UI portions of that app here
so there are many lingering WarBLE references which I was afraid to find/replace and break things.

Development is ongoing and this is a snapshot of build 2.0.34

## License


