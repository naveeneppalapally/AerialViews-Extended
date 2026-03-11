# Contributing

Thanks for contributing to AerialViews+.

## Workflow

1. Fork the repository
2. Create a feature or fix branch
3. Make your changes
4. Open a pull request against this fork

## Notes For This Fork

- This project is a GPL-licensed fork of `theothernt/AerialViews`
- The maintainer uses AI-assisted implementation tools, including Codex and Claude, for parts of development
- Please keep changes focused and clearly describe user-visible behavior changes in the pull request

## NewPipe Fixes

If YouTube extraction breaks after a YouTube site change, the first place to check is the NewPipe Extractor version in:

```text
app/build.gradle.kts
```

In many cases, the fix is a version bump of the `newpipeextractor` dependency plus any required compatibility adjustments.

## Bug Reports

Always include:

- Device model
- Android version
- App version
- Whether the issue happens in YouTube-only playback, mixed playback, or non-YouTube sources
- Relevant logcat output if available

## Pull Requests

- Keep one logical change per pull request when possible
- Do not remove upstream attribution
- Do not replace the project license with a different license
