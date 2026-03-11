# AerialViews+ Agent Routing

This folder is a repo-local playbook for choosing the right agent strategy
for work in AerialViews+. It is not an automatic Codex config file.

If you want Codex to auto-load repo instructions, `AGENTS.md` is the native
mechanism. Since this repo should avoid that, use this folder as the manual
routing reference.

## Available Agent Types In This Environment

- `default`: main orchestrator. Best for cross-cutting work, final integration,
  TV verification with `adb`, and tasks that touch multiple slices.
- `explorer`: read-only codebase investigator. Best for tracing a bug,
  locating wiring points, and answering focused repo questions quickly.
- `worker`: implementation agent. Best for bounded edits with a clear write
  scope and a short verification target.

## Best Agent For Common Tasks

- New media source or provider integration:
  use `default` to orchestrate, plus one `explorer` for wiring discovery.
- YouTube filtering, caching, Projectivy, or provider bugs:
  use `default` or one `worker` scoped to `providers/youtube` and related
  settings files.
- Playback, subtitle, buffering, aspect ratio, or renderer issues:
  use `default`; these usually span `ui/core`, `services`, and provider code.
- Settings-only changes:
  use one `worker` scoped to `ui/settings`, `ui/sources`, and `res/xml`.
- Build, signing, release, repo docs, or GitHub Actions:
  use one `worker` scoped to Gradle, workflow, README, and release files.
- Runtime TV debugging:
  use `default` because it needs `adb`, app knowledge, and judgment.

## Recommended Multi-Agent Patterns

### Pattern 1: New Source Integration

- `explorer`: identify provider wiring points
- `worker`: implement provider/repository/cache layer
- `worker`: implement settings/resources/UI layer
- `default`: integrate, verify, and install on TV

### Pattern 2: Playback Or Screensaver Bug

- `explorer`: trace the relevant flow through services and player code
- `worker`: patch one area only if the write scope is clear
- `default`: run tests, rebuild APK, and verify on TV

### Pattern 3: Release Or Signing Work

- `worker`: patch Gradle or workflow files
- `worker`: patch docs or release notes
- `default`: verify build artifacts, GitHub release state, and signing

## Codebase Slices

- `app/src/main/java/com/neilturner/aerialviews/providers`
  Source-specific provider implementations
- `app/src/main/java/com/neilturner/aerialviews/providers/youtube`
  YouTube search, cache, refresh, and source logic
- `app/src/main/java/com/neilturner/aerialviews/services`
  Playlist/service orchestration and runtime entry points
- `app/src/main/java/com/neilturner/aerialviews/services/projectivy`
  Projectivy integration
- `app/src/main/java/com/neilturner/aerialviews/ui/core`
  Playback/rendering/runtime screen control
- `app/src/main/java/com/neilturner/aerialviews/ui/settings`
  Main settings screens
- `app/src/main/java/com/neilturner/aerialviews/ui/sources`
  Source-specific settings screens
- `app/src/main/res/xml`
  Preference screen definitions
- `.github/workflows`
  Release automation

## High-Risk Cross-Cutting Files

- `app/src/main/java/com/neilturner/aerialviews/services/MediaService.kt`
- `app/src/main/java/com/neilturner/aerialviews/ui/core/VideoPlayerHelper.kt`
- `app/src/main/java/com/neilturner/aerialviews/ui/core/VideoPlayerView.kt`
- `app/src/main/java/com/neilturner/aerialviews/ui/AerialApp.kt`
- `app/build.gradle.kts`

## Verification Baseline

For most Android/Kotlin code changes:

- `env JAVA_HOME=/home/naveen/Documents/AerialViews/.jdk-temurin21 ANDROID_HOME=/usr/lib/android-sdk ANDROID_SDK_ROOT=/usr/lib/android-sdk ./gradlew :app:compileBetaDebugKotlin`
- `env JAVA_HOME=/home/naveen/Documents/AerialViews/.jdk-temurin21 ANDROID_HOME=/usr/lib/android-sdk ANDROID_SDK_ROOT=/usr/lib/android-sdk ./gradlew :app:testBetaDebugUnitTest`

For a releasable APK:

- `env JAVA_HOME=/home/naveen/Documents/AerialViews/.jdk-temurin21 ANDROID_HOME=/usr/lib/android-sdk ANDROID_SDK_ROOT=/usr/lib/android-sdk ./gradlew :app:assembleGithubNonMinifiedRelease`

For TV validation:

- `adb install -r app/build/outputs/apk/github/nonMinifiedRelease/app-github-nonMinifiedRelease.apk`

