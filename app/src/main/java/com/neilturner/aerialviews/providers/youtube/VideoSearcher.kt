package com.neilturner.aerialviews.providers.youtube

import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Search seam. Production hits the network via NewPipe; tests plug in fakes,
 * which is what makes the refresh pipeline testable offline.
 */
interface VideoSearcher {
    suspend fun searchVideos(
        query: String,
        category: QueryFormulaEngine.ContentCategory?,
    ): List<StreamInfoItem>
}
