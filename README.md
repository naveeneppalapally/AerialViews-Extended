# AerialViews Extended

[![Latest GitHub release](https://img.shields.io/github/v/release/naveeneppalapally/AerialViews-Extended.svg?logo=github&label=GitHub&cacheSeconds=3600)](https://github.com/naveeneppalapally/AerialViews-Extended/releases/latest)
[![GitHub Downloads](https://img.shields.io/github/downloads/naveeneppalapally/AerialViews-Extended/total?color=blue&label=Downloads&logo=github)](https://github.com/naveeneppalapally/AerialViews-Extended/releases/latest)
[![License](https://img.shields.io/:license-gpl%20v3-lightgrey.svg?style=flat)](https://raw.githubusercontent.com/naveeneppalapally/AerialViews-Extended/main/LICENSE)
[![API](https://img.shields.io/badge/API-23%2B-lightgrey.svg?style=flat)](https://android-arsenal.com/api?level=23)

A fork of AerialViews that adds YouTube as a native video source.  
Plays fresh 4K nature, aerial, and ambient videos from YouTube directly on your Android TV — no API key, no server, no login required.

## Everything from AerialViews

* 4K Dolby Vision (HDR) videos if your TV supports it
* Over 250 videos from Apple, Amazon, Jetson Creative and Robin Fourcade
* USB, Immich, Samba, WebDAV, and custom feed support
* Clock, date, location, now playing, and custom text overlays
* Burn-in protection by alternating overlay positions
* Playlist controls, shuffle, skip, and media length limits
* Refresh rate switching for 24fps, 50fps content

## New in this fork

* YouTube as a native video source (no API key, no server)
* On-device search using NewPipe Extractor
* Video local cache with automatic daily refresh
* Diversity engine so the same video never repeats
* Stream URL auto-renewal before YouTube links expire
* YouTube mixed with built-in sources in the same playlist
* Background refresh via WorkManager

## How to Get It

Download the APK from the [Releases tab](https://github.com/naveeneppalapally/AerialViews-Extended/releases/latest) and install it manually.  
Sideloading is required — this fork is not on the Play Store.

1. Download the APK from the Releases tab
2. Enable `Install unknown apps` on your Android TV
3. Install via a file manager, or use ADB:

```sh
adb install -r app-github-nonMinifiedRelease.apk
```

## How to set AerialViews Extended as the default screensaver

> These setup instructions are taken directly from the original 
> [AerialViews](https://github.com/theothernt/AerialViews) by 
> [Neil McAlister](https://github.com/theothernt) and are reproduced 
> here with credit and thanks. The steps are identical for this fork.

Since 2023, nearly all devices that ship with Google TV, running Android TV 12 or later, have no user-interface to change the screensaver to a 3rd party one...

* __Chromecast with Google TV, Google TV Streamer__
* __Recent MECOOL devices__
* __Recent TCL, Philips, and Sony TVs__
* __onn. Google TV devices (excluding the 2021 model)__
* __Fire TV (won't work with Fire OS 8.1 and above)__

But it can be done manually. Here is an overview of the steps...

1. Enable Developer mode, enable USB debugging, then find the IP address of your device
2. Use a Mac, iPhone, PC or Android phone with the required software or app
3. Connect to your Android/Google/Fire TV device
4. Run two ADB commands, one to set Aerial Views as the default screensaver, the other to set how long it takes the screensaver to start

The full instructions are below, please click or tap to expand each step.

Another option is to use the *TDUK Screensaver Manager* app. Details on the app are below.

<details>
<summary>Enable Developer Mode on your Android/Google TV</summary>
&nbsp;

Navigate to the Settings menu on your device, then to the About screen. Depending on the device…

`Settings > System > About` or
`Settings > Device Preferences > About`

Scroll down to __Build__ and select __Build__ several times until you get the message "You are now a developer!"

Return to __Settings__ or __Settings > System__ and look for the newly enabled __Developer options__ page.

On the __Developer options__ page, look for the __USB debugging__ option and enable it.

Next, find the __IP address__ of your device. Try looking in the Network & Internet settings of the device, check the properties of the current LAN or WIFI connection - that should list the current IP address eg. 192.168.1.105
</details>

<details>
<summary>Enable Developer Mode on Fire Stick/TV</summary>
&nbsp;

Open __Settings__, then navigate to __My Fire TV__ then the __About__ screen.

Highlight your device name and press the action button on your remote seven times.

You'll now see a message confirming "You are now a developer", and it'll unlock the __Developer Options__ in the previous menu.

Navigate to the __Developer Options__ page, look for the __ADB debugging__ option and enable it.

Next, find the IP address of your device and make a note of it. Navigate to the __About__ then __Network__ screen, which will show your current IP address eg. 192.168.1.120
</details>

<details>
<summary>Allow Auto Launch on TCL TVs</summary>
&nbsp;

If you have a TCL TV with Google TV, you need to allow the Auto Launch permission so that Aerial Views can be launched from the background when the screensaver starts.

Otherwise, the screensaver cannot be started, either automatically, or manually via the Screensaver menu shortcut, unless the Aerial Views app has been recently opened (see [#191](https://github.com/theothernt/AerialViews/issues/191) for details).

1. Open the __Safety Guard__ app on your TV
2. Navigate to `Permission Shield > Auto Launch Permission`
3. Change the `Auto manager` at the top to `Closed` - this allows you to manually select which apps can auto-launch instead of the system deciding automatically
4. Scroll to __Aerial Views__ and change it to `Opened`

Not all TCL TVs have the same software and features. If the above __Safety Guard__ app does not exist on your TV, the following ADB command might help…

Android <v14:

```sh
appops set com.neilturner.aerialviews APP_AUTO_START allow
```

Androind >=v14

```sh
appops set com.neilturner.aerialviews AUTO_START allow
```

You can confirm the available options:

```sh
appops get com.neilturner.aerialviews

# Returns
WAKE_LOCK: allow; time=+5m55s466ms ago; duration=+2s889ms
READ_MEDIA_IMAGES: allow; time=+1m43s406ms ago
READ_MEDIA_VISUAL_USER_SELECTED: allow; time=+1m43s408ms ago
AUTO_START: ignore; rejectTime=+7m6s72ms ago
```

</details>

<details>
<summary>Connect using an iPhone</summary>
&nbsp;

Find an iPhone app that is capable of running ADB commands, [such as iSH Shell](https://ish.app/), which is free.

Once installed, run the app and install the Android Tools with the following commands…

```sh
apk update
apk add android-tools
```

To check if the ADB command is working, try typing…

```sh
adb version 
```

After pressing return, you should see something like this

```sh
Android Debug Bridge version 1.0.41
Version  31.0.0p1-android-tools
```

Now you can execute ADB commands.
</details>

<details>
<summary>Connect using an Android phone</summary>
&nbsp;

Find an Android app that is capable of running ADB commands, [such as Remote Termux](https://play.google.com/store/apps/details?id=com.termux), which is free.

Once installed, run the app and install the Android Tools with the following commands…

```sh
pkg update
pkg install android-tools
```

To check if the ADB command is working, try typing…

```sh
adb version 
```

After pressing return, you should see something like this

```sh
Android Debug Bridge version 1.0.41
Version  34.0.0p1-android-tools
```

Now you can execute ADB commands.

</details>

<details>
<summary>Connect using a Mac</summary>
&nbsp;

Download the official [SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools) for Mac.

Extract the files from the ZIP archive to a folder. Then open a Terminal or Command Prompt and navigate to the folder.

To check if the ADB command is working, try typing…

```sh
adb version
```

After pressing return, you should see something like this

```sh
Android Debug Bridge version 1.0.41
Version  35.0.0-11411520
```

Now you can execute ADB commands.
</details>

<details>
<summary>Connect using a PC with Windows</summary>
&nbsp;

Download the official [SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools) for Windows.

An alternate option is [Tiny ADB and Fastboot Tool (Portable version)](https://androidmtk.com/tiny-adb-and-fastboot-tool) but they both work in the same way.

Extract the files from the ZIP archive to a folder. Then open a Terminal or Command Prompt and navigate to the folder.

To check if the ADB command is working, try typing…

```sh
adb version
```

After pressing return, you should see something like this

```sh
Android Debug Bridge version 1.0.41
Version  35.0.0-11411520
```

</details>

<details>
<summary>Extra steps for Chromecast with Google TV HD &amp; 4K</summary>

Since the update to Android TV 14, the Chromecast with Google TV (CCwGTV) has a bug which affects ADB. Normally, ADB uses port 5555 but on the CCwGTV the port is randomised.

This means the `adb connect <ip_address>` command will likely fail with a 'connection refused' message.

As a work-around, we can use the wireless debugging connection...

1. In `Settings > System > Developer options > Wireless debugging`, select Pair device with pairing code

2. Run the command: `adb pair <ip_address>:<port>`

3. The IP and port are displayed on the TV upon selecting Pair device with pairing code

4. ADB then asks for the pairing code displayed on the screen.

5. Once entered, you should see a message confirming that wireless debugging is connected.

You should be connected to your CCwGTV device with ADB. Now you can run `adb shell` and the other commands in the instructions that follow.

:information_source: *There are other ways to fix this issue, like using a feature in tvQuickActions Pro. [See TDUK's video for more details.](https://www.youtube.com/watch?v=WVRBXktSw0c)*

</details>

<details>
<summary>ADB command - set Aerial Views as the default screensaver</summary>
&nbsp;

Connect to your Android TV device and start a command shell...

```sh
adb connect <ip_address>
```

:information_source: *Use the IP address of your device from earlier steps, it should be something like 192.168.1.98*

```sh
adb shell
```

:information_source: *The first time you connect to your Android TV device, you will probably see a confirmation dialogue asking to "allow" the connection*

Next, set Aerial Views as the default screensaver with this command…

```sh
settings put secure screensaver_components com.neilturner.aerialviews/.ui.screensaver.DreamActivity
```

Optional: Confirm that the command was run successfully, as there is no confirmation when the command above is run.

```sh
settings get secure screensaver_components
```

If set correctly, you should see... 

```sh
com.neilturner.aerialviews/.ui.screensaver.DreamActivity
```

</details>

<details>
<summary>ADB command - extra command for Fire TV + Fire OS 7.6.x.x</summary>
&nbsp;

Recent updates to Fire OS mean extra commands are required for Aerial Views to function properly as the default screensaver.

Like with previous ADB commands, connect to your Android TV device and start a command shell. Then run the following commands...

```sh
settings put secure screensaver_default_component com.neilturner.aerialviews/.ui.screensaver.DreamActivity
settings put secure contextual_screen_off_timeout 300000 
settings put secure screensaver_enabled 1
```

</details>

<details>
<summary>ADB command - extra command for Fire TV + Fire OS 8.1.x.x</summary>
&nbsp;

Fire OS 8.x introduces a new Ambient Experience screensaver. This must also be disabled for a 3rd party screensaver, like Aerial Views, to run normally.

To disable the Ambient Experience, run this ADB command...

```sh
settings put secure amazon_ambient_enabled 0
```

Then reboot your Fire TV for the setting to take effect.

</details>

<details>
<summary>ADB command - change the screensaver timeout</summary>
&nbsp;

To change the default timeout use this command with a value in milliseconds. So, 5 minutes is 300000, 10 minutes is 600000 and so on.

```sh
settings put system screen_off_timeout 600000
```

:information_source: *On modern Google TV devices (Android TV 12+), the minimum value is 6 minutes or 360000. If you set a value lower than this, the screensaver won't start.*

:information_source: *If you are using Projectivy launcher, make sure to disable: Projectivy Launcher settings > Power > Enable internal idle detection*


</details>

<details>
<summary>How to revert back to the default screensaver</summary>
&nbsp;

For whatever reason, if you would like to stop using Aerial Views and revert back to the original screensaver, there are two options…

* Reset your device. Doing so will also reset the screensaver preference
* Use an ADB commands to enable the default screensaver, depending on your device

1. Follow the instructions above to connect to your Android/Google TV device using an iPhone, Android phone, Mac, PC, etc
2. Run one of the following commands...

### To restore the default Google TV ambient screensaver

```sh
settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
```

### To restore the default Fire TV screensaver

```sh
settings put secure screensaver_components com.amazon.bueller.photos/.daydream.ScreenSaverService
```

### To restore the default (older) Android TV backdrop screensaver

```sh
settings put secure screensaver_components com.google.android.backdrop/.Backdrop
```

</details>

<details>
<summary>Use the TDUK Screensaver Manager app</summary>
&nbsp;

The [TDUK Screensaver Manager](https://play.google.com/store/apps/details?id=com.tduk.scrmgr) is a paid app (approx. $2/£2/€2) which allows you to easily change the active screensaver on your Android/Google TV device using a simple interface.

Please make sure to enable **Developer Mode** and **USB/Networking Debugging**. Instructions are above.

:information_source: This app will not work on recent Fire TV devices due to changes by Amazon.

</details>

## How YouTube Works

When YouTube is enabled as a source, the app builds a rotating set of search queries on-device and uses NewPipe Extractor to find and resolve playable video streams — no Google account needed, no API key, nothing sent to a custom server.

Results are cached locally for about 24 hours. Stream URLs are refreshed automatically before they expire, and a background job keeps the cache warm overnight. If a refresh fails, the app falls back to the last working cache so playback never stops.

## About the YouTube Videos

Unlike the built-in Apple and Amazon videos in AerialViews — which are 
a fixed, hand-picked collection — the YouTube source in this fork pulls 
fresh content automatically. This is intentional, but it comes with a 
different experience worth understanding.

**What you will see:**
- New videos appear regularly as the cache refreshes daily
- Content changes week to week as search results rotate
- A wide variety of locations, seasons, and subjects over time

**What keeps it feeling consistent:**
- Only videos longer than 8 minutes are included — no short clips
- Keywords like "ambient", "no music", and "no talking" filter out 
  videos with narration, intros, or background music
- AI-generated videos are filtered out by title, channel name, 
  and duration patterns
- Channel caps (max 3 videos per channel) prevent any single 
  creator from dominating the playlist
- Location diversity caps prevent the same place appearing 
  back to back
- Play history tracking means you will not see the same video 
  twice in a short period

**The honest tradeoff:**
The original AerialViews videos are carefully curated and always 
look cinematic and consistent. The YouTube source trades that 
stability for variety and freshness — you get new content every 
day instead of the same 250 videos forever. Some videos will be 
better than others. If a video looks out of place, the app moves 
on automatically after it finishes.

If you want the stable, curated experience, enable Apple or 
Amazon sources alongside YouTube or instead of it. All sources 
can be mixed in the same playlist.

## FAQ

Please click or tap to expand each item below...

<details>
<summary>YouTube stopped working suddenly</summary>
&nbsp;

NewPipe Extractor occasionally breaks when YouTube updates its internal API. This is fixed by updating the NewPipe version.

Check [github.com/TeamNewPipe/NewPipeExtractor/releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases) for the latest version and open an issue on this repo — it is usually fixed within a few days.
</details>

<details>
<summary>Some videos are skipped</summary>
&nbsp;

Videos may be skipped due to age restrictions, regional blocks, deleted uploads, or unavailable stream quality. The app moves on to the next video automatically.
</details>

<details>
<summary>Can I use this on Nvidia Shield?</summary>
&nbsp;

Yes. Go to `Settings > Device Preferences > Screen saver` and select AerialViews Extended.
</details>

<details>
<summary>Why is this not on the Play Store?</summary>
&nbsp;

The original AerialViews license prohibits any fork from being uploaded to the Play Store. Sideloading is the only option.
</details>

<details>
<summary>Does this replace AerialViews or work alongside it?</summary>
&nbsp;

It replaces it — install one or the other, not both. All original features are included in this fork.
</details>

## Building from Source

Requires JDK 21.

```sh
./gradlew :app:assembleGithubNonMinifiedRelease
```

Output:

```text
app/build/outputs/apk/github/nonMinifiedRelease/
```

## Contributing

Pull requests are welcome. This fork is maintained with AI assistance, so turnaround on complex fixes may take a few days.

For NewPipe breakage: just open an issue with your device model and Android version. The fix is almost always a one-line version bump in `gradle/libs.versions.toml`.

Please open an issue before submitting a pull request for larger changes.

## About

AerialViews Extended is based on [AerialViews](https://github.com/theothernt/AerialViews) by Neil McAlister, which itself is based on [Aerial Dream](https://github.com/cachapa/AerialDream) by Daniel Cachapa, created in late 2015. This fork was created to add YouTube as a video source without requiring any external server or API key.

## Credits

See [CREDITS.md](./CREDITS.md) for full attribution.

## License

GPL v3 — see [LICENSE](./LICENSE).

## Disclaimer

This fork is not affiliated with YouTube, Google, or Apple. It uses NewPipe Extractor for on-device stream extraction and is distributed for personal sideload use only.
