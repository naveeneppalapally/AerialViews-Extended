package com.neilturner.aerialviews.providers.youtube

import android.media.MediaCodecList
import android.os.Build
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
        minDurationSeconds: Int = 600,
        category: QueryFormulaEngine.ContentCategory? = null,
    ): List<StreamInfoItem> =
        withContext(Dispatchers.IO) {
            init()

            try {
                val requiredMinDurationSeconds = maxOf(minDurationSeconds, MIN_SEARCH_DURATION_SECONDS)
                val searchInfo = loadSearchInfo(query)
                val baseCandidates = buildSearchCandidates(searchInfo, requiredMinDurationSeconds)
                selectSearchResults(query, category, baseCandidates)
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
        preferredQuality: String = "1080p",
        preferVideoOnly: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            init()

            try {
                loadPlayableStreamUrl(videoPageUrl, preferredQuality, preferVideoOnly)
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
        requiredMinDurationSeconds: Int,
    ): List<StreamInfoItem> =
        searchInfo.relatedItems
            .asSequence()
            .filterIsInstance<StreamInfoItem>()
            .filter(::hasUsableMetadata)
            .filter { item -> item.getDuration() >= requiredMinDurationSeconds }
            .filterNot(::isFilteredCandidate)
            .filter(::isRecentEnough)
            .toList()

    private fun selectSearchResults(
        query: String,
        category: QueryFormulaEngine.ContentCategory?,
        baseCandidates: List<StreamInfoItem>,
    ): List<StreamInfoItem> {
        val queryLower = query.lowercase(Locale.US)
        val categoryMatchedCandidates =
            baseCandidates.filter { candidate ->
                QueryFormulaEngine.matchesCandidateCategory(
                    title = candidate.getName(),
                    uploader = candidate.getUploaderName().orEmpty(),
                    category = category,
                )
            }.ifEnoughOrElse(MIN_QUERY_MATCH_RESULTS) { baseCandidates }

        val queryMatchedCandidates =
            categoryMatchedCandidates.filter { candidate ->
                matchesQueryIntent(
                    queryLower = queryLower,
                    titleLower = candidate.getName().lowercase(Locale.US),
                    category = category,
                )
            }.ifEnoughOrElse(MIN_QUERY_MATCH_RESULTS) { categoryMatchedCandidates }

        return queryMatchedCandidates
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
            isBumperOrVlogTitle(titleLower) ||
            isLikelySyntheticWallpaperTitle(titleLower)
    }

    private fun loadPlayableStreamUrl(
        videoPageUrl: String,
        preferredQuality: String,
        preferVideoOnly: Boolean,
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

        return selectBestStreamUrl(
            progressiveStreams = streamExtractor.videoStreams,
            videoOnlyStreams = streamExtractor.videoOnlyStreams,
            dashUrl = streamExtractor.dashMpdUrl,
            hlsUrl = streamExtractor.hlsUrl,
            preferredQuality = preferredQuality,
            preferVideoOnly = preferVideoOnly,
        )?.takeIf(String::isNotBlank)
            ?: throw YouTubeExtractionException("No playable stream found for $videoPageUrl")
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
        val playableProgressiveStreams =
            progressiveStreams.filter { !it.isVideoOnly() && it.isUrl() && it.getContent().isNotBlank() }
        Timber.tag(TAG).i(
            "Evaluating YouTube progressive streams (preferred=%s, playable=%s, available=%s)",
            preferredQuality,
            playableProgressiveStreams.size,
            playableProgressiveStreams.joinToString { stream -> describeStream(stream) },
        )
        return selectStreamContent(playableProgressiveStreams, normalizedPreference, allowUnsupportedFallback = false)
            ?: selectStreamContent(playableProgressiveStreams, normalizedPreference, allowUnsupportedFallback = true)
            ?: run {
                Timber.tag(TAG).w(
                    "No playable progressive YouTube stream found for preference=%s (videoOnly=%s dash=%s hls=%s preferVideoOnly=%s)",
                    preferredQuality,
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
        preferredQuality: String,
        allowUnsupportedFallback: Boolean,
    ): String? =
        selectBestVideoStream(streams, preferredQuality, allowUnsupportedFallback)?.let { stream ->
            logSelectedStream(stream)
            stream.getContent()
        }

    private fun selectBestVideoStream(
        streams: List<VideoStream>,
        preferredQuality: String,
        allowUnsupportedFallback: Boolean = false,
    ): VideoStream? {
        val candidates =
            streams
                .filterNot { it.getItag() in REJECTED_LOW_QUALITY_ITAGS }
                .filter { streamHeight(it) >= MIN_PROGRESSIVE_HEIGHT }
        if (candidates.isEmpty()) {
            Timber.tag(TAG).w(
                "Rejecting YouTube progressive streams below %sp (available=%s)",
                MIN_PROGRESSIVE_HEIGHT,
                streams.map { stream -> "${streamHeight(stream)}p/itag=${stream.getItag()}" },
            )
            return null
        }

        val preferredHeight = qualityToHeight(preferredQuality)
        val supportPriority =
            buildList {
                add(DecoderSupport.SUPPORTED)
                add(DecoderSupport.UNKNOWN)
                if (allowUnsupportedFallback) {
                    add(DecoderSupport.UNSUPPORTED)
                }
            }

        supportPriority.forEach { support ->
            val supportCandidates =
                candidates.filter { candidate ->
                    decoderSupport(codecFamily(candidate), candidate) == support
                }
            if (supportCandidates.isEmpty()) {
                return@forEach
            }

            selectBestVideoStreamFromTier(supportCandidates, preferredHeight)?.let { return it }
        }

        return null
    }

    private fun selectBestVideoStreamFromTier(
        streams: List<VideoStream>,
        preferredHeight: Int?,
    ): VideoStream? {
        if (preferredHeight != null) {
            selectBestStreamAtResolution(streams, preferredHeight)?.let { return it }
        }

        RESOLUTION_PRIORITY.forEach { resolution ->
            selectBestStreamAtResolution(streams, resolution)?.let { return it }
        }

        Timber.tag(TAG).w("No preferred YouTube stream quality found, using best available fallback")
        return streams.maxWithOrNull(
            compareByDescending<VideoStream> { streamHeight(it) }
                .thenBy { codecPenalty(it) }
                .thenBy { codecPriorityIndex(it) }
                .thenByDescending { it.getBitrate() },
        )
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

    private fun selectBestStreamAtResolution(
        streams: List<VideoStream>,
        resolution: Int,
    ): VideoStream? {
        val atResolution = streams.filter { streamHeight(it) == resolution }
        if (atResolution.isEmpty()) {
            return null
        }

        CODEC_PRIORITY.forEach { codec ->
            atResolution
                .filter { stream ->
                    stream.getCodec().orEmpty().lowercase(Locale.US).contains(codec)
                }.minWithOrNull(
                    compareBy<VideoStream> { codecPenalty(it) }
                        .thenByDescending { it.getBitrate() },
                )?.let { return it }
        }

        return atResolution.minWithOrNull(
            compareBy<VideoStream> { codecPenalty(it) }
                .thenByDescending { it.getBitrate() },
        )
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

    private fun codecPriorityIndex(stream: VideoStream): Int {
        val codec = stream.getCodec().orEmpty().lowercase(Locale.US)
        return CODEC_PRIORITY.indexOfFirst(codec::contains).takeIf { it >= 0 } ?: CODEC_PRIORITY.size
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

    private fun logSelectedStream(stream: VideoStream) {
        Timber.tag(TAG).i("Selected YouTube progressive stream: %s", describeStream(stream))
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
        if (height < 2160) {
            return false
        }

        val isAmlogicDevice =
            DEVICE_FINGERPRINT.contains("amlogic") ||
                DEVICE_FINGERPRINT.contains("t5d")

        val usesProblematicCodec =
            codecFamily == CodecFamily.VP9 || codecFamily == CodecFamily.AV1

        if (isAmlogicDevice && usesProblematicCodec) {
            Timber.tag(TAG).w(
                "Treating %sp %s as unsupported on this device due to Amlogic 4K decoder compatibility",
                height,
                stream.getCodec(),
            )
            return true
        }

        return false
    }

    private fun isRecentEnough(item: StreamInfoItem): Boolean {
        val uploadInstant = item.getUploadDate()?.getInstant() ?: return true
        return uploadInstant.isAfter(ZonedDateTime.now().minusMonths(6).toInstant())
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

    private const val MIN_SEARCH_DURATION_SECONDS = 240
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
    private const val MIN_PROGRESSIVE_HEIGHT = 1080
    private val RESOLUTION_PRIORITY = listOf(2160, 1440, 1080)
    private val CODEC_PRIORITY = listOf("av01", "vp9", "vp09", "avc1", "avc")
    private val REJECTED_LOW_QUALITY_ITAGS = setOf(18, 133, 134, 135, 160)
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
