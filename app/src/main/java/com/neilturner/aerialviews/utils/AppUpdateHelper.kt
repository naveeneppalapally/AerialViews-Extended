package com.neilturner.aerialviews.utils

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.neilturner.aerialviews.BuildConfig
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

object AppUpdateHelper {
    enum class Availability {
        UNCHECKED,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        LOCAL_BUILD,
        NO_RELEASES,
        ERROR,
        UNSUPPORTED,
    }

    data class UpdateStatus(
        val availability: Availability,
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
        val checkedAt: Long,
    ) {
        val updateAvailable: Boolean
            get() = availability == Availability.UPDATE_AVAILABLE
    }

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateInFlight = AtomicBoolean(false)
    private val httpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun isSupportedFlavor(): Boolean {
        val flavor = BuildConfig.FLAVOR.lowercase()
        return flavor == "github" || flavor == "beta"
    }

    fun cachedStatus(context: Context): UpdateStatus {
        if (!isSupportedFlavor()) {
            return unsupportedStatus()
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val availability =
            prefs.getString(KEY_UPDATE_AVAILABILITY, Availability.UNCHECKED.name)
                ?.let { stored ->
                    Availability.entries.firstOrNull { availability -> availability.name == stored }
                } ?: Availability.UNCHECKED

        return UpdateStatus(
            availability = availability,
            currentVersion = BuildConfig.VERSION_NAME,
            latestVersion = prefs.getString(KEY_UPDATE_LATEST_VERSION, "").orEmpty(),
            releaseUrl = prefs.getString(KEY_UPDATE_RELEASE_URL, RELEASES_PAGE_URL).orEmpty().ifBlank { RELEASES_PAGE_URL },
            checkedAt = prefs.getLong(KEY_UPDATE_CHECKED_AT, 0L),
        )
    }

    fun refreshInBackground(
        context: Context,
        force: Boolean = false,
        onComplete: ((UpdateStatus) -> Unit)? = null,
    ) {
        if (!force && !updateInFlight.compareAndSet(false, true)) {
            return
        }
        if (force) {
            updateInFlight.set(true)
        }

        val appContext = context.applicationContext
        updateScope.launch {
            val status =
                try {
                    fetchStatus(appContext, force)
                } finally {
                    updateInFlight.set(false)
                }

            if (onComplete != null) {
                withContext(Dispatchers.Main) {
                    onComplete(status)
                }
            }
        }
    }

    suspend fun fetchStatus(
        context: Context,
        force: Boolean = false,
    ): UpdateStatus =
        withContext(Dispatchers.IO) {
            if (!isSupportedFlavor()) {
                return@withContext unsupportedStatus()
            }

            val cached = cachedStatus(context)
            val now = System.currentTimeMillis()
            val cacheFresh = cached.checkedAt > 0L && now - cached.checkedAt < UPDATE_CACHE_TTL_MS
            if (!force && cacheFresh && cached.availability != Availability.UNCHECKED) {
                return@withContext cached
            }

            val request =
                Request
                    .Builder()
                    .url(RELEASES_API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "AerialViewsPlus/${BuildConfig.VERSION_NAME}")
                    .build()

            return@withContext runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("GitHub update check failed with HTTP ${response.code}")
                    }

                    val body = response.body.string()
                    val json = JSONObject(body)
                    val latestVersion = json.optString("tag_name").orEmpty().ifBlank { cached.latestVersion }
                    val releaseUrl = json.optString("html_url").orEmpty().ifBlank { RELEASES_PAGE_URL }
                    val availability = resolveAvailability(latestVersion, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE)

                    UpdateStatus(
                        availability = availability,
                        currentVersion = BuildConfig.VERSION_NAME,
                        latestVersion = latestVersion,
                        releaseUrl = releaseUrl,
                        checkedAt = now,
                    )
                }
            }.onSuccess { status ->
                persistStatus(context, status)
            }.onFailure { exception ->
                Timber.w(exception, "Unable to check GitHub releases")
            }.getOrElse {
                val fallback =
                    if (cached.updateAvailable) {
                        cached.copy(checkedAt = now)
                    } else {
                        UpdateStatus(
                            availability = Availability.ERROR,
                            currentVersion = BuildConfig.VERSION_NAME,
                            latestVersion = cached.latestVersion,
                            releaseUrl = cached.releaseUrl.ifBlank { RELEASES_PAGE_URL },
                            checkedAt = now,
                        )
                    }
                persistStatus(context, fallback)
                fallback
            }
        }

    internal fun compareVersionsForTest(
        left: String,
        right: String,
    ): Int = compareVersions(left, right)

    internal fun isNewerVersionForTest(
        latestVersion: String,
        currentVersion: String,
    ): Boolean = isNewerVersion(latestVersion, currentVersion)

    internal fun resolveAvailabilityForTest(
        latestVersion: String,
        currentVersion: String,
        buildType: String,
    ): Availability = resolveAvailability(latestVersion, currentVersion, buildType)

    private fun unsupportedStatus(): UpdateStatus =
        UpdateStatus(
            availability = Availability.UNSUPPORTED,
            currentVersion = BuildConfig.VERSION_NAME,
            latestVersion = "",
            releaseUrl = RELEASES_PAGE_URL,
            checkedAt = 0L,
        )

    private fun persistStatus(
        context: Context,
        status: UpdateStatus,
    ) {
        PreferenceManager
            .getDefaultSharedPreferences(context.applicationContext)
            .edit {
                putString(KEY_UPDATE_AVAILABILITY, status.availability.name)
                putString(KEY_UPDATE_LATEST_VERSION, status.latestVersion)
                putString(KEY_UPDATE_RELEASE_URL, status.releaseUrl)
                putLong(KEY_UPDATE_CHECKED_AT, status.checkedAt)
            }
    }

    private fun isNewerVersion(
        latestVersion: String,
        currentVersion: String,
    ): Boolean = compareVersions(latestVersion, currentVersion) > 0

    private fun resolveAvailability(
        latestVersion: String,
        currentVersion: String,
        buildType: String,
    ): Availability =
        when {
            latestVersion.isBlank() -> Availability.NO_RELEASES
            isNewerVersion(latestVersion, currentVersion) -> Availability.UPDATE_AVAILABLE
            !buildType.equals("release", ignoreCase = true) -> Availability.LOCAL_BUILD
            else -> Availability.UP_TO_DATE
        }

    private fun compareVersions(
        left: String,
        right: String,
    ): Int {
        val leftVersion = parseVersion(left)
        val rightVersion = parseVersion(right)
        val segmentCount = maxOf(leftVersion.numbers.size, rightVersion.numbers.size)
        repeat(segmentCount) { index ->
            val leftNumber = leftVersion.numbers.getOrElse(index) { 0 }
            val rightNumber = rightVersion.numbers.getOrElse(index) { 0 }
            if (leftNumber != rightNumber) {
                return leftNumber.compareTo(rightNumber)
            }
        }

        return comparePrerelease(leftVersion.prerelease, rightVersion.prerelease)
    }

    private fun comparePrerelease(
        left: String?,
        right: String?,
    ): Int {
        if (left == right) {
            return 0
        }
        if (left == null) {
            return 1
        }
        if (right == null) {
            return -1
        }

        val leftTokens = tokenizePrerelease(left)
        val rightTokens = tokenizePrerelease(right)
        val tokenCount = maxOf(leftTokens.size, rightTokens.size)
        repeat(tokenCount) { index ->
            val leftToken = leftTokens.getOrNull(index)
            val rightToken = rightTokens.getOrNull(index)
            if (leftToken == rightToken) {
                return@repeat
            }
            if (leftToken == null) {
                return -1
            }
            if (rightToken == null) {
                return 1
            }

            val leftNumber = leftToken.toIntOrNull()
            val rightNumber = rightToken.toIntOrNull()
            val comparison =
                when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> leftToken.compareTo(rightToken)
                }
            if (comparison != 0) {
                return comparison
            }
        }

        return 0
    }

    private fun parseVersion(version: String): ParsedVersion {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        val withoutBuildMetadata = cleaned.substringBefore('+')
        val core = withoutBuildMetadata.substringBefore('-')
        val prerelease = withoutBuildMetadata.substringAfter('-', "").ifBlank { null }
        val numbers =
            core
                .split('.')
                .mapNotNull { part -> part.toIntOrNull() }
                .ifEmpty { listOf(0) }
        return ParsedVersion(numbers = numbers, prerelease = prerelease)
    }

    private fun tokenizePrerelease(value: String): List<String> =
        VERSION_TOKEN_REGEX.findAll(value.lowercase())
            .map { match -> match.value }
            .toList()

    private data class ParsedVersion(
        val numbers: List<Int>,
        val prerelease: String?,
    )

    private const val RELEASES_API_URL = "https://api.github.com/repos/naveeneppalapally/AerialViews-Plus/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/naveeneppalapally/AerialViews-Plus/releases"
    private const val UPDATE_CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val KEY_UPDATE_AVAILABILITY = "app_update_availability"
    private const val KEY_UPDATE_LATEST_VERSION = "app_update_latest_version"
    private const val KEY_UPDATE_RELEASE_URL = "app_update_release_url"
    private const val KEY_UPDATE_CHECKED_AT = "app_update_checked_at"
    private val VERSION_TOKEN_REGEX = Regex("[a-z]+|\\d+")
}