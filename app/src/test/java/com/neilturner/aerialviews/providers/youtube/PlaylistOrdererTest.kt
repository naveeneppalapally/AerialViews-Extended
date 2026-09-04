package com.neilturner.aerialviews.providers.youtube

import java.util.ArrayDeque
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Playlist Orderer Tests")
internal class PlaylistOrdererTest {
    @Test
    @DisplayName("Should never pick the just-played video when alternatives exist")
    fun testAvoidsImmediateRepeat() {
        val entries = listOf(
            entry("videoA", "Forest trail"),
            entry("videoB", "Mountain valley"),
        )

        val picked =
            PlaylistOrderer.pickCandidate(
                entries = entries,
                playbackHistory = listOf("videoA"),
                recentThemes = emptyList(),
                lastChannel = "",
                firstLaunchActive = false,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            )

        assertEquals("videoB", picked?.videoId)
    }

    @Test
    @DisplayName("Should return null for empty or all-bad entries")
    fun testNullWhenNothingPlayable() {
        assertNull(
            PlaylistOrderer.pickCandidate(
                entries = emptyList(),
                playbackHistory = emptyList(),
                recentThemes = emptyList(),
                lastChannel = "",
                firstLaunchActive = false,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            ),
        )
        assertNull(
            PlaylistOrderer.pickCandidate(
                entries = listOf(entry("videoA", "Forest trail", isBad = true)),
                playbackHistory = emptyList(),
                recentThemes = emptyList(),
                lastChannel = "",
                firstLaunchActive = false,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            ),
        )
    }

    @Test
    @DisplayName("Should prefer unseen themes and channels while alternatives exist")
    fun testStrictExclusionsApply() {
        // Strict tier needs MIN_STRICT_PLAYBACK_CANDIDATES (10) to hold;
        // below that the logic intentionally relaxes.
        val entries =
            (1..12).map { index ->
                entry("videoForest$index", "Forest trail pines $index", uploader = "Woodland Films")
            } + listOf(
                entry("videoOcean", "Ocean waves beach", uploader = "Sea Channel"),
                entry("videoSameChannel", "Forest trail", uploader = "Sea Channel"),
            )

        val picked =
            PlaylistOrderer.pickCandidate(
                entries = entries,
                playbackHistory = emptyList(),
                recentThemes = listOf("ocean"),
                lastChannel = "Sea Channel",
                firstLaunchActive = false,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            )

        assertTrue(picked != null && picked.videoId.startsWith("videoForest"))
    }

    @Test
    @DisplayName("Should open first launch with the sequenced theme")
    fun testFirstLaunchSequence() {
        // First-launch sequencing needs MIN_FIRST_LAUNCH_CANDIDATES (12).
        val entries =
            (1..12).map { index ->
                entry("videoForest$index", "Forest trail pines $index")
            } + listOf(entry("videoSpace", "Earth from space ISS view"))

        val picked =
            PlaylistOrderer.pickCandidate(
                entries = entries,
                playbackHistory = emptyList(),
                recentThemes = emptyList(),
                lastChannel = "",
                firstLaunchActive = true,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            )

        assertEquals("videoSpace", picked?.videoId)
    }

    @Test
    @DisplayName("Should detect location themes from titles")
    fun testDetectTheme() {
        assertEquals("japan", PlaylistOrderer.detectTheme("Tokyo tower evening lights"))
        assertEquals("desert", PlaylistOrderer.detectTheme("Sahara dunes sunrise"))
        assertEquals("other", PlaylistOrderer.detectTheme("Gentle meadow breeze"))
    }

    @Test
    @DisplayName("Should restrict the repeat window to unwatched entries")
    fun testApplyRepeatWindow() {
        val now = System.currentTimeMillis()
        val entries = listOf(
            entry("playedRecent", "Forest trail", lastPlayedAt = now),
            entry("unplayed", "Mountain valley", lastPlayedAt = 0L),
        )

        val windowed = PlaylistOrderer.applyRepeatWindow(entries, recentPlaybackCutoff = now - 1_000L)

        assertEquals(listOf("unplayed"), windowed.map { it.videoId })
    }

    @Test
    @DisplayName("Simulation record should advance history and first-launch state")
    fun testSimulationRecord() {
        val simulation =
            PlaylistOrderer.PlaylistSimulation(
                history = ArrayDeque(),
                themeHistory = ArrayDeque(),
                lastChannel = "",
                firstLaunchActive = true,
                firstLaunchIndex = 0,
                random = Random(0),
            )

        simulation.record(entry("videoA", "Forest trail", uploader = "Woodland Films"), "forest")

        assertTrue(simulation.history.contains("videoA"))
        assertTrue(simulation.themeHistory.contains("forest"))
        assertEquals("Woodland Films", simulation.lastChannel)
        assertEquals(1, simulation.firstLaunchIndex)
    }

    private fun entry(
        videoId: String,
        title: String,
        uploader: String = "Some Channel",
        lastPlayedAt: Long = 0L,
        isBad: Boolean = false,
    ): YouTubeCacheEntity =
        YouTubeCacheEntity(
            videoId = videoId,
            videoPageUrl = "https://www.youtube.com/watch?v=$videoId",
            streamUrl = "https://cdn.example.com/$videoId.mp4",
            title = title,
            uploaderName = uploader,
            durationSeconds = 600,
            categoryKey = "nature",
            streamUrlExpiresAt = System.currentTimeMillis() + 86_400_000L,
            searchCachedAt = System.currentTimeMillis(),
            searchQuery = "4K aerial nature ambient",
            isBad = isBad,
            lastPlayedAt = lastPlayedAt,
        )
}
