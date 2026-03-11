package com.neilturner.aerialviews.providers.youtube

import android.media.MediaCodecList
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
        minDurationSeconds: Int = 600,
    ): List<StreamInfoItem> =
        withContext(Dispatchers.IO) {
            init()
            val requiredMinDurationSeconds = maxOf(minDurationSeconds, MIN_SEARCH_DURATION_SECONDS)

            try {
                val service = NewPipe.getService("YouTube")
                val searchUrl = buildSearchUrl(query, YoutubeSearchQueryHandlerFactory.VIDEOS)
                val searchInfo =
                    SearchInfo.getInfo(
                        service,
                        SearchQueryHandler(
                            searchUrl,
                            searchUrl,
                            query,
                            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                            "",
                        ),
                    )

                val baseCandidates =
                    searchInfo.relatedItems
                        .asSequence()
                        .filterIsInstance<StreamInfoItem>()
                        .filter { item ->
                            item.getDuration() >= requiredMinDurationSeconds &&
                                item.getUrl().isNotBlank() &&
                                item.getName().isNotBlank()
                        }.filter { item ->
                            val titleLower = item.getName().lowercase(Locale.US)
                            !isLikelyAI(item) &&
                                !isBumperOrVlogTitle(titleLower) &&
                                !isLikelySyntheticWallpaperTitle(titleLower)
                        }.filter(::isRecentEnough)
                        .toList()

                val relevantCandidates =
                    baseCandidates.filter { candidate ->
                        matchesQueryIntent(
                            queryLower = query.lowercase(Locale.US),
                            titleLower = candidate.getName().lowercase(Locale.US),
                        )
                    }

                val queryMatchedCandidates =
                    if (relevantCandidates.size >= MIN_QUERY_MATCH_RESULTS) {
                        relevantCandidates
                    } else {
                        baseCandidates
                    }

                val preferredCandidates =
                    queryMatchedCandidates.filter { candidate ->
                        hasPreferredContentSignal(candidate.getName().lowercase(Locale.US))
                    }

                val selectedCandidates =
                    if (preferredCandidates.size >= MIN_PREFERRED_RESULTS_PER_QUERY) {
                        preferredCandidates
                    } else {
                        queryMatchedCandidates
                    }

                selectedCandidates
                    .asSequence()
                    .distinctBy { extractVideoId(it.getUrl()) ?: it.getUrl() }
                    .distinctBy {
                        val fallbackKey = extractVideoId(it.getUrl()) ?: it.getUrl()
                        normalizeTitleFingerprint(it.getName()).ifBlank { fallbackKey }
                    }
                    .take(20)
                    .toList()
            } catch (throwable: Throwable) {
                throw YouTubeExtractionException(
                    "Failed to search YouTube for \"$query\"",
                    throwable,
                )
            }
        }

    suspend fun extractStreamUrl(
        videoPageUrl: String,
        preferredQuality: String = "1080p",
        preferVideoOnly: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            init()

            try {
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
                val streamUrl =
                    selectBestStreamUrl(
                        progressiveStreams = streamExtractor.videoStreams,
                        videoOnlyStreams = streamExtractor.videoOnlyStreams,
                        dashUrl = streamExtractor.dashMpdUrl,
                        hlsUrl = streamExtractor.hlsUrl,
                        preferredQuality = preferredQuality,
                        preferVideoOnly = preferVideoOnly,
                    )
                if (streamUrl.isNullOrBlank()) {
                    throw YouTubeExtractionException(
                        "No playable stream found for $videoPageUrl",
                    )
                }
                return@withContext streamUrl
            } catch (exception: AgeRestrictedContentException) {
                throw exception
            } catch (exception: GeographicRestrictionException) {
                throw exception
            } catch (exception: ContentNotAvailableException) {
                throw exception
            } catch (exception: ExtractionException) {
                throw exception
            } catch (exception: YouTubeExtractionException) {
                throw exception
            } catch (throwable: Throwable) {
                throw YouTubeExtractionException(
                    "Failed to extract a playable stream for $videoPageUrl",
                    throwable,
                )
            }
        }

    private fun selectBestStreamUrl(
        progressiveStreams: List<VideoStream>,
        videoOnlyStreams: List<VideoStream>,
        dashUrl: String?,
        hlsUrl: String?,
        preferredQuality: String,
        preferVideoOnly: Boolean,
    ): String? {
        val normalizedPreference = preferredQuality.trim().lowercase()
        val preferredHeight = qualityToHeight(normalizedPreference)
        val minimumAcceptableHeight = minimumAcceptableHeight(normalizedPreference)
        val playableProgressiveStreams =
            progressiveStreams.filter { !it.isVideoOnly() && it.isUrl() && it.getContent().isNotBlank() }
        val playableVideoOnlyStreams =
            videoOnlyStreams.filter { it.isUrl() && it.getContent().isNotBlank() }
        val playableDashUrl = dashUrl?.takeIf { it.isNotBlank() }
        val playableHlsUrl = hlsUrl?.takeIf { it.isNotBlank() }

        val primaryStreams = if (preferVideoOnly) playableVideoOnlyStreams else playableProgressiveStreams
        val secondaryStreams = if (preferVideoOnly) playableProgressiveStreams else playableVideoOnlyStreams

        selectBestVideoStream(primaryStreams, preferredHeight, minimumAcceptableHeight)?.let { stream ->
            logSelectedStream(stream)
            return stream.getContent()
        }

        selectBestVideoStream(secondaryStreams, preferredHeight, minimumAcceptableHeight)?.let { stream ->
            logSelectedStream(stream)
            return stream.getContent()
        }

        playableDashUrl?.let { return it }
        if (playableHlsUrl != null) {
            return playableHlsUrl
        }

        return selectBestVideoStream(primaryStreams + secondaryStreams, preferredHeight, null)?.let { stream ->
            logSelectedStream(stream)
            stream.getContent()
        }
    }

    private fun selectBestVideoStream(
        streams: List<VideoStream>,
        preferredHeight: Int?,
        minimumAcceptableHeight: Int?,
    ): VideoStream? {
        val filteredStreams =
            streams
                .filterNot { it.getItag() in REJECTED_LOW_QUALITY_ITAGS }
                .filter { minimumAcceptableHeight == null || streamHeight(it) >= minimumAcceptableHeight }
        if (filteredStreams.isEmpty()) {
            return null
        }

        val bitrateCap = bitrateCapForHeight(preferredHeight)
        val memoryFriendlyStreams =
            if (bitrateCap == null) {
                filteredStreams
            } else {
                filteredStreams.filter { it.getBitrate() <= bitrateCap }.ifEmpty { filteredStreams }
            }

        val rankedStreams = memoryFriendlyStreams.sortedWith(streamComparator(preferredHeight))
        return rankedStreams.firstOrNull()
    }

    private fun minimumAcceptableHeight(quality: String): Int? =
        when (quality) {
            "2160p",
            "4k",
            -> 1080

            "1080p",
            "720p",
            "best",
            "best available",
            -> 720

            else -> null
        }

    private fun qualityToHeight(quality: String): Int? =
        when (quality) {
            "2160p",
            "4k",
            -> 2160
            "1080p" -> 1080
            "720p" -> 720
            "best",
            "best available",
            -> null
            else -> null
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

    private fun streamComparator(preferredHeight: Int?): Comparator<VideoStream> =
        compareBy<VideoStream> { streamScore(it, preferredHeight) }
            .thenByDescending { it.getBitrate() }
            .thenByDescending { streamHeight(it) }

    private fun streamScore(
        stream: VideoStream,
        preferredHeight: Int?,
    ): Int =
        heightPenalty(stream, preferredHeight) + codecPenalty(stream)

    private fun heightPenalty(
        stream: VideoStream,
        preferredHeight: Int?,
    ): Int {
        if (preferredHeight == null) {
            return 0
        }

        val height = streamHeight(stream)
        return if (height > preferredHeight) {
            1_000 + (height - preferredHeight) * 2
        } else {
            preferredHeight - height
        }
    }

    private fun codecPenalty(stream: VideoStream): Int {
        val codecFamily = codecFamily(stream)
        return when (decoderSupport(codecFamily, stream)) {
            DecoderSupport.SUPPORTED ->
                when (codecFamily) {
                    CodecFamily.AV1 -> 0
                    CodecFamily.VP9 -> 100
                    CodecFamily.AVC -> 200
                    CodecFamily.OTHER -> 300
                }

            DecoderSupport.UNKNOWN ->
                when (codecFamily) {
                    CodecFamily.VP9 -> 100
                    CodecFamily.AVC -> 200
                    CodecFamily.OTHER -> 300
                    CodecFamily.AV1 -> 1_000
                }

            DecoderSupport.UNSUPPORTED ->
                when (codecFamily) {
                    CodecFamily.VP9 -> 5_000
                    CodecFamily.AVC -> 5_100
                    CodecFamily.OTHER -> 5_200
                    CodecFamily.AV1 -> 6_000
                }
        }
    }

    private fun bitrateCapForHeight(preferredHeight: Int?): Int? =
        when {
            preferredHeight == null -> 20_000_000
            preferredHeight >= 2160 -> 22_000_000
            preferredHeight >= 1080 -> 10_000_000
            preferredHeight >= 720 -> 6_500_000
            else -> 4_500_000
        }

    private fun logSelectedStream(stream: VideoStream) {
        Timber.d(
            "Selected YouTube stream: %sp codec=%s bitrate=%s itag=%s support=%s",
            streamHeight(stream),
            stream.getCodec(),
            stream.getBitrate(),
            stream.getItag(),
            decoderSupport(codecFamily(stream), stream),
        )
    }

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

    private fun matchesQueryIntent(
        queryLower: String,
        titleLower: String,
    ): Boolean {
        val queryTokens = significantQueryTokens(queryLower)
        val matchedTokens = queryTokens.count { token -> titleLower.contains(token) }
        val queryIsAerial = AERIAL_QUERY_REGEX.containsMatchIn(queryLower)
        val titleIsAerial = AERIAL_TITLE_REGEX.containsMatchIn(titleLower)

        return when {
            queryIsAerial -> titleIsAerial || matchedTokens >= 2
            else -> matchedTokens >= 2 || hasPreferredContentSignal(titleLower)
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
        val mimeType = codecFamily.mimeType ?: return DecoderSupport.UNKNOWN
        val streamSize = streamSize(stream) ?: return decoderAvailability(mimeType)
        val cacheKey = DecoderSupportKey(mimeType, streamSize.first, streamSize.second)

        synchronized(decoderSupportCache) {
            decoderSupportCache[cacheKey]?.let { return it }
        }

        val support =
            runCatching {
                val decoders =
                    MediaCodecList(MediaCodecList.ALL_CODECS)
                        .codecInfos
                        .asSequence()
                        .filterNot { it.isEncoder }
                        .filter { codecInfo ->
                            codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                        }.toList()

                if (decoders.isEmpty()) {
                    DecoderSupport.UNSUPPORTED
                } else {
                    val isSupported =
                        decoders.any { codecInfo ->
                            val supportedType =
                                codecInfo.supportedTypes.firstOrNull { it.equals(mimeType, ignoreCase = true) }
                                    ?: return@any false
                            val capabilities = codecInfo.getCapabilitiesForType(supportedType)
                            val videoCapabilities = capabilities.videoCapabilities ?: return@any true
                            videoCapabilities.isSizeSupported(streamSize.first, streamSize.second) ||
                                videoCapabilities.isSizeSupported(streamSize.second, streamSize.first)
                        }
                    if (isSupported) {
                        DecoderSupport.SUPPORTED
                    } else {
                        DecoderSupport.UNSUPPORTED
                    }
                }
            }.getOrElse { exception ->
                Timber.w(
                    exception,
                    "Failed to inspect decoder support for %s at %sx%s",
                    mimeType,
                    streamSize.first,
                    streamSize.second,
                )
                DecoderSupport.UNKNOWN
            }

        synchronized(decoderSupportCache) {
            decoderSupportCache[cacheKey] = support
        }
        return support
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
                Timber.w(exception, "Failed to inspect decoder availability for %s", mimeType)
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

    private fun isRecentEnough(item: StreamInfoItem): Boolean {
        val uploadInstant = item.getUploadDate()?.getInstant() ?: return true
        return uploadInstant.isAfter(ZonedDateTime.now().minusMonths(6).toInstant())
    }

    private class OkHttpDownloader(
        private val client: OkHttpClient,
    ) : Downloader() {
        override fun execute(request: Request): Response {
            val requestBuilder =
                Builder()
                    .url(request.url())
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .header("Origin", ORIGIN)

            request.headers().forEach { (name, values) ->
                if (name.isBlank()) {
                    return@forEach
                }

                requestBuilder.removeHeader(name)
                values.forEach { value ->
                    requestBuilder.addHeader(name, value)
                }
            }

            val okHttpRequest =
                when (request.httpMethod()) {
                    "POST" -> {
                        val mediaType = request.getHeader("Content-Type")?.toMediaTypeOrNull()
                        val body = (request.dataToSend() ?: ByteArray(0)).toRequestBody(mediaType)
                        requestBuilder.post(body).build()
                    }

                    "HEAD" -> requestBuilder.head().build()
                    else -> requestBuilder.get().build()
                }

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

        private fun Request.getHeader(name: String): String? =
            headers()[name]?.firstOrNull()
    }

    private val SearchInfo.relatedItems: List<InfoItem>
        get() = getRelatedItems()

    private const val MIN_SEARCH_DURATION_SECONDS = 480
    private const val MIN_QUERY_MATCH_RESULTS = 4
    private const val MIN_PREFERRED_RESULTS_PER_QUERY = 6
    private val AI_WORD_REGEX = Regex("\\bai\\b", RegexOption.IGNORE_CASE)
    private val AI_PUNCT_WORD_REGEX = Regex("\\ba\\W*i\\b", RegexOption.IGNORE_CASE)
    private val QUERY_TOKEN_SPLIT_REGEX = Regex("[^a-z0-9']+")
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
            "pixverse",
            "luma ai",
            "hailuo",
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
            "sora clips",
            "runway clips",
            "synthwave",
            "neural",
            "diffusion studio",
            "ai cinema",
            "artificial",
        )
    private val SUSPICIOUS_EXACT_DURATIONS = setOf(3600, 7200, 10800, 14400, 21600)
    private val REJECTED_LOW_QUALITY_ITAGS = setOf(18, 133, 160)
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
