# AerialViews+

[![Latest GitHub release](https://img.shields.io/github/v/release/naveeneppalapally/AerialViews-Plus.svg?logo=github&label=GitHub&cacheSeconds=3600)](https://github.com/naveeneppalapally/AerialViews-Plus/releases/latest)
[![GitHub Downloads](https://img.shields.io/github/downloads/naveeneppalapally/AerialViews-Plus/total?color=blue&label=Downloads&logo=github)](https://github.com/naveeneppalapally/AerialViews-Plus/releases/latest)
[![License](https://img.shields.io/:license-gpl%20v3-lightgrey.svg?style=flat)](https://raw.githubusercontent.com/naveeneppalapally/AerialViews-Plus/main/LICENSE)
[![API](https://img.shields.io/badge/API-23%2B-lightgrey.svg?style=flat)](https://android-arsenal.com/api?level=23)

A fork of [AerialViews](https://github.com/theothernt/AerialViews) that adds YouTube as a native video source. Plays fresh 4K nature, aerial, and ambient videos from YouTube directly on your Android TV. No API key, no account, no server required.

## Everything from AerialViews

- 4K Dolby Vision (HDR) videos if your TV supports it
- Over 250 videos from Apple, Amazon, Jetson Creative and Robin Fourcade
- USB, Immich, Samba, WebDAV, and custom feed support
- Clock, date, location, now playing, and custom text overlays
- Burn-in protection by alternating overlay positions
- Playlist controls, shuffle, skip, and media length limits
- Refresh rate switching for 24fps and 50fps content

## New in this fork

- YouTube as a native video source, no API key or account needed
- On-device search using [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) v0.26.0
- Local cache of up to 200 videos, refreshed daily in the background
- 8 content categories: Nature, Wildlife, Drone, Ocean, Space, Cities, Weather, Winter
- Filters out AI-generated videos, vlogs, and talking head content
- Videos never repeat within a 7-day window
- Stream URLs auto-renewed before YouTube links expire
- YouTube mixes with built-in sources in the same playlist
- Projectivy Launcher integration

## Installation

1. Download the latest APK from the [Releases tab](https://github.com/naveeneppalapally/AerialViews-Plus/releases/latest)
2. On your Android TV, enable `Install unknown apps` in Settings
3. Transfer the APK to your TV and install it with a file manager

> Play Protect may show a warning during install. Tap **Install anyway**. This is normal for all sideloaded apps. Source code is fully open above.

This app is not on the Play Store and never will be. See the FAQ for why.

## How to set AerialViews+ as the default screensaver

> These setup instructions are taken directly from the original
> [AerialViews](https://github.com/theothernt/AerialViews) by
> [Neil Turner](https://github.com/theothernt) and are reproduced
> here with credit and thanks. The steps are identical for this fork.

Since 2023, most devices running Google TV (Android TV 12 or later) have no interface to change the screensaver to a third-party app. This includes:

- Chromecast with Google TV, Google TV Streamer
- Recent MECOOL devices
- Recent TCL, Philips, and Sony TVs
- onn. Google TV devices (excluding the 2021 model)
- Fire TV (does not work with Fire OS 8.1 and above)

It can still be done using ADB commands. The steps are:

1. Enable Developer mode and USB debugging on your TV
2. Find the IP address of your TV
3. Connect from your phone, Mac, or PC
4. Run two ADB commands to set AerialViews+ as the screensaver

Full instructions are below. Click or tap each section to expand.

Another option is the [TDUK Screensaver Manager](https://play.google.com/store/apps/details?id=com.tduk.scrmgr) app, which handles this without ADB commands.

<details>
<summary>Enable Developer Mode on Android/Google TV</summary>
&nbsp;

Navigate to Settings, then the About screen. Depending on your device:

`Settings > System > About` or `Settings > Device Preferences > About`

Scroll to **Build** and select it several times until you see "You are now a developer!"

Return to Settings and find the newly enabled **Developer options** page. Enable **USB debugging**.

Then find the IP address of your device in `Settings > Network & Internet`.
</details>

<details>
<summary>Enable Developer Mode on Fire Stick/TV</summary>
&nbsp;

Open **Settings**, then go to **My Fire TV > About**.

Highlight your device name and press the action button seven times until you see "You are now a developer".

Go to **Developer Options** and enable **ADB debugging**.

Find your IP address at **About > Network**.
</details>

<details>
<summary>Allow Auto Launch on TCL TVs</summary>
&nbsp;

TCL TVs with Google TV require an extra Auto Launch permission, otherwise the screensaver cannot start automatically.

1. Open the **Safety Guard** app on your TV
2. Go to `Permission Shield > Auto Launch Permission`
3. Set **Auto manager** at the top to `Closed`
4. Find **AerialViews+** and set it to `Opened`

If Safety Guard is not on your TV, use this ADB command instead.

Android below v14:
```sh
appops set com.naveen.aerialviewsplus APP_AUTO_START allow
```

Android v14 and above:
```sh
appops set com.naveen.aerialviewsplus AUTO_START allow
```
</details>

<details>
<summary>TCL troubleshooting: screensaver not triggering automatically</summary>
&nbsp;

If AerialViews+ does not trigger automatically on a TCL Android TV, first re-enable the app package. In tested cases, this alone restored normal screensaver behavior.

```sh
adb shell pm enable com.naveen.aerialviewsplus
```

Optional checks (only if you still have issues):

```sh
adb shell pm list packages -d | grep naveen
adb shell settings get secure screensaver_components
```

If the issue persists, then apply the TCL Auto Launch permission steps in the section above.
</details>

<details>
<summary>Connect using an iPhone</summary>
&nbsp;

Download [iSH Shell](https://ish.app/) from the App Store (free), then install ADB:

```sh
apk update
apk add android-tools
```

Verify it works:
```sh
adb version
```
</details>

<details>
<summary>Connect using an Android phone</summary>
&nbsp;

Download [Termux](https://play.google.com/store/apps/details?id=com.termux) from the Play Store (free), then install ADB:

```sh
pkg update
pkg install android-tools
```

Verify it works:
```sh
adb version
```
</details>

<details>
<summary>Connect using a Mac</summary>
&nbsp;

Download [SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools) for Mac, extract the ZIP, then open Terminal in that folder.

Verify it works:
```sh
adb version
```
</details>

<details>
<summary>Connect using a PC</summary>
&nbsp;

Download [SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools) for Windows, extract the ZIP, then open Command Prompt in that folder.

Verify it works:
```sh
adb version
```
</details>

<details>
<summary>ADB command - set AerialViews+ as the screensaver</summary>
&nbsp;

Connect to your TV over the network:
```sh
adb connect [your TV IP address]
```

Then run:
```sh
settings put secure screensaver_components com.naveen.aerialviewsplus/.ui.screensaver.DreamActivity
settings put secure screensaver_enabled 1
```

To confirm it worked:
```sh
settings get secure screensaver_components
```

You should see:
```sh
com.naveen.aerialviewsplus/.ui.screensaver.DreamActivity
```
</details>

<details>
<summary>ADB command - extra steps for Fire TV and Fire OS 7.6.x</summary>
&nbsp;

```sh
settings put secure screensaver_default_component com.naveen.aerialviewsplus/.ui.screensaver.DreamActivity
settings put secure contextual_screen_off_timeout 300000
settings put secure screensaver_enabled 1
```
</details>

<details>
<summary>ADB command - extra steps for Fire TV and Fire OS 8.1.x</summary>
&nbsp;

Fire OS 8.x has a built-in Ambient Experience that must be disabled first:

```sh
settings put secure amazon_ambient_enabled 0
```

Reboot your Fire TV, then set AerialViews+ as the screensaver using the commands above.
</details>

<details>
<summary>ADB command - change the screensaver timeout</summary>
&nbsp;

The value is in milliseconds. 5 minutes is 300000, 10 minutes is 600000.

```sh
settings put system screen_off_timeout 600000
```

:information_source: On Google TV devices running Android TV 12 or later, the minimum is 6 minutes (360000). Lower values will not work.

:information_source: If you use Projectivy Launcher, disable `Projectivy Launcher > Power > Enable internal idle detection`.
</details>

<details>
<summary>How to revert to the default screensaver</summary>
&nbsp;

To restore the default Google TV screensaver:
```sh
settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
```

To restore the default Fire TV screensaver:
```sh
settings put secure screensaver_components com.amazon.bueller.photos/.daydream.ScreenSaverService
```

To restore the older Android TV backdrop screensaver:
```sh
settings put secure screensaver_components com.google.android.backdrop/.Backdrop
```
</details>

<details>
<summary>Use the TDUK Screensaver Manager app</summary>
&nbsp;

The [TDUK Screensaver Manager](https://play.google.com/store/apps/details?id=com.tduk.scrmgr) is a paid app (around $2) that lets you change the screensaver using a simple on-screen interface instead of ADB commands.

Enable Developer Mode and USB/Network Debugging first. Instructions are above.

:information_source: This app does not work on recent Fire TV devices.
</details>

## How YouTube works

When YouTube is enabled, the app builds a set of search queries on-device and uses NewPipe Extractor to find and resolve playable video streams. Nothing is sent to a custom server. No Google account is needed.

Results are cached locally for up to 200 videos and refreshed daily. Stream URLs are renewed automatically before they expire. If a refresh fails, the app falls back to the last working cache so playback keeps going.

You can choose which types of content to include from the YouTube settings screen: Nature, Wildlife, Drone, Ocean, Space, Cities, Weather, and Winter. Categories can be toggled individually and the library updates without wiping existing videos.

## What to expect from YouTube videos

The built-in Apple and Amazon videos in AerialViews are a fixed, hand-picked collection. The YouTube source in this fork pulls fresh content automatically. It is a different experience.

**What you will see:**
- New videos as the cache refreshes daily
- A wide variety of locations, seasons, and subjects over time
- Content that rotates week to week

**What keeps it consistent:**
- AI-generated videos are filtered by title, channel name, and duration patterns
- Vlog, tutorial, and talking head videos are filtered out
- No more than a few videos from the same channel in one cache
- The same video will not repeat within 7 days

The Apple and Amazon sources can be used alongside YouTube or instead of it. All sources mix in the same playlist.

## FAQ

<details>
<summary>YouTube stopped working suddenly</summary>
&nbsp;

NewPipe Extractor occasionally breaks when YouTube updates its internal API. Open an issue on this repo with your device model and Android version. It is usually fixed within a few days by bumping the NewPipe version in `gradle/libs.versions.toml`.

Check [NewPipe Extractor releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases) to see if a newer version is available.
</details>

<details>
<summary>Some videos are skipped</summary>
&nbsp;

Videos may be skipped because of age restrictions, regional blocks, deleted uploads, or unavailable stream quality. The app moves on to the next video automatically.
</details>

<details>
<summary>Can I use this on Nvidia Shield?</summary>
&nbsp;

Yes. Go to `Settings > Device Preferences > Screen saver` and select AerialViews+.
</details>

<details>
<summary>Why is this not on the Play Store?</summary>
&nbsp;

The original AerialViews license prohibits any fork from being uploaded to the Play Store. Sideloading is the only option.
</details>

<details>
<summary>Does this replace AerialViews or work alongside it?</summary>
&nbsp;

It replaces it. Both apps cannot be installed at the same time. All original AerialViews features are included in this fork.
</details>

<details>
<summary>Play Protect shows a warning during install</summary>
&nbsp;

This is normal for any app not distributed through the Play Store. Tap **Install anyway**. The source code is fully open on this page if you want to review it.
</details>

## Building from source

Clone the repo and open it in Android Studio. JDK 21 and Android Studio Hedgehog or newer are required.

```sh
./gradlew :app:assembleGithubNonMinifiedRelease
```

The APK will be at `app/build/outputs/apk/github/nonMinifiedRelease/`.

## Contributing

Pull requests are welcome. For NewPipe breakage, open an issue with your device model and Android version. The fix is almost always a version bump in `gradle/libs.versions.toml`.

Please open an issue before submitting a pull request for larger changes.

## About

AerialViews+ is based on [AerialViews](https://github.com/theothernt/AerialViews) by Neil Turner, which is based on [Aerial Dream](https://github.com/cachapa/AerialDream) by Daniel Cachapa. This fork adds YouTube as a video source without requiring any external server or API key.

## Credits

See [CREDITS.md](./CREDITS.md) for full attribution.

## License

GPL v3. See [LICENSE](./LICENSE).

## Disclaimer

This fork is not affiliated with YouTube, Google, or Apple. It uses NewPipe Extractor for on-device stream extraction and is distributed for personal use only.
