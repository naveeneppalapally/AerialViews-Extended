package com.neilturner.aerialviews.models

import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.videos.AerialMedia
import io.mockk.mockk
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

internal class MediaPlaylistTest {
    @Test
    fun `reshuffle on wrap changes cycle order`() {
        val items =
            listOf(
                media("one"),
                media("two"),
                media("three"),
            )

        val playlist = MediaPlaylist(items, reshuffleOnWrap = true, random = Random(7))
        val firstCycle = listOf(playlist.nextItem(), playlist.nextItem(), playlist.nextItem())
        val secondCycle = listOf(playlist.nextItem(), playlist.nextItem(), playlist.nextItem())

        assertEquals(3, firstCycle.distinct().size)
        assertEquals(3, secondCycle.distinct().size)
        assertNotEquals(firstCycle, secondCycle)
    }

    @Test
    fun `peek next item previews reshuffled cycle`() {
        val items =
            listOf(
                media("one"),
                media("two"),
            )

        val playlist = MediaPlaylist(items, reshuffleOnWrap = true, random = Random(3))
        val first = playlist.nextItem()
        val second = playlist.nextItem()
        val preview = playlist.peekNextItem()
        val wrapped = playlist.nextItem()

        assertEquals(second, items[1])
        assertEquals(preview, wrapped)
    }

    private fun media(id: String) =
        AerialMedia(
            uri = mockk(name = id),
            type = AerialMediaType.VIDEO,
            source = AerialMediaSource.YOUTUBE,
        )
}
