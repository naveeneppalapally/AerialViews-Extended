package com.neilturner.aerialviews.providers.youtube

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import timber.log.Timber
import kotlin.random.Random

class YouTubeSourceRepository(
    private val cacheDao: YouTubeCacheDao,
    private val sharedPreferences: SharedPreferences,
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundWarmInFlight = AtomicBoolean(false)
    private val lastBackgroundWarmAt = AtomicLong(0L)
    private val preResolvedLock = Any()
    private var badCountThisSession = 0

    @Volatile
    private var preResolvedEntry: YouTubeCacheEntity? = null

    @Volatile
    private var preResolvingJob: Job? = null

    suspend fun getNextVideoUrl(): String =
        withContext(Dispatchers.IO) {
            consumeAnyPreResolvedEntry()?.let { cachedEntry ->
                if (!cachedEntry.isBad && isUsableStreamUrl(cachedEntry.streamUrl)) {
                    recordPlayback(cachedEntry)
                    maybeWarmSearchCacheNearPlaylistEnd()
                    preResolveNext(repositoryScope)
                    return@withContext cachedEntry.streamUrl
                }
            }

            val entries = ensureSearchCache()
            prunePlayHistory(entries)
            val attemptedIds = mutableSetOf<String>()

            repeat(MAX_PLAYBACK_RESOLVE_ATTEMPTS) {
                val selectedEntry =
                    selectEntryForPlayback(
                        entries.filterNot { entry ->
                            entry.isBad || entry.videoId in attemptedIds
                        },
                    ) ?: return@repeat
                attemptedIds += selectedEntry.videoId

                resolveEntryStreamUrlOrNull(selectedEntry)?.let { resolvedUrl ->
                    preResolveNext(repositoryScope)
                    return@withContext resolvedUrl
                }
            }

            throw YouTubeSourceException("No videos available")
        }

    suspend fun getCachedVideos(): List<YouTubeCacheEntity> =
        withContext(Dispatchers.IO) {
            val searchCache = ensureSearchCache()
            prunePlayHistory(searchCache)
            val cachedEntries = buildPlaylistEntries(searchCache)
            updateCachedCount(cachedEntries.size)
            cachedEntries
        }

    suspend fun getLocalCachedVideos(): List<YouTubeCacheEntity> =
        withContext(Dispatchers.IO) {
            val cachedEntries = cacheDao.getAll().filterNot { it.isBad }
            if (cachedEntries.isEmpty()) {
                updateCachedCount(0)
                return@withContext emptyList()
            }

            prunePlayHistory(cachedEntries)
            buildPlaylistEntries(cachedEntries).also { entries ->
                updateCachedCount(entries.size)
            }
        }

    suspend fun getCacheSize(): Int =
        withContext(Dispatchers.IO) {
            cacheDao.getAll().count { !it.isBad }
        }

    suspend fun markAsPlayed(videoId: String) =
        withContext(Dispatchers.IO) {
            cacheDao.getAll().firstOrNull { it.videoId == videoId }?.let(::recordPlayback)
        }

    fun playbackUrl(entry: YouTubeCacheEntity): String =
        if (hasFreshStreamUrl(entry)) {
            entry.streamUrl
        } else {
            entry.videoPageUrl
        }

    fun preWarmInBackground() {
        scheduleBackgroundWarmCache(forceSearchRefresh = true)
    }

    fun preResolveNext(scope: CoroutineScope) {
        preResolvingJob?.cancel()
        preResolvingJob =
            scope.launch(Dispatchers.IO) {
                if (cacheDao.getAll().count { !it.isBad } < COLD_CACHE_SKIP_THRESHOLD) {
                    clearPreResolvedEntry()
                    return@launch
                }

                try {
                    val nextEntry = selectNextCandidate() ?: run {
                        clearPreResolvedEntry()
                        return@launch
                    }
                    val resolvedAt = System.currentTimeMillis()
                    val resolvedUrl = resolveEntryStreamUrl(nextEntry, recordPlayback = false)
                    cachePreResolvedEntry(
                        buildResolvedEntry(
                            entry = nextEntry,
                            resolvedUrl = resolvedUrl,
                            resolvedAt = resolvedAt,
                        ),
                    )
                    Timber.tag(TAG).d("Pre-resolved YouTube video: %s", nextEntry.title)
                } catch (exception: Exception) {
                    clearPreResolvedEntry()
                    Timber.tag(TAG).w(exception, "Failed to pre-resolve next YouTube video")
                }
            }
    }

    fun preResolveVideo(
        videoPageUrl: String,
        scope: CoroutineScope,
    ) {
        preResolvingJob?.cancel()
        preResolvingJob =
            scope.launch(Dispatchers.IO) {
                try {
                    val entry =
                        cacheDao.getByVideoPageUrl(videoPageUrl)
                            ?.takeIf { !it.isBad }
                            ?: buildDirectCacheEntry(
                                videoPageUrl = videoPageUrl,
                                cachedAt = System.currentTimeMillis(),
                                preferredQuality = preferredQuality(),
                            )
                            ?: return@launch
                    val resolvedAt = System.currentTimeMillis()
                    val resolvedUrl = resolveEntryStreamUrl(entry, recordPlayback = false)
                    cachePreResolvedEntry(
                        buildResolvedEntry(
                            entry = entry,
                            resolvedUrl = resolvedUrl,
                            resolvedAt = resolvedAt,
                        ),
                    )
                    Timber.tag(TAG).d("Pre-resolved requested YouTube video: %s", entry.title)
                } catch (exception: Exception) {
                    clearPreResolvedEntry()
                    Timber.tag(TAG).w(exception, "Failed to pre-resolve requested YouTube video")
                }
            }
    }

    suspend fun preloadVideoUrl(videoPageUrl: String): String? =
        withContext(Dispatchers.IO) {
            peekPreResolvedEntry(videoPageUrl)?.streamUrl?.let { return@withContext it }

            cacheDao.getByVideoPageUrl(videoPageUrl)?.takeIf { !it.isBad }?.let { cachedEntry ->
                return@withContext runCatching {
                    resolveEntryStreamUrl(cachedEntry, recordPlayback = false)
                }.getOrNull()
            }

            runCatching {
                buildDirectCacheEntry(
                    videoPageUrl = videoPageUrl,
                    cachedAt = System.currentTimeMillis(),
                    preferredQuality = preferredQuality(),
                )
            }.getOrNull()?.also { directEntry ->
                cacheDao.insertAll(listOf(directEntry))
                updateCachedCount(cacheDao.getAll().count { !it.isBad })
            }?.streamUrl
        }

    suspend fun resolveVideoUrl(videoPageUrl: String): String =
        withContext(Dispatchers.IO) {
            consumePreResolvedEntry(videoPageUrl)?.let { cachedEntry ->
                if (!cachedEntry.isBad && isUsableStreamUrl(cachedEntry.streamUrl)) {
                    recordPlayback(cachedEntry)
                    maybeWarmSearchCacheNearPlaylistEnd()
                    preResolveNext(repositoryScope)
                    return@withContext cachedEntry.streamUrl
                }
            }

            cacheDao.getByVideoPageUrl(videoPageUrl)?.takeIf { !it.isBad }?.let { cachedEntry ->
                resolveEntryStreamUrlOrNull(cachedEntry)?.let { resolvedUrl ->
                    return@withContext resolvedUrl.also {
                        preResolveNext(repositoryScope)
                    }
                }
            }

            val directEntry =
                buildDirectCacheEntry(
                    videoPageUrl = videoPageUrl,
                    cachedAt = System.currentTimeMillis(),
                    preferredQuality = preferredQuality(),
                ) ?: throw YouTubeSourceException("No videos available")

            cacheDao.insertAll(listOf(directEntry))
            updateCachedCount(cacheDao.getAll().count { !it.isBad })
            recordPlayback(directEntry)
            maybeWarmSearchCacheNearPlaylistEnd()
            preResolveNext(repositoryScope)
            directEntry.streamUrl
        }

    suspend fun warmCache(forceSearchRefresh: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            val cachedEntries = cacheDao.getAll().filterNot { it.isBad }

            val refreshedEntries =
                when {
                    cachedEntries.isEmpty() -> loadFreshSearchResults(replaceExistingCache = true)
                    forceSearchRefresh ||
                        isSearchCacheExpired() ||
                        isCacheVersionStale() ||
                        isCacheSignatureStale() ||
                        isCacheUndersized(cachedEntries) -> {
                        runCatching {
                            loadFreshSearchResults(
                                replaceExistingCache = forceSearchRefresh || isCacheSignatureStale(),
                            )
                        }.getOrElse { exception ->
                            Timber.tag(TAG).w(exception, "Using cached YouTube entries after warm refresh failure")
                            updateCachedCount(cachedEntries.size)
                            cachedEntries
                        }
                    }

                    else -> refreshExpiringStreamUrls(cachedEntries)
                }

            updateCachedCount(refreshedEntries.size)
            refreshedEntries.size
        }

    private suspend fun ensureSearchCache(): List<YouTubeCacheEntity> {
        val cachedEntries = cacheDao.getAll().filterNot { it.isBad }
        if (cachedEntries.isEmpty()) {
            return try {
                loadFreshSearchResults(replaceExistingCache = true)
            } catch (exception: Exception) {
                throw when (exception) {
                    is YouTubeSourceException -> exception
                    else -> YouTubeSourceException("No videos available", exception)
                }
            }
        }

        if (isCacheVersionStale() || isCacheSignatureStale()) {
            return runCatching {
                loadFreshSearchResults(replaceExistingCache = true)
            }
                .getOrElse { exception ->
                    Timber.tag(TAG).w(exception, "Using cached YouTube entries after synchronous cache refresh failure")
                    updateCachedCount(cachedEntries.size)
                    cachedEntries
                }
        }

        if (shouldRunBackgroundSearchWarm(cachedEntries)) {
            scheduleBackgroundWarmCache(forceSearchRefresh = true)
        } else if (hasExpiringStreams(cachedEntries)) {
            scheduleBackgroundWarmCache(forceSearchRefresh = false)
        }

        updateCachedCount(cachedEntries.size)
        return cachedEntries
    }

    suspend fun refreshSearchResults(
        replaceExistingCache: Boolean = true,
    ): List<YouTubeCacheEntity> =
        withContext(Dispatchers.IO) {
            loadFreshSearchResults(replaceExistingCache)
        }

    suspend fun forceRefresh(): Int =
        withContext(Dispatchers.IO) {
            refreshSearchResults(replaceExistingCache = true).size
        }

    private suspend fun loadFreshSearchResults(
        replaceExistingCache: Boolean = false,
    ): List<YouTubeCacheEntity> =
        runCatching {
            val refreshPlan = buildRefreshPlan()
            val searchResults = searchRefreshCandidates(refreshPlan)
            val extractedEntries = extractRefreshEntries(refreshPlan, searchResults)
            val entries = mergeRefreshedEntries(refreshPlan, extractedEntries, replaceExistingCache)
            persistFreshEntries(refreshPlan, entries)
            entries
        }.getOrElse { exception ->
            throw when (exception) {
                is YouTubeSourceException -> exception
                else -> YouTubeSourceException("Failed to refresh YouTube videos", exception)
            }
        }

    private fun buildRefreshPlan(): RefreshPlan {
        val cachedAt = System.currentTimeMillis()
        val categoryPreferences = categoryPreferences()
        return RefreshPlan(
            query = searchQuery(),
            queryPool =
                QueryFormulaEngine.generateQueryPool(
                    count = QUERY_POOL_SIZE,
                    entropySeed = cachedAt,
                    prefs = categoryPreferences,
                ),
            minimumDurationSeconds = minimumDurationSeconds(),
            preferredQuality = preferredQuality(),
            cachedAt = cachedAt,
            entropySeed = System.nanoTime() xor cachedAt,
            existingEntries = cacheDao.getAll().filterNot { it.isBad },
            recentRefreshIds = recentRefreshIds().toSet(),
        )
    }

    private suspend fun searchRefreshCandidates(refreshPlan: RefreshPlan): List<SearchCandidate> {
        val mainSearchResults =
            searchCandidateVideos(
                queries = refreshPlan.queryPool,
                minDurationSeconds = refreshPlan.minimumDurationSeconds,
            )
        val expandedResults = maybeExpandWithLongTail(mainSearchResults, refreshPlan.minimumDurationSeconds)
        return ensureHealthyCandidatePool(refreshPlan.query, expandedResults)
    }

    private suspend fun extractRefreshEntries(
        refreshPlan: RefreshPlan,
        searchResults: List<SearchCandidate>,
    ): List<YouTubeCacheEntity> {
        val filteredCandidates =
            filterCategoryMismatchedCandidates(
                filterRecentlyPlayedCandidates(searchResults),
            )
        val rankedCandidates =
            rankCandidatesWithStyleBalance(filteredCandidates)
                .let(::deduplicateCandidatesByTitle)
                .let { applyCandidateDiversityCaps(it, EXTRACTION_TARGET_SIZE) }

        return extractEntries(
            items = rankedCandidates,
            cachedAt = refreshPlan.cachedAt,
            preferredQuality = refreshPlan.preferredQuality,
            limit = EXTRACTION_TARGET_SIZE,
            publishMinimumCache = refreshPlan.existingEntries.size < COLD_CACHE_SKIP_THRESHOLD,
        )
    }

    private fun mergeRefreshedEntries(
        refreshPlan: RefreshPlan,
        extractedEntries: List<YouTubeCacheEntity>,
        replaceExistingCache: Boolean,
    ): List<YouTubeCacheEntity> {
        val entries =
            if (replaceExistingCache) {
                applyEntryDiversityCaps(extractedEntries, TARGET_CACHE_SIZE)
            } else {
                replenishEntriesFromExistingCache(
                    extractedEntries = extractedEntries,
                    existingEntries = refreshPlan.existingEntries,
                    entropySeed = refreshPlan.entropySeed,
                    recentRefreshIds = refreshPlan.recentRefreshIds,
                ).let { applyEntryDiversityCaps(it, TARGET_CACHE_SIZE) }
            }

        if (entries.isEmpty()) {
            throw YouTubeSourceException("No videos available")
        }

        val existingPlaybackHistory =
            refreshPlan.existingEntries.associate { existingEntry ->
                existingEntry.videoId to existingEntry.lastPlayedAt
            }

        return entries.map { entry ->
            existingPlaybackHistory[entry.videoId]
                ?.takeIf { playedAt -> playedAt > 0L }
                ?.let { playedAt -> entry.copy(lastPlayedAt = playedAt) }
                ?: entry
        }
    }

    private fun persistFreshEntries(
        refreshPlan: RefreshPlan,
        entries: List<YouTubeCacheEntity>,
    ) {
        cacheDao.clearAndInsert(entries)
        badCountThisSession = 0
        recordRefreshHistory(entries)
        markSearchCacheFresh(entries.size)
        Timber.tag(TAG).i(
            "Cached %s YouTube videos for query \"%s\" across %s searches",
            entries.size,
            refreshPlan.query,
            refreshPlan.queryPool.size,
        )
    }

    private suspend fun searchCandidateVideos(
        queries: List<String>,
        minDurationSeconds: Int,
    ): List<SearchCandidate> {
        val variantBuckets = linkedMapOf<String, ArrayDeque<SearchCandidate>>()

        for (variantChunk in queries.chunked(QUERY_SEARCH_BATCH_SIZE)) {
            searchVariantChunk(variantChunk, minDurationSeconds).forEach { (variant, results) ->
                addVariantResults(variantBuckets, variant, results)
            }
        }

        return interleaveVariantResults(variantBuckets).take(TARGET_CANDIDATE_POOL_SIZE)
    }

    private suspend fun searchVariantChunk(
        variants: List<String>,
        minDurationSeconds: Int,
    ): List<Pair<String, List<StreamInfoItem>>> =
        supervisorScope {
            variants.map { variant ->
                async {
                    val category = QueryFormulaEngine.categoryForQuery(variant)
                    val results =
                        withTimeoutOrNull(SEARCH_CALL_TIMEOUT_MS) {
                            runCatching {
                                NewPipeHelper.searchVideos(
                                    query = variant,
                                    minDurationSeconds = minDurationSeconds,
                                    category = category,
                                )
                            }.getOrElse { exception ->
                                Timber.tag(TAG).w(exception, "YouTube search failed for variant \"%s\"", variant)
                                emptyList()
                            }
                        }

                    variant to (results ?: emptyList())
                }
            }.awaitAll()
        }

    private fun addVariantResults(
        variantBuckets: LinkedHashMap<String, ArrayDeque<SearchCandidate>>,
        variant: String,
        results: List<StreamInfoItem>,
    ) {
        if (results.isEmpty()) {
            Timber.tag(TAG).w("YouTube search returned no usable results for variant \"%s\"", variant)
            return
        }

        val shuffledCandidates =
            results
                .shuffled()
                .map { item ->
                    SearchCandidate(
                        item = item,
                        searchQuery = variant,
                        category = QueryFormulaEngine.categoryForQuery(variant),
                    )
                }
        variantBuckets[variant] = ArrayDeque(shuffledCandidates)
    }

    private suspend fun maybeExpandWithLongTail(
        mainSearchResults: List<SearchCandidate>,
        minDurationSeconds: Int,
    ): List<SearchCandidate> {
        val uniqueMainResults = uniqueCandidateCount(mainSearchResults)
        if (uniqueMainResults >= MIN_MAIN_SEARCH_UNIQUE_VIDEOS) {
            return mainSearchResults
        }

        val longTailQueries =
            QueryFormulaEngine.generateFallbackQueryPool(
                baseQuery = "",
                count = LONG_TAIL_QUERY_COUNT,
                entropySeed = System.nanoTime() xor uniqueMainResults.toLong(),
                prefs = categoryPreferences(),
            )
        val longTailResults = searchCandidateVideos(longTailQueries, minDurationSeconds)
        val mergedResults = mergeCandidatePools(mainSearchResults, longTailResults)
        Timber.tag(TAG).i(
            "Expanded YouTube candidate pool with %s category fallback queries (%s -> %s unique candidates)",
            longTailQueries.size,
            uniqueMainResults,
            uniqueCandidateCount(mergedResults),
        )
        return mergedResults
    }

    private fun filterRecentlyPlayedCandidates(candidates: List<SearchCandidate>): List<SearchCandidate> {
        val recentPlayedIds = playHistory().toSet()
        if (recentPlayedIds.isEmpty() || candidates.size < MIN_HEALTHY_CANDIDATE_POOL_SIZE) {
            return candidates
        }

        return candidates.filter { candidate ->
            val candidateId = extractVideoId(candidate.item.getUrl())
            candidateId == null || candidateId !in recentPlayedIds
        }
    }

    private fun filterCategoryMismatchedCandidates(candidates: List<SearchCandidate>): List<SearchCandidate> {
        val categoryMatched =
            candidates.filter { candidate ->
                QueryFormulaEngine.matchesCandidateCategory(
                    title = candidate.item.getName(),
                    uploader = candidate.item.getUploaderName().orEmpty(),
                    category = candidate.category,
                )
            }

        return categoryMatched.ifEmpty { candidates }
    }

    private suspend fun ensureHealthyCandidatePool(
        query: String,
        candidates: List<SearchCandidate>,
    ): List<SearchCandidate> {
        if (candidates.size >= MIN_HEALTHY_CANDIDATE_POOL_SIZE) {
            return candidates
        }

        val fallbackQueries =
            QueryFormulaEngine.generateFallbackQueryPool(
                baseQuery = "",
                count = FALLBACK_QUERY_POOL_SIZE,
                entropySeed = System.nanoTime(),
                prefs = categoryPreferences(),
            )
        val fallbackCandidates = searchCandidateVideos(fallbackQueries, minimumDurationSeconds())
        if (fallbackCandidates.isEmpty()) {
            return candidates
        }

        val mergedCandidates = linkedMapOf<String, SearchCandidate>()
        (candidates + fallbackCandidates).forEach { candidate ->
            val url = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: return@forEach
            val key = extractVideoId(url) ?: url
            mergedCandidates.putIfAbsent(key, candidate)
        }

        Timber.tag(TAG).i(
            "Expanded YouTube candidate pool from %s to %s using %s fallback queries",
            candidates.size,
            mergedCandidates.size,
            fallbackQueries.size,
        )

        return mergedCandidates.values.take(TARGET_CANDIDATE_POOL_SIZE)
    }

    private fun mergeCandidatePools(
        primary: List<SearchCandidate>,
        secondary: List<SearchCandidate>,
    ): List<SearchCandidate> {
        val merged = linkedMapOf<String, SearchCandidate>()
        (primary + secondary).forEach { candidate ->
            val candidateUrl = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: return@forEach
            val candidateKey = extractVideoId(candidateUrl) ?: candidateUrl
            merged.putIfAbsent(candidateKey, candidate)
        }
        return merged.values.take(TARGET_CANDIDATE_POOL_SIZE)
    }

    private fun uniqueCandidateCount(candidates: List<SearchCandidate>): Int =
        candidates
            .asSequence()
            .map { candidate ->
                val candidateUrl = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: return@map null
                extractVideoId(candidateUrl) ?: candidateUrl
            }.filterNotNull()
            .distinct()
            .count()

    private fun replenishEntriesFromExistingCache(
        extractedEntries: List<YouTubeCacheEntity>,
        existingEntries: List<YouTubeCacheEntity>,
        entropySeed: Long,
        recentRefreshIds: Set<String>,
    ): List<YouTubeCacheEntity> {
        if (existingEntries.isEmpty()) {
            return prioritizeNovelEntries(extractedEntries, recentRefreshIds, entropySeed).take(TARGET_CACHE_SIZE)
        }

        val mergedEntries = linkedMapOf<String, YouTubeCacheEntity>()
        prioritizeNovelEntries(extractedEntries, recentRefreshIds, entropySeed).forEach { entry ->
            mergedEntries.putIfAbsent(entry.videoId, entry)
        }

        appendReusableEntries(
            mergedEntries = mergedEntries,
            existingEntries = existingEntries,
            entropySeed = entropySeed,
            recentRefreshIds = recentRefreshIds,
            reuseLimit = reuseLimitFor(extractedEntries.size),
        )

        val mergedList = mergedEntries.values.take(TARGET_CACHE_SIZE)
        if (mergedList.size > extractedEntries.size) {
            Timber.tag(TAG).i(
                "Reused %s prior YouTube entries to prevent a small repetitive cache (new=%s, final=%s)",
                mergedList.size - extractedEntries.size,
                extractedEntries.size,
                mergedList.size,
            )
        }
        return mergedList
    }

    private fun reuseLimitFor(extractedEntryCount: Int): Int =
        when {
            extractedEntryCount >= MIN_HEALTHY_CACHE_SIZE -> TARGET_CACHE_SIZE
            extractedEntryCount == 0 -> TARGET_CACHE_SIZE
            else -> MIN_HEALTHY_CACHE_SIZE
        }

    private fun appendReusableEntries(
        mergedEntries: LinkedHashMap<String, YouTubeCacheEntity>,
        existingEntries: List<YouTubeCacheEntity>,
        entropySeed: Long,
        recentRefreshIds: Set<String>,
        reuseLimit: Int,
    ) {
        prioritizeNovelEntries(existingEntries, recentRefreshIds, entropySeed)
            .forEach { existingEntry ->
                if (mergedEntries.size >= reuseLimit) {
                    return
                }
                mergedEntries.putIfAbsent(existingEntry.videoId, existingEntry)
            }
    }

    private fun prioritizeNovelEntries(
        entries: List<YouTubeCacheEntity>,
        recentRefreshIds: Set<String>,
        entropySeed: Long,
    ): List<YouTubeCacheEntity> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val random = Random(entropySeed)
        val unseen = entries.filterNot { it.videoId in recentRefreshIds }.shuffled(random)
        val repeated = entries.filter { it.videoId in recentRefreshIds }.shuffled(random)
        return unseen + repeated
    }

    private fun interleaveVariantResults(variantBuckets: LinkedHashMap<String, ArrayDeque<SearchCandidate>>): List<SearchCandidate> {
        val selectedItems = mutableListOf<SearchCandidate>()
        val seenVideoKeys = linkedSetOf<String>()

        while (selectedItems.size < TARGET_CANDIDATE_POOL_SIZE && variantBuckets.isNotEmpty()) {
            val exhaustedVariants = mutableListOf<String>()

            variantBuckets.forEach { (variant, bucket) ->
                var nextItem: SearchCandidate? = null
                while (bucket.isNotEmpty() && nextItem == null) {
                    val candidate = bucket.removeFirst()
                    val candidateUrl = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: continue
                    val candidateKey = extractVideoId(candidateUrl) ?: candidateUrl
                    if (seenVideoKeys.add(candidateKey)) {
                        nextItem = candidate
                    }
                }

                if (nextItem != null) {
                    selectedItems += nextItem
                }

                if (bucket.isEmpty()) {
                    exhaustedVariants += variant
                }
            }

            exhaustedVariants.forEach(variantBuckets::remove)
        }

        return selectedItems
    }

    private suspend fun extractEntries(
        items: List<SearchCandidate>,
        cachedAt: Long,
        preferredQuality: String,
        limit: Int,
        publishMinimumCache: Boolean,
    ): List<YouTubeCacheEntity> =
        supervisorScope {
            val entries = mutableListOf<YouTubeCacheEntity>()
            var minimumCachePublished = false

            for (chunk in items.chunked(EXTRACTION_BATCH_SIZE)) {
                val extractedChunk =
                    chunk
                        .map { candidate ->
                            async {
                                withTimeoutOrNull(EXTRACTION_CALL_TIMEOUT_MS) {
                                    buildCacheEntry(
                                        candidate = candidate,
                                        cachedAt = cachedAt,
                                        preferredQuality = preferredQuality,
                                    )
                                } ?: run {
                                    Timber.tag(TAG).w("Timed out extracting YouTube stream for %s", candidate.item.getUrl())
                                    null
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()

                entries += extractedChunk
                if (publishMinimumCache && !minimumCachePublished && entries.size >= MINIMUM_VIABLE_CACHE_SIZE) {
                    cacheDao.clearAndInsert(entries.toList())
                    updateCachedCount(entries.size)
                    minimumCachePublished = true
                    Timber.tag(TAG).i("Minimum viable YouTube cache ready (%s videos)", entries.size)
                }

                if (entries.size >= limit) {
                    break
                }
            }

            entries.take(limit)
        }

    private suspend fun refreshExpiringStreamUrls(entries: List<YouTubeCacheEntity>): List<YouTubeCacheEntity> {
        val entriesToRefresh =
            entries
                .filter { entry -> entry.streamUrl.isBlank() || isStreamUrlExpiringSoon(entry) }
                .take(MAX_STREAM_URL_REFRESHES_PER_WARM)

        if (entriesToRefresh.isEmpty()) {
            return entries
        }

        supervisorScope {
            entriesToRefresh
                .chunked(EXTRACTION_BATCH_SIZE)
                .forEach { chunk -> refreshStreamChunk(chunk) }
        }

        return cacheDao.getAll().filterNot { it.isBad }
    }

    private suspend fun refreshStreamChunk(chunk: List<YouTubeCacheEntity>) =
        supervisorScope {
            chunk
                .map { entry ->
                    async {
                        val refreshed =
                            withTimeoutOrNull(EXTRACTION_CALL_TIMEOUT_MS) {
                                runCatching {
                                    refreshStreamUrl(entry)
                                }.onFailure { exception ->
                                    Timber.tag(TAG).w(exception, "Failed to warm YouTube stream URL for %s", entry.videoId)
                                }
                            }

                        if (refreshed == null) {
                            Timber.tag(TAG).w("Timed out warming YouTube stream URL for %s", entry.videoId)
                        }
                    }
                }.awaitAll()
        }

    private suspend fun refreshStreamUrl(entry: YouTubeCacheEntity) {
        val now = System.currentTimeMillis()
        val updatedUrl =
            NewPipeHelper.extractStreamUrl(
                entry.videoPageUrl,
                preferredQuality(),
                preferVideoOnly = shouldPreferVideoOnly(),
            )
        cacheDao.updateStreamUrl(entry.videoId, updatedUrl, now + STREAM_URL_TTL_MS)
    }

    private fun shouldRunBackgroundSearchWarm(cachedEntries: List<YouTubeCacheEntity>): Boolean =
        isSearchCacheExpired() ||
            isCacheVersionStale() ||
            isCacheSignatureStale() ||
            isCacheUndersized(cachedEntries)

    private fun hasExpiringStreams(cachedEntries: List<YouTubeCacheEntity>): Boolean =
        cachedEntries.any(::isStreamUrlExpiringSoon)

    private fun hasFreshStreamUrl(entry: YouTubeCacheEntity): Boolean =
        entry.streamUrl.isNotBlank() && !isStreamUrlExpiringSoon(entry)

    private fun isStreamUrlExpiringSoon(entry: YouTubeCacheEntity): Boolean =
        entry.streamUrlExpiresAt < System.currentTimeMillis() + STREAM_REEXTRACT_BUFFER_MS

    private fun scheduleBackgroundWarmCache(forceSearchRefresh: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastBackgroundWarmAt.get() < BACKGROUND_REFRESH_COOLDOWN_MS) {
            return
        }

        if (!backgroundWarmInFlight.compareAndSet(false, true)) {
            return
        }

        lastBackgroundWarmAt.set(now)
        repositoryScope.launch {
            try {
                warmCache(forceSearchRefresh)
            } catch (exception: Exception) {
                Timber.tag(TAG).w(exception, "Background YouTube warm refresh failed")
            } finally {
                backgroundWarmInFlight.set(false)
            }
        }
    }

    private fun selectNextCandidate(): YouTubeCacheEntity? {
        val cachedEntries = cacheDao.getAll().filterNot { it.isBad }
        if (cachedEntries.isEmpty()) {
            return null
        }

        prunePlayHistory(cachedEntries)
        return selectEntryForPlayback(cachedEntries)
    }

    private fun buildPlaylistEntries(entries: List<YouTubeCacheEntity>): List<YouTubeCacheEntity> {
        val goodEntries = entries.filterNot { it.isBad }
        if (goodEntries.isEmpty()) {
            return emptyList()
        }

        if (!shouldShuffle() && !isFirstLaunchActive()) {
            return goodEntries
        }

        val playbackOrder = mutableListOf<YouTubeCacheEntity>()
        val remainingEntries = goodEntries.toMutableList()
        val simulation = createPlaylistSimulation()

        while (remainingEntries.isNotEmpty()) {
            val nextEntry = selectSimulatedEntry(remainingEntries, simulation)

            playbackOrder += nextEntry
            remainingEntries.removeAll { it.videoId == nextEntry.videoId }
            simulation.record(nextEntry, detectTheme(nextEntry.title))
        }

        return playbackOrder
    }

    private fun selectSimulatedEntry(
        entries: List<YouTubeCacheEntity>,
        simulation: PlaylistSimulation,
    ): YouTubeCacheEntity =
        selectEntryForPlayback(
            entries = entries,
            playbackHistory = simulation.history.toList(),
            recentThemes = simulation.themeHistory.toList(),
            lastChannel = simulation.lastChannel,
            firstLaunchActive = simulation.firstLaunchActive,
            firstLaunchSequenceIndex = simulation.firstLaunchIndex,
            random = simulation.random,
        ) ?: entries.random(simulation.random)

    private fun selectEntryForPlayback(entries: List<YouTubeCacheEntity>): YouTubeCacheEntity? {
        return selectEntryForPlayback(
            entries = entries,
            playbackHistory = playHistory().toList(),
            recentThemes = themeHistory().toList(),
            lastChannel = lastPlayedChannel(),
            firstLaunchActive = isFirstLaunchActive(),
            firstLaunchSequenceIndex = firstLaunchIndex(),
            random = Random(System.nanoTime()),
        )
    }

    private fun selectEntryForPlayback(
        entries: List<YouTubeCacheEntity>,
        playbackHistory: List<String>,
        recentThemes: List<String>,
        lastChannel: String,
        firstLaunchActive: Boolean,
        firstLaunchSequenceIndex: Int,
        random: Random,
    ): YouTubeCacheEntity? {
        val goodEntries = entries.filterNot { it.isBad }
        if (goodEntries.isEmpty()) {
            return null
        }

        val repeatWindowCandidates = applyRepeatWindow(goodEntries)
        val baseEntries = repeatWindowCandidates.ifEmpty { goodEntries }

        val exclusions = PlaybackExclusions(playbackHistory, recentThemes, lastChannel)

        if (firstLaunchActive) {
            getFirstLaunchVideo(baseEntries, firstLaunchSequenceIndex, exclusions.strictVideoIds, random)?.let { return it }
        }

        val finalCandidates = resolvePlaybackCandidates(baseEntries, exclusions)

        return weightedRandomPick(finalCandidates, playbackHistory, random)
            ?: cacheDao.getUnwatchedEntry(recentPlaybackCutoff())
            ?: cacheDao.getLeastRecentlyPlayed()
    }

    private fun resolvePlaybackCandidates(
        entries: List<YouTubeCacheEntity>,
        exclusions: PlaybackExclusions,
    ): List<YouTubeCacheEntity> {
        val strictCandidates =
            applyPlaybackExclusions(
                entries = entries,
                excludedVideoIds = exclusions.strictVideoIds,
                excludedThemes = exclusions.recentThemes,
                excludedChannel = exclusions.lastChannel,
            )
        if (strictCandidates.size >= MIN_STRICT_PLAYBACK_CANDIDATES) {
            return strictCandidates
        }

        val themeRelaxedCandidates =
            applyPlaybackExclusions(
                entries = entries,
                excludedVideoIds = exclusions.strictVideoIds,
                excludedThemes = emptySet(),
                excludedChannel = exclusions.lastChannel,
            )
        if (themeRelaxedCandidates.size >= MIN_STRICT_PLAYBACK_CANDIDATES) {
            return themeRelaxedCandidates
        }

        val channelRelaxedCandidates =
            applyPlaybackExclusions(
                entries = entries,
                excludedVideoIds = exclusions.strictVideoIds,
                excludedThemes = emptySet(),
                excludedChannel = "",
            )

        return when {
            channelRelaxedCandidates.isNotEmpty() -> channelRelaxedCandidates
            exclusions.relaxedVideoIds.isNotEmpty() -> entries.filterNot { it.videoId in exclusions.relaxedVideoIds }.ifEmpty { entries }
            else -> entries
        }
    }

    private fun applyRepeatWindow(entries: List<YouTubeCacheEntity>): List<YouTubeCacheEntity> {
        val cutoff = recentPlaybackCutoff()
        val unwatchedEntries =
            entries.filter { entry ->
                entry.lastPlayedAt == 0L || entry.lastPlayedAt < cutoff
            }

        return when {
            unwatchedEntries.isNotEmpty() -> unwatchedEntries
            entries.isNotEmpty() -> entries.sortedBy { entry -> entry.lastPlayedAt }
            else -> emptyList()
        }
    }

    private fun prunePlayHistory(cachedEntries: List<YouTubeCacheEntity>) {
        if (cachedEntries.isEmpty()) {
            return
        }

        val cacheIds = cachedEntries.mapTo(linkedSetOf()) { it.videoId }
        val history = playHistory()
        val prunedHistory = ArrayDeque(history.filter { it in cacheIds })
        val targetHistorySize = (cachedEntries.size * MAX_PLAY_HISTORY_FACTOR_PER_CACHE).toInt().coerceAtLeast(MIN_PLAY_HISTORY_SIZE)

        while (prunedHistory.size > targetHistorySize) {
            prunedHistory.removeFirst()
        }

        val existingHistoryString = history.joinToString(HISTORY_SEPARATOR)
        val prunedHistoryString = prunedHistory.joinToString(HISTORY_SEPARATOR)
        if (prunedHistoryString != existingHistoryString) {
            sharedPreferences.edit {
                putString(KEY_PLAY_HISTORY, prunedHistoryString)
            }
        }
    }

    private fun isCacheUndersized(entries: List<YouTubeCacheEntity>): Boolean =
        entries.size < TARGET_CACHE_SIZE

    private fun playHistory(): ArrayDeque<String> = readHistory(KEY_PLAY_HISTORY)

    private fun recentRefreshIds(): ArrayDeque<String> = readHistory(KEY_RECENT_REFRESH_IDS)

    private fun themeHistory(): ArrayDeque<String> = readHistory(KEY_THEME_HISTORY)

    private fun recordRefreshHistory(entries: List<YouTubeCacheEntity>) {
        val history = recentRefreshIds()
        entries.forEach { entry ->
            history.remove(entry.videoId)
            history.addLast(entry.videoId)
        }

        while (history.size > MAX_RECENT_REFRESH_IDS) {
            history.removeFirst()
        }

        writeHistory(KEY_RECENT_REFRESH_IDS, history)
    }

    private fun lastPlayedChannel(): String =
        sharedPreferences.getString(KEY_LAST_CHANNEL, "")?.trim().orEmpty()

    private fun isFirstLaunchActive(): Boolean =
        sharedPreferences.getBoolean(KEY_FIRST_LAUNCH, true)

    private fun firstLaunchIndex(): Int =
        sharedPreferences.getInt(KEY_FIRST_LAUNCH_INDEX, 0)

    private fun recordPlayback(entry: YouTubeCacheEntity) {
        val playedAt = System.currentTimeMillis()
        cacheDao.markAsPlayed(entry.videoId, playedAt)

        val history = playHistory()
        history.addLast(entry.videoId)
        trimHistory(history, MAX_PLAY_HISTORY)

        val themes = themeHistory()
        val theme = detectTheme(entry.title)
        themes.addLast(theme)
        trimHistory(themes, MAX_THEME_HISTORY)

        val firstLaunchStillActive = isFirstLaunchActive()
        val nextFirstLaunchIndex =
            if (firstLaunchStillActive) {
                (firstLaunchIndex() + 1).coerceAtMost(FIRST_LAUNCH_SEQUENCE.size)
            } else {
                firstLaunchIndex()
            }

        sharedPreferences.edit {
            putString(KEY_PLAY_HISTORY, history.joinToString(HISTORY_SEPARATOR))
            putString(KEY_THEME_HISTORY, themes.joinToString(HISTORY_SEPARATOR))
            putString(KEY_LAST_CATEGORY, entry.searchQuery.orEmpty())
            putString(KEY_LAST_CHANNEL, entry.uploaderName)
            putInt(KEY_FIRST_LAUNCH_INDEX, nextFirstLaunchIndex)
            putBoolean(KEY_FIRST_LAUNCH, nextFirstLaunchIndex < FIRST_LAUNCH_SEQUENCE.size)
        }
    }

    private fun maybeWarmSearchCacheNearPlaylistEnd() {
        val cacheSize = cacheDao.getAll().count { !it.isBad }
        if (cacheSize <= SEARCH_PREWARM_REMAINING_ITEMS) {
            return
        }

        val remainingUnique = (cacheSize - playHistory().toSet().size).coerceAtLeast(0)
        if (remainingUnique <= SEARCH_PREWARM_REMAINING_ITEMS) {
            scheduleBackgroundWarmCache(forceSearchRefresh = true)
        }
    }

    private fun peekPreResolvedEntry(videoPageUrl: String): YouTubeCacheEntity? =
        synchronized(preResolvedLock) {
            preResolvedEntry?.takeIf { entry -> entry.videoPageUrl == videoPageUrl }
        }

    private fun consumePreResolvedEntry(videoPageUrl: String): YouTubeCacheEntity? =
        synchronized(preResolvedLock) {
            val entry = preResolvedEntry?.takeIf { cachedEntry -> cachedEntry.videoPageUrl == videoPageUrl }
            if (entry != null) {
                preResolvedEntry = null
            }
            entry
        }

    private fun consumeAnyPreResolvedEntry(): YouTubeCacheEntity? =
        synchronized(preResolvedLock) {
            val entry = preResolvedEntry
            preResolvedEntry = null
            entry
        }

    private fun cachePreResolvedEntry(entry: YouTubeCacheEntity) {
        synchronized(preResolvedLock) {
            preResolvedEntry = entry
        }
    }

    private fun clearPreResolvedEntry() {
        synchronized(preResolvedLock) {
            preResolvedEntry = null
        }
    }

    private fun cacheSignature(): String =
        "${QueryFormulaEngine.freshnessSeed(searchQuery())}|${minimumDurationMinutes()}|${preferredQuality()}|${streamMode()}|${categorySignature()}"

    private fun isCacheSignatureStale(): Boolean =
        sharedPreferences.getString(KEY_CACHE_SIGNATURE, "") != cacheSignature()

    private suspend fun resolveEntryStreamUrl(entry: YouTubeCacheEntity): String {
        return resolveEntryStreamUrl(entry, recordPlayback = true)
    }

    private suspend fun resolveEntryStreamUrlOrNull(entry: YouTubeCacheEntity): String? =
        runCatching { resolveEntryStreamUrl(entry) }
            .onFailure { exception ->
                markEntryAsBad(entry, exception)
            }.getOrNull()

    private suspend fun resolveEntryStreamUrl(
        entry: YouTubeCacheEntity,
        recordPlayback: Boolean,
    ): String {
        val now = System.currentTimeMillis()
        val resolvedUrl =
            if (!hasFreshStreamUrl(entry)) {
                try {
                    val updatedUrl =
                        NewPipeHelper.extractStreamUrl(
                            entry.videoPageUrl,
                            preferredQuality(),
                            preferVideoOnly = shouldPreferVideoOnly(),
                        )
                    val newExpiresAt = now + STREAM_URL_TTL_MS
                    if (!isUsableStreamUrl(updatedUrl)) {
                        markEntryAsBad(entry)
                        throw YouTubeSourceException("No videos available")
                    }
                    cacheDao.updateStreamUrl(entry.videoId, updatedUrl, newExpiresAt)
                    badCountThisSession = 0
                    updatedUrl
                } catch (exception: Exception) {
                    if (isUsableStreamUrl(entry.streamUrl)) {
                        Timber.tag(TAG).w(exception, "Falling back to cached YouTube stream URL for %s", entry.videoId)
                        scheduleBackgroundWarmCache(forceSearchRefresh = false)
                        entry.streamUrl
                    } else {
                        markEntryAsBad(entry, exception)
                        throw YouTubeSourceException("No videos available", exception)
                    }
                }
            } else {
                if (!isUsableStreamUrl(entry.streamUrl)) {
                    markEntryAsBad(entry)
                    throw YouTubeSourceException("No videos available")
                }
                entry.streamUrl
            }

        if (recordPlayback) {
            recordPlayback(entry)
            maybeWarmSearchCacheNearPlaylistEnd()
        }
        return resolvedUrl
    }

    private fun markEntryAsBad(
        entry: YouTubeCacheEntity,
        exception: Throwable? = null,
    ) {
        cacheDao.markAsBad(entry.videoId)
        badCountThisSession += 1
        if (exception != null) {
            Timber.tag(TAG).w(exception, "Marking broken YouTube cache entry as bad: %s", entry.videoId)
        } else {
            Timber.tag(TAG).w("Marking broken YouTube cache entry as bad: %s", entry.videoId)
        }
        if (badCountThisSession >= BAD_ENTRY_REFRESH_THRESHOLD) {
            Timber.tag(TAG).w("Too many broken YouTube entries, triggering background refresh")
            scheduleBackgroundWarmCache(forceSearchRefresh = true)
            badCountThisSession = 0
        }
    }

    private fun isUsableStreamUrl(url: String): Boolean =
        url.isNotBlank() && url.startsWith("http", ignoreCase = true)

    private fun buildResolvedEntry(
        entry: YouTubeCacheEntity,
        resolvedUrl: String,
        resolvedAt: Long,
    ): YouTubeCacheEntity =
        if (entry.streamUrl == resolvedUrl && entry.streamUrlExpiresAt >= resolvedAt + STREAM_REEXTRACT_BUFFER_MS) {
            entry
        } else {
            entry.copy(
                streamUrl = resolvedUrl,
                streamUrlExpiresAt = resolvedAt + STREAM_URL_TTL_MS,
            )
        }

    private suspend fun buildCacheEntry(
        candidate: SearchCandidate,
        cachedAt: Long,
        preferredQuality: String,
    ): YouTubeCacheEntity? {
        val item = candidate.item
        val title = item.getName().takeIf { it.isNotBlank() } ?: item.getUrl()

        return try {
            val videoPageUrl = item.getUrl().takeIf { it.isNotBlank() } ?: return null
            val videoId = extractVideoId(videoPageUrl) ?: return null
            val streamUrl =
                NewPipeHelper.extractStreamUrl(
                    videoPageUrl,
                    preferredQuality,
                    preferVideoOnly = shouldPreferVideoOnly(),
                )

            YouTubeCacheEntity(
                videoId = videoId,
                videoPageUrl = videoPageUrl,
                streamUrl = streamUrl,
                title = title,
                uploaderName = item.getUploaderName().orEmpty(),
                streamUrlExpiresAt = cachedAt + STREAM_URL_TTL_MS,
                searchCachedAt = cachedAt,
                searchQuery = candidate.searchQuery,
            )
        } catch (exception: Exception) {
            if (shouldSilentlySkip(exception)) {
                Timber.tag(TAG).w("Skipping unavailable YouTube result: %s", title)
            } else {
                Timber.tag(TAG).w(exception, "Skipping YouTube result: %s", title)
            }
            null
        }
    }

    private suspend fun buildDirectCacheEntry(
        videoPageUrl: String,
        cachedAt: Long,
        preferredQuality: String,
    ): YouTubeCacheEntity? {
        val videoId = extractVideoId(videoPageUrl) ?: return null
        val streamUrl =
            NewPipeHelper.extractStreamUrl(
                videoPageUrl,
                preferredQuality,
                preferVideoOnly = shouldPreferVideoOnly(),
            )
        return YouTubeCacheEntity(
            videoId = videoId,
            videoPageUrl = videoPageUrl,
            streamUrl = streamUrl,
            title = videoId,
            uploaderName = "",
            streamUrlExpiresAt = cachedAt + STREAM_URL_TTL_MS,
            searchCachedAt = cachedAt,
            searchQuery = searchQuery(),
        )
    }

    private fun shouldSilentlySkip(exception: Throwable): Boolean =
        when (exception) {
            is AgeRestrictedContentException,
            is GeographicRestrictionException,
            is ContentNotAvailableException,
            -> true

            is ExtractionException -> skipMessage(exception.message)
            is YouTubeExtractionException -> skipMessage(exception.message) || exception.cause?.let(::shouldSilentlySkip) == true
            else -> skipMessage(exception.message)
        }

    private fun skipMessage(message: String?): Boolean {
        val normalizedMessage = message?.lowercase().orEmpty()
        return normalizedMessage.contains("403") || normalizedMessage.contains("not available")
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

    private fun isSearchCacheExpired(): Boolean {
        val oldestCachedAt = cacheDao.getOldestCachedAt() ?: return true
        return System.currentTimeMillis() - oldestCachedAt >= SEARCH_CACHE_TTL_MS
    }

    private fun isCacheVersionStale(): Boolean =
        sharedPreferences.getInt(KEY_CACHE_VERSION, 0) != CURRENT_CACHE_VERSION

    private fun categoryPreferences(): QueryFormulaEngine.CategoryPreferences =
        QueryFormulaEngine.CategoryPreferences(
            categoryNature = sharedPreferences.getBoolean(KEY_CATEGORY_NATURE, DEFAULT_CATEGORY_NATURE),
            categoryAnimals = sharedPreferences.getBoolean(KEY_CATEGORY_ANIMALS, DEFAULT_CATEGORY_ANIMALS),
            categoryDrone = sharedPreferences.getBoolean(KEY_CATEGORY_DRONE, DEFAULT_CATEGORY_DRONE),
            categoryCities = sharedPreferences.getBoolean(KEY_CATEGORY_CITIES, DEFAULT_CATEGORY_CITIES),
            categorySpace = sharedPreferences.getBoolean(KEY_CATEGORY_SPACE, DEFAULT_CATEGORY_SPACE),
            categoryOcean = sharedPreferences.getBoolean(KEY_CATEGORY_OCEAN, DEFAULT_CATEGORY_OCEAN),
            categoryWeather = sharedPreferences.getBoolean(KEY_CATEGORY_WEATHER, DEFAULT_CATEGORY_WEATHER),
            categoryWinter = sharedPreferences.getBoolean(KEY_CATEGORY_WINTER, DEFAULT_CATEGORY_WINTER),
        )

    private fun categorySignature(): String =
        QueryFormulaEngine.categorySignature(categoryPreferences())

    private fun searchQuery(): String =
        sharedPreferences
            .getString(KEY_QUERY, DEFAULT_QUERY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_QUERY

    private fun preferredQuality(): String =
        sharedPreferences
            .getString(KEY_QUALITY, DEFAULT_QUALITY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_QUALITY

    private fun minimumDurationMinutes(): Int =
        sharedPreferences.getInt(KEY_MIN_DURATION, DEFAULT_MIN_DURATION_MINUTES)

    private fun minimumDurationSeconds(): Int =
        minimumDurationMinutes() * SECONDS_PER_MINUTE

    private fun shouldShuffle(): Boolean =
        sharedPreferences.getBoolean(KEY_SHUFFLE, DEFAULT_SHUFFLE)

    private fun shouldPreferVideoOnly(): Boolean =
        true

    private fun streamMode(): String = "video_only"

    private fun updateCachedCount(count: Int) {
        sharedPreferences.edit {
            putString(KEY_COUNT, count.toString())
        }
    }

    private fun markSearchCacheFresh(count: Int) {
        sharedPreferences.edit {
            putString(KEY_COUNT, count.toString())
            putInt(KEY_CACHE_VERSION, CURRENT_CACHE_VERSION)
            putString(KEY_CACHE_SIGNATURE, cacheSignature())
        }
    }

    private fun applyCandidateDiversityCaps(
        candidates: List<SearchCandidate>,
        limit: Int,
    ): List<SearchCandidate> =
        applyDiversityCaps(
            items = candidates,
            limit = limit,
            idSelector = { candidate ->
                extractVideoId(candidate.item.getUrl()) ?: candidate.item.getUrl()
            },
            channelSelector = { candidate ->
                candidate.item.getUploaderName().orEmpty()
            },
            querySelector = { candidate ->
                candidate.searchQuery
            },
            titleSelector = { candidate ->
                candidate.item.getName()
            },
        )

    private fun applyEntryDiversityCaps(
        entries: List<YouTubeCacheEntity>,
        limit: Int,
    ): List<YouTubeCacheEntity> =
        applyDiversityCaps(
            items = entries,
            limit = limit,
            idSelector = { entry -> entry.videoId },
            channelSelector = { entry -> entry.uploaderName },
            querySelector = { entry -> entry.searchQuery.orEmpty() },
            titleSelector = { entry -> entry.title },
        )

    private fun <T> applyDiversityCaps(
        items: List<T>,
        limit: Int,
        idSelector: (T) -> String,
        channelSelector: (T) -> String,
        querySelector: (T) -> String,
        titleSelector: (T) -> String,
    ): List<T> {
        if (items.isEmpty()) {
            return emptyList()
        }

        val filteredItems =
            filterItemsByChannelAndQueryCaps(
                items = items,
                idSelector = idSelector,
                channelSelector = channelSelector,
                querySelector = querySelector,
            )
        val themeBuckets = bucketItemsByTheme(filteredItems, titleSelector)
        return selectItemsWithThemeCaps(themeBuckets, limit)
    }

    private fun detectTheme(title: String): String {
        val lower = title.lowercase()
        return LOCATION_THEMES.entries
            .firstOrNull { (_, keywords) ->
                keywords.any { keyword -> lower.contains(keyword) }
            }?.key ?: "other"
    }

    private fun applyPlaybackExclusions(
        entries: List<YouTubeCacheEntity>,
        excludedVideoIds: Set<String>,
        excludedThemes: Set<String>,
        excludedChannel: String,
    ): List<YouTubeCacheEntity> =
        entries.filter { entry ->
            entry.videoId !in excludedVideoIds &&
                (excludedThemes.isEmpty() || detectTheme(entry.title) !in excludedThemes) &&
                (excludedChannel.isBlank() || !entry.uploaderName.equals(excludedChannel, ignoreCase = true))
        }

    private fun weightedRandomPick(
        entries: List<YouTubeCacheEntity>,
        playbackHistory: List<String>,
        random: Random,
    ): YouTubeCacheEntity? {
        if (entries.isEmpty()) {
            return null
        }

        val candidates =
            entries.map { entry ->
                val playCount = playbackHistory.count { it == entry.videoId }
                val weight =
                    when {
                        playCount == 0 -> UNPLAYED_WEIGHT
                        playCount == 1 -> SINGLE_PLAY_WEIGHT
                        else -> REPEAT_WEIGHT
                    }
                entry to weight
            }

        val totalWeight = candidates.sumOf { (_, weight) -> weight }.coerceAtLeast(1)
        var remainingWeight = random.nextInt(totalWeight)
        candidates.forEach { (entry, weight) ->
            remainingWeight -= weight
            if (remainingWeight < 0) {
                return entry
            }
        }

        return candidates.lastOrNull()?.first
    }

    private fun getFirstLaunchVideo(
        entries: List<YouTubeCacheEntity>,
        sequenceIndex: Int,
        excludedVideoIds: Set<String>,
        random: Random,
    ): YouTubeCacheEntity? {
        if (entries.isEmpty()) {
            return null
        }

        val sequence = FIRST_LAUNCH_SEQUENCE.drop(sequenceIndex.coerceAtLeast(0))
        sequence.forEach { targetTheme ->
            val candidates =
                entries.filter { entry ->
                    entry.videoId !in excludedVideoIds &&
                        detectTheme(entry.title) == targetTheme
                }
            if (candidates.isNotEmpty()) {
                return candidates.random(random)
            }
        }

        val unseenCandidates = entries.filterNot { it.videoId in excludedVideoIds }
        return if (unseenCandidates.isNotEmpty()) {
            unseenCandidates.random(random)
        } else {
            entries.random(random)
        }
    }

    private fun rankCandidatesWithStyleBalance(candidates: List<SearchCandidate>): List<SearchCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val scoredCandidates = scoreCandidates(candidates)
        val balancedSelection = selectBalancedCandidates(scoredCandidates)
        val rankedCandidates = if (balancedSelection.isEmpty()) scoredCandidates else balancedSelection

        return rankedCandidates
            .sortedByDescending { (_, score) -> score }
            .map { (candidate, _) -> candidate }
    }

    private fun deduplicateCandidatesByTitle(candidates: List<SearchCandidate>): List<SearchCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val dedupedByTitle = linkedMapOf<String, SearchCandidate>()
        candidates.forEach { candidate ->
            val fallbackKey = extractVideoId(candidate.item.getUrl()) ?: candidate.item.getUrl()
            val normalizedTitle = normalizeTitleFingerprint(candidate.item.getName()).ifBlank { fallbackKey }
            dedupedByTitle.putIfAbsent(normalizedTitle, candidate)
        }

        return dedupedByTitle.values.toList()
    }

    private fun normalizeTitleFingerprint(title: String): String =
        title
            .lowercase()
            .replace("\\b(4k|8k|hdr|uhd|ambient|no music|no talking|screensaver|hours?|hour|mins?|minutes?)\\b".toRegex(), " ")
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()

    private fun scoreVideo(candidate: SearchCandidate): Int {
        val item = candidate.item
        val title = item.getName().lowercase()
        val uploaderName = item.getUploaderName().orEmpty()
        val qualitySignalScore =
            QueryFormulaEngine.qualitySignals.count { signal ->
                title.contains(signal.lowercase())
            }
        val durationScore =
            when {
                item.getDuration() > LONG_FORM_DURATION_SECONDS -> LONG_FORM_BONUS + VERY_LONG_FORM_BONUS
                item.getDuration() > MEDIUM_FORM_DURATION_SECONDS -> LONG_FORM_BONUS
                else -> 0
            }
        val categoryScore =
            QueryFormulaEngine.categoryMatchScore(
                title = item.getName(),
                uploader = uploaderName,
                category = candidate.category,
            ) +
                when (queryCategory(candidate)) {
                    QueryFormulaEngine.QueryCategory.AERIAL -> AERIAL_CATEGORY_BONUS
                    QueryFormulaEngine.QueryCategory.NATURE -> 0
                }
        val penaltyScore =
            (if (isVlogLikeTitle(title)) VLOG_TITLE_PENALTY else 0) +
                (if (isDigitHeavyChannelName(uploaderName)) DIGIT_HEAVY_CHANNEL_PENALTY else 0)

        return qualitySignalScore + durationScore + categoryScore - penaltyScore
    }

    private fun queryCategory(candidate: SearchCandidate): QueryFormulaEngine.QueryCategory =
        candidate.category?.queryCategory ?: QueryFormulaEngine.categoryOf(candidate.searchQuery)

    private fun isVlogLikeTitle(title: String): Boolean {
        val normalized = title.lowercase()
        return normalized.contains("vlog") ||
            normalized.contains("travel") ||
            normalized.contains("trip") ||
            normalized.contains("itinerary") ||
            normalized.contains("things to do") ||
            normalized.contains("hotel") ||
            normalized.contains("resort") ||
            normalized.contains("travel guide") ||
            normalized.contains("tour") ||
            normalized.contains("review") ||
            normalized.contains("how to")
    }

    private fun isDigitHeavyChannelName(channelName: String): Boolean {
        val digits = channelName.count(Char::isDigit)
        val letters = channelName.count(Char::isLetter)
        return digits >= 3 && digits >= letters
    }

    private fun <T> filterItemsByChannelAndQueryCaps(
        items: List<T>,
        idSelector: (T) -> String,
        channelSelector: (T) -> String,
        querySelector: (T) -> String,
    ): List<T> {
        val filteredItems = mutableListOf<T>()
        val channelCounts = mutableMapOf<String, Int>()
        val queryCounts = mutableMapOf<String, Int>()

        items.forEach { item ->
            val channelKey = channelSelector(item).trim().ifBlank { idSelector(item) }
            val queryKey = querySelector(item).trim().ifBlank { DEFAULT_CATEGORY_KEY }
            if ((channelCounts[channelKey] ?: 0) >= MAX_VIDEOS_PER_CHANNEL) {
                return@forEach
            }
            if ((queryCounts[queryKey] ?: 0) >= MAX_VIDEOS_PER_QUERY_BUCKET) {
                return@forEach
            }

            filteredItems += item
            channelCounts[channelKey] = (channelCounts[channelKey] ?: 0) + 1
            queryCounts[queryKey] = (queryCounts[queryKey] ?: 0) + 1
        }

        return filteredItems
    }

    private fun <T> bucketItemsByTheme(
        items: List<T>,
        titleSelector: (T) -> String,
    ): LinkedHashMap<String, ArrayDeque<T>> {
        val themeBuckets = linkedMapOf<String, ArrayDeque<T>>()
        items.forEach { item ->
            val theme = detectTheme(titleSelector(item))
            themeBuckets.getOrPut(theme) { ArrayDeque() }.addLast(item)
        }
        return themeBuckets
    }

    private fun <T> selectItemsWithThemeCaps(
        themeBuckets: LinkedHashMap<String, ArrayDeque<T>>,
        limit: Int,
    ): List<T> {
        val selectedItems = mutableListOf<T>()
        val perThemeSelections = mutableMapOf<String, Int>()

        while (selectedItems.size < limit) {
            var addedAny = false
            themeBuckets.forEach { (theme, bucket) ->
                if (selectedItems.size >= limit || bucket.isEmpty()) {
                    return@forEach
                }
                if ((perThemeSelections[theme] ?: 0) >= INITIAL_THEME_ROUND_ROBIN_CAP) {
                    return@forEach
                }

                selectedItems += bucket.removeFirst()
                perThemeSelections[theme] = (perThemeSelections[theme] ?: 0) + 1
                addedAny = true
            }

            if (!addedAny) {
                break
            }
        }

        if (selectedItems.size < limit) {
            themeBuckets.values.forEach { bucket ->
                while (selectedItems.size < limit && bucket.isNotEmpty()) {
                    selectedItems += bucket.removeFirst()
                }
            }
        }

        return selectedItems.take(limit)
    }

    private fun scoreCandidates(candidates: List<SearchCandidate>): List<Pair<SearchCandidate, Int>> =
        candidates
            .map { candidate -> candidate to scoreVideo(candidate) }
            .sortedByDescending { (_, score) -> score }

    private fun selectBalancedCandidates(
        candidates: List<Pair<SearchCandidate, Int>>,
    ): List<Pair<SearchCandidate, Int>> {
        val categoryBuckets =
            linkedMapOf<QueryFormulaEngine.ContentCategory?, ArrayDeque<Pair<SearchCandidate, Int>>>().apply {
                candidates.forEach { candidate ->
                    getOrPut(candidate.first.category) { ArrayDeque() }.addLast(candidate)
                }
            }
        val selected = mutableListOf<Pair<SearchCandidate, Int>>()

        while (selected.size < EXTRACTION_TARGET_SIZE && categoryBuckets.isNotEmpty()) {
            val exhausted = mutableListOf<QueryFormulaEngine.ContentCategory?>()
            categoryBuckets.forEach { (category, bucket) ->
                if (bucket.isNotEmpty()) {
                    selected += bucket.removeFirst()
                }
                if (bucket.isEmpty()) {
                    exhausted += category
                }
                if (selected.size >= EXTRACTION_TARGET_SIZE) {
                    return@forEach
                }
            }
            exhausted.forEach(categoryBuckets::remove)
        }

        return selected.take(EXTRACTION_TARGET_SIZE)
    }

    private fun readHistory(key: String): ArrayDeque<String> {
        val rawHistory = sharedPreferences.getString(key, "").orEmpty()
        val parsedHistory =
            rawHistory
                .split(HISTORY_SEPARATOR)
                .map(String::trim)
                .filter(String::isNotBlank)
        return ArrayDeque(parsedHistory)
    }

    private fun writeHistory(
        key: String,
        values: ArrayDeque<String>,
    ) {
        sharedPreferences.edit {
            putString(key, values.joinToString(HISTORY_SEPARATOR))
        }
    }

    private fun trimHistory(
        values: ArrayDeque<String>,
        maxSize: Int,
    ) {
        while (values.size > maxSize) {
            values.removeFirst()
        }
    }

    private fun recentPlaybackCutoff(): Long =
        System.currentTimeMillis() - RECENT_PLAYBACK_WINDOW_MS

    private fun createPlaylistSimulation(): PlaylistSimulation =
        PlaylistSimulation(
            history = playHistory(),
            themeHistory = themeHistory(),
            lastChannel = lastPlayedChannel(),
            firstLaunchActive = isFirstLaunchActive(),
            firstLaunchIndex = firstLaunchIndex(),
            random = Random(System.nanoTime()),
        )

    private fun PlaylistSimulation.record(
        entry: YouTubeCacheEntity,
        theme: String,
    ) {
        history.addLast(entry.videoId)
        trimHistory(history, MAX_PLAY_HISTORY)
        themeHistory.addLast(theme)
        trimHistory(themeHistory, MAX_THEME_HISTORY)
        lastChannel = entry.uploaderName
        if (firstLaunchActive) {
            firstLaunchIndex += 1
            if (firstLaunchIndex >= FIRST_LAUNCH_SEQUENCE.size) {
                firstLaunchActive = false
            }
        }
    }

    private data class RefreshPlan(
        val query: String,
        val queryPool: List<String>,
        val minimumDurationSeconds: Int,
        val preferredQuality: String,
        val cachedAt: Long,
        val entropySeed: Long,
        val existingEntries: List<YouTubeCacheEntity>,
        val recentRefreshIds: Set<String>,
    )

    private data class SearchCandidate(
        val item: StreamInfoItem,
        val searchQuery: String,
        val category: QueryFormulaEngine.ContentCategory?,
    )

    private data class PlaybackExclusions(
        val strictVideoIds: Set<String>,
        val relaxedVideoIds: Set<String>,
        val recentThemes: Set<String>,
        val lastChannel: String,
    ) {
        constructor(
            playbackHistory: List<String>,
            recentThemes: List<String>,
            lastChannel: String,
        ) : this(
            strictVideoIds = playbackHistory.takeLast(LAST_VIDEO_EXCLUSION_COUNT).toSet(),
            relaxedVideoIds = playbackHistory.takeLast(RELAXED_LAST_VIDEO_EXCLUSION_COUNT).toSet(),
            recentThemes = recentThemes.takeLast(LAST_THEME_EXCLUSION_COUNT).toSet(),
            lastChannel = lastChannel.trim(),
        )
    }

    private data class PlaylistSimulation(
        val history: ArrayDeque<String>,
        val themeHistory: ArrayDeque<String>,
        var lastChannel: String,
        var firstLaunchActive: Boolean,
        var firstLaunchIndex: Int,
        val random: Random,
    )

    companion object {
        private const val TAG = "YouTubeSource"
        const val KEY_QUERY = "yt_query"
        const val KEY_QUALITY = "yt_quality"
        const val KEY_MIN_DURATION = "yt_min_duration"
        const val KEY_MIX_WEIGHT = "yt_mix_weight"
        const val KEY_SHUFFLE = "yt_shuffle"
        const val KEY_ENABLED = "yt_enabled"
        const val KEY_COUNT = "yt_count"
        const val KEY_CACHE_VERSION = "yt_cache_version"
        const val KEY_CACHE_SIGNATURE = "yt_cache_signature"
        const val KEY_PLAY_HISTORY = "yt_play_history"
        const val KEY_LAST_CATEGORY = "yt_last_category"
        const val KEY_THEME_HISTORY = "yt_theme_history"
        const val KEY_LAST_CHANNEL = "yt_last_channel"
        const val KEY_FIRST_LAUNCH = "yt_first_launch"
        const val KEY_FIRST_LAUNCH_INDEX = "yt_first_launch_index"
        const val KEY_RECENT_REFRESH_IDS = "yt_recent_refresh_ids"
        const val KEY_CATEGORY_NATURE = "yt_category_nature"
        const val KEY_CATEGORY_ANIMALS = "yt_category_animals"
        const val KEY_CATEGORY_DRONE = "yt_category_drone"
        const val KEY_CATEGORY_CITIES = "yt_category_cities"
        const val KEY_CATEGORY_SPACE = "yt_category_space"
        const val KEY_CATEGORY_OCEAN = "yt_category_ocean"
        const val KEY_CATEGORY_WEATHER = "yt_category_weather"
        const val KEY_CATEGORY_WINTER = "yt_category_winter"
        private const val KEY_MUTE_VIDEOS = "mute_videos"

        const val DEFAULT_QUERY = "4K aerial nature ambient"
        const val DEFAULT_QUALITY = "best"
        const val DEFAULT_MIN_DURATION_MINUTES = 10
        const val DEFAULT_MIX_WEIGHT = "2"
        const val DEFAULT_SHUFFLE = true
        private const val DEFAULT_MUTE_VIDEOS = true
        private const val DEFAULT_CATEGORY_NATURE = true
        private const val DEFAULT_CATEGORY_ANIMALS = true
        private const val DEFAULT_CATEGORY_DRONE = true
        private const val DEFAULT_CATEGORY_CITIES = false
        private const val DEFAULT_CATEGORY_SPACE = true
        private const val DEFAULT_CATEGORY_OCEAN = true
        private const val DEFAULT_CATEGORY_WEATHER = false
        private const val DEFAULT_CATEGORY_WINTER = true

        private const val SECONDS_PER_MINUTE = 60
        private const val TARGET_CACHE_SIZE = 200
        private const val EXTRACTION_TARGET_SIZE = 280
        private const val MIN_HEALTHY_CACHE_SIZE = 150
        private const val TARGET_CANDIDATE_POOL_SIZE = 480
        private const val EXTRACTION_BATCH_SIZE = 4
        private const val MAX_STREAM_URL_REFRESHES_PER_WARM = 24
        private const val QUERY_SEARCH_BATCH_SIZE = 5
        private const val QUERY_POOL_SIZE = 50
        private const val FALLBACK_QUERY_POOL_SIZE = 12
        private const val MAX_PLAY_HISTORY = 320
        private const val MIN_PLAY_HISTORY_SIZE = 24
        private const val MAX_PLAY_HISTORY_FACTOR_PER_CACHE = 0.70
        private const val MAX_RECENT_REFRESH_IDS = 960
        private const val MIN_HEALTHY_CANDIDATE_POOL_SIZE = 120
        private const val SEARCH_PREWARM_REMAINING_ITEMS = 30
        private const val MINIMUM_VIABLE_CACHE_SIZE = 10
        private const val COLD_CACHE_SKIP_THRESHOLD = 5
        private const val BACKGROUND_REFRESH_COOLDOWN_MS = 30L * 60L * 1000L
        private const val SEARCH_CALL_TIMEOUT_MS = 20_000L
        private const val EXTRACTION_CALL_TIMEOUT_MS = 25_000L
        private const val SEARCH_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        // YouTube stream URLs typically expire around the 6 hour mark.
        private const val STREAM_URL_TTL_MS = 5L * 60L * 60L * 1000L + 30L * 60L * 1000L
        private const val STREAM_REEXTRACT_BUFFER_MS = 30L * 60L * 1000L
        private const val RECENT_PLAYBACK_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_PLAYBACK_RESOLVE_ATTEMPTS = 5
        private const val BAD_ENTRY_REFRESH_THRESHOLD = 10
        private const val CURRENT_CACHE_VERSION = 22
        private const val HISTORY_SEPARATOR = "|"
        private const val DEFAULT_CATEGORY_KEY = "__uncategorized__"
        private const val MIN_MAIN_SEARCH_UNIQUE_VIDEOS = 60
        private const val MAX_VIDEOS_PER_CHANNEL = 5
        private const val MAX_VIDEOS_PER_QUERY_BUCKET = 8
        private const val INITIAL_THEME_ROUND_ROBIN_CAP = 8
        private const val LAST_VIDEO_EXCLUSION_COUNT = 15
        private const val RELAXED_LAST_VIDEO_EXCLUSION_COUNT = 5
        private const val LAST_THEME_EXCLUSION_COUNT = 3
        private const val MIN_STRICT_PLAYBACK_CANDIDATES = 10
        private const val MAX_THEME_HISTORY = 12
        private const val UNPLAYED_WEIGHT = 3
        private const val SINGLE_PLAY_WEIGHT = 2
        private const val REPEAT_WEIGHT = 1
        private const val MEDIUM_FORM_DURATION_SECONDS = 3_600L
        private const val LONG_FORM_DURATION_SECONDS = 7_200L
        private const val LONG_FORM_BONUS = 2
        private const val VERY_LONG_FORM_BONUS = 3
        private const val AERIAL_CATEGORY_BONUS = 3
        private const val VLOG_TITLE_PENALTY = 6
        private const val DIGIT_HEAVY_CHANNEL_PENALTY = 2
        private val QUERY_VIDEO_ID_REGEX = Regex("[?&]v=([^&#]+)")

        private const val LONG_TAIL_QUERY_COUNT = 10

        private val FIRST_LAUNCH_SEQUENCE =
            listOf(
                "space",
                "ocean",
                "forest",
                "mountain",
                "other",
            )

        private val LOCATION_THEMES =
            mapOf(
                "japan" to listOf("japan", "tokyo", "kyoto", "fuji", "sakura", "japanese", "hokkaido", "osaka"),
                "iceland" to listOf("iceland", "icelandic", "reykjavik"),
                "norway" to listOf("norway", "norwegian", "fjord", "lofoten", "svalbard"),
                "ocean" to listOf("ocean", "sea", "beach", "coastal", "waves", "reef", "underwater", "coral"),
                "forest" to listOf("forest", "rainforest", "woodland", "jungle", "bamboo", "trees"),
                "mountain" to listOf("mountain", "alps", "himalaya", "peak", "summit", "glacier", "snow"),
                "space" to listOf("space", "earth from", "iss", "nasa", "galaxy", "nebula", "cosmos"),
                "desert" to listOf("desert", "sahara", "dunes", "arid", "canyon", "sandstone"),
                "city" to listOf("city", "skyline", "urban", "downtown", "rooftop", "aerial city"),
                "weather" to listOf("storm", "lightning", "aurora", "northern lights", "rain", "fog", "mist", "clouds"),
            )
    }
}
