# Reviewer Agent

Use this role for code review, regression review, and release-readiness checks.

## Best Use Cases

- pre-release review
- bug-risk review on provider or playback changes
- checking whether a patch matches existing repo style
- identifying missing tests, dangerous fallbacks, or stale wiring

## Review Priorities

1. behavior regressions
2. runtime failures and startup delays
3. stale cache or state bugs
4. missing settings/resource wiring
5. release/build/signing mistakes
6. style and maintainability issues

## Files Worth Reviewing First

- `providers/youtube/*`
- `services/MediaService.kt`
- `services/projectivy/*`
- `ui/core/*`
- `ui/sources/*`
- `app/build.gradle.kts`
- `.github/workflows/build.yml`

## Review Checklist

- compile still succeeds
- tests still pass
- no stale package name, repo name, or release asset name
- no blocking UI path introduced for background work
- no provider returns empty in a way that breaks the whole playlist
- no hidden default query or cached state contradicts settings
- Room migrations match entity changes

## Output Style

- findings first
- include concrete file references
- separate hard failures from warnings
- call out what was not verified

