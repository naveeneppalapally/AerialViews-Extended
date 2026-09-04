package com.neilturner.aerialviews.providers.youtube

import java.util.ArrayDeque
import kotlin.random.Random

/**
 * Pure playback-ordering logic: repeat windows, exclusions, weighted picks,
 * first-launch sequencing and theme detection. No DAO, no prefs, no network —
 * every input arrives as a parameter, which is what makes this unit-testable
 * without fakes. The repository supplies state and owns the DB fallbacks.
 */
object PlaylistOrderer {
    data class PlaybackExclusions(
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

    data class PlaylistSimulation(
        val history: ArrayDeque<String>,
        val themeHistory: ArrayDeque<String>,
        var lastChannel: String,
        var firstLaunchActive: Boolean,
        var firstLaunchIndex: Int,
        val random: Random,
    ) {
        fun record(
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
    }

    fun trimHistory(
        values: ArrayDeque<String>,
        maxSize: Int,
    ) {
        while (values.size > maxSize) {
            values.removeFirst()
        }
    }

    fun detectTheme(title: String): String {
        val lower = title.lowercase()
        return LOCATION_THEMES.entries
            .firstOrNull { (_, keywords) ->
                keywords.any { keyword -> lower.contains(keyword) }
            }?.key ?: "other"
    }

    fun applyRepeatWindow(
        entries: List<YouTubeCacheEntity>,
        recentPlaybackCutoff: Long,
    ): List<YouTubeCacheEntity> {
        val unwatchedEntries =
            entries.filter { entry ->
                entry.lastPlayedAt == 0L || entry.lastPlayedAt < recentPlaybackCutoff
            }

        return when {
            unwatchedEntries.isNotEmpty() -> unwatchedEntries
            entries.isNotEmpty() -> entries.sortedBy { entry -> entry.lastPlayedAt }
            else -> emptyList()
        }
    }

    fun applyPlaybackExclusions(
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

    fun resolvePlaybackCandidates(
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

    fun weightedRandomPick(
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

    fun getFirstLaunchVideo(
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

    /**
     * Pure candidate pick. Returns null when nothing matches so the caller
     * (repository) can apply its DB fallbacks.
     */
    fun pickCandidate(
        entries: List<YouTubeCacheEntity>,
        playbackHistory: List<String>,
        recentThemes: List<String>,
        lastChannel: String,
        firstLaunchActive: Boolean,
        firstLaunchSequenceIndex: Int,
        recentPlaybackCutoff: Long,
        random: Random,
    ): YouTubeCacheEntity? {
        val goodEntries = entries.filterNot { it.isBad }
        if (goodEntries.isEmpty()) {
            return null
        }

        val repeatWindowCandidates = applyRepeatWindow(goodEntries, recentPlaybackCutoff)
        val baseEntries = repeatWindowCandidates.ifEmpty { goodEntries }

        val exclusions = PlaybackExclusions(playbackHistory, recentThemes, lastChannel)

        if (firstLaunchActive && baseEntries.size >= MIN_FIRST_LAUNCH_CANDIDATES) {
            getFirstLaunchVideo(baseEntries, firstLaunchSequenceIndex, exclusions.strictVideoIds, random)?.let { return it }
        }

        val finalCandidates = resolvePlaybackCandidates(baseEntries, exclusions)
        val immediateRepeatSafeCandidates =
            playbackHistory.lastOrNull()?.let { lastPlayedVideoId ->
                finalCandidates.filterNot { it.videoId == lastPlayedVideoId }.ifEmpty { finalCandidates }
            } ?: finalCandidates

        return weightedRandomPick(immediateRepeatSafeCandidates, playbackHistory, random)
    }

    const val MAX_PLAY_HISTORY = 320
    const val MAX_THEME_HISTORY = 12
    const val MIN_FIRST_LAUNCH_CANDIDATES = 12
    const val LAST_VIDEO_EXCLUSION_COUNT = 50
    const val RELAXED_LAST_VIDEO_EXCLUSION_COUNT = 30
    const val LAST_THEME_EXCLUSION_COUNT = 3
    const val MIN_STRICT_PLAYBACK_CANDIDATES = 10
    const val UNPLAYED_WEIGHT = 3
    const val SINGLE_PLAY_WEIGHT = 2
    const val REPEAT_WEIGHT = 1

    val FIRST_LAUNCH_SEQUENCE =
        listOf(
            "space",
            "ocean",
            "forest",
            "mountain",
            "other",
        )

    val LOCATION_THEMES =
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
