package com.neilturner.aerialviews.models

import com.neilturner.aerialviews.models.videos.AerialMedia
import kotlin.random.Random

class MediaPlaylist(
    videos: List<AerialMedia>,
    private val reshuffleOnWrap: Boolean = false,
    private val random: Random = Random.Default,
) {
    private val items = videos.toMutableList()
    private var position = -1
    private var nextCycleItems: List<AerialMedia>? = null

    val size: Int = items.size

    fun nextItem(): AerialMedia {
        if (items.isEmpty()) {
            throw NoSuchElementException("Playlist is empty")
        }

        if (position + 1 >= items.size) {
            prepareNextCycle()
            applyNextCycleIfAvailable()
            position = -1
        }

        position += 1
        return items[position]
    }

    fun previousItem(): AerialMedia {
        if (items.isEmpty()) {
            throw NoSuchElementException("Playlist is empty")
        }

        position = calculateNext(--position)
        return items[position]
    }

    fun peekNextItem(): AerialMedia {
        if (items.isEmpty()) {
            throw NoSuchElementException("Playlist is empty")
        }

        val nextPosition = position + 1
        if (nextPosition < items.size) {
            return items[nextPosition]
        }

        val nextCycle = previewNextCycle()
        return nextCycle.firstOrNull() ?: items.first()
    }

    fun peekPreviousItem(): AerialMedia {
        if (items.isEmpty()) {
            throw NoSuchElementException("Playlist is empty")
        }

        val previousPosition = calculateNext(position - 1)
        return items[previousPosition]
    }

    fun remainingUntilWrap(): Int {
        if (items.isEmpty()) {
            return 0
        }

        return (items.size - 1 - position).coerceAtLeast(0)
    }

    private fun calculateNext(number: Int): Int {
        val next =
            if (number < 0) {
                items.size + number
            } else {
                (number).rem(items.size)
            }
        return next
    }

    private fun previewNextCycle(): List<AerialMedia> {
        if (!reshuffleOnWrap || items.size < 2) {
            return items
        }

        return nextCycleItems ?: buildNextCycleItems().also { nextCycleItems = it }
    }

    private fun prepareNextCycle() {
        if (!reshuffleOnWrap || items.size < 2) {
            return
        }

        if (nextCycleItems == null) {
            nextCycleItems = buildNextCycleItems()
        }
    }

    private fun applyNextCycleIfAvailable() {
        val nextCycle = nextCycleItems ?: return
        items.clear()
        items.addAll(nextCycle)
        nextCycleItems = null
    }

    private fun buildNextCycleItems(): List<AerialMedia> {
        val shuffled = items.shuffled(random).toMutableList()
        val currentItem = items.getOrNull(position) ?: return shuffled
        if (shuffled.size > 1 && shuffled.first() == currentItem) {
            shuffled.add(shuffled.removeAt(0))
        }
        return shuffled
    }
}
