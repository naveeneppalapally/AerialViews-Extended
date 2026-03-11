# Provider And YouTube Agent

Use this route for:

- provider additions
- query/filter bugs
- cache, refresh, and repeat issues
- Projectivy source registration

## Best Agent Choice

- Small repo question: `explorer`
- One bounded code fix: `worker`
- Anything touching provider + settings + TV verification: `default`

## Primary Files

- `app/src/main/java/com/neilturner/aerialviews/providers/MediaProvider.kt`
- `app/src/main/java/com/neilturner/aerialviews/services/MediaService.kt`
- `app/src/main/java/com/neilturner/aerialviews/providers/youtube/*`
- `app/src/main/java/com/neilturner/aerialviews/models/prefs/YouTubeVideoPrefs.kt`
- `app/src/main/java/com/neilturner/aerialviews/ui/sources/YouTubeSettingsFragment.kt`
- `app/src/main/res/xml/sources_youtube_settings.xml`

## Typical Split

- `explorer`: find the service wiring, enum, and settings entry points
- `worker`: patch repository/cache/provider logic
- `worker`: patch settings/resources/UI if needed
- `default`: compile, test, install, and sanity-check on TV

## Verification

- compile
- unit tests
- one fresh APK build if behavior changed
- `adb` install for runtime issues

