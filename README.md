# Real FM Radio [![Releases](https://img.shields.io/github/downloads/vladislav805/RFM-Radio/total)](https://github.com/vladislav805/RFM-Radio/releases/latest)

Real FM hardware radio app for Android Qualcomm Snapdragon based devices.

[<img alt="IzzyRepo" src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" width="176" />](https://apt.izzysoft.de/fdroid/index/apk/com.vlad805.fmradio)

## Requirements

- Android 6.0+ (SDK 23+).
- Root access is required, especially on AOSP-based ROMs.

## Features

- Live FM playback using supported hardware backend.
- RDS metadata: PS, RT, PTY.
- Recording to `.wav` and `.mp3`.
- Favorites (presets) with custom names.
- Multiple favorite lists.
- Favorite list manager screen with import/export.

## Favorites UX

- Main screen: tune quickly, reorder presets, switch active favorite list.
- Favorite Lists screen: manage lists (create, rename, remove, import, export).

## Settings highlights

### Tuner

#### Enable RDS

RDS is a radio data system.
When this option **is enabled**, the application will display data transmitted from the radio station, such as the Program Service and Radio Text.

#### Antenna

Select an antenna. In most cases: 0 - default, headphones; 1 - internal (bad quality).

### Application
#### Autostart

If enabled, audio from the radio will be immediately turned on after starting the application.


### Notifications
#### Show PS in title

If the option is enabled, then with the "Enable RDS" option enabled, the Program Service and Radio Text will be displayed in the notification header.


## Screenshots

<img src="images/main.png" width="33%" alt="Main screen"/><img src="images/settings.png" width="33%" alt="Settings screen"/><img src="images/favorites.png" width="33%" alt="Favorites screen"/>

## Building

```bash
./gradlew :app:assembleDebug
```

For native/FM backend-related work you also need Android NDK headers and device-specific tooling.

## Inspired by

> Spirit1 and Spirit2 by Mike Reid.
>
> R.I.P. 2016

## Disclaimer

The project is based on hard-to-piece collected documentation around Tavarua, Iris, I2C, V4L2.

## Contributors

- Vladislav Veluga ([@vladislav805](https://github.com/vladislav805)) — developer
- Mike Reid ([@mikereidis](https://github.com/mikereidis)) — developer of Spirit 1/2
- Konstantin Manaev ([@SoSlowMan](https://github.com/SoSlowMan)) — translator (English)
- Andus ([@AndusDEV](https://github.com/AndusDEV)) — translator (Polish)
