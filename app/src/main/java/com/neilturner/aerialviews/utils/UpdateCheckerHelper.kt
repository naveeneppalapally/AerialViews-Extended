package com.neilturner.aerialviews.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val downloadUrl: String,
    val releaseNotes: String = "",
)

sealed interface UpdateCheckResult {
    data class Available(val updateInfo: UpdateInfo) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult

    data object Failed : UpdateCheckResult
}

object UpdateCheckerHelper {
    private const val GITHUB_REPO = "naveeneppalapally/AerialViews-Plus"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val GITHUB_LATEST_URL = "https://github.com/$GITHUB_REPO/releases/latest"
    private const val GITHUB_DOWNLOAD_URL = "https://github.com/$GITHUB_REPO/releases/download"
    private const val UPDATE_METADATA_FILE = "update-metadata.json"

    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            try {
                val latest = fetchLatestReleaseInfo(currentVersion) ?: return@withContext UpdateCheckResult.Failed
                if (isNewerVersion(latest.tagName, currentVersion)) {
                    UpdateCheckResult.Available(latest)
                } else {
                    UpdateCheckResult.UpToDate
                }
            } catch (e: Exception) {
                Timber.e(e, "UpdateChecker: check failed")
                UpdateCheckResult.Failed
            }
        }

    private fun fetchLatestReleaseInfo(currentVersion: String): UpdateInfo? =
        fetchLatestReleaseInfoFromApi(currentVersion) ?: fetchLatestReleaseInfoFromRedirect(currentVersion)

    private fun fetchLatestReleaseInfoFromApi(currentVersion: String): UpdateInfo? {
        val conn = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "AerialViewsPlus/$currentVersion")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) {
                Timber.w("UpdateChecker: API HTTP ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val apkUrl =
                json.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length())
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                        ?.optString("browser_download_url")
                } ?: return null
            fetchReleaseMetadata(tag, currentVersion)
                ?: UpdateInfo(
                    tagName = tag,
                    downloadUrl = apkUrl,
                    releaseNotes = normaliseReleaseNotes(json.optString("body")),
                )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchLatestReleaseInfoFromRedirect(currentVersion: String): UpdateInfo? {
        val conn = URL(GITHUB_LATEST_URL).openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "AerialViewsPlus/$currentVersion")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val location = conn.getHeaderField("Location")
            if (location.isNullOrBlank()) {
                Timber.w("UpdateChecker: latest redirect missing Location header (HTTP ${conn.responseCode})")
                return null
            }
            val tag = location.substringAfterLast('/').takeIf { it.startsWith("v") } ?: return null
            fetchReleaseMetadata(tag, currentVersion)
                ?: UpdateInfo(
                    tagName = tag,
                    downloadUrl = buildApkDownloadUrl(tag),
                )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchReleaseMetadata(
        tag: String,
        currentVersion: String,
    ): UpdateInfo? {
        val conn = URL(buildMetadataUrl(tag)).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "AerialViewsPlus/$currentVersion")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) {
                Timber.w("UpdateChecker: metadata HTTP ${conn.responseCode} for $tag")
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val metadataTag = json.optString("tagName").takeIf { it.isNotBlank() } ?: tag
            val downloadUrl =
                json.optString("downloadUrl").takeIf { it.isNotBlank() }
                    ?: buildApkDownloadUrl(metadataTag)
            UpdateInfo(
                tagName = metadataTag,
                downloadUrl = downloadUrl,
                releaseNotes = normaliseReleaseNotes(json.optString("releaseNotes")),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun buildMetadataUrl(tag: String): String = "$GITHUB_DOWNLOAD_URL/$tag/$UPDATE_METADATA_FILE"

    private fun buildApkDownloadUrl(tag: String): String = "$GITHUB_DOWNLOAD_URL/$tag/AerialViews-Plus-$tag.apk"

    private fun normaliseReleaseNotes(releaseNotes: String): String =
        releaseNotes
            .lineSequence()
            .map { line ->
                when {
                    line.startsWith("## ") -> line.removePrefix("## ").trim()
                    line.startsWith("# ") -> line.removePrefix("# ").trim()
                    else -> line.trimEnd()
                }
            }
            .joinToString("\n")
            .trim()

    fun enqueueDownload(
        context: Context,
        updateInfo: UpdateInfo,
    ): Long {
        val fileName = "AerialViews-${updateInfo.tagName}.apk"
        val request =
            DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
                .setTitle("AerialViews ${updateInfo.tagName}")
                .setDescription("Downloading update…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setMimeType("application/vnd.android.package-archive")
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    fun installDownloadedApk(
        context: Context,
        downloadId: Long,
    ): Boolean {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = downloadManager.getUriForDownloadedFile(downloadId)
        if (uri == null) {
            Timber.e("UpdateChecker: downloaded file URI null for id=$downloadId")
            return false
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(android.content.Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(android.content.Intent.EXTRA_RETURN_RESULT, true)
        }
        context.startActivity(intent)
        return true
    }

    fun isNewerVersion(
        tagName: String,
        currentVersion: String,
    ): Boolean {
        val remote = parseVersionParts(tagName)
        val local = parseVersionParts(currentVersion)
        for (index in 0 until maxOf(remote.size, local.size)) {
            val remoteValue = remote.getOrElse(index) { 0 }
            val localValue = local.getOrElse(index) { 0 }
            if (remoteValue > localValue) return true
            if (remoteValue < localValue) return false
        }
        return false
    }

    fun isSameVersion(
        firstVersion: String,
        secondVersion: String,
    ): Boolean {
        val first = parseVersionParts(firstVersion)
        val second = parseVersionParts(secondVersion)
        for (index in 0 until maxOf(first.size, second.size)) {
            val firstValue = first.getOrElse(index) { 0 }
            val secondValue = second.getOrElse(index) { 0 }
            if (firstValue != secondValue) return false
        }
        return true
    }

    private fun parseVersionParts(version: String): List<Int> =
        version
            .removePrefix("v")
            .split(".")
            .mapNotNull { segment ->
                segment
                    .takeWhile { character -> character.isDigit() }
                    .takeIf { it.isNotEmpty() }
                    ?.toIntOrNull()
            }
}