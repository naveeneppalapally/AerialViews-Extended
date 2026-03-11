# Release And Ops Agent

Use this route for:

- Gradle or signing changes
- GitHub Actions workflow changes
- release asset naming
- README, credits, legal notices, and repo metadata
- APK install, `adb`, and smoke-test tasks

## Best Agent Choice

- One docs/workflow patch: `worker`
- Release orchestration or TV verification: `default`

## Primary Files

- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `.github/workflows/build.yml`
- `README.md`
- `CREDITS.md`
- `DISCLAIMER.md`
- `app/src/main/java/com/neilturner/aerialviews/ui/settings/LegalNoticesFragment.kt`
- `app/src/main/res/xml/settings_legal.xml`

## Verification

- compile if build logic changed
- assemble the release APK when signing or output paths changed
- verify release asset names and GitHub release state when applicable
