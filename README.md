# AerialViews-Extended

AerialViews-Extended is a GPL-licensed fork of [theothernt/AerialViews](https://github.com/theothernt/AerialViews) for Android TV and Google TV devices. This fork keeps the original screensaver experience and adds a native YouTube video source that works on-device with NewPipe Extractor, without a server or YouTube API key.

## Features

Inherited from AerialViews:

- Apple, Amazon, community, and local media sources for Android TV screensaver playback
- USB, Immich, Samba, WebDAV, and custom feed support
- Overlay options such as clock, date, metadata, and custom text
- Playlist and shuffle controls, burn-in reduction, and media skipping controls
- Refresh-rate matching and high-resolution playback on supported devices

NEW in this fork:

- NEW: Native YouTube video source for Android TV screensaver playback
- NEW: On-device YouTube search and stream extraction using NewPipe Extractor
- NEW: No API key, no external server, and no hosted backend
- NEW: Local Room cache for YouTube search results with automatic refresh
- NEW: Automatic stream URL renewal before YouTube stream links expire
- NEW: Mixed source playback support so YouTube can be weighted alongside built-in AerialViews media

## Screenshots / Preview

Screenshots will be added here later.

## Installation

1. Download the latest APK from the GitHub Releases tab.
2. Enable `Install unknown apps` / `Install from unknown sources` on your Android TV device.
3. Install the APK with a file manager on the device, or with ADB:

```sh
adb install -r app-github-nonMinifiedRelease.apk
```

## Requirements

- Android TV / Google TV / Fire TV device
- Android 6.0 or newer (`minSdk 23`)
- Internet connection for the YouTube source

## How It Works

When the YouTube source is enabled, the app builds a rotating set of YouTube search queries directly on the device. It uses NewPipe Extractor to search YouTube and resolve playable video streams without a Google API key and without sending requests through a custom server.

Search results are cached locally in Room for about 24 hours. Expiring stream URLs are refreshed automatically before playback, and background refresh runs through WorkManager so the cache stays warm over time. If a refresh fails, the app falls back to the last working local cache instead of stopping playback immediately.

## Known Limitations

- Sideload only. This fork is intended for GitHub Releases and manual install, not Google Play distribution.
- The YouTube source depends on NewPipe Extractor. If YouTube changes its internals, extraction may temporarily break until NewPipe is updated.
- Some YouTube videos can be skipped because of age restrictions, regional restrictions, removed uploads, or unavailable streams.
- An internet connection is required for the initial YouTube cache build and later refreshes.

## Building from Source

This project uses Gradle and JDK 21.

```sh
./gradlew :app:assembleGithubNonMinifiedRelease
```

Release APK output:

```text
app/build/outputs/apk/github/nonMinifiedRelease/app-github-nonMinifiedRelease.apk
```

## Contributing

Issues and pull requests are welcome.

If something breaks, especially after a YouTube or NewPipe change, please open an issue and include:

- Device model
- Android version
- App version
- Whether the problem happens in YouTube-only mode or mixed-source mode

See [.github/CONTRIBUTING.md](./.github/CONTRIBUTING.md) for the contribution notes used in this fork.

## Credits

See [CREDITS.md](./CREDITS.md) for attribution, upstream credit, and third-party library details.

## License

This project is licensed under the GNU General Public License v3.0.

See the [LICENSE](./LICENSE) file for details.

## Disclaimer

This project is not affiliated with YouTube, Google, Apple, Amazon, or the original AerialViews maintainer. The YouTube source uses NewPipe Extractor for on-device stream extraction. This fork is distributed for personal sideload use and open-source development.
