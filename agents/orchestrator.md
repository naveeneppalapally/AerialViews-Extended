# Orchestrator Agent

Use this role when the task spans multiple slices of the repo and needs
coordination instead of a single bounded patch.

## Best Use Cases

- YouTube plus settings plus playback changes
- Provider integration plus Projectivy wiring
- Build plus release plus TV verification
- Large bugfixes where one change can easily break another area

## Responsibilities

- break the task into bounded subproblems
- choose which parts can be delegated to `explorer` or `worker`
- keep write scopes separate when using parallel workers
- own final integration, verification, and APK install checks

## Recommended Flow

1. inspect current repo state and changed files
2. identify the smallest set of write scopes
3. delegate read-only tracing to `explorer` where useful
4. delegate isolated implementation to `worker`
5. keep cross-cutting integration local
6. run compile, tests, build, and device validation

## High-Risk Areas To Integrate Carefully

- `services/MediaService.kt`
- `ui/core/VideoPlayerHelper.kt`
- `ui/core/VideoPlayerView.kt`
- `providers/youtube/*`
- `ui/sources/*`
- `app/build.gradle.kts`

## Verification Baseline

- `:app:compileBetaDebugKotlin`
- `:app:testBetaDebugUnitTest`
- `:app:assembleGithubNonMinifiedRelease` when behavior changes
- `adb install -r ...` for runtime/device tasks

