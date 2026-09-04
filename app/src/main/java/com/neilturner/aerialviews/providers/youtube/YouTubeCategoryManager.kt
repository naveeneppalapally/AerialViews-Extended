package com.neilturner.aerialviews.providers.youtube

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Owns category quotas, balance plans, rebalancing, category filtering and
 * the category snapshot prefs. Pure math plus light DAO filter ops; the
 * multi-step delta-refresh orchestration (search/extract/persist) stays with
 * the repository, which calls in here.
 */
class YouTubeCategoryManager(
    private val cacheDao: YouTubeCacheDao,
    private val sharedPreferences: SharedPreferences,
) {
    data class CategoryBalancePlan(
        val targets: Map<String, Int>,
        val deficitCategories: List<String>,
    )

    data class RebalanceOutcome(
        val evictedVideoIds: List<String>,
        val deficitCategories: List<String>,
    )

    data class CategoryRemovalPreview(
        val removedCount: Int,
        val remainingCount: Int,
    )

    fun categoryPreferences(): QueryFormulaEngine.CategoryPreferences =
        YouTubeSettings.read(sharedPreferences).categories

    fun enabledCategoryKeys(): List<String> =
        QueryFormulaEngine.ContentCategory.entries.filter { category ->
            categoryPreferences().isEnabled(category)
        }.map { category -> category.key }

    fun categorySignature(): String =
        QueryFormulaEngine.categorySignature(categoryPreferences())

    fun initializeCategorySnapshotIfNeeded() {
        if (sharedPreferences.contains(KEY_CATEGORY_SNAPSHOT)) {
            return
        }
        persistCategorySnapshot(enabledCategoryKeys().toSet())
    }

    fun readCategorySnapshot(): Set<String> =
        sharedPreferences
            .getStringSet(KEY_CATEGORY_SNAPSHOT, emptySet())
            .orEmpty()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

    fun persistCategorySnapshot(enabledCategoryKeys: Set<String>) {
        sharedPreferences.edit {
            putStringSet(KEY_CATEGORY_SNAPSHOT, enabledCategoryKeys)
        }
    }

    fun resolveCategoryKey(
        entry: YouTubeCacheEntity,
        allowedKeys: Set<String>,
    ): String? {
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

    fun inferCategoryKeyFromMetadata(
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

    fun cachedEntryScore(entry: YouTubeCacheEntity): Int {
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

    fun isVlogLikeTitle(title: String): Boolean {
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

    fun isDigitHeavyChannelName(channelName: String): Boolean {
        val digits = channelName.count(Char::isDigit)
        val letters = channelName.count(Char::isLetter)
        return digits >= 3 && digits >= letters
    }

    fun buildCategoryBalancePlan(
        enabledCategoryKeys: List<String>,
        entries: List<YouTubeCacheEntity>,
        totalSlots: Int,
    ): CategoryBalancePlan {
        val targets = allocateCategoryTargets(enabledCategoryKeys, totalSlots)
        val counts = computeCategoryCounts(entries, targets.keys)
        val deficitCategories = computeDeficitPriorityList(targets, counts)
        return CategoryBalancePlan(targets, deficitCategories)
    }

    fun allocateCategoryTargets(
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

    fun computeCategoryCounts(
        entries: List<YouTubeCacheEntity>,
        targetKeys: Set<String>,
    ): Map<String, Int> {
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

    fun computeDeficitPriorityList(
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

    fun rebalanceOverQuotaCategories(
        entries: List<YouTubeCacheEntity>,
        targets: Map<String, Int>,
        recentPlaybackCutoff: Long,
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

        // Evict already-seen content first (recently played, newest first),
        // keep unseen videos: they cost extraction work and the user never saw them.
        val comparator =
            compareBy<YouTubeCacheEntity> { entry ->
                if (entry.lastPlayedAt >= recentPlaybackCutoff) 0 else 1
            }.thenByDescending { entry -> entry.lastPlayedAt }
                .thenBy { entry -> entry.searchCachedAt }
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

    fun rebalanceEntriesToCategoryTargets(
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

    fun removableCategoryVideoIds(
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

    fun filteredExistingEntries(entries: List<YouTubeCacheEntity>): List<YouTubeCacheEntity> {
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

    suspend fun applyCurrentCategoryFilterInternal(): Int {
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

    suspend fun previewCategoryRemovalSnapshot(): CategoryRemovalPreview {
        val entries = cacheDao.getAllGood()
        val enabledKeys = enabledCategoryKeys().toSet()
        if (enabledKeys.isEmpty()) {
            return CategoryRemovalPreview(
                removedCount = entries.size,
                remainingCount = 0,
            )
        }
        val removableIds = removableCategoryVideoIds(entries, enabledKeys)
        return CategoryRemovalPreview(
            removedCount = removableIds.size,
            remainingCount = (entries.size - removableIds.size).coerceAtLeast(0),
        )
    }

    private fun consumeCategoryQuotaRotation(categoryCount: Int): Int {
        // Time-based rotation: stable for every allocate call within the same
        // refresh (no SharedPrefs writes), varies day to day for fairness.
        if (categoryCount <= 0) {
            return 0
        }
        val dayBucket = System.currentTimeMillis() / DAY_IN_MILLIS
        return (((dayBucket % categoryCount) + categoryCount) % categoryCount).toInt()
    }

    companion object {
        const val KEY_CATEGORY_SNAPSHOT = "yt_category_snapshot"
        val ALL_CATEGORY_KEYS = QueryFormulaEngine.ContentCategory.entries.map { it.key }.toSet()
        const val MEDIUM_FORM_DURATION_SECONDS = 3_600L
        const val LONG_FORM_DURATION_SECONDS = 7_200L
        const val LONG_FORM_BONUS = 2
        const val VERY_LONG_FORM_BONUS = 3
        const val AERIAL_CATEGORY_BONUS = 3
        const val VLOG_TITLE_PENALTY = 6
        const val DIGIT_HEAVY_CHANNEL_PENALTY = 2
        private const val DAY_IN_MILLIS = 24L * 60L * 60L * 1000L
    }
}
