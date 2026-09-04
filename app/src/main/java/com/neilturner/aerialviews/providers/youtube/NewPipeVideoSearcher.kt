package com.neilturner.aerialviews.providers.youtube

import org.schabi.newpipe.extractor.stream.StreamInfoItem

class NewPipeVideoSearcher : VideoSearcher {
    override suspend fun searchVideos(
        query: String,
        category: QueryFormulaEngine.ContentCategory?,
    ): List<StreamInfoItem> = NewPipeHelper.searchVideos(query = query, category = category)
}
