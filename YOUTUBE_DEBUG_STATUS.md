# YouTube Debug Status

Date: 2026-03-14

This document records the current YouTube implementation state in `AerialViews+`, the live-TV failures that were confirmed, and the latest code changes that were pushed so the next person can continue without rebuilding the full context.

## Current user-facing status

- YouTube search/filtering is now returning many more candidates than before.
- The old "only 6 videos in cache" problem was traced to the recency filter collapsing most searches after all other filters.
- The app is still not reliably playing YouTube videos on the Xiaomi / Amlogic TV that was used for testing.
- The current visible failure is a black screen / videos not coming, even though the screensaver activity starts.

## What was confirmed on the TV

### 1. Search/filter side

Live logcat from the TV showed that the small-cache problem was caused by the recency filter:

- Before the recency change, many searches ended like:
  - `After duration filter: 18`
  - `After AI filter: 18`
  - `After human filter: 17`
  - `After synthetic filter: 17`
  - `After recency filter: 1`
- Similar runs were dropping to `0`, `1`, `2`, `3`, `4`.

This proved that the cache size issue was not caused by `MAX_CACHE_SIZE`; it was caused by candidate loss late in filtering.

### 2. Playback side

Even after cache improvements, the TV kept showing AV1 decoder activity while AerialViews+ screensaver was active:

- `ammvdec_av1_v4l`
- `av1 Instance`
- `av1 cached=0 need_size=3072`

This is the main evidence that the black-screen failure is in the stream decode/playback path, not the search path.

## What was changed

### Search / filtering fixes

Files:
- `app/src/main/java/com/neilturner/aerialviews/providers/youtube/NewPipeHelper.kt`
- `app/src/main/java/com/neilturner/aerialviews/providers/youtube/YouTubeCacheDao.kt`
- `app/src/main/java/com/neilturner/aerialviews/providers/youtube/YouTubeSourceRepository.kt`
- `app/src/main/java/com/neilturner/aerialviews/ui/sources/YouTubeSettingsFragment.kt`
- `app/src/main/java/com/neilturner/aerialviews/ui/sources/YouTubeSettingsViewModel.kt`

Implemented:
- Category filtering no longer wipes the whole cache.
- If all categories are disabled, the DB is preserved instead of being cleared.
- Duration changes now apply an immediate DB filter and update count live.
- Category changes now use the post-persisted preference state and trigger a refresh path.
- Search/filter pass counts are logged with `Log.i(...)` so device logcat can show where candidate loss happens.
- Human/vlog/talking content filtering was expanded.
- The recency filter was loosened from 6 months to 10 years because old scenic footage is still valid screensaver content.

### Playback / codec fixes

Files:
- `app/src/main/java/com/neilturner/aerialviews/providers/youtube/NewPipeHelper.kt`
- `app/src/main/java/com/neilturner/aerialviews/providers/youtube/YouTubeSourceRepository.kt`

Implemented:
- Added device-specific codec restrictions for the Xiaomi / Amlogic TV path.
- First attempt:
  - Treat AV1 as unsupported on Amlogic.
  - Treat 4K VP9 / AV1 as unsupported on Amlogic.
- Second stronger attempt:
  - On Amlogic, force AVC/H.264-only if available.
  - Otherwise remove AV1 and fall back to non-AV1 streams.
- Bumped `CURRENT_CACHE_VERSION` from `26` to `27` so old cached stream URLs are invalidated and re-extracted under the new codec rule.

## What is currently unverified

The last installed build contains:

- Amlogic AVC-only / non-AV1 restriction
- cache version bump to force stream URL refresh

However, after installing that build, the final user confirmation was still:

- "videos not coming"

So the current pushed state should be treated as:

- improved search/filtering
- playback fix attempted
- final playback success on the TV still not confirmed

## Most likely remaining root cause

If YouTube still fails after the AVC-only restriction and cache-version bump, the next likely problems are:

1. The device is still receiving old in-memory playlist items after app/process restart timing.
2. The stream family returned by NewPipe is still not actually safe on this TV, even after codec-family restrictions.
3. The TV needs a stronger device-specific rule:
   - prefer only AVC progressive streams at 1080p or lower on this Xiaomi / Amlogic device
   - avoid all AV1 and VP9 completely
4. Playback may be resolving to a URL that ExoPlayer can open but the device decoder cannot render correctly.

## Recommended next debugging steps

1. Start with fresh logcat:
   - `adb -s 192.168.1.2:5555 logcat -c`
2. Start AerialViews+ screensaver.
3. Inspect:
   - `STREAM PICKED`
   - `ammvdec_av1_v4l`
   - `vp9`
   - `avc`
   - `PlaybackException`
   - `DecoderInitializationException`
   - `No videos available`
4. If AV1 still appears, the next patch should hard reject AV1 and VP9 before any fallback stage, not just penalize them.
5. If AVC appears but playback is still black, inspect the exact progressive URL and ExoPlayer error path.

## Local build/test state before handoff

Before this handoff:

- `:app:compileBetaDebugKotlin` passed after the latest codec/cache changes
- `:app:assembleGithubNonMinifiedRelease` passed
- the APK was installed to the TV
- the app was force-stopped so stale in-memory state would not survive

No claim is made here that YouTube playback is now fully fixed on the TV. The document reflects the exact state reached in this session.
