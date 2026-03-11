# Playback And UI Agent

Use this route for:

- subtitle/caption behavior
- buffering and startup latency
- scaling, aspect ratio, crop, letterboxing
- screensaver flow and UI state issues

## Best Agent Choice

- Read-only trace: `explorer`
- Focused patch in one slice: `worker`
- Player plus service plus TV behavior: `default`

## Primary Files

- `app/src/main/java/com/neilturner/aerialviews/ui/core/VideoPlayerHelper.kt`
- `app/src/main/java/com/neilturner/aerialviews/ui/core/VideoPlayerView.kt`
- `app/src/main/java/com/neilturner/aerialviews/ui/core/ScreenController.kt`
- `app/src/main/java/com/neilturner/aerialviews/services/MediaService.kt`
- `app/src/main/java/com/neilturner/aerialviews/ui/settings/*`
- `app/src/main/java/com/neilturner/aerialviews/ui/sources/*`

## Notes

- Playback bugs often look provider-specific but are really player setup issues.
- Settings bugs often require both fragment code and `res/xml` preference changes.
- TV validation matters here more than unit tests alone.

## Verification

- compile
- targeted tests if available
- release APK build when playback code changes
- install and verify on device

