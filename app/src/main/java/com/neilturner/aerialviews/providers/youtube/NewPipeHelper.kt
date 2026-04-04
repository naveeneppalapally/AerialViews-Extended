package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object NewPipeHelper {
    private const val TAG = "NewPipeHelper"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.youtube.com/"
    private const val ORIGIN = "https://www.youtube.com"

    @Volatile
    private var initialized = false

    private val httpClient by lazy {
        OkHttpClient
            .Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun init() {
        if (initialized) {
            return
        }

        synchronized(this) {
            if (initialized) {
                return
            }

            NewPipe.init(OkHttpDownloader(httpClient))
            initialized = true
        }
    }

    suspend fun searchVideos(
        query: String,
        category: QueryFormulaEngine.ContentCategory? = null,
    ): List<StreamInfoItem> =
        withContext(Dispatchers.IO) {
            init()

            try {
                val searchInfo = loadSearchInfo(query)
                val baseCandidates = buildSearchCandidates(searchInfo)
                selectSearchResults(category, baseCandidates)
            } catch (exception: Exception) {
                Timber.tag(TAG).w(exception, "Failed to search YouTube for \"%s\"", query)
                throw YouTubeExtractionException(
                    "Failed to search YouTube for \"$query\"",
                    exception,
                )
            }
        }

    suspend fun extractStreamUrl(
        videoPageUrl: String,
        context: Context,
        preferredQuality: String = "1080p",
        preferVideoOnly: Boolean = false,
        allowAdaptiveManifests: Boolean = true,
    ): String =
        withContext(Dispatchers.IO) {
            init()

            try {
                loadPlayableStreamUrl(
                    videoPageUrl = videoPageUrl,
                    context = context,
                    preferredQuality = preferredQuality,
                    preferVideoOnly = preferVideoOnly,
                    allowAdaptiveManifests = allowAdaptiveManifests,
                )
            } catch (exception: AgeRestrictedContentException) {
                Timber.tag(TAG).d("Age-restricted stream rejected: %s", videoPageUrl)
                throw exception
            } catch (exception: GeographicRestrictionException) {
                Timber.tag(TAG).d("Geo-blocked stream rejected: %s", videoPageUrl)
                throw exception
            } catch (exception: ContentNotAvailableException) {
                Timber.tag(TAG).d("Unavailable stream rejected: %s", videoPageUrl)
                throw exception
            } catch (exception: ExtractionException) {
                Timber.tag(TAG).w(exception, "NewPipe extraction failed for %s", videoPageUrl)
                throw exception
            } catch (exception: YouTubeExtractionException) {
                throw exception
            } catch (exception: Exception) {
                Timber.tag(TAG).w(exception, "Unexpected stream extraction failure for %s", videoPageUrl)
                throw YouTubeExtractionException(
                    "Failed to extract a playable stream for $videoPageUrl",
                    exception,
                )
            }
        }

    private fun loadSearchInfo(query: String): SearchInfo {
        val service = NewPipe.getService("YouTube")
        val searchUrl = buildSearchUrl(query, YoutubeSearchQueryHandlerFactory.VIDEOS)
        return SearchInfo.getInfo(
            service,
            SearchQueryHandler(
                searchUrl,
                searchUrl,
                query,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                "",
            ),
        )
    }

    private fun buildSearchCandidates(
        searchInfo: SearchInfo,
    ): List<StreamInfoItem> {
        val rawCandidates = searchInfo.relatedItems.filterIsInstance<StreamInfoItem>()
        val withMetadata = rawCandidates.filter(::hasUsableMetadata)
        Log.i(TAG, "YouTube search candidates: raw=${rawCandidates.size} metadata=${withMetadata.size}")

        val afterAiFilter = withMetadata.filterNot(::isLikelyAI)
        Log.i(TAG, "After AI filter: ${afterAiFilter.size} passed")

        val afterHumanFilter =
            afterAiFilter.filterNot { item ->
                isHumanContent(
                    title = item.getName(),
                    uploader = item.getUploaderName().orEmpty(),
                )
            }
        Log.i(TAG, "After human filter: ${afterHumanFilter.size} passed")

        val afterSyntheticFilter =
            afterHumanFilter.filterNot { item ->
                isLikelySyntheticWallpaperTitle(item.getName().lowercase(Locale.US))
            }
        Log.i(TAG, "After synthetic filter: ${afterSyntheticFilter.size} passed")

        val afterRecency = afterSyntheticFilter.filter(::isRecentEnough)
        Log.i(TAG, "After recency filter: ${afterRecency.size} passed")
        return afterRecency
    }

    private fun selectSearchResults(
        category: QueryFormulaEngine.ContentCategory?,
        baseCandidates: List<StreamInfoItem>,
    ): List<StreamInfoItem> {
        return baseCandidates
            .asSequence()
            .distinctBy { extractVideoId(it.getUrl()) ?: it.getUrl() }
            .distinctBy {
                val fallbackKey = extractVideoId(it.getUrl()) ?: it.getUrl()
                normalizeTitleFingerprint(it.getName()).ifBlank { fallbackKey }
            }
            .sortedByDescending { candidate ->
                QueryFormulaEngine.categoryMatchScore(
                    title = candidate.getName(),
                    uploader = candidate.getUploaderName().orEmpty(),
                    category = category,
                ) + preferredContentScore(candidate.getName().lowercase(Locale.US))
            }
            .take(MAX_RESULTS_PER_QUERY)
            .toList()
    }

    private fun hasUsableMetadata(item: StreamInfoItem): Boolean =
        item.getUrl().isNotBlank() && item.getName().isNotBlank()

    private fun isFilteredCandidate(item: StreamInfoItem): Boolean {
        val titleLower = item.getName().lowercase(Locale.US)
        return isLikelyAI(item) ||
            isHumanContent(item.getName(), item.getUploaderName().orEmpty()) ||
            isLikelySyntheticWallpaperTitle(titleLower)
    }

    private fun loadPlayableStreamUrl(
        videoPageUrl: String,
        context: Context,
        preferredQuality: String,
        preferVideoOnly: Boolean,
        allowAdaptiveManifests: Boolean,
    ): String {
        val service = NewPipe.getService("YouTube")
        val videoId =
            extractVideoId(videoPageUrl)
                ?: throw YouTubeExtractionException("Could not extract a video ID from $videoPageUrl")
        val streamExtractor =
            service.getStreamExtractor(
                LinkHandler(
                    videoPageUrl,
                    videoPageUrl,
                    videoId,
                ),
            )
        streamExtractor.fetchPage()
        val screenHeight = getScreenHeight(context)

        return selectBestStreamUrl(
            progressiveStreams = streamExtractor.videoStreams,
            videoOnlyStreams = streamExtractor.videoOnlyStreams,
            dashUrl = streamExtractor.dashMpdUrl,
            hlsUrl = streamExtractor.hlsUrl,
            screenHeight = screenHeight,
            preferredQuality = preferredQuality,
            preferVideoOnly = preferVideoOnly,
            allowAdaptiveManifests = allowAdaptiveManifests,
        )?.takeIf(String::isNotBlank)
            ?: throw YouTubeExtractionException("No playable stream found for $videoPageUrl")
    }

    private fun selectBestStreamUrl(
        progressiveStreams: List<VideoStream>,
        videoOnlyStreams: List<VideoStream>,
        dashUrl: String?,
        hlsUrl: String?,
        screenHeight: Int,
        preferredQuality: String,
        preferVideoOnly: Boolean,
        allowAdaptiveManifests: Boolean,
    ): String? {
        val effectiveScreenHeight =
            if (isAmlogicDevice() && screenHeight in 1 until 1080) {
                1080
            } else {
                screenHeight
            }
        val screenTargetHeight = targetHeightForScreen(effectiveScreenHeight)
        val qualityTargetHeight = preferredHeightForQuality(preferredQuality)
        val targetHeight = if (qualityTargetHeight > 0) qualityTargetHeight else screenTargetHeight
        val minimumFallbackHeight = minimumAllowedHeight(targetHeight)
        Log.i(
            TAG,
            "Screen: ${screenHeight}p (effective=${effectiveScreenHeight}p, screenTarget=${screenTargetHeight}p), quality=${preferredQuality}, targeting: ${targetHeight}p",
        )
        val playableProgressiveStreams =
            progressiveStreams.filter { !it.isVideoOnly() && it.isUrl() && it.getContent().isNotBlank() }
        val playableVideoOnlyStreams =
            videoOnlyStreams.filter { it.isUrl() && it.getContent().isNotBlank() }
        val playableAnyStreams = (playableProgressiveStreams + playableVideoOnlyStreams)
        val primaryStreams =
            if (preferVideoOnly && playableVideoOnlyStreams.isNotEmpty()) {
                playableVideoOnlyStreams
            } else {
                playableProgressiveStreams
            }
        val secondaryStreams =
            if (primaryStreams === playableVideoOnlyStreams) {
                playableProgressiveStreams
            } else {
                playableVideoOnlyStreams
            }
        Timber.tag(TAG).i(
            "Evaluating YouTube streams (preferred=%s, progressive=%s, videoOnly=%s, mode=%s)",
            preferredQuality,
            playableProgressiveStreams.size,
            playableVideoOnlyStreams.size,
            if (primaryStreams === playableVideoOnlyStreams) "videoOnlyPreferred" else "progressivePreferred",
        )
        return selectStreamContent(primaryStreams, targetHeight, allowUnsupportedFallback = false)
            ?: selectStreamContent(primaryStreams, targetHeight, allowUnsupportedFallback = true)
            ?: selectStreamContent(secondaryStreams, targetHeight, allowUnsupportedFallback = false)
            ?: selectStreamContent(secondaryStreams, targetHeight, allowUnsupportedFallback = true)
            ?: playableProgressiveStreams
                .filter { stream -> streamHeight(stream) >= minimumFallbackHeight }
                .sortedWith(streamQualityComparator())
                .firstOrNull()
                ?.let { stream ->
                Log.w(TAG, "STREAM FALLBACK 1 (quality floor ${minimumFallbackHeight}p): ${describeStream(stream)}")
                stream.getContent()
            }
            ?: playableAnyStreams
                .filter { stream -> streamHeight(stream) >= minimumFallbackHeight }
                .sortedWith(streamQualityComparator())
                .firstOrNull()
                ?.let { stream ->
                Log.w(TAG, "STREAM FALLBACK 2 (quality floor ${minimumFallbackHeight}p): ${describeStream(stream)}")
                stream.getContent()
            }
            ?: dashUrl?.takeIf { allowAdaptiveManifests && it.isNotBlank() }?.also {
                Log.w(TAG, "STREAM FALLBACK 3 (dash): using DASH manifest URL")
            }
            ?: hlsUrl?.takeIf { allowAdaptiveManifests && it.isNotBlank() }?.also {
                Log.w(TAG, "STREAM FALLBACK 4 (hls): using HLS manifest URL")
            }
            ?: run {
                Timber.tag(TAG).w(
                    "No playable YouTube stream found for target=%sp (preference=%s progressive=%s videoOnly=%s dash=%s hls=%s preferVideoOnly=%s)",
                    targetHeight,
                    preferredQuality,
                    progressiveStreams.size,
                    videoOnlyStreams.size,
                    !dashUrl.isNullOrBlank(),
                    !hlsUrl.isNullOrBlank(),
                    preferVideoOnly,
                )
                null
            }
    }

    private fun selectStreamContent(
        streams: List<VideoStream>,
        targetHeight: Int,
        allowUnsupportedFallback: Boolean,
    ): String? =
        selectBestVideoStream(streams, targetHeight, allowUnsupportedFallback)?.let { stream ->
            logSelectedStream(stream)
            stream.getContent()
        }

    private fun selectBestVideoStream(
        streams: List<VideoStream>,
        targetHeight: Int,
        allowUnsupportedFallback: Boolean = false,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport = ::decoderSupport,
    ): VideoStream? {
        val minimumHeight = minimumAllowedHeight(targetHeight)
        val deviceSafeCandidates =
            streams
                .let(::applyDeviceCodecRestrictions)
                .filter { streamHeight(it) > 0 }
                .filter { streamHeight(it) >= minimumHeight }
                .filter { streamHeight(it) <= maxTargetHeight(targetHeight) }
        if (deviceSafeCandidates.isEmpty()) {
            Timber.tag(TAG).w(
                "Rejecting YouTube streams because no candidates fit target=%sp min=%sp (available=%s)",
                targetHeight,
                minimumHeight,
                streams.map { stream -> "${streamHeight(stream)}p/itag=${stream.getItag()}" },
            )
            return null
        }

        val preferredCandidates =
            deviceSafeCandidates.filter { candidate ->
                streamHeight(candidate) in MIN_PREFERRED_PROGRESSIVE_HEIGHT..targetHeight &&
                    candidate.getItag() !in REJECTED_LOW_QUALITY_ITAGS
            }
        val rankedCandidates =
            preferredCandidates.ifEmpty { deviceSafeCandidates }
        val strictPreferredMinimumHeight = strictMinimumPreferredHeight(targetHeight)
        val strictCandidates =
            rankedCandidates.filter { candidate ->
                streamHeight(candidate) >= strictPreferredMinimumHeight
            }
        val supportPriority =
            buildList {
                add(DecoderSupport.SUPPORTED)
                add(DecoderSupport.UNKNOWN)
                if (allowUnsupportedFallback) {
                    add(DecoderSupport.UNSUPPORTED)
                }
            }

        pickBestFromSupportTiers(strictCandidates, targetHeight, supportPriority, supportResolver)?.let { return it }
        if (strictCandidates.size != rankedCandidates.size) {
            pickBestFromSupportTiers(rankedCandidates, targetHeight, supportPriority, supportResolver)?.let { return it }
        }

        return null
    }

    internal fun selectBestVideoStreamForTest(
        streams: List<VideoStream>,
        targetHeight: Int,
        allowUnsupportedFallback: Boolean = false,
        supportedItags: Set<Int> = emptySet(),
        unsupportedItags: Set<Int> = emptySet(),
    ): VideoStream? =
        selectBestVideoStream(
            streams = streams,
            targetHeight = targetHeight,
            allowUnsupportedFallback = allowUnsupportedFallback,
            supportResolver = { _, stream ->
                when (stream.getItag()) {
                    in supportedItags -> DecoderSupport.SUPPORTED
                    in unsupportedItags -> DecoderSupport.UNSUPPORTED
                    else -> DecoderSupport.UNKNOWN
                }
            },
        )

    private fun pickBestFromSupportTiers(
        candidates: List<VideoStream>,
        targetHeight: Int,
        supportPriority: List<DecoderSupport>,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport,
    ): VideoStream? {
        if (candidates.isEmpty()) {
            return null
        }

        supportPriority.forEach { support ->
            val supportCandidates =
                candidates.filter { candidate ->
                    supportResolver(codecFamily(candidate), candidate) == support
                }
            if (supportCandidates.isEmpty()) {
                return@forEach
            }
            selectBestVideoStreamFromTier(supportCandidates, targetHeight, supportResolver)?.let { return it }
        }
        return null
    }

    private fun applyDeviceCodecRestrictions(streams: List<VideoStream>): List<VideoStream> {
        if (!isAmlogicDevice()) {
            return streams
        }

        val nonAv1Candidates =
            streams.filter { stream ->
                codecFamily(stream) != CodecFamily.AV1
            }
        if (nonAv1Candidates.size != streams.size) {
            Log.i(TAG, "Applying Amlogic safe codec restriction: removing AV1 streams (${nonAv1Candidates.size}/${streams.size} streams remain)")
        }
        return nonAv1Candidates.ifEmpty { streams }
    }

    private fun selectBestVideoStreamFromTier(
        streams: List<VideoStream>,
        preferredHeight: Int,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport,
    ): VideoStream? {
        selectBestStreamAtResolution(streams, preferredHeight, supportResolver)?.let { return it }

        RESOLUTION_PRIORITY.forEach { resolution ->
            if (resolution > preferredHeight) {
                return@forEach
            }
            selectBestStreamAtResolution(streams, resolution, supportResolver)?.let { return it }
        }

        Timber.tag(TAG).w("No preferred YouTube stream quality found, using best available fallback")
        return streams.sortedWith(streamQualityComparator(supportResolver)).firstOrNull()
    }

    private fun selectBestStreamAtResolution(
        streams: List<VideoStream>,
        resolution: Int,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport,
    ): VideoStream? {
        val atResolution = streams.filter { streamHeight(it) == resolution }
        if (atResolution.isEmpty()) {
            return null
        }

        CODEC_PRIORITY.forEach { codec ->
            atResolution
                .filter { stream ->
                    stream.getCodec().orEmpty().lowercase(Locale.US).contains(codec)
                }.sortedWith(streamQualityComparator(supportResolver))
                    .firstOrNull()
                    ?.let { return it }
        }

        return atResolution.sortedWith(streamQualityComparator(supportResolver)).firstOrNull()
    }

    private fun streamHeight(stream: VideoStream): Int {
        if (stream.getHeight() > 0) {
            return stream.getHeight()
        }

        val fromResolution =
            RESOLUTION_REGEX
                .find(stream.getResolution())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        if (fromResolution != null) {
            return fromResolution
        }

        return RESOLUTION_REGEX
            .find(stream.getQuality().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun codecScore(stream: VideoStream): Int {
        val codec = stream.getCodec().orEmpty().lowercase(Locale.US)
        return when {
            codec.contains("vp9") || codec.contains("vp09") -> 3
            codec.contains("avc") || codec.contains("h264") -> 2
            codec.contains("av01") || codec.contains("av1") -> 1
            else -> 0
        }
    }

    private fun codecPriorityIndex(stream: VideoStream): Int {
        val codec = stream.getCodec().orEmpty().lowercase(Locale.US)
        return CODEC_PRIORITY.indexOfFirst(codec::contains).takeIf { it >= 0 } ?: CODEC_PRIORITY.size
    }

    private fun codecPenalty(
        stream: VideoStream,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport,
    ): Int {
        val codecFamily = codecFamily(stream)
        return when (supportResolver(codecFamily, stream)) {
            DecoderSupport.SUPPORTED ->
                when (codecFamily) {
                    CodecFamily.VP9 -> 0
                    CodecFamily.AVC -> 100
                    CodecFamily.AV1 -> 200
                    CodecFamily.OTHER -> 300
                }

            DecoderSupport.UNKNOWN ->
                when (codecFamily) {
                    CodecFamily.VP9 -> 0
                    CodecFamily.AVC -> 100
                    CodecFamily.AV1 -> 200
                    CodecFamily.OTHER -> 300
                }

            DecoderSupport.UNSUPPORTED ->
                when (codecFamily) {
                    CodecFamily.VP9 -> 2_000 // Heavily penalize but still allow if no other choice
                    CodecFamily.AVC -> 2_100
                    CodecFamily.AV1 -> 5_200 // AV1 is risky on some TV chips, keep high penalty
                    CodecFamily.OTHER -> 3_300
                }
        }
    }

    private fun logSelectedStream(stream: VideoStream) {
        Log.i(TAG, "STREAM PICKED: ${describeStream(stream)}")
    }

    private fun describeStream(stream: VideoStream): String =
        "${streamHeight(stream)}p codec=${stream.getCodec()} itag=${stream.getItag()} bitrate=${stream.getBitrate()} support=${decoderSupport(codecFamily(stream), stream)}"

    private fun isLikelyAiTitle(titleLower: String): Boolean {
        if (AI_WORD_REGEX.containsMatchIn(titleLower) || AI_PUNCT_WORD_REGEX.containsMatchIn(titleLower)) {
            return true
        }

        return QueryFormulaEngine.aiVideoBlacklist.any { blacklisted ->
            titleLower.contains(blacklisted.lowercase(Locale.US))
        }
    }

    private fun isLikelyAI(item: StreamInfoItem): Boolean {
        val titleLower = item.getName().lowercase(Locale.US)
        val uploaderLower = item.getUploaderName().orEmpty().lowercase(Locale.US)
        val duration = item.getDuration().toInt()

        val titleMatch = isLikelyAiTitle(titleLower)
        val channelMatch =
            AI_CHANNEL_PATTERNS.any { pattern ->
                uploaderLower.contains(pattern)
            }
        val durationMatch = duration in SUSPICIOUS_EXACT_DURATIONS

        return titleMatch || channelMatch || durationMatch
    }

    private fun isBumperOrVlogTitle(titleLower: String): Boolean =
        QueryFormulaEngine.bumperTitleBlacklist.any { blacklisted ->
            titleLower.contains(blacklisted.lowercase(Locale.US))
        }

    private fun isHumanContent(
        title: String,
        uploader: String,
    ): Boolean {
        val titleLower = title.lowercase(Locale.US)
        val uploaderLower = uploader.lowercase(Locale.US)
        return isBumperOrVlogTitle(titleLower) ||
            isTopListTitle(titleLower) ||
            hasDramaticPipePattern(title) ||
            HUMAN_TITLE_BLACKLIST.any(titleLower::contains) ||
            HUMAN_CHANNEL_BLACKLIST.any(uploaderLower::contains) ||
            PERSONAL_VLOG_TITLE_REGEX.containsMatchIn(title)
    }

    internal fun isLikelyHumanContentForTest(
        title: String,
        uploader: String = "",
    ): Boolean = isHumanContent(title = title, uploader = uploader)

    private fun isTopListTitle(titleLower: String): Boolean =
        TOP_LIST_TITLE_REGEX.containsMatchIn(titleLower) ||
            TOP_LIST_TITLE_BLACKLIST.any(titleLower::contains)

    private fun hasDramaticPipePattern(title: String): Boolean {
        val parts = title.split("|").map(String::trim).filter(String::isNotEmpty)
        if (parts.size < 2) {
            return false
        }

        return parts.any { part ->
            val partLower = part.lowercase(Locale.US)
            DRAMATIC_PIPE_WORDS.any(partLower::contains)
        }
    }

    private fun matchesQueryIntent(
        queryLower: String,
        titleLower: String,
        category: QueryFormulaEngine.ContentCategory?,
    ): Boolean {
        val queryTokens = significantQueryTokens(queryLower)
        val matchedTokens = queryTokens.count { token -> titleLower.contains(token) }
        val queryIsAerial = AERIAL_QUERY_REGEX.containsMatchIn(queryLower)
        val titleIsAerial = AERIAL_TITLE_REGEX.containsMatchIn(titleLower)
        val requiredTokenMatches = if (category == null) 2 else 1

        return when {
            queryTokens.isEmpty() -> true
            queryIsAerial -> titleIsAerial || matchedTokens >= requiredTokenMatches
            else -> matchedTokens >= requiredTokenMatches
        }
    }

    private fun significantQueryTokens(queryLower: String): List<String> =
        queryLower
            .split(QUERY_TOKEN_SPLIT_REGEX)
            .map(String::trim)
            .filter { token ->
                token.length >= 4 &&
                    token !in GENERIC_QUERY_TOKENS &&
                    token.any(Char::isLetter)
            }

    private fun hasPreferredContentSignal(titleLower: String): Boolean =
        PREFERRED_CONTENT_SIGNALS.any { signal ->
            titleLower.contains(signal)
        }

    private fun preferredContentScore(titleLower: String): Int =
        PREFERRED_CONTENT_SIGNALS.count(titleLower::contains)

    private fun streamQualityComparator(
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport = ::decoderSupport,
    ): Comparator<VideoStream> =
        compareByDescending<VideoStream> { streamHeight(it) }
            .thenByDescending { meetsBitrateFloor(it) }
            .thenBy { codecPenalty(it, supportResolver) }
            .thenByDescending { codecScore(it) }
            .thenByDescending { it.getBitrate() }
            .thenBy { codecPriorityIndex(it) }

    private fun meetsBitrateFloor(stream: VideoStream): Boolean {
        val bitrate = stream.getBitrate()
        if (bitrate <= 0) {
            return true
        }

        val floor =
            when {
                streamHeight(stream) >= 2160 -> 12_000_000
                streamHeight(stream) >= 1440 -> 8_000_000
                streamHeight(stream) >= 1080 -> 4_500_000
                streamHeight(stream) >= 720 -> 2_500_000
                streamHeight(stream) >= 480 -> 1_200_000
                else -> 0
            }
        return bitrate >= floor
    }

    private fun isLikelySyntheticWallpaperTitle(titleLower: String): Boolean =
        SYNTHETIC_WALLPAPER_BLACKLIST.any { token ->
            titleLower.contains(token)
        }

    private fun normalizeTitleFingerprint(title: String): String =
        title
            .lowercase(Locale.US)
            .replace("\\b(4k|8k|hdr|uhd|ambient|no music|no talking|screensaver|hours?|hour|mins?|minutes?)\\b".toRegex(), " ")
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()

    private fun decoderSupport(
        codecFamily: CodecFamily,
        stream: VideoStream,
    ): DecoderSupport {
        if (isKnownUnsupportedTvCodecPath(codecFamily, stream)) {
            return DecoderSupport.UNSUPPORTED
        }

        val mimeType = codecFamily.mimeType ?: return DecoderSupport.UNKNOWN
        val streamSize = streamSize(stream) ?: return decoderAvailability(mimeType)
        val cacheKey = DecoderSupportKey(mimeType, streamSize.first, streamSize.second)

        synchronized(decoderSupportCache) {
            decoderSupportCache[cacheKey]?.let { return it }
        }

        val support = inspectDecoderSupport(mimeType, streamSize)

        synchronized(decoderSupportCache) {
            decoderSupportCache[cacheKey] = support
        }
        return support
    }

    private fun inspectDecoderSupport(
        mimeType: String,
        streamSize: Pair<Int, Int>,
    ): DecoderSupport =
        runCatching {
            val decoders =
                MediaCodecList(MediaCodecList.ALL_CODECS)
                    .codecInfos
                    .asSequence()
                    .filterNot { it.isEncoder }
                    .filter { codecInfo ->
                        codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                    }.toList()

            when {
                decoders.isEmpty() -> DecoderSupport.UNSUPPORTED
                decoders.any { codecInfo -> codecSupportsSize(codecInfo, mimeType, streamSize) } -> DecoderSupport.SUPPORTED
                else -> DecoderSupport.UNSUPPORTED
            }
        }.getOrElse { exception ->
            Timber.tag(TAG).w(
                exception,
                "Failed to inspect decoder support for %s at %sx%s",
                mimeType,
                streamSize.first,
                streamSize.second,
            )
            DecoderSupport.UNKNOWN
        }

    private fun codecSupportsSize(
        codecInfo: android.media.MediaCodecInfo,
        mimeType: String,
        streamSize: Pair<Int, Int>,
    ): Boolean {
        val supportedType =
            codecInfo.supportedTypes.firstOrNull { it.equals(mimeType, ignoreCase = true) }
                ?: return false
        val capabilities = codecInfo.getCapabilitiesForType(supportedType)
        val videoCapabilities = capabilities.videoCapabilities ?: return true
        return videoCapabilities.isSizeSupported(streamSize.first, streamSize.second) ||
            videoCapabilities.isSizeSupported(streamSize.second, streamSize.first)
    }

    private fun decoderAvailability(mimeType: String): DecoderSupport {
        synchronized(decoderAvailabilityCache) {
            decoderAvailabilityCache[mimeType]?.let { return it }
        }

        val support =
            runCatching {
                val hasDecoder =
                    MediaCodecList(MediaCodecList.ALL_CODECS)
                        .codecInfos
                        .asSequence()
                        .filterNot { it.isEncoder }
                        .any { codecInfo ->
                            codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                        }
                if (hasDecoder) {
                    DecoderSupport.UNKNOWN
                } else {
                    DecoderSupport.UNSUPPORTED
                }
            }.getOrElse { exception ->
                Timber.tag(TAG).w(exception, "Failed to inspect decoder availability for %s", mimeType)
                DecoderSupport.UNKNOWN
            }

        synchronized(decoderAvailabilityCache) {
            decoderAvailabilityCache[mimeType] = support
        }
        return support
    }

    private fun codecFamily(stream: VideoStream): CodecFamily {
        val codec = stream.getCodec().orEmpty().lowercase(Locale.US)
        return when {
            codec.contains("av01") -> CodecFamily.AV1
            codec.contains("vp09") || codec.contains("vp9") -> CodecFamily.VP9
            codec.contains("avc") -> CodecFamily.AVC
            else -> CodecFamily.OTHER
        }
    }

    private fun streamSize(stream: VideoStream): Pair<Int, Int>? {
        val resolution = stream.getResolution().orEmpty()
        RESOLUTION_PAIR_REGEX.find(resolution)?.let { match ->
            val width = match.groupValues.getOrNull(1)?.toIntOrNull()
            val height = match.groupValues.getOrNull(2)?.toIntOrNull()
            if (width != null && height != null) {
                return Pair(width, height)
            }
        }

        val height = streamHeight(stream)
        if (height <= 0) {
            return null
        }

        val width = (height * 16f / 9f).toInt().coerceAtLeast(1)
        return Pair(width, height)
    }

    private fun isKnownUnsupportedTvCodecPath(
        codecFamily: CodecFamily,
        stream: VideoStream,
    ): Boolean {
        val height = streamHeight(stream)
        if (isAmlogicDevice() && codecFamily == CodecFamily.AV1) {
            Timber.tag(TAG).w(
                "Treating %sp %s as unsupported on this device due to Amlogic AV1 decoder instability",
                height,
                stream.getCodec(),
            )
            return true
        }

        return false
    }

    private fun isAmlogicDevice(): Boolean =
        DEVICE_FINGERPRINT.contains("amlogic") ||
            DEVICE_FINGERPRINT.contains("t5d") ||
            DEVICE_FINGERPRINT.contains("rango") ||
            DEVICE_FINGERPRINT.contains("mitv")

    private fun isRecentEnough(item: StreamInfoItem): Boolean {
        val uploadInstant = item.getUploadDate()?.getInstant() ?: return true
        return uploadInstant.isAfter(ZonedDateTime.now().minusYears(10).toInstant())
    }

    fun getScreenHeight(context: Context): Int {
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val visualDisplay =
                runCatching { context.display }
                    .getOrNull()
            val display =
                visualDisplay
                    ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            val activeHeight = display?.mode?.physicalHeight ?: 0
            val supportedHeight = display?.supportedModes?.maxOfOrNull { mode -> mode.physicalHeight } ?: 0
            val windowHeight = wm.currentWindowMetrics.bounds.height()
            return maxOf(activeHeight, supportedHeight, windowHeight).coerceAtLeast(720)
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels
    }

    private fun targetHeightForScreen(screenHeight: Int): Int =
        when {
            screenHeight >= 2160 -> 2160
            screenHeight >= 1440 -> 1440
            screenHeight >= 1080 -> 1080
            else -> 1080 // Default to 1080p target even on 720p screens for better supersampled quality
        }

    private fun preferredHeightForQuality(preferredQuality: String): Int {
        val quality = preferredQuality.lowercase(Locale.US)
        return when {
            quality == "best" -> 2160 // Target 4K if available for 'best'
            quality.contains("2160") || quality.contains("4k") -> 2160
            quality.contains("1440") -> 1440
            quality.contains("1080") -> 1080
            quality.contains("720") -> 720
            else -> 1080 // Default to 1080p if not specified
        }
    }

    private fun maxTargetHeight(targetHeight: Int): Int =
        if (targetHeight <= 1080) 2160 else ((targetHeight * 1.2f).toInt()).coerceAtLeast(targetHeight)

    private fun minimumAllowedHeight(targetHeight: Int): Int =
        when {
            targetHeight >= 2160 -> 1080
            targetHeight >= 1440 -> 1080
            targetHeight >= 1080 -> 720
            targetHeight >= 720 -> 480
            else -> 360
        }

    private fun strictMinimumPreferredHeight(targetHeight: Int): Int =
        when {
            targetHeight >= 2160 -> 1440
            targetHeight >= 1440 -> 1080
            targetHeight >= 1080 -> 1080
            targetHeight >= 720 -> 720
            else -> minimumAllowedHeight(targetHeight)
        }

    private class OkHttpDownloader(
        private val client: OkHttpClient,
    ) : Downloader() {
        override fun execute(request: Request): Response {
            val okHttpRequest = buildOkHttpRequest(request)

            client.newCall(okHttpRequest).execute().use { response ->
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    if (request.httpMethod() == "HEAD") "" else response.body.string(),
                    response.request.url.toString(),
                )
            }
        }

        private fun buildOkHttpRequest(request: Request) =
            requestBuilder(request).let { requestBuilder ->
                when (request.httpMethod()) {
                    "POST" -> {
                        val mediaType = request.getHeader("Content-Type")?.toMediaTypeOrNull()
                        val body = (request.dataToSend() ?: ByteArray(0)).toRequestBody(mediaType)
                        requestBuilder.post(body).build()
                    }

                    "HEAD" -> requestBuilder.head().build()
                    else -> requestBuilder.get().build()
                }
            }

        private fun requestBuilder(request: Request): Builder =
            Builder()
                .url(request.url())
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Origin", ORIGIN)
                .also { requestBuilder ->
                    request.headers().forEach { (name, values) ->
                        if (name.isBlank()) {
                            return@forEach
                        }

                        requestBuilder.removeHeader(name)
                        values.forEach { value ->
                            requestBuilder.addHeader(name, value)
                        }
                    }
                }

        private fun Request.getHeader(name: String): String? =
            headers()[name]?.firstOrNull()
    }

    private val SearchInfo.relatedItems: List<InfoItem>
        get() = getRelatedItems()

    private const val MAX_RESULTS_PER_QUERY = 24
    private const val MIN_QUERY_MATCH_RESULTS = 4
    private const val MIN_PREFERRED_RESULTS_PER_QUERY = 6
    private val AI_WORD_REGEX = Regex("\\bai\\b", RegexOption.IGNORE_CASE)
    private val AI_PUNCT_WORD_REGEX = Regex("\\ba\\W*i\\b", RegexOption.IGNORE_CASE)
    private val QUERY_TOKEN_SPLIT_REGEX = Regex("[^a-z0-9']+")
    private val DEVICE_FINGERPRINT =
        listOf(
            Build.HARDWARE,
            Build.BOARD,
            Build.DEVICE,
            Build.MANUFACTURER,
            Build.MODEL,
        ).joinToString(" ") { value ->
            value.orEmpty()
        }.lowercase(Locale.US)
    private val AERIAL_QUERY_REGEX = Regex("(aerial|drone|flyover|flythrough|bird's eye|hyperlapse)", RegexOption.IGNORE_CASE)
    private val AERIAL_TITLE_REGEX = Regex("(aerial|drone|flyover|flythrough|fpv|bird's eye|uav)", RegexOption.IGNORE_CASE)
    private val GENERIC_QUERY_TOKENS =
        setOf(
            "4k",
            "8k",
            "hdr",
            "ultra",
            "ultrahd",
            "hd",
            "cinematic",
            "aerial",
            "drone",
            "footage",
            "timelapse",
            "flyover",
            "flythrough",
            "view",
            "video",
            "nature",
            "music",
            "ambient",
            "sounds",
            "only",
            "clear",
            "crystal",
            "sunrise",
            "sunset",
        )
    private val PREFERRED_CONTENT_SIGNALS =
        listOf(
            "no music",
            "no talking",
            "wildlife",
            "timelapse",
            "slow motion",
            "documentary",
            "real footage",
            "tripod",
            "locked off",
            "nature film",
            "national park",
            "underwater",
            "ocean",
            "waterfall",
            "forest",
            "coral reef",
            "drone",
            "aerial",
            "flyover",
            "fpv",
            "whale",
            "dolphin",
            "penguin",
            "elephant",
        )
    private val SYNTHETIC_WALLPAPER_BLACKLIST =
        listOf(
            "wallpaper",
            "cgi",
            "3d",
            "render",
            "rendered",
            "animation",
            "animated",
            "loop",
            "visualizer",
            "unreal engine",
            "blender",
            "simulation",
            "fantasy",
            "dreamscape",
            "ambient video",
            "relaxing video",
            "generated video",
            "upscaled",
            "upscale",
            "text to video",
            "veo",
            "sora",
            "kling",
            "pika",
            "pixverse",
            "luma ai",
            "hailuo",
            "haiper",
            "hunyuan",
            "dreamina",
            "image to video",
            "sleep",
            "study",
            "meditation",
            "healing",
            "zen",
            "slideshow",
            "backgrounds",
            "stock footage",
            "travel wallpaper",
            "nature wallpaper",
        )
    private val AI_CHANNEL_PATTERNS =
        listOf(
            "ai art",
            "ai video",
            "ai film",
            "ai nature",
            "ai generated",
            "sora",
            "kling",
            "pika",
            "veo",
            "hailuo",
            "haiper",
            "runway clips",
            "runway",
            "synthwave",
            "neural",
            "diffusion studio",
            "ai cinema",
            "artificial",
        )
    private val SUSPICIOUS_EXACT_DURATIONS = setOf(3600, 7200, 10800, 14400, 21600)
    private const val MIN_PREFERRED_PROGRESSIVE_HEIGHT = 1080
    private val RESOLUTION_PRIORITY = listOf(2160, 1440, 1080, 720, 480)
    private val CODEC_PRIORITY = listOf("vp9", "vp09", "avc1", "avc", "av01", "av1")
    private val REJECTED_LOW_QUALITY_ITAGS = setOf(18, 36, 133, 134, 135, 160) // itag 18 is 360p, 133 is 240p, 134 is 360p, 135 is 480p, 160 is 144p. Added 36 (240p).
    private val TOP_LIST_TITLE_BLACKLIST =
        listOf(
            "top 10",
            "top ten",
            "most beautiful places",
            "best places to visit",
        )
    private val TOP_LIST_TITLE_REGEX = Regex("\\btop\\s*\\d+\\b", RegexOption.IGNORE_CASE)
    private val HUMAN_TITLE_BLACKLIST =
        listOf(
            "vlog",
            "day in my life",
            "daily vlog",
            "week in my life",
            "come with me",
            "grwm",
            "get ready with me",
            "storytime",
            "story time",
            "what i eat",
            "tutorial",
            "how to",
            "tips",
            "tricks",
            "guide",
            "review",
            "unboxing",
            "haul",
            "try on",
            "reaction",
            "reacts",
            "challenge",
            "prank",
            "comedy",
            "funny",
            "fails",
            "compilation",
            "my morning",
            "my routine",
            "my day",
            "my life",
            "i tried",
            "i spent",
            "i ate",
            "we tried",
            "interview",
            "podcast",
            "talk show",
            "news",
            "explained",
            "analysis",
            "opinion",
            "gameplay",
            "gaming",
            "playthrough",
            "let's play",
            "recipe",
            "cooking",
            "mukbang",
            "food review",
            "music video",
            "official video",
            "lyric video",
            "live performance",
            "concert",
            "epilepsy warning",
            "flashing lights",
            "flashing warning",
            "seizure warning",
            "photosensitive",
            "strobe",
            "strobing",
            "flicker warning",
            "trigger warning",
            "content warning",
            "wildlife documentary",
            "animal documentary",
            "animals documentary",
            "nature documentary",
            "unbelievable",
            "cliff chase",
            "fight",
            "attack",
            "predator vs",
            "vs predator",
            "hunt",
            "hunting",
            "chase",
            "survival",
            "incredible moment",
            "caught on camera",
            "rare footage",
            "amazing footage",
            "shocking",
            "brutal",
            "epic battle",
        )
    private val HUMAN_CHANNEL_BLACKLIST =
        listOf(
            "vlog",
            "daily",
            "family",
            "kids",
            "children",
            "cooking",
            "gaming",
            "news",
            "politics",
            "comedy",
            "funny",
            "entertainment",
            "reviews",
            "tutorials",
            "podcast",
            "tv shows",
            "official",
            "epilepsy",
            "seizure",
            "strobe",
            "flicker",
            "documentary",
            "wildlife films",
            "animal planet",
            "nat geo wild",
            "discovery channel",
        )
    private val DRAMATIC_PIPE_WORDS =
        listOf(
            "fight",
            "attack",
            "chase",
            "hunt",
            "kill",
            "vs",
            "predator",
            "prey",
            "incredible",
            "unbelievable",
            "shocking",
            "rare",
            "amazing",
            "caught",
        )
    private val PERSONAL_VLOG_TITLE_REGEX =
        Regex(
            "^[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,2}\\s+(vlog|storytime|tutorial|review|challenge|podcast|interview)\\b",
        )
    private val RESOLUTION_REGEX = Regex("(\\d{3,4})p")
    private val RESOLUTION_PAIR_REGEX = Regex("(\\d{3,4})\\s*[xX]\\s*(\\d{3,4})")
    private val decoderSupportCache = mutableMapOf<DecoderSupportKey, DecoderSupport>()
    private val decoderAvailabilityCache = mutableMapOf<String, DecoderSupport>()

    private fun buildSearchUrl(
        query: String,
        contentFilter: String,
    ): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val encodedSearchParameter =
            URLEncoder.encode(
                YoutubeSearchQueryHandlerFactory.getSearchParameter(contentFilter),
                "UTF-8",
            )
        return "https://www.youtube.com/results?search_query=$encodedQuery&sp=$encodedSearchParameter"
    }

    private fun extractVideoId(videoPageUrl: String): String? {
        QUERY_VIDEO_ID_REGEX
            .find(videoPageUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val trimmedPath =
            videoPageUrl
                .substringAfter("://", videoPageUrl)
                .substringAfter('/', "")
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')

        return trimmedPath.takeIf { it.isNotBlank() }
    }

    fun playbackRequestHeaders(): Map<String, String> =
        mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to REFERER,
            "Origin" to ORIGIN,
        )

    private inline fun <T> List<T>.ifEnoughOrElse(
        minimumSize: Int,
        fallback: () -> List<T>,
    ): List<T> = if (size >= minimumSize) this else fallback()

    private data class DecoderSupportKey(
        val mimeType: String,
        val width: Int,
        val height: Int,
    )

    private enum class CodecFamily(
        val mimeType: String?,
    ) {
        AV1("video/av01"),
        VP9("video/x-vnd.on2.vp9"),
        AVC("video/avc"),
        OTHER(null),
    }

    private enum class DecoderSupport {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN,
    }

    private val QUERY_VIDEO_ID_REGEX = Regex("[?&]v=([^&#]+)")
}
