package com.neilturner.aerialviews.ui.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber

class YouTubeSettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = YouTubeFeature.repository(application)
    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    private val _cacheSize = MutableStateFlow(-1)
    private var backgroundRefreshJob: Job? = null

    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()
    val cacheSize: StateFlow<Int> = _cacheSize.asStateFlow()

    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<YouTubeSettingsEvent>()
    val events: kotlinx.coroutines.flow.SharedFlow<YouTubeSettingsEvent> = _events.asSharedFlow()

    sealed interface YouTubeSettingsEvent {
        data class CategoryRemoved(
            val removedCount: Int,
            val remainingCount: Int,
        ) : YouTubeSettingsEvent

        data object AllCategoriesDisabled : YouTubeSettingsEvent
        data object LibraryFullOnCategory : YouTubeSettingsEvent
    }

    init {
        YouTubeFeature.preWarmIfNeeded(viewModelScope)
        viewModelScope.launch {
            repository.cacheCount.collect { count ->
                _cacheSize.value = count
            }
        }
        refreshCacheSize()
    }

    fun refreshIfCachePending() {
        if (_refreshState.value == RefreshState.Loading) {
            return
        }

        viewModelScope.launch {
            if (repository.getCacheSize() <= 0) {
                refreshInBackground()
            }
        }
    }

    fun forceRefresh() {
        if (_refreshState.value == RefreshState.Loading) {
            return
        }

        viewModelScope.launch {
            _refreshState.value = RefreshState.Loading
            // Diagnostic: immediately emit 0% progress to confirm the flow and observer are working
            repository.publishProgress(0, 200)
            
            _refreshState.value =
                try {
                    repository.forceRefresh().let(RefreshState::Success)
                } catch (exception: Exception) {
                    Timber.e(exception, "YouTube refresh failed")
                    RefreshState.Error
                }
        }
    }

    fun triggerInitialProgress() {
        viewModelScope.launch {
            repository.publishProgress(0, 200)
        }
    }

    fun refreshNow() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.forceRefreshDirect()
        }
    }

    fun refreshInBackground() {
        YouTubeFeature.requestImmediateRefresh(getApplication(), forceSearchRefresh = true)
    }

    fun onCategoryChanged() {
        backgroundRefreshJob?.cancel()
        backgroundRefreshJob =
            viewModelScope.launch {
                runCatching {
                    val result = repository.applyCategoryDeltaRefresh()
                    if (result.allCategoriesDisabled) {
                        _events.emit(YouTubeSettingsEvent.AllCategoriesDisabled)
                    } else if (result.removedCount > 0) {
                        _events.emit(
                            YouTubeSettingsEvent.CategoryRemoved(
                                removedCount = result.removedCount,
                                remainingCount = result.finalCount,
                            ),
                        )
                    } else if (result.insertedCount == 0 && result.libraryFull) {
                        // User tried to turn on a category but we didn't insert anything because cache is full
                        _events.emit(YouTubeSettingsEvent.LibraryFullOnCategory)
                    }
                }.onFailure { exception ->
                    Timber.e(exception, "Failed to apply YouTube category delta refresh")
                }
            }
    }

    fun scheduleBackgroundRefresh(delayMs: Long = 750L) {
        backgroundRefreshJob?.cancel()
        backgroundRefreshJob =
            viewModelScope.launch {
                if (delayMs > 0) {
                    kotlinx.coroutines.delay(delayMs)
                }
                refreshInBackground()
            }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = repository.getCacheSize()
        }
    }

    fun clearRefreshState() {
        _refreshState.value = RefreshState.Idle
    }
}

sealed interface RefreshState {
    data object Idle : RefreshState

    data object Loading : RefreshState

    data class Success(
        val count: Int,
    ) : RefreshState

    data object Error : RefreshState
}
