# Porting the GitHub In-App Updater

This guide documents every change needed to bring the GitHub release updater
(developed and validated in `AerialViews-release-test`) into the main
`AerialViews-Plus` repository.

Everything was developed against package `com.naveen.aerialviewsplus` with
the `github` product flavor.  The test and production repos share the same
package name, so no search-and-replace of the package name is needed — only
the `GITHUB_REPO` constant must change (see step 3.1).

---

## What the feature does

* **Settings → About** shows a "Check GitHub for Updates" preference that
  fetches the latest release from the GitHub API.  If a newer version exists
  it becomes tappable; pressing it enqueues a background download via
  `DownloadManager` and auto-launches the system package installer when done.
* **Home screen (startup)** — every time `MainActivity.onResume` fires, a
  lightweight version check runs in the background.  If a newer release is
  found, a non-intrusive dialog appears ("New release / AerialViews+ X.Y.Z /
  Download update / Later").  Pressing "Later" suppresses the prompt for that
  tag until the app is updated or restarted.
* **ABI metadata file** — the GitHub Actions workflow publishes an
  `update-metadata.json` asset alongside the APK.  The updater prefers this
  structured file over parsing the raw API JSON, giving control over the
  exact download URL and release notes format.
* **Signer pinning** — the workflow verifies the APK fingerprint matches the
  pinned SHA-256 before publishing, preventing accidentally releasing a
  debug-signed build.

---

## Files overview

### New files (copy verbatim from test repo, then substitute GITHUB_REPO)

| Source path in test repo | Destination in AerialViews-Plus |
|---|---|
| `app/src/main/java/…/utils/UpdateCheckerHelper.kt` | same path |
| `app/src/main/java/…/utils/HomeUpdatePromptHelper.kt` | same path |
| `app/src/main/java/…/models/prefs/UpdatePrefs.kt` | same path |
| `app/src/main/res/layout/dialog_update_prompt.xml` | same path |
| `app/src/main/res/drawable/update_prompt_panel_background.xml` | same path |
| `app/src/main/res/drawable/update_prompt_button_primary_background.xml` | same path |
| `app/src/main/res/drawable/update_prompt_button_secondary_background.xml` | same path |
| `app/src/main/res/drawable/update_prompt_notes_background.xml` | same path |
| `app/src/main/res/drawable/update_prompt_badge_background.xml` | same path (unused after restyle but harmless) |
| `app/src/test/…/utils/UpdateCheckerHelperTest.kt` | same path |

### Modified files

| File | Nature of change |
|---|---|
| `AndroidManifest.xml` | +1 permission |
| `app/build.gradle.kts` | move `signingConfigs` before `buildTypes`, add explicit signing to `nonMinifiedRelease` |
| `.github/workflows/build.yml` | signer verification step, metadata step, `files: dist/*` |
| `app/src/main/res/values/colors.xml` | add 14 `update_prompt_*` colors |
| `app/src/main/res/values/strings.xml` | add 10 `about_update_*` / `home_update_*` strings |
| `app/src/main/res/xml/settings_about.xml` | add `about_update` preference row |
| `ui/settings/AboutFragment.kt` | replace the whole file |
| `ui/MainFragment.kt` | add imports + `hasCheckedStartupUpdate` field + `maybeShowStartupUpdatePrompt()` + `clearDismissedUpdateIfInstalled()` |
| `ui/MainActivity.kt` | add download receiver, `startAppUpdateDownload()`, `maybeShowStartupUpdatePrompt()`, receiver register/unregister, change `handleCustomLaunching()` to return `Boolean` |

---

## Step-by-step instructions

### Step 1 — Copy new files

From `/home/naveen/Documents/AerialViews-release-test/` copy every path
listed in the "New files" table above into the same relative path in
`AerialViews-Plus/`.

```bash
SRC=/home/naveen/Documents/AerialViews-release-test
DST=/home/naveen/Documents/AerialViews-plus

declare -a FILES=(
  "app/src/main/java/com/neilturner/aerialviews/utils/UpdateCheckerHelper.kt"
  "app/src/main/java/com/neilturner/aerialviews/utils/HomeUpdatePromptHelper.kt"
  "app/src/main/java/com/neilturner/aerialviews/models/prefs/UpdatePrefs.kt"
  "app/src/main/res/layout/dialog_update_prompt.xml"
  "app/src/main/res/drawable/update_prompt_panel_background.xml"
  "app/src/main/res/drawable/update_prompt_button_primary_background.xml"
  "app/src/main/res/drawable/update_prompt_button_secondary_background.xml"
  "app/src/main/res/drawable/update_prompt_notes_background.xml"
  "app/src/main/res/drawable/update_prompt_badge_background.xml"
  "app/src/test/java/com/neilturner/aerialviews/utils/UpdateCheckerHelperTest.kt"
)

for f in "${FILES[@]}"; do
  mkdir -p "$DST/$(dirname "$f")"
  cp "$SRC/$f" "$DST/$f"
done
```

### Step 2 — One-time substitution: GITHUB_REPO constant

In the freshly-copied `UpdateCheckerHelper.kt` change the `GITHUB_REPO`
constant from the test repo to the production repo:

```
# Before
private const val GITHUB_REPO = "naveeneppalapally/AerialViews-release-test"

# After
private const val GITHUB_REPO = "naveeneppalapally/AerialViews-Plus"
```

Everything else in `UpdateCheckerHelper.kt` is correct as-is.

---

### Step 3 — `AndroidManifest.xml`

Add `REQUEST_INSTALL_PACKAGES` alongside the existing permissions block:

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

---

### Step 4 — `app/build.gradle.kts`

Two changes are required:

#### 4a — Move `signingConfigs` before `buildTypes`

The `nonMinifiedRelease` build type (used by the release workflow) must
reference a signing config, and Gradle requires signing configs to be
declared before they are referenced inside `buildTypes`.

Currently `signingConfigs { … }` appears **after** `buildTypes { … }` in the
original `build.gradle.kts`.  Move the entire `signingConfigs` block so it
sits **before** the `buildTypes` block.

Find the block (around line 129 in the original):
```kotlin
    signingConfigs {
        create("release") { … }
        create("legacy") { … }
    }
```
Cut it out and paste it above the `buildTypes {` line.

#### 4b — Add explicit signing to `nonMinifiedRelease`

Inside the `buildTypes` block, add one line to `nonMinifiedRelease` so it
uses the release keystore instead of falling back to the debug keystore:

```kotlin
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            // ADD THIS LINE ↓
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "ENABLE_YOUTUBE_LOGS", "false")
            matchingFallbacks += listOf("release")
        }
```

Without this, every CI build gets a freshly-generated debug signer and
Android refuses to upgrade the installed app.

---

### Step 5 — `app/src/main/res/values/colors.xml`

Add these 14 colors anywhere inside the `<resources>` element (top is fine):

```xml
<color name="update_prompt_surface_top">#F12B2B2B</color>
<color name="update_prompt_surface_bottom">#F12B2B2B</color>
<color name="update_prompt_surface_stroke">#26FFFFFF</color>
<color name="update_prompt_text_primary">#FFF5F7F7</color>
<color name="update_prompt_text_secondary">#BFC8CB</color>
<color name="update_prompt_text_accent">#8FD2D1</color>
<color name="update_prompt_accent_soft">#1A8FD2D1</color>
<color name="update_prompt_notes_surface">#11000000</color>
<color name="update_prompt_button_primary">#12363B3E</color>
<color name="update_prompt_button_primary_focused">#365E61</color>
<color name="update_prompt_button_primary_pressed">#2B4B4E</color>
<color name="update_prompt_button_secondary">#0AFFFFFF</color>
<color name="update_prompt_button_secondary_focused">#18FFFFFF</color>
<color name="update_prompt_button_secondary_pressed">#12FFFFFF</color>
```

---

### Step 6 — `app/src/main/res/values/strings.xml`

Add these strings at the end of the file, before `</resources>`:

```xml
<!-- About screen — update checker -->
<string name="about_update_title">Check GitHub for Updates</string>
<string name="about_update_checking">Checking…</string>
<string name="about_update_uptodate">Up to date</string>
<string name="about_update_failed">Couldn\'t check for updates</string>
<string name="about_update_available">%1$s available — press to download &amp; install</string>
<string name="about_update_downloading">Downloading update…</string>
<!-- Home-screen update prompt dialog -->
<string name="home_update_title">New release</string>
<string name="home_update_version">AerialViews+ %1$s</string>
<string name="home_update_summary">Version %2$s is available. You can keep using the app while it downloads.</string>
<string name="home_update_whats_new">Release notes</string>
<string name="home_update_empty_notes">Bug fixes and improvements</string>
<string name="home_update_download">Download update</string>
<string name="home_update_later">Later</string>
<string name="home_update_download_started">Downloading AerialViews+ %1$s…</string>
<string name="home_update_download_failed">Couldn\'t start the update download</string>
```

---

### Step 7 — `app/src/main/res/xml/settings_about.xml`

Add the `about_update` preference row **inside** the existing
`<PreferenceCategory>` block, after the `about_date` preference:

```xml
<Preference
    app:key="about_update"
    app:title="@string/about_update_title"
    app:summary="@string/about_update_checking"/>
```

The complete file should look like:

```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen
    xmlns:app="http://schemas.android.com/apk/res-auto">
        <PreferenceCategory app:title="@string/category_about">
                <Preference
                    app:key="about_version"
                    app:title="@string/about_version_title"
                    app:summary=""/>

                <Preference
                    app:key="about_date"
                    app:title="@string/about_date_title"
                    app:summary=""/>

                <Preference
                    app:key="about_update"
                    app:title="@string/about_update_title"
                    app:summary="@string/about_update_checking"/>
        </PreferenceCategory>
</PreferenceScreen>
```

---

### Step 8 — Replace `AboutFragment.kt`

The original `AboutFragment.kt` only shows version info.  Replace the entire
file with the version from the test repo:

```
cp $SRC/app/src/main/java/com/neilturner/aerialviews/ui/settings/AboutFragment.kt \
   $DST/app/src/main/java/com/neilturner/aerialviews/ui/settings/AboutFragment.kt
```

The new file adds:
* `pendingUpdate: UpdateInfo?` field
* `checkForUpdates()` — calls `UpdateCheckerHelper.checkForUpdate()` and
  shows the result in the `about_update` preference summary
* `startUpdateDownload(update)` — delegates to `MainActivity.startAppUpdateDownload()`
* Removes the inline `BroadcastReceiver` that was previously in `AboutFragment`
  (receiver is now owned by `MainActivity`)

The `@file:Suppress("SameReturnValue")` annotation at the top of the
original file is not present in the new version — that's fine to omit.

---

### Step 9 — Modify `MainFragment.kt`

#### 9a — Add imports (after the existing import block)

```kotlin
import com.neilturner.aerialviews.BuildConfig
import com.neilturner.aerialviews.models.prefs.UpdatePrefs
import com.neilturner.aerialviews.utils.HomeUpdatePromptHelper
import com.neilturner.aerialviews.utils.UpdateCheckResult
import com.neilturner.aerialviews.utils.UpdateCheckerHelper
```

#### 9b — Add field to the class body (after the class declaration, before `onCreatePreferences`)

```kotlin
private var hasCheckedStartupUpdate = false
```

#### 9c — Add the two functions before `companion object`

```kotlin
fun maybeShowStartupUpdatePrompt() {
    if (hasCheckedStartupUpdate || !isAdded || parentFragmentManager.isStateSaved) return

    hasCheckedStartupUpdate = true
    clearDismissedUpdateIfInstalled()

    viewLifecycleOwner.lifecycleScope.launch {
        when (val result = UpdateCheckerHelper.checkForUpdate(BuildConfig.VERSION_NAME)) {
            is UpdateCheckResult.Available -> {
                if (UpdatePrefs.homeUpdatePromptDismissedTag == result.updateInfo.tagName) return@launch

                HomeUpdatePromptHelper.show(
                    context = requireContext(),
                    currentVersion = BuildConfig.VERSION_NAME,
                    updateInfo = result.updateInfo,
                    onDownload = {
                        UpdatePrefs.homeUpdatePromptDismissedTag = ""
                        val mainActivity = activity as? MainActivity
                        if (mainActivity == null) {
                            Timber.w("UpdateChecker: MainActivity unavailable for startup update download")
                            return@show
                        }
                        mainActivity.startAppUpdateDownload(result.updateInfo)
                    },
                    onLater = {
                        UpdatePrefs.homeUpdatePromptDismissedTag = result.updateInfo.tagName
                    },
                )
            }

            UpdateCheckResult.UpToDate,
            UpdateCheckResult.Failed,
            -> Unit
        }
    }
}

private fun clearDismissedUpdateIfInstalled() {
    if (UpdatePrefs.homeUpdatePromptDismissedTag.removePrefix("v") == BuildConfig.VERSION_NAME) {
        UpdatePrefs.homeUpdatePromptDismissedTag = ""
    }
}
```

---

### Step 10 — Modify `MainActivity.kt`

#### 10a — Add imports

```kotlin
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.neilturner.aerialviews.utils.ToastHelper
import com.neilturner.aerialviews.utils.UpdateCheckerHelper
import com.neilturner.aerialviews.utils.UpdateInfo
```

#### 10b — Add fields to the class body

```kotlin
private var updateDownloadId: Long = -1L
private var startupUpdatePromptHandled = false
private var isDownloadReceiverRegistered = false

private val downloadReceiver =
    object : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != updateDownloadId) return

            val didLaunchInstaller = UpdateCheckerHelper.installDownloadedApk(this@MainActivity, updateDownloadId)
            if (!didLaunchInstaller) {
                lifecycleScope.launch {
                    ToastHelper.show(this@MainActivity, R.string.home_update_download_failed)
                }
            }
            updateDownloadId = -1L
        }
    }
```

#### 10c — Change `onResume`

Replace:
```kotlin
    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("Main", this)
        lifecycleScope.launch {
            handleCustomLaunching()
        }
    }
```

With:
```kotlin
    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("Main", this)
        registerDownloadReceiver()
        lifecycleScope.launch {
            val shouldShowStartupPrompt = handleCustomLaunching()
            if (shouldShowStartupPrompt) {
                maybeShowStartupUpdatePrompt()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterDownloadReceiver()
    }
```

#### 10d — Change `handleCustomLaunching` signature and add early returns

Change the function signature from `private fun handleCustomLaunching()` to
`private fun handleCustomLaunching(): Boolean`.

Inside the function, find the two early-exit branches and add `return false`:

```kotlin
        if (shouldExitApp) {
            startScreensaver()
            fromScreensaver = false
            return false
        } else if (hasValidIntentAndData) {
            // … existing bundle/fragment code …
            fromScreensaver = false
            return false
        }
        fromScreensaver = false
        return true   // ← only reached if no early exit → show startup update prompt
```

#### 10e — Add three new functions (add before or after `startScreensaver()`)

```kotlin
fun startAppUpdateDownload(updateInfo: UpdateInfo) {
    runCatching {
        updateDownloadId = UpdateCheckerHelper.enqueueDownload(this, updateInfo)
    }.onSuccess {
        lifecycleScope.launch {
            ToastHelper.show(
                this@MainActivity,
                getString(R.string.home_update_download_started, updateInfo.tagName.removePrefix("v")),
            )
        }
    }.onFailure { exception ->
        Timber.e(exception, "UpdateChecker: failed to enqueue home-screen update download")
        lifecycleScope.launch {
            ToastHelper.show(this@MainActivity, R.string.home_update_download_failed)
        }
    }
}

private fun maybeShowStartupUpdatePrompt() {
    if (startupUpdatePromptHandled) return

    binding.container.post {
        val mainFragment = supportFragmentManager.findFragmentById(binding.container.id) as? MainFragment ?: return@post
        startupUpdatePromptHandled = true
        mainFragment.maybeShowStartupUpdatePrompt()
    }
}

private fun registerDownloadReceiver() {
    if (isDownloadReceiverRegistered) return
    ContextCompat.registerReceiver(
        this,
        downloadReceiver,
        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        ContextCompat.RECEIVER_EXPORTED,
    )
    isDownloadReceiverRegistered = true
}

private fun unregisterDownloadReceiver() {
    if (!isDownloadReceiverRegistered) return
    runCatching { unregisterReceiver(downloadReceiver) }
    isDownloadReceiverRegistered = false
}
```

---

### Step 11 — Update `.github/workflows/build.yml`

Three changes:

#### 11a — Add `EXPECTED_SIGNER_SHA256` env var to the job

Inside `jobs: build-release-apk:`, add an `env:` section:

```yaml
    env:
      EXPECTED_SIGNER_SHA256: "B3:57:E7:EB:33:FA:40:1B:7A:D4:81:A0:3A:AC:B2:6D:F3:63:C7:8C:4D:EE:FC:B9:07:48:80:EE:DA:2B:AB:B5"
```

> **Note:** this is the SHA-256 thumbprint of the release keystore stored in
> GitHub Secrets.  If the repo already uses a different keystore, run
> `keytool -printcert -jarfile <existing-release.apk>` and use that
> fingerprint instead.  You can also set it to `""` on the first release and
> it will self-discover.

#### 11b — Add signer verification step and metadata step

After the "Rename APK" step, insert these two steps:

```yaml
      - name: Verify APK signer fingerprint
        run: |
          APK_PATH=$(find app/build/outputs/apk/github/nonMinifiedRelease -name '*.apk' | head -n 1)
          SIGNER_OWNER=$(keytool -printcert -jarfile "$APK_PATH" | sed -n 's/^Owner: //p' | head -n 1 | tr -d '\r')
          ACTUAL_SIGNER_SHA256=$(keytool -printcert -jarfile "$APK_PATH" | sed -n 's/.*SHA256: //p' | head -n 1 | tr -d '\r')

          echo "APK path: $APK_PATH"
          echo "Signer owner:          $SIGNER_OWNER"
          echo "Actual signer SHA256:   $ACTUAL_SIGNER_SHA256"

          if echo "$SIGNER_OWNER" | grep -q 'CN=Android Debug'; then
            echo "Refusing to publish a debug-signed release APK" >&2
            exit 1
          fi

          if [ -z "$ACTUAL_SIGNER_SHA256" ]; then
            echo "Failed to read signer fingerprint from built APK" >&2
            exit 1
          fi

          if [ -z "$EXPECTED_SIGNER_SHA256" ]; then
            echo "No pinned signer fingerprint configured yet; skipping verification for this release"
            exit 0
          fi

          echo "Expected signer SHA256: $EXPECTED_SIGNER_SHA256"

          if [ "$ACTUAL_SIGNER_SHA256" != "$EXPECTED_SIGNER_SHA256" ]; then
            echo "APK signer fingerprint does not match the pinned release signer" >&2
            exit 1
          fi

      - name: Create update metadata
        run: |
          jq -n \
            --arg tagName "v${{ github.event.inputs.version }}" \
            --arg versionName "${{ github.event.inputs.version }}" \
            --arg apkFileName "AerialViews-Plus-v${{ github.event.inputs.version }}.apk" \
            --arg downloadUrl "https://github.com/${{ github.repository }}/releases/download/v${{ github.event.inputs.version }}/AerialViews-Plus-v${{ github.event.inputs.version }}.apk" \
            --arg releaseNotes "${{ github.event.inputs.release_notes }}" \
            --arg publishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
            '{
              tagName: $tagName,
              versionName: $versionName,
              apkFileName: $apkFileName,
              downloadUrl: $downloadUrl,
              releaseNotes: $releaseNotes,
              publishedAt: $publishedAt
            }' > dist/update-metadata.json
```

#### 11c — Change `files: dist/*.apk` → `files: dist/*`

In the "Create GitHub Release" step:
```yaml
          files: dist/*
```

This makes the workflow upload both the APK and the `update-metadata.json`
file as release assets.

Also update the `KEYSTORE_PATH` env var in the "Build signed release APK"
step.  The original uses a relative path; change it to absolute so Gradle
resolves it correctly:

```yaml
      - name: Build signed release APK
        run: ./gradlew --no-daemon :app:assembleGithubNonMinifiedRelease
        env:
          KEYSTORE_PATH: ${{ github.workspace }}/app/aerialviews-plus-release.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
```

---

## GitHub repository setup

The workflow requires four repository secrets (Settings → Secrets → Actions):

| Secret name | Content |
|---|---|
| `KEYSTORE_BASE64` | `base64 < path/to/aerialviews-plus-release.jks` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (default: `aerialviewsplus`) |
| `KEY_PASSWORD` | Key password |

These are the same secrets already used by `AerialViews-release-test`.  If
the production repo is a fork / copy, create them under the new repo.

---

## First release checklist

1. Apply all changes above and push to `main`.
2. Go to **Actions → Build APK → Run workflow**.
3. Enter the version number (e.g. `1.0.0`) and release notes.
4. The workflow builds, verifies the signer, creates a GitHub Release, and
   uploads `AerialViews-Plus-v1.0.0.apk` + `update-metadata.json`.
5. Install the APK on a TV device.
6. Publish a second release with a higher version number.
7. Launch the app on the device — the "New release" dialog should appear
   within a few seconds of the home screen loading.

---

## Testing notes

* The startup dialog only shows once per tag.  Press "Later" to dismiss it;
  it will not reappear for that version.  On fresh install the preference is
  blank so every check will show the dialog.
* `UpdateCheckerHelperTest` in `app/src/test/` covers the beta-suffix version
  comparison edge case: `1.3.6-beta12` correctly reports `v1.3.6` as
  **not** newer, and `v1.3.7` as **newer**.
* The signer fingerprint in `EXPECTED_SIGNER_SHA256` can be left as `""` on
  the very first release to bypass the pin check.  Set it to the actual
  fingerprint (printed in the workflow log) from the second release onward.
