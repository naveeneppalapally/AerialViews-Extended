package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import timber.log.Timber
import kotlin.random.Random

class YouTubeSourceRepository(
    private val context: Context,
    private val cacheDao: YouTubeCacheDao,
    private val watchHistoryDao: YouTubeWatchHistoryDao,
    private val sharedPreferences: SharedPreferences,
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingManualFullRebuild = AtomicBoolean(false)
    
    suspend fun triggerFullLibraryRebuild() {
        if (!refreshMutex.tryLock()) {
            pendingManualFullRebuild.set(true)
            _isRefreshingFlow.value = true // Ensure UI sees it's locked
            _refreshEvents.emit(RefreshEvent.AlreadyInProgress)
            return
        }
        try {
            isRefreshing = true
            _isRefreshingFlow.value = true
            withTimeout(5 * 60 * 1000L) { // 5-minute safety timeout
                _cacheLoadingProgress.emit(Pair(0, TARGET_CACHE_SIZE))
                cacheDao.clearAll()
                performLoadFreshSearchResults(replaceExistingCache = true)
            }
        } catch (exception: Exception) {
            Timber.tag(TAG).e(exception, "Forced library rebuild failed or timed out")
        } finally {
            val finalCount = cacheDao.countGoodEntries()
            // ORDER MATTERS: Set count FIRST, clear progress, THEN reset isRefreshing LAST
            // so Fragment sees settled data when isRefreshing flips to false
            _cacheCount.value = finalCount
            sharedPreferences.edit { putString(KEY_COUNT, finalCount.toString()) }
            _cacheLoadingProgress.emit(null)
            isRefreshing = false
            _isRefreshingFlow.value = false
            refreshMutex.unlock()
            if (pendingManualFullRebuild.getAndSet(false)) {
                repositoryScope.launch {
                    triggerFullLibraryRebuild()
                }
            }
        }
    }

    private val backgroundWarmInFlight = AtomicBoolean(false)
    private val lastBackgroundWarmAt = AtomicLong(0L)
    private val preResolvedLock = Any()
    private val _cacheCount = MutableStateFlow(sharedPreferences.getString(KEY_COUNT, "0")?.toIntOrNull() ?: 0)
    val cacheCount: StateFlow<Int> = _cacheCount.asStateFlow()
    private val _cacheFullEvent = MutableStateFlow(false)
    val cacheFullEvent: StateFlow<Boolean> = _cacheFullEvent.asStateFlow()
    private val _cacheLoadingProgress = MutableSharedFlow<Pair<Int, Int>?>(replay = 1, extraBufferCapacity = 64)
    val cacheLoadingProgress: SharedFlow<Pair<Int, Int>?> = _cacheLoadingProgress.asSharedFlow()
    private val _isRefreshingFlow = MutableStateFlow(false)
    val isRefreshingFlow: StateFlow<Boolean> = _isRefreshingFlow.asStateFlow()
    private val _refreshEvents = MutableSharedFlow<RefreshEvent>(extraBufferCapacity = 16)
    val refreshEvents: SharedFlow<RefreshEvent> = _refreshEvents.asSharedFlow()
    
    sealed interface RefreshEvent {
        data object AlreadyInProgress : RefreshEvent
    }

    fun publishProgress(current: Int, total: Int) {
        _cacheLoadingProgress.tryEmit(Pair(current, total))
    }

    suspend fun clearProgress() {
        _cacheLoadingProgress.emit(null)
    }

    @Volatile
    private var isRefreshing = false

    private val refreshMutex = Mutex()

    private var badCountThisSession = 0

    @Volatile
    private var preResolvedEntry: YouTubeCacheEntity? = null

    @Volatile
    private var preResolvingJob: Job? = null

    init {
        initializeCategorySnapshotIfNeeded()
        repositoryScope.launch {
            val dbCount = cacheDao.countGoodEntries()
            _cacheCount.value = dbCount
            sharedPreferences.edit {
                putString(KEY_COUNT, dbCount.toString())
            }
        }
    }

    fun consumeCacheFullEvent() {
        _cacheFullEvent.value = false
    }

    suspend fun getNextVideoUrl(): String =
        withContext(Dispatchers.IO) {
            consumeAnyPreResolvedEntry()?.let { cachedEntry ->
                if (!cachedEntry.isBad && isUsableCachedStream(cachedEntry)) {
                    Log.i(TAG, "Using pre-resolved URL instantly")
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
            if (isCacheVersionStale() || isCacheSignatureStale()) {
                return@withContext runCatching { ensureSearchCache() }
                    .getOrElse { exception ->
                        Timber.tag(TAG).w(exception, "Using locally cached YouTube entries after stale-cache refresh failure")
                        cacheDao.getAllGood()
                    }.let { refreshedEntries ->
                        prunePlayHistory(refreshedEntries)
                        buildPlaylistEntries(refreshedEntries).also { entries ->
                            updateCachedCount(entries.size)
                        }
                    }
            }

            val cachedEntries = cacheDao.getAllGood()
            if (cachedEntries.isEmpty()) {
                updateCachedCount(0)
                return@withContext emptyList()
            }

            prunePlayHistory(cachedEntries)
            buildPlaylistEntries(cachedEntries).also { entries ->
                updateCachedCount(entries.size)
            }
        }

    suspend fun getCachedVideosSnapshot(): List<YouTubeCacheEntity> =
        withContext(Dispatchers.IO) {
            val cachedEntries = cacheDao.getAllGood()
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
            cacheDao.countGoodEntries()
        }

    suspend fun applyCurrentFilters(): Int =
        withContext(Dispatchers.IO) {
            val initialCount = cacheDao.countGoodEntries()
            val removedByCategory = applyCurrentCategoryFilterInternal()
            val filteredCount = currentFilteredCount()
            if (filteredCount != initialCount || removedByCategory > 0) {
                clearPreResolvedEntry()
            }
            updateCachedCount(filteredCount)
            Log.i(
                TAG,
                "Applied YouTube cache filters instantly (categories=${enabledCategoryKeys()}, " +
                    "removedByCategory=$removedByCategory, remaining=$filteredCount)",
            )
            filteredCount
        }

    suspend fun applyCurrentCategoryFilter(): Int =
        withContext(Dispatchers.IO) {
            val removed = applyCurrentCategoryFilterInternal()
            val filteredCount = currentFilteredCount()
            if (removed > 0) {
                clearPreResolvedEntry()
            }
            val dbCount = cacheDao.countGoodEntries()
            updateCachedCount(dbCount)
            markCategoryStateFresh(dbCount)
            Log.i(
                TAG,
                "Applied YouTube category filter instantly (enabled=${enabledCategoryKeys()}, removed=$removed, remaining=$dbCount)",
            )
            dbCount
        }

    data class DeltaRefreshResult(
        val removedCount: Int,
        val insertedCount: Int,
        val countAfterRemoval: Int,
        val finalCount: Int,
        val allCategoriesDisabled: Boolean,
        val libraryFull: Boolean,
        val removedCategoriesCount: Int,
    )

    data class CategoryRemovalPreview(
        val removedCount: Int,
        val remainingCount: Int,
    )

    suspend fun previewCategoryRemovalSnapshot(): CategoryRemovalPreview =
        withContext(Dispatchers.IO) {
            val entries = cacheDao.getAllGood()
            val enabledKeys = enabledCategoryKeys().toSet()
            if (enabledKeys.isEmpty()) {
                return@withContext CategoryRemovalPreview(
                    removedCount = entries.size,
                    remainingCount = 0,
                )
            }
            val removableIds = removableCategoryVideoIds(entries, enabledKeys)
            CategoryRemovalPreview(
                removedCount = removableIds.size,
                remainingCount = (entries.size - removableIds.size).coerceAtLeast(0),
            )
        }

    suspend fun applyCategoryDeltaRefresh(): DeltaRefreshResult =
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val currentEnabled = enabledCategoryKeys().toSet()
                val previousEnabled = readCategorySnapshot().ifEmpty { currentEnabled }
                val removedCategories = previousEnabled - currentEnabled
                val addedCategories = currentEnabled - previousEnabled
                val removedCategoriesCount = removedCategories.size

                var removedCount = 0
                var insertedCount = 0
                var countAfterRemoval = 0

                try {
                    _isRefreshingFlow.value = true
                    isRefreshing = true
                    _cacheLoadingProgress.emit(null)

                    removedCount = applyCurrentCategoryFilterInternal()

                    var entriesSnapshot = cacheDao.getAllGood()
                    countAfterRemoval = entriesSnapshot.size
                    _cacheCount.value = countAfterRemoval
                    sharedPreferences.edit { putString(KEY_COUNT, countAfterRemoval.toString()) }

                    val currentEnabledList = currentEnabled.toList()
                    val categoryPlan = currentEnabledList.takeIf { it.isNotEmpty() }
                        ?.let { enabled ->
                            buildCategoryBalancePlan(enabled, entriesSnapshot, TARGET_CACHE_SIZE)
                        }
                    var preferredDeficitOrder = categoryPlan?.deficitCategories ?: emptyList()

                    val needsRebalance =
                        addedCategories.isNotEmpty() &&
                            countAfterRemoval >= TARGET_CACHE_SIZE &&
                            categoryPlan != null

                    if (needsRebalance) {
                        val plan = categoryPlan
                        val rebalanceOutcome =
                            rebalanceOverQuotaCategories(entriesSnapshot, plan.targets)
                        if (rebalanceOutcome.evictedVideoIds.isNotEmpty()) {
                            val evicted = cacheDao.deleteByVideoIds(rebalanceOutcome.evictedVideoIds)
                            removedCount += evicted
                        }
                        entriesSnapshot = cacheDao.getAllGood()
                        countAfterRemoval = entriesSnapshot.size
                        preferredDeficitOrder = rebalanceOutcome.deficitCategories
                    }

                    if (countAfterRemoval < TARGET_CACHE_SIZE && currentEnabledList.isNotEmpty()) {
                        val targets = categoryPlan?.targets ?: allocateCategoryTargets(currentEnabledList, TARGET_CACHE_SIZE)
                        var entriesForBackfill = entriesSnapshot
                        var remainingBackfill = (TARGET_CACHE_SIZE - countAfterRemoval).coerceAtLeast(0)
                        var rounds = 0
                        while (remainingBackfill > 0 && rounds < CATEGORY_DELTA_BACKFILL_ROUNDS) {
                            val counts = computeCategoryCounts(entriesForBackfill, targets.keys)
                            val categoriesToFill =
                                computeDeficitPriorityList(
                                    targets = targets,
                                    counts = counts,
                                    preferredOrder = preferredDeficitOrder,
                                ).ifEmpty { targets.keys.toList() }

                            var insertedThisRound = 0
                            categoriesToFill.forEach { category ->
                                if (remainingBackfill <= 0) {
                                    return@forEach
                                }
                                val categoryDeficit =
                                    ((targets[category] ?: 0) - (counts[category] ?: 0)).coerceAtLeast(0)
                                val extractionLimitForCategory =
                                    if (categoryDeficit > 0) {
                                        minOf(categoryDeficit, remainingBackfill)
                                    } else {
                                        minOf(remainingBackfill, CATEGORY_DELTA_FALLBACK_BATCH_PER_CATEGORY)
                                    }
                                if (extractionLimitForCategory <= 0) {
                                    return@forEach
                                }

                                val insertedForCategory =
                                    addEntriesForCategories(
                                        categoryKeys = listOf(category),
                                        existingEntries = entriesForBackfill,
                                        initialCount = countAfterRemoval + insertedCount,
                                        extractionLimit = extractionLimitForCategory,
                                    )
                                if (insertedForCategory > 0) {
                                    insertedCount += insertedForCategory
                                    insertedThisRound += insertedForCategory
                                    remainingBackfill -= insertedForCategory
                                    entriesForBackfill = cacheDao.getAllGood()
                                }
                            }

                            if (insertedThisRound <= 0) {
                                break
                            }
                            preferredDeficitOrder = categoriesToFill
                            rounds += 1
                        }
                    } else if (addedCategories.isNotEmpty() && countAfterRemoval >= TARGET_CACHE_SIZE) {
                        _cacheFullEvent.value = true
                    }
                } finally {
                    val finalCount = cacheDao.countGoodEntries()
                    // ORDER MATTERS: Set count FIRST, clear progress, THEN reset isRefreshing LAST
                    _cacheCount.value = finalCount
                    sharedPreferences.edit { putString(KEY_COUNT, finalCount.toString()) }
                    _cacheLoadingProgress.emit(null)
                    isRefreshing = false
                    _isRefreshingFlow.value = false
                }

                if (removedCount > 0 || insertedCount > 0) {
                    clearPreResolvedEntry()
                }
                val dbCount = cacheDao.countGoodEntries()
                markCategoryStateFresh(dbCount)
                Log.i(
                    TAG,
                    "Applied category delta refresh (added=${addedCategories.size}, removed=${removedCategories.size}, " +
                        "removedCategoriesCount=$removedCategoriesCount, removedRows=$removedCount, " +
                        "postRemoval=$countAfterRemoval, backfilled=$insertedCount, finalCount=$dbCount)",
                )
                DeltaRefreshResult(
                    removedCount = removedCount,
                    insertedCount = insertedCount,
                    countAfterRemoval = countAfterRemoval,
                    finalCount = dbCount,
                    allCategoriesDisabled = currentEnabled.isEmpty(),
                    libraryFull = cacheDao.countGoodEntries() >= TARGET_CACHE_SIZE,
                    removedCategoriesCount = removedCategoriesCount,
                )
            }
        }

    suspend fun markAsPlayed(videoId: String) =
        withContext(Dispatchers.IO) {
            cacheDao.getAllGood().firstOrNull { it.videoId == videoId }?.let(::recordPlayback)
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
                if (cacheDao.countGoodEntries() < COLD_CACHE_SKIP_THRESHOLD) {
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
                    val resolvedUrl =
                        if (hasFreshStreamUrl(entry)) {
                            entry.streamUrl
                        } else {
                            resolveEntryStreamUrl(entry, recordPlayback = false)
                        }
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
                if (hasFreshStreamUrl(cachedEntry)) {
                    return@withContext cachedEntry.streamUrl
                }
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
                updateCachedCount(cacheDao.countGoodEntries())
            }?.streamUrl
        }

    suspend fun preloadProjectivyVideoUrl(videoPageUrl: String): String? =
        withContext(Dispatchers.IO) {
            peekPreResolvedEntry(videoPageUrl)?.streamUrl
                ?.takeIf(::isProjectivyUsableStreamUrl)
                ?.let { return@withContext it }

            cacheDao.getByVideoPageUrl(videoPageUrl)?.takeIf { !it.isBad }?.let { cachedEntry ->
                if (hasFreshStreamUrl(cachedEntry) && isProjectivyUsableStreamUrl(cachedEntry.streamUrl)) {
                    return@withContext cachedEntry.streamUrl
                }

                return@withContext resolveProjectivyStreamUrl(videoPageUrl)?.also { resolvedUrl ->
                    cacheDao.updateStreamUrl(
                        cachedEntry.videoId,
                        resolvedUrl,
                        System.currentTimeMillis() + STREAM_URL_TTL_MS,
                    )
                }
            }

            runCatching {
                buildDirectCacheEntry(
                    videoPageUrl = videoPageUrl,
                    cachedAt = System.currentTimeMillis(),
                    preferredQuality = playbackResolutionQuality(fallbackQuality = preferredQuality()),
                    allowAdaptiveManifests = false,
                )
            }.getOrNull()
                ?.takeIf { entry -> isProjectivyUsableStreamUrl(entry.streamUrl) }
                ?.also { directEntry ->
                    cacheDao.insertAll(listOf(directEntry))
                    updateCachedCount(cacheDao.countGoodEntries())
                }?.streamUrl
        }

    suspend fun resolveVideoUrl(videoPageUrl: String): String =
        withContext(Dispatchers.IO) {
            consumePreResolvedEntry(videoPageUrl)?.let { cachedEntry ->
                if (!cachedEntry.isBad && isUsableCachedStream(cachedEntry)) {
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
            updateCachedCount(cacheDao.countGoodEntries())
            recordPlayback(directEntry)
            maybeWarmSearchCacheNearPlaylistEnd()
            preResolveNext(repositoryScope)
            directEntry.streamUrl
        }

    suspend fun warmCache(
        forceSearchRefresh: Boolean = false,
        replaceExistingCacheOverride: Boolean? = null,
    ): Int =
        withContext(Dispatchers.IO) {
            val cachedEntries = cacheDao.getAllGood()

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
                                replaceExistingCache =
                                    replaceExistingCacheOverride ?: (forceSearchRefresh || isCacheSignatureStale()),
                            )
                        }.getOrElse { exception ->
                            Timber.tag(TAG).w(exception, "Using cached YouTube entries after warm refresh failure")
                            updateCachedCount(cachedEntries.size)
                            cachedEntries
                        }
                    }

                    else -> refreshExpiringStreamUrls(cachedEntries)
                }

            val liveCount = cacheDao.countGoodEntries()
            updateCachedCount(liveCount)
            liveCount
        }

    private suspend fun ensureSearchCache(): List<YouTubeCacheEntity> {
        val cachedEntries = cacheDao.getAllGood()
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
    ): List<YouTubeCacheEntity> {
        return refreshMutex.withLock {
            performLoadFreshSearchResults(replaceExistingCache)
        }
    }

    private suspend fun performLoadFreshSearchResults(
        replaceExistingCache: Boolean = false,
    ): List<YouTubeCacheEntity> {
        if (enabledCategoryKeys().isEmpty()) {
            Timber.tag(TAG).w(
                "All YouTube categories are disabled; using fallback discovery categories to avoid an empty playlist",
            )
        }

        return try {
            isRefreshing = true
            _isRefreshingFlow.value = true
            withTimeout(5 * 60 * 1000L) { // 5-minute safety timeout
                val refreshPlan = buildRefreshPlan()
                val searchResults = searchRefreshCandidates(refreshPlan)
                val extractedEntries =
                    extractRefreshEntries(
                        refreshPlan = refreshPlan,
                        searchResults = searchResults,
                        replaceExistingCache = replaceExistingCache,
                        initialCount = refreshPlan.existingEntries.size,
                    )
                val entries = mergeRefreshedEntries(refreshPlan, extractedEntries, replaceExistingCache)
                persistFreshEntries(refreshPlan, entries)
                topUpCacheToTargetAfterRefresh(entries)
            }
        } catch (exception: Exception) {
            val fallbackEntries = filteredExistingEntries(cacheDao.getAllGood())
            if (fallbackEntries.isNotEmpty()) {
                Timber.tag(TAG).w(exception, "Using filtered cached YouTube entries after refresh failure or timeout")
                updateCachedCount(fallbackEntries.size)
                fallbackEntries
            } else {
                throw when (exception) {
                    is YouTubeSourceException -> exception
                    else -> YouTubeSourceException("Failed to refresh YouTube videos or timed out", exception)
                }
            }
        } finally {
            val finalCount = cacheDao.countGoodEntries()
            // ORDER MATTERS: Set count FIRST, clear progress, THEN reset isRefreshing LAST
            _cacheCount.value = finalCount
            sharedPreferences.edit { putString(KEY_COUNT, finalCount.toString()) }
            _cacheLoadingProgress.emit(null)
            isRefreshing = false
            _isRefreshingFlow.value = false
        }
    }

    private fun buildRefreshPlan(): RefreshPlan {
        val cachedAt = System.currentTimeMillis()
        val categoryPreferences = categoryPreferences()
        val existingEntries = filteredExistingEntries(cacheDao.getAllGood())
        val isColdStart = existingEntries.size < COLD_CACHE_SKIP_THRESHOLD
        return RefreshPlan(
            query = searchQuery(),
            queryPool =
                QueryFormulaEngine.generateQueryPool(
                    count = if (isColdStart) COLD_START_QUERY_POOL_SIZE else QUERY_POOL_SIZE,
                    entropySeed = cachedAt,
                    prefs = categoryPreferences,
                ),
            preferredQuality = preferredQuality(),
            cachedAt = cachedAt,
            entropySeed = System.nanoTime() xor cachedAt,
            existingEntries = existingEntries,
            recentRefreshIds = recentRefreshIds().toSet(),
            isColdStart = isColdStart,
        )
    }

    private suspend fun searchRefreshCandidates(refreshPlan: RefreshPlan): List<SearchCandidate> {
        // Emit "Searching" state (negative progress) to separate search from extraction UI
        _cacheLoadingProgress.emit(Pair(-1, TARGET_CACHE_SIZE))
        delay(300)
        val mainSearchResults =
            searchCandidateVideos(
                queries = refreshPlan.queryPool,
            )
        if (refreshPlan.isColdStart) {
            Timber.tag(TAG).i(
                "Using fast cold-start YouTube candidate pool (%s queries, %s/%s unique candidates)",
                refreshPlan.queryPool.size,
                mainSearchResults.size,
                uniqueCandidateCount(mainSearchResults),
            )
            return mainSearchResults
        }
        val expandedResults = maybeExpandWithLongTail(mainSearchResults)
        val healthyResults = ensureHealthyCandidatePool(refreshPlan.query, expandedResults)
        val healthyUniqueCount = uniqueCandidateCount(healthyResults)
        val finalResults =
            if (healthyUniqueCount >= MIN_HEALTHY_CACHE_SIZE) {
                healthyResults
            } else {
                val supplementalQueries =
                    QueryFormulaEngine.generateFallbackQueryPool(
                        baseQuery = refreshPlan.query,
                        count = SUPPLEMENTAL_QUERY_POOL_SIZE,
                        entropySeed = refreshPlan.entropySeed xor healthyUniqueCount.toLong(),
                        prefs = categoryPreferences(),
                    )
                val supplementalResults = searchCandidateVideos(supplementalQueries)
                val supplementedResults = mergeCandidatePools(healthyResults, supplementalResults)
                Timber.tag(TAG).i(
                    "Supplemented YouTube candidate pool with %s queries (%s -> %s unique candidates)",
                    supplementalQueries.size,
                    healthyUniqueCount,
                    uniqueCandidateCount(supplementedResults),
                )
                supplementedResults
            }

        Timber.tag(TAG).i(
            "Prepared YouTube candidate pool (queries=%s, main=%s/%s unique, final=%s/%s unique)",
            refreshPlan.queryPool.size,
            mainSearchResults.size,
            uniqueCandidateCount(mainSearchResults),
            finalResults.size,
            uniqueCandidateCount(finalResults),
        )
        return finalResults
    }

    private suspend fun extractRefreshEntries(
        refreshPlan: RefreshPlan,
        searchResults: List<SearchCandidate>,
        replaceExistingCache: Boolean,
        initialCount: Int,
    ): List<YouTubeCacheEntity> {
        val filteredCandidates =
            filterCategoryMismatchedCandidates(
                filterRecentlyPlayedCandidates(searchResults),
            )
        val rankedCandidates =
            rankCandidatesWithStyleBalance(filteredCandidates)
                .let(::deduplicateCandidatesByTitle)
                .let(::deduplicateCandidatesByVideoId)
                .let { applyCandidateDiversityCaps(it, EXTRACTION_TARGET_SIZE) }
 
        val extractedEntries =
            extractEntries(
                items = rankedCandidates,
                cachedAt = refreshPlan.cachedAt,
                preferredQuality = refreshPlan.preferredQuality,
                limit = EXTRACTION_TARGET_SIZE,
                publishMinimumCache = refreshPlan.existingEntries.size < COLD_CACHE_SKIP_THRESHOLD,
                publishProgress = true,
                initialCount = initialCount,
            )

        Timber.tag(TAG).i(
            "Extracted YouTube refresh entries (search=%s, filtered=%s, ranked=%s, extracted=%s)",
            searchResults.size,
            filteredCandidates.size,
            rankedCandidates.size,
            extractedEntries.size,
        )
        return extractedEntries
    }

    private fun mergeRefreshedEntries(
        refreshPlan: RefreshPlan,
        extractedEntries: List<YouTubeCacheEntity>,
        replaceExistingCache: Boolean,
    ): List<YouTubeCacheEntity> {
        val dedupedExtractedEntries = deduplicateEntriesByVideoId(extractedEntries)
        val entries =
            if (replaceExistingCache) {
                applyEntryDiversityCaps(dedupedExtractedEntries, TARGET_CACHE_SIZE)
            } else {
                replenishEntriesFromExistingCache(
                    extractedEntries = dedupedExtractedEntries,
                    existingEntries = refreshPlan.existingEntries,
                    entropySeed = refreshPlan.entropySeed,
                    recentRefreshIds = refreshPlan.recentRefreshIds,
                ).let { applyEntryDiversityCaps(it, TARGET_CACHE_SIZE) }
            }

        if (entries.isEmpty()) {
            val fallbackEntries = filteredExistingEntries(refreshPlan.existingEntries)
            if (fallbackEntries.isNotEmpty()) {
                Timber.tag(TAG).w("Reusing filtered cached YouTube entries because refresh produced no results")
                return fallbackEntries
            }
            throw YouTubeSourceException("No videos available")
        }

        val quotaBalancedEntries =
            rebalanceEntriesToCategoryTargets(
                entries = entries,
                enabledCategoryKeys = enabledCategoryKeys(),
                totalSlots = TARGET_CACHE_SIZE,
            )
        val finalEntries = quotaBalancedEntries.ifEmpty { entries }

        val existingPlaybackHistory =
            refreshPlan.existingEntries.associate { existingEntry ->
                existingEntry.videoId to existingEntry.lastPlayedAt
            }

        return finalEntries.map { entry ->
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
        val uniqueEntries = deduplicateEntriesByVideoId(entries)
        cacheDao.clearAndInsert(uniqueEntries)
        badCountThisSession = 0
        recordRefreshHistory(uniqueEntries)
        val persistedCount = cacheDao.countGoodEntries()
        markCategoryStateFresh(persistedCount)
        Log.i(
            TAG,
            "Cached YouTube videos for query \"${refreshPlan.query}\" across ${refreshPlan.queryPool.size} searches " +
                "(requested=${entries.size}, unique=${uniqueEntries.size}, persisted=$persistedCount, " +
                "categories=${uniqueEntries.groupingBy { it.categoryKey.ifBlank { "unknown" } }.eachCount()})",
        )
    }

    private suspend fun topUpCacheToTargetAfterRefresh(
        persistedEntries: List<YouTubeCacheEntity>,
    ): List<YouTubeCacheEntity> {
        var entriesSnapshot = deduplicateEntriesByVideoId(persistedEntries)
        var remainingToInsert = (TARGET_CACHE_SIZE - entriesSnapshot.size).coerceAtLeast(0)
        if (remainingToInsert <= 0) {
            return entriesSnapshot
        }

        val enabledCategories = enabledCategoryKeys()
        if (enabledCategories.isEmpty()) {
            return entriesSnapshot
        }

        val targets = allocateCategoryTargets(enabledCategories, TARGET_CACHE_SIZE)
        var preferredOrder =
            computeDeficitPriorityList(
                targets = targets,
                counts = computeCategoryCounts(entriesSnapshot, targets.keys),
            ).ifEmpty { targets.keys.toList() }
        var rounds = 0
        var insertedTotal = 0

        while (remainingToInsert > 0 && rounds < FULL_REFRESH_TOPUP_ROUNDS) {
            val counts = computeCategoryCounts(entriesSnapshot, targets.keys)
            val categoriesToFill =
                computeDeficitPriorityList(
                    targets = targets,
                    counts = counts,
                    preferredOrder = preferredOrder,
                ).ifEmpty { targets.keys.toList() }

            var insertedThisRound = 0
            categoriesToFill.forEach { category ->
                if (remainingToInsert <= 0) {
                    return@forEach
                }

                val categoryDeficit =
                    ((targets[category] ?: 0) - (counts[category] ?: 0)).coerceAtLeast(0)
                val extractionLimitForCategory =
                    when {
                        categoryDeficit > 0 ->
                            minOf(categoryDeficit, remainingToInsert, FULL_REFRESH_TOPUP_BATCH_PER_CATEGORY)
                        else ->
                            minOf(remainingToInsert, FULL_REFRESH_TOPUP_BATCH_PER_CATEGORY)
                    }
                if (extractionLimitForCategory <= 0) {
                    return@forEach
                }

                val insertedForCategory =
                    addEntriesForCategories(
                        categoryKeys = listOf(category),
                        existingEntries = entriesSnapshot,
                        initialCount = cacheDao.countGoodEntries(),
                        extractionLimit = extractionLimitForCategory,
                    )
                if (insertedForCategory > 0) {
                    insertedTotal += insertedForCategory
                    insertedThisRound += insertedForCategory
                    remainingToInsert -= insertedForCategory
                    entriesSnapshot = cacheDao.getAllGood()
                }
            }

            if (insertedThisRound <= 0) {
                break
            }
            preferredOrder = categoriesToFill
            rounds += 1
        }

        val finalEntries = cacheDao.getAllGood()
        if (insertedTotal > 0) {
            recordRefreshHistory(finalEntries)
            markCategoryStateFresh(finalEntries.size)
        }
        Timber.tag(TAG).i(
            "Full refresh top-up complete (initial=%s, inserted=%s, final=%s)",
            persistedEntries.size,
            insertedTotal,
            finalEntries.size,
        )
        return finalEntries
    }

    private suspend fun searchCandidateVideos(
        queries: List<String>,
    ): List<SearchCandidate> {
        val variantBuckets = linkedMapOf<String, ArrayDeque<SearchCandidate>>()

        for (variantChunk in queries.chunked(QUERY_SEARCH_BATCH_SIZE)) {
            searchVariantChunk(variantChunk).forEach { (variant, results) ->
                addVariantResults(variantBuckets, variant, results)
            }
        }

        return interleaveVariantResults(variantBuckets).take(TARGET_CANDIDATE_POOL_SIZE)
    }

    private suspend fun searchVariantChunk(
        variants: List<String>,
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
        val longTailResults = searchCandidateVideos(longTailQueries)
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
        // Keep category matching as a ranking signal only; do not hard-drop candidates by title keywords.
        return candidates
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
        val fallbackCandidates = searchCandidateVideos(fallbackQueries)
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
        publishProgress: Boolean = true,
        initialCount: Int = 0,
    ): List<YouTubeCacheEntity> =
        supervisorScope {
            val entries = mutableListOf<YouTubeCacheEntity>()

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

                if (extractedChunk.isNotEmpty()) {
                    val toInsert = if (entries.size + extractedChunk.size > limit) {
                        extractedChunk.take(limit - entries.size)
                    } else {
                        extractedChunk
                    }
                    if (toInsert.isNotEmpty()) {
                        entries += toInsert
                        
                        if (publishProgress) {
                            val currentTotal = initialCount + entries.size
                            _cacheLoadingProgress.emit(
                                Pair(currentTotal.coerceAtMost(TARGET_CACHE_SIZE), TARGET_CACHE_SIZE)
                            )
                        }
                    }
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

        return cacheDao.getAllGood()
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
                context,
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
        entry.streamUrl.isNotBlank() &&
            !isStreamUrlExpiringSoon(entry) &&
            !isBelowMinimumCachedQuality(entry.streamUrl)

    private fun isStreamUrlExpiringSoon(entry: YouTubeCacheEntity): Boolean =
        entry.streamUrlExpiresAt < System.currentTimeMillis() + STREAM_REEXTRACT_BUFFER_MS

    private fun scheduleBackgroundWarmCache(
        forceSearchRefresh: Boolean,
        bypassCooldown: Boolean = false,
        replaceExistingCacheOverride: Boolean? = null,
    ) {
        val now = System.currentTimeMillis()
        if (!bypassCooldown && now - lastBackgroundWarmAt.get() < BACKGROUND_REFRESH_COOLDOWN_MS) {
            return
        }

        if (!backgroundWarmInFlight.compareAndSet(false, true)) {
            return
        }

        lastBackgroundWarmAt.set(now)
        repositoryScope.launch {
            try {
                warmCache(
                    forceSearchRefresh = forceSearchRefresh,
                    replaceExistingCacheOverride = replaceExistingCacheOverride,
                )
            } catch (exception: Exception) {
                Timber.tag(TAG).w(exception, "Background YouTube warm refresh failed")
            } finally {
                backgroundWarmInFlight.set(false)
            }
        }
    }

    private fun selectNextCandidate(): YouTubeCacheEntity? {
        val cachedEntries = cacheDao.getAllGood()
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
    }

    private fun isCacheUndersized(entries: List<YouTubeCacheEntity>): Boolean =
        entries.size < TARGET_CACHE_SIZE

    private fun playHistory(): ArrayDeque<String> {
        val dbHistory = watchHistoryDao.recentHistory(MAX_PLAY_HISTORY)
        if (dbHistory.isNotEmpty()) {
            return ArrayDeque(dbHistory.asReversed().map { it.videoId })
        }
        return readHistory(KEY_PLAY_HISTORY)
    }

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
        watchHistoryDao.insert(
            YouTubeWatchHistoryEntity(
                videoId = entry.videoId,
                playedAt = playedAt,
            ),
        )
        watchHistoryDao.trimToLimit(MAX_WATCH_HISTORY_ROWS)

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
        val cachedEntries = cacheDao.getAllGood()
        if (cachedEntries.isEmpty()) {
            return
        }

        val cacheIds = cachedEntries.mapTo(mutableSetOf()) { it.videoId }
        val playedInCache = playHistory().asSequence().filter { it in cacheIds }.toSet().size
        val remainingUnique = (cachedEntries.size - playedInCache).coerceAtLeast(0)
        when {
            remainingUnique <= EMERGENCY_REFILL_REMAINING_ITEMS -> {
                scheduleBackgroundWarmCache(
                    forceSearchRefresh = true,
                    bypassCooldown = true,
                    replaceExistingCacheOverride = false,
                )
            }
            remainingUnique <= FORCE_REFRESH_REMAINING_ITEMS -> {
                scheduleBackgroundWarmCache(
                    forceSearchRefresh = true,
                    bypassCooldown = true,
                    replaceExistingCacheOverride = false,
                )
            }
            remainingUnique <= BACKGROUND_PREWARM_REMAINING_ITEMS -> {
                scheduleBackgroundWarmCache(
                    forceSearchRefresh = true,
                    replaceExistingCacheOverride = false,
                )
            }
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

    private fun cacheSignature(): String {
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        // DO NOT include categorySignature here; category changes should not invalidate the whole cache.
        return "$appVersion|v$CURRENT_CACHE_VERSION"
    }

    private fun isCacheSignatureStale(): Boolean {
        val currentSignature = cacheSignature()
        val storedSignature = sharedPreferences.getString(KEY_CACHE_SIGNATURE, "")
        val isStale = storedSignature != currentSignature
        if (isStale) {
            Timber.tag(TAG).d("YouTube cache signature stale: current=\"%s\", stored=\"%s\"", currentSignature, storedSignature)
        }
        return isStale
    }

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
                            context,
                            playbackResolutionQuality(),
                            preferVideoOnly = playbackPreferVideoOnly(),
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
                    if (isUsableCachedStream(entry)) {
                        Timber.tag(TAG).w(exception, "Falling back to cached YouTube stream URL for %s", entry.videoId)
                        scheduleBackgroundWarmCache(forceSearchRefresh = false)
                        entry.streamUrl
                    } else {
                        markEntryAsBad(entry, exception)
                        throw YouTubeSourceException("No videos available", exception)
                    }
                }
            } else {
                if (!isUsableCachedStream(entry)) {
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

    private suspend fun resolveProjectivyStreamUrl(videoPageUrl: String): String? =
        runCatching {
            NewPipeHelper.extractStreamUrl(
                videoPageUrl,
                context,
                playbackResolutionQuality(fallbackQuality = preferredQuality()),
                preferVideoOnly = playbackPreferVideoOnly(),
                allowAdaptiveManifests = false,
            )
        }.getOrNull()?.takeIf(::isProjectivyUsableStreamUrl)

    private fun markEntryAsBad(
        entry: YouTubeCacheEntity,
        exception: Throwable? = null,
    ) {
        val rowsMarkedBad = cacheDao.markAsBad(entry.videoId)
        if (rowsMarkedBad > 0) {
            val liveCount = cacheDao.countGoodEntries()
            _cacheCount.value = liveCount
            sharedPreferences.edit { putString(KEY_COUNT, liveCount.toString()) }
        }
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

    private fun isProjectivyUsableStreamUrl(url: String): Boolean {
        if (!isUsableStreamUrl(url)) {
            return false
        }

        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = parsed.host.orEmpty().lowercase(Locale.US)
        val path = parsed.encodedPath.orEmpty().lowercase(Locale.US)
        val mime = parsed.getQueryParameter("mime")?.lowercase(Locale.US).orEmpty()

        if (host.contains("youtube.com") || host.contains("youtu.be")) {
            return false
        }
        if (host.contains("manifest.googlevideo.com")) {
            return false
        }
        if (path.endsWith(".mpd") || path.endsWith(".m3u8") || path.contains("/manifest/")) {
            return false
        }
        if (
            mime.contains("application/dash+xml") ||
            mime.contains("application/vnd.apple.mpegurl") ||
            mime.contains("application/x-mpegurl")
        ) {
            return false
        }

        return true
    }

    private fun isUsableCachedStream(entry: YouTubeCacheEntity): Boolean {
        if (!isUsableStreamUrl(entry.streamUrl)) {
            return false
        }
        val streamHeight = cachedStreamHeight(entry.streamUrl)
        if (streamHeight != null && streamHeight in 1 until MIN_ACCEPTABLE_CACHED_STREAM_HEIGHT) {
            Log.i(
                TAG,
                "Rejecting cached low-quality stream for ${entry.videoId}: ${streamHeight}p (< ${MIN_ACCEPTABLE_CACHED_STREAM_HEIGHT}p). Forcing re-extraction.",
            )
            return false
        }
        return true
    }

    private fun isBelowMinimumCachedQuality(streamUrl: String): Boolean =
        cachedStreamHeight(streamUrl)?.let { height ->
            height in 1 until MIN_ACCEPTABLE_CACHED_STREAM_HEIGHT
        } == true

    private fun cachedStreamHeight(streamUrl: String): Int? {
        val uri = runCatching { Uri.parse(streamUrl) }.getOrNull()
        parseHeightHint(uri?.getQueryParameter("quality_label"))?.let { return it }
        parseHeightHint(uri?.getQueryParameter("quality"))?.let { return it }
        parseHeightHint(streamUrl)?.let { return it }

        val itag = uri?.getQueryParameter("itag")?.toIntOrNull() ?: return null
        return STREAM_ITAG_HEIGHT_HINTS[itag]
    }

    private fun parseHeightHint(value: String?): Int? {
        val normalized = value?.lowercase(Locale.US)?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }

        HEIGHT_HINT_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return QUALITY_LABEL_HEIGHT_HINTS[normalized]
    }

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
            val uploaderName = item.getUploaderName().orEmpty()
            val streamUrl =
                NewPipeHelper.extractStreamUrl(
                    videoPageUrl,
                    context,
                    preferredQuality,
                    preferVideoOnly = shouldPreferVideoOnly(),
                )

            YouTubeCacheEntity(
                videoId = videoId,
                videoPageUrl = videoPageUrl,
                streamUrl = streamUrl,
                title = title,
                uploaderName = uploaderName,
                durationSeconds = item.getDuration().toInt(),
                categoryKey = resolveCandidateCategoryKey(candidate, title, uploaderName),
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
        allowAdaptiveManifests: Boolean = true,
    ): YouTubeCacheEntity? {
        val videoId = extractVideoId(videoPageUrl) ?: return null
        val streamUrl =
            NewPipeHelper.extractStreamUrl(
                videoPageUrl,
                context,
                playbackResolutionQuality(fallbackQuality = preferredQuality),
                preferVideoOnly = playbackPreferVideoOnly(),
                allowAdaptiveManifests = allowAdaptiveManifests,
            )
        return YouTubeCacheEntity(
            videoId = videoId,
            videoPageUrl = videoPageUrl,
            streamUrl = streamUrl,
            title = videoId,
            uploaderName = "",
            durationSeconds = 0,
            categoryKey = QueryFormulaEngine.categoryForQuery(searchQuery())?.key.orEmpty(),
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

    private fun initializeCategorySnapshotIfNeeded() {
        if (sharedPreferences.contains(KEY_CATEGORY_SNAPSHOT)) {
            return
        }
        persistCategorySnapshot(enabledCategoryKeys().toSet())
    }

    private fun readCategorySnapshot(): Set<String> =
        sharedPreferences
            .getStringSet(KEY_CATEGORY_SNAPSHOT, emptySet())
            .orEmpty()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

    private fun persistCategorySnapshot(enabledCategoryKeys: Set<String>) {
        sharedPreferences.edit {
            putStringSet(KEY_CATEGORY_SNAPSHOT, enabledCategoryKeys)
        }
    }

    private suspend fun addEntriesForCategories(
        categoryKeys: List<String>,
        existingEntries: List<YouTubeCacheEntity>,
        initialCount: Int,
        extractionLimit: Int = CATEGORY_DELTA_EXTRACTION_LIMIT,
    ): Int {
        val normalizedCategories =
            categoryKeys.map(String::trim).filter(String::isNotBlank)
        if (normalizedCategories.isEmpty() || extractionLimit <= 0) {
            return 0
        }

        val uniqueCategories = normalizedCategories.distinct()
        val existingById = existingEntries.associateBy { entry -> entry.videoId }.toMutableMap()
        var insertedTotal = 0
        var remainingToInsert = extractionLimit

        repeat(CATEGORY_DELTA_FETCH_ATTEMPTS) { attempt ->
            if (remainingToInsert <= 0) {
                return@repeat
            }

            val cachedAt = System.currentTimeMillis()
            val entropySeed = (System.nanoTime() xor cachedAt) + attempt
            val demandDrivenQueryCount =
                ((remainingToInsert + CATEGORY_DELTA_QUERY_TO_VIDEO_RATIO - 1) / CATEGORY_DELTA_QUERY_TO_VIDEO_RATIO)
                    .coerceAtLeast(1)
            val queryCount =
                maxOf(
                    uniqueCategories.size * CATEGORY_DELTA_QUERY_COUNT_PER_CATEGORY,
                    demandDrivenQueryCount * uniqueCategories.size,
                ).coerceAtMost(MAX_CATEGORY_DELTA_QUERY_COUNT)
                    .coerceAtLeast(uniqueCategories.size)

            val queryPool =
                QueryFormulaEngine.generateQueryPool(
                    count = queryCount,
                    prefs = categoryPreferencesForKeys(uniqueCategories.toSet()),
                    entropySeed = entropySeed,
                )
            if (queryPool.isEmpty()) {
                return@repeat
            }

            val searchResults = searchCandidateVideos(queryPool)
            if (searchResults.isEmpty()) {
                return@repeat
            }

            val filteredCandidates =
                filterCategoryMismatchedCandidates(
                    filterRecentlyPlayedCandidates(searchResults),
                ).filter { candidate ->
                    candidate.category?.key in uniqueCategories
                }
            val rankedCandidates =
                rankCandidatesWithStyleBalance(filteredCandidates)
                    .let(::deduplicateCandidatesByTitle)
                    .let(::deduplicateCandidatesByVideoId)
                    .let { applyCandidateDiversityCaps(it, remainingToInsert) }
            if (rankedCandidates.isEmpty()) {
                return@repeat
            }

            val extractedEntries =
                extractEntries(
                    items = rankedCandidates,
                    cachedAt = cachedAt,
                    preferredQuality = preferredQuality(),
                    limit = remainingToInsert,
                    publishMinimumCache = false,
                    publishProgress = true,
                    initialCount = initialCount + insertedTotal,
                )
            if (extractedEntries.isEmpty()) {
                return@repeat
            }

            val entriesToInsert =
                deduplicateEntriesByVideoId(extractedEntries.map { extracted ->
                    existingById[extracted.videoId]
                        ?.takeIf { existing -> existing.lastPlayedAt > 0L }
                        ?.let { existing -> extracted.copy(lastPlayedAt = existing.lastPlayedAt) }
                        ?: extracted
                })
            if (entriesToInsert.isEmpty()) {
                return@repeat
            }

            val beforeInsertCount = cacheDao.countGoodEntries()
            cacheDao.insertAll(entriesToInsert)
            val afterInsertCount = cacheDao.countGoodEntries()

            val insertedThisAttempt =
                (afterInsertCount - beforeInsertCount).coerceAtLeast(0)
            entriesToInsert.forEach { entry ->
                existingById[entry.videoId] = entry
            }

            if (insertedThisAttempt > 0) {
                insertedTotal += insertedThisAttempt
                remainingToInsert = (extractionLimit - insertedTotal).coerceAtLeast(0)
            }
        }

        if (insertedTotal <= 0) {
            _cacheLoadingProgress.emit(null)
        }
        return insertedTotal
    }

    private data class CategoryBalancePlan(
        val targets: Map<String, Int>,
        val deficitCategories: List<String>,
    )

    private data class RebalanceOutcome(
        val evictedVideoIds: List<String>,
        val deficitCategories: List<String>,
    )

    private fun buildCategoryBalancePlan(
        enabledCategoryKeys: List<String>,
        entries: List<YouTubeCacheEntity>,
        totalSlots: Int,
    ): CategoryBalancePlan {
        val targets = allocateCategoryTargets(enabledCategoryKeys, totalSlots)
        val counts = computeCategoryCounts(entries, targets.keys)
        val deficitCategories = computeDeficitPriorityList(targets, counts)
        return CategoryBalancePlan(targets, deficitCategories)
    }

    private fun allocateCategoryTargets(
        enabledCategoryKeys: List<String>,
        totalSlots: Int,
    ): Map<String, Int> {
        if (enabledCategoryKeys.isEmpty()) {
            return emptyMap()
        }

        val base = totalSlots / enabledCategoryKeys.size
        var remainder = totalSlots % enabledCategoryKeys.size
        val rotation = consumeCategoryQuotaRotation(enabledCategoryKeys.size)
        val allocations = linkedMapOf<String, Int>()
        for (offset in enabledCategoryKeys.indices) {
            val key = enabledCategoryKeys[(rotation + offset) % enabledCategoryKeys.size]
            val allocation = base + if (remainder > 0) 1 else 0
            allocations[key] = allocation
            if (remainder > 0) {
                remainder -= 1
            }
        }
        return allocations
    }

    private fun computeCategoryCounts(entries: List<YouTubeCacheEntity>, targetKeys: Set<String>): Map<String, Int> {
        val counts = targetKeys.associateWith { 0 }.toMutableMap()
        if (targetKeys.isEmpty()) {
            return counts
        }
        entries.forEach { entry ->
            resolveCategoryKey(entry, targetKeys)?.let { key ->
                counts[key] = counts.getValue(key) + 1
            }
        }
        return counts
    }

    private fun computeDeficitPriorityList(
        targets: Map<String, Int>,
        counts: Map<String, Int>,
        preferredOrder: List<String> = emptyList(),
    ): List<String> {
        if (targets.isEmpty()) {
            return emptyList()
        }
        val targetOrder = targets.keys.withIndex().associate { indexed -> indexed.value to indexed.index }
        val preferredIndex = preferredOrder.withIndex().associate { indexed -> indexed.value to indexed.index }
        val deficits =
            targets.mapNotNull { (key, target) ->
                val deficit = (target - (counts[key] ?: 0)).coerceAtLeast(0)
                if (deficit > 0) {
                    key to deficit
                } else {
                    null
                }
            }
        if (deficits.isEmpty()) {
            return emptyList()
        }

        return deficits
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenBy { (key, _) -> preferredIndex[key] ?: Int.MAX_VALUE }
                    .thenBy { (key, _) -> targetOrder[key] ?: Int.MAX_VALUE },
            ).map { it.first }
    }

    private fun consumeCategoryQuotaRotation(categoryCount: Int): Int {
        if (categoryCount <= 0) {
            return 0
        }
        val storedCursor = sharedPreferences.getInt(KEY_CATEGORY_QUOTA_CURSOR, 0)
        val normalizedCursor = ((storedCursor % categoryCount) + categoryCount) % categoryCount
        sharedPreferences.edit {
            putInt(KEY_CATEGORY_QUOTA_CURSOR, (normalizedCursor + 1) % categoryCount)
        }
        return normalizedCursor
    }

    private fun rebalanceOverQuotaCategories(
        entries: List<YouTubeCacheEntity>,
        targets: Map<String, Int>,
    ): RebalanceOutcome {
        if (targets.isEmpty()) {
            return RebalanceOutcome(emptyList(), emptyList())
        }

        val targetKeys = targets.keys.toSet()
        val buckets = targetKeys.associateWith { mutableListOf<YouTubeCacheEntity>() }
        entries.forEach { entry ->
            resolveCategoryKey(entry, targetKeys)?.let { key ->
                buckets[key]?.add(entry)
            }
        }

        val playbackCutoff = recentPlaybackCutoff()
        val comparator =
            compareBy<YouTubeCacheEntity> { entry ->
                if (entry.lastPlayedAt >= playbackCutoff) 1 else 0
            }.thenBy { entry -> entry.searchCachedAt }
                .thenBy { entry -> cachedEntryScore(entry) }

        val countsAfterEviction = mutableMapOf<String, Int>()
        val evictedEntries = mutableListOf<YouTubeCacheEntity>()

        buckets.forEach { (category, bucket) ->
            val currentCount = bucket.size
            val targetCount = targets.getValue(category)
            val surplus = (currentCount - targetCount).coerceAtLeast(0)
            if (surplus > 0) {
                val candidates = bucket.sortedWith(comparator).take(surplus)
                evictedEntries += candidates
            }
            countsAfterEviction[category] = (currentCount - surplus).coerceAtLeast(0)
        }

        val deficitCategories = computeDeficitPriorityList(targets, countsAfterEviction)
        return RebalanceOutcome(evictedEntries.map { it.videoId }.distinct(), deficitCategories)
    }

    private fun resolveCategoryKey(entry: YouTubeCacheEntity, allowedKeys: Set<String>): String? {
        if (allowedKeys.isEmpty()) {
            return null
        }
        val explicitKey = entry.categoryKey.takeIf { it.isNotBlank() }
        val queryMappedKey = QueryFormulaEngine.categoryForQuery(entry.searchQuery.orEmpty())?.key
        val metadataInferredKey =
            inferCategoryKeyFromMetadata(
                title = entry.title,
                uploader = entry.uploaderName,
                allowedKeys = allowedKeys,
            )
        return (explicitKey ?: queryMappedKey ?: metadataInferredKey)?.takeIf { it in allowedKeys }
    }

    private fun inferCategoryKeyFromMetadata(
        title: String,
        uploader: String,
        allowedKeys: Set<String>,
    ): String? {
        if (allowedKeys.isEmpty()) {
            return null
        }
        return QueryFormulaEngine.ContentCategory.entries
            .asSequence()
            .filter { category -> category.key in allowedKeys }
            .map { category ->
                category to QueryFormulaEngine.categoryMatchScore(
                    title = title,
                    uploader = uploader,
                    category = category,
                )
            }.maxByOrNull { (_, score) -> score }
            ?.takeIf { (_, score) -> score > 0 }
            ?.first
            ?.key
    }

    private fun resolveCandidateCategoryKey(
        candidate: SearchCandidate,
        title: String,
        uploaderName: String,
    ): String {
        candidate.category?.key?.takeIf { it.isNotBlank() }?.let { return it }
        QueryFormulaEngine.categoryForQuery(candidate.searchQuery)?.key?.let { return it }
        return inferCategoryKeyFromMetadata(
            title = title,
            uploader = uploaderName,
            allowedKeys = ALL_CATEGORY_KEYS,
        ).orEmpty()
    }

    private fun cachedEntryScore(entry: YouTubeCacheEntity): Int {
        val title = entry.title
        val normalizedTitle = title.lowercase()
        val qualitySignalScore = QueryFormulaEngine.qualitySignals.count { normalizedTitle.contains(it) }
        val durationScore =
            when {
                entry.durationSeconds > LONG_FORM_DURATION_SECONDS -> LONG_FORM_BONUS + VERY_LONG_FORM_BONUS
                entry.durationSeconds > MEDIUM_FORM_DURATION_SECONDS -> LONG_FORM_BONUS
                else -> 0
            }
        val category =
            QueryFormulaEngine.ContentCategory.entries.firstOrNull { category -> category.key == entry.categoryKey }
                ?: QueryFormulaEngine.categoryForQuery(entry.searchQuery.orEmpty())
        val categoryScore = QueryFormulaEngine.categoryMatchScore(entry.title, entry.uploaderName, category)
        val penaltyScore =
            (if (isVlogLikeTitle(title)) VLOG_TITLE_PENALTY else 0) +
                (if (isDigitHeavyChannelName(entry.uploaderName)) DIGIT_HEAVY_CHANNEL_PENALTY else 0)
        return qualitySignalScore + durationScore + categoryScore - penaltyScore
    }

    private fun rebalanceEntriesToCategoryTargets(
        entries: List<YouTubeCacheEntity>,
        enabledCategoryKeys: List<String>,
        totalSlots: Int,
    ): List<YouTubeCacheEntity> {
        if (entries.isEmpty() || enabledCategoryKeys.isEmpty()) {
            return entries.take(totalSlots)
        }

        val targets = allocateCategoryTargets(enabledCategoryKeys, totalSlots)
        if (targets.isEmpty()) {
            return entries.take(totalSlots)
        }

        val targetKeys = targets.keys.toSet()
        val buckets = targets.keys.associateWith { mutableListOf<YouTubeCacheEntity>() }.toMutableMap()
        val uncategorized = mutableListOf<YouTubeCacheEntity>()
        entries.forEach { entry ->
            val key = resolveCategoryKey(entry, targetKeys)
            if (key == null) {
                uncategorized += entry
            } else {
                buckets.getValue(key) += entry
            }
        }

        val selected = mutableListOf<YouTubeCacheEntity>()
        val seenIds = mutableSetOf<String>()
        targets.forEach { (key, quota) ->
            if (quota <= 0) return@forEach
            val bucket = buckets.getValue(key)
            bucket.asSequence()
                .filter { entry -> seenIds.add(entry.videoId) }
                .take(quota)
                .forEach(selected::add)
        }

        if (selected.size >= totalSlots) {
            return selected.take(totalSlots)
        }

        val overflowBuckets =
            targets.keys.associateWith { key ->
                ArrayDeque(
                    buckets.getValue(key).filter { entry -> entry.videoId !in seenIds },
                )
            }
        val uncategorizedOverflow = ArrayDeque(uncategorized.filter { entry -> entry.videoId !in seenIds })

        while (selected.size < totalSlots) {
            var addedAny = false
            targets.keys.forEach { key ->
                if (selected.size >= totalSlots) {
                    return@forEach
                }
                val bucket = overflowBuckets.getValue(key)
                while (bucket.isNotEmpty()) {
                    val candidate = bucket.removeFirst()
                    if (seenIds.add(candidate.videoId)) {
                        selected += candidate
                        addedAny = true
                        break
                    }
                }
            }
            if (!addedAny) {
                break
            }
        }

        while (selected.size < totalSlots && uncategorizedOverflow.isNotEmpty()) {
            val candidate = uncategorizedOverflow.removeFirst()
            if (seenIds.add(candidate.videoId)) {
                selected += candidate
            }
        }
        return selected.take(totalSlots)
    }

    private fun categoryPreferencesForKeys(categoryKeys: Set<String>): QueryFormulaEngine.CategoryPreferences =
        QueryFormulaEngine.CategoryPreferences(
            categoryNature = QueryFormulaEngine.ContentCategory.NATURE.key in categoryKeys,
            categoryAnimals = QueryFormulaEngine.ContentCategory.ANIMALS.key in categoryKeys,
            categoryDrone = QueryFormulaEngine.ContentCategory.DRONE.key in categoryKeys,
            categoryCities = QueryFormulaEngine.ContentCategory.CITIES.key in categoryKeys,
            categorySpace = QueryFormulaEngine.ContentCategory.SPACE.key in categoryKeys,
            categoryOcean = QueryFormulaEngine.ContentCategory.OCEAN.key in categoryKeys,
            categoryWeather = QueryFormulaEngine.ContentCategory.WEATHER.key in categoryKeys,
            categoryWinter = QueryFormulaEngine.ContentCategory.WINTER.key in categoryKeys,
        )

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

    private fun enabledCategoryKeys(): List<String> =
        QueryFormulaEngine.ContentCategory.entries.filter { category ->
            categoryPreferences().isEnabled(category)
        }.map { category -> category.key }

    private fun currentFilteredCount(): Int =
        filteredExistingEntries(cacheDao.getAllGood()).size

    private fun applyCurrentCategoryFilterInternal(): Int {
        val enabledKeys = enabledCategoryKeys().toSet()
        if (enabledKeys.isEmpty()) {
            val removed = cacheDao.countGoodEntries()
            if (removed > 0) {
                cacheDao.clearAllGood()
            }
            return removed
        }
        val removableIds = removableCategoryVideoIds(cacheDao.getAllGood(), enabledKeys)
        if (removableIds.isEmpty()) {
            return 0
        }
        return cacheDao.deleteByVideoIds(removableIds)
    }

    private fun removableCategoryVideoIds(
        entries: List<YouTubeCacheEntity>,
        enabledKeys: Set<String>,
    ): List<String> =
        entries.mapNotNull { entry ->
            val resolvedCategoryKey =
                resolveCategoryKey(
                    entry = entry,
                    allowedKeys = ALL_CATEGORY_KEYS,
                )
            if (resolvedCategoryKey != null && resolvedCategoryKey !in enabledKeys) {
                entry.videoId
            } else {
                null
            }
        }

    private fun filteredExistingEntries(entries: List<YouTubeCacheEntity>): List<YouTubeCacheEntity> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val enabledCategories = enabledCategoryKeys().toSet()
        if (enabledCategories.isEmpty()) {
            return emptyList()
        }
        return entries.filter { entry ->
            val resolvedCategoryKey =
                resolveCategoryKey(
                    entry = entry,
                    allowedKeys = ALL_CATEGORY_KEYS,
                )
            val categoryAllowed = resolvedCategoryKey == null || resolvedCategoryKey in enabledCategories
            categoryAllowed
        }
    }

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

    private fun shouldShuffle(): Boolean =
        sharedPreferences.getBoolean(KEY_SHUFFLE, DEFAULT_SHUFFLE)

    private fun shouldPreferVideoOnly(): Boolean =
        true

    private fun playbackResolutionQuality(fallbackQuality: String = preferredQuality()): String =
        fallbackQuality

    private fun playbackPreferVideoOnly(): Boolean =
        shouldPreferVideoOnly()

    private fun streamMode(): String =
        "video_only_preferred"

    private fun updateCachedCount(count: Int) {
        if (isRefreshing) return
        _cacheCount.value = count
        sharedPreferences.edit {
            putString(KEY_COUNT, count.toString())
        }
    }

    private fun markCategoryStateFresh(count: Int) {
        val signature = categorySignature()
        sharedPreferences.edit {
            putString(KEY_COUNT, count.toString())
            putString(KEY_CACHE_SIGNATURE, signature)
            putStringSet(KEY_CATEGORY_SNAPSHOT, enabledCategoryKeys().toSet())
            putInt(KEY_CACHE_VERSION, CURRENT_CACHE_VERSION)
            putLong("yt_last_search_at", System.currentTimeMillis())
        }
        _cacheCount.value = count
        Timber.tag(TAG).d("Marked YouTube cache fresh: count=%s, signature=\"%s\"", count, signature)
    }

    private fun markSearchCacheFresh(count: Int) {
        markCategoryStateFresh(count)
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

    private fun deduplicateCandidatesByVideoId(candidates: List<SearchCandidate>): List<SearchCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val dedupedByVideoId = linkedMapOf<String, SearchCandidate>()
        candidates.forEach { candidate ->
            val candidateUrl = candidate.item.getUrl().takeIf { it.isNotBlank() } ?: return@forEach
            val key = extractVideoId(candidateUrl) ?: candidateUrl
            dedupedByVideoId.putIfAbsent(key, candidate)
        }
        return dedupedByVideoId.values.toList()
    }

    private fun deduplicateEntriesByVideoId(entries: List<YouTubeCacheEntity>): List<YouTubeCacheEntity> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val deduped = linkedMapOf<String, YouTubeCacheEntity>()
        entries.forEach { entry ->
            deduped.putIfAbsent(entry.videoId, entry)
        }
        return deduped.values.toList()
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
        val preferredQuality: String,
        val cachedAt: Long,
        val entropySeed: Long,
        val existingEntries: List<YouTubeCacheEntity>,
        val recentRefreshIds: Set<String>,
        val isColdStart: Boolean,
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
        const val KEY_CATEGORY_SNAPSHOT = "yt_category_snapshot"
        private const val KEY_CATEGORY_QUOTA_CURSOR = "yt_category_quota_cursor"
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
        const val DEFAULT_MIX_WEIGHT = "1"
        const val DEFAULT_SHUFFLE = true
        private const val DEFAULT_MUTE_VIDEOS = true
        private const val DEFAULT_CATEGORY_NATURE = true
        private const val DEFAULT_CATEGORY_ANIMALS = true
        private const val DEFAULT_CATEGORY_DRONE = true
        private const val DEFAULT_CATEGORY_CITIES = true
        private const val DEFAULT_CATEGORY_SPACE = true
        private const val DEFAULT_CATEGORY_OCEAN = true
        private const val DEFAULT_CATEGORY_WEATHER = true
        private const val DEFAULT_CATEGORY_WINTER = true
        private val ALL_CATEGORY_KEYS = QueryFormulaEngine.ContentCategory.entries.map { it.key }.toSet()

        private const val TARGET_CACHE_SIZE = 200
        private const val EXTRACTION_TARGET_SIZE = 300
        private const val MIN_HEALTHY_CACHE_SIZE = 200
        private const val TARGET_CANDIDATE_POOL_SIZE = 600
        private const val EXTRACTION_BATCH_SIZE = 4
        private const val CATEGORY_DELTA_QUERY_COUNT_PER_CATEGORY = 12
        private const val CATEGORY_DELTA_QUERY_TO_VIDEO_RATIO = 4
        private const val MAX_CATEGORY_DELTA_QUERY_COUNT = 120
        private const val CATEGORY_DELTA_FETCH_ATTEMPTS = 3
        private const val CATEGORY_DELTA_BACKFILL_ROUNDS = 3
        private const val CATEGORY_DELTA_FALLBACK_BATCH_PER_CATEGORY = 6
        private const val CATEGORY_DELTA_EXTRACTION_LIMIT = 300
        private const val FULL_REFRESH_TOPUP_ROUNDS = 5
        private const val FULL_REFRESH_TOPUP_BATCH_PER_CATEGORY = 12
        private const val MAX_STREAM_URL_REFRESHES_PER_WARM = 24
        // Bounded search concurrency (do not fan out one worker per category).
        private const val QUERY_SEARCH_BATCH_SIZE = 4
        private const val COLD_START_QUERY_POOL_SIZE = 10
        private const val QUERY_POOL_SIZE = 25
        private const val FALLBACK_QUERY_POOL_SIZE = 12
        private const val SUPPLEMENTAL_QUERY_POOL_SIZE = 16
        private const val MAX_PLAY_HISTORY = 320
        private const val MAX_WATCH_HISTORY_ROWS = 5_000
        private const val MAX_RECENT_REFRESH_IDS = 960
        private const val MIN_HEALTHY_CANDIDATE_POOL_SIZE = 250
        private const val BACKGROUND_PREWARM_REMAINING_ITEMS = 60
        private const val FORCE_REFRESH_REMAINING_ITEMS = 50
        private const val EMERGENCY_REFILL_REMAINING_ITEMS = 25
        private const val MINIMUM_VIABLE_CACHE_SIZE = 10
        private const val COLD_CACHE_SKIP_THRESHOLD = 5
        private const val BACKGROUND_REFRESH_COOLDOWN_MS = 10L * 60L * 1000L
        private const val SEARCH_CALL_TIMEOUT_MS = 20_000L
        private const val EXTRACTION_CALL_TIMEOUT_MS = 25_000L
        private const val SEARCH_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        // YouTube stream URLs typically expire around the 6 hour mark.
        private const val STREAM_URL_TTL_MS = 5L * 60L * 60L * 1000L + 30L * 60L * 1000L
        private const val STREAM_REEXTRACT_BUFFER_MS = 30L * 60L * 1000L
        private const val RECENT_PLAYBACK_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_PLAYBACK_RESOLVE_ATTEMPTS = 5
        private const val BAD_ENTRY_REFRESH_THRESHOLD = 10
        private const val CURRENT_CACHE_VERSION = 29
        private const val MIN_ACCEPTABLE_CACHED_STREAM_HEIGHT = 720
        private const val HISTORY_SEPARATOR = "|"
        private const val DEFAULT_CATEGORY_KEY = "__uncategorized__"
        private const val MIN_MAIN_SEARCH_UNIQUE_VIDEOS = 180
        private const val MAX_VIDEOS_PER_CHANNEL = 7
        private const val MAX_VIDEOS_PER_QUERY_BUCKET = 10
        private const val INITIAL_THEME_ROUND_ROBIN_CAP = 40
        private const val LAST_VIDEO_EXCLUSION_COUNT = 50
        private const val RELAXED_LAST_VIDEO_EXCLUSION_COUNT = 30
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
        private val HEIGHT_HINT_REGEX = Regex("(\\d{3,4})p")
        private val QUALITY_LABEL_HEIGHT_HINTS =
            mapOf(
                "hd2160" to 2160,
                "hd1440" to 1440,
                "hd1080" to 1080,
                "hd720" to 720,
                "large" to 480,
                "medium" to 360,
                "small" to 240,
                "tiny" to 144,
            )
        private val STREAM_ITAG_HEIGHT_HINTS =
            mapOf(
                5 to 240,
                6 to 270,
                13 to 144,
                17 to 144,
                18 to 360,
                34 to 360,
                36 to 240,
                43 to 360,
                82 to 360,
                83 to 480,
                92 to 240,
                93 to 360,
                94 to 480,
                100 to 360,
                101 to 480,
                132 to 240,
                133 to 240,
                134 to 360,
                160 to 144,
                242 to 240,
                243 to 360,
                278 to 144,
                394 to 144,
                395 to 240,
                396 to 360,
            )

        private const val LONG_TAIL_QUERY_COUNT = 16

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
