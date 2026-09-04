package com.neilturner.aerialviews.providers.youtube

/**
 * Stream-extraction seam. Production resolves via NewPipe; tests return
 * canned playback URLs so resolve/refresh paths run without network.
 */
interface StreamExtractor {
    suspend fun extractPlaybackStreams(
        videoPageUrl: String,
        preferredQuality: String,
        preferVideoOnly: Boolean = false,
        allowAdaptiveManifests: Boolean = true,
        preferAdaptiveManifests: Boolean = false,
        // Main playback wants adaptive manifests (fast start, best
        // sustainable quality). External consumers (Projectivy wallpaper)
        // need direct files and pass false.
        preferManifests: Boolean = true,
    ): YouTubePlaybackUrls

    suspend fun extractStreamUrl(
        videoPageUrl: String,
        preferredQuality: String,
        preferVideoOnly: Boolean = false,
        allowAdaptiveManifests: Boolean = true,
        preferAdaptiveManifests: Boolean = false,
        preferManifests: Boolean = true,
    ): String = extractPlaybackStreams(
        videoPageUrl = videoPageUrl,
        preferredQuality = preferredQuality,
        preferVideoOnly = preferVideoOnly,
        allowAdaptiveManifests = allowAdaptiveManifests,
        preferAdaptiveManifests = preferAdaptiveManifests,
        preferManifests = preferManifests,
    ).videoUrl
}
