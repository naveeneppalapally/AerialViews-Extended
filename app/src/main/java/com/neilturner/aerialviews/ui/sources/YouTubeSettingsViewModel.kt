package com.neilturner.aerialviews.ui.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
            _refreshState.value =
                try {
                    repository.forceRefresh().let(RefreshState::Success)
                } catch (exception: Exception) {
                    Timber.e(exception, "YouTube refresh failed")
                    RefreshState.Error
                }
        }
    }

    fun refreshInBackground() {
        YouTubeFeature.requestImmediateRefresh(getApplication(), forceSearchRefresh = true)
    }

    fun onCategoryChanged() {
        applyFiltersThenScheduleRefresh()
    }

    fun onMinimumDurationChanged() {
        applyFiltersThenScheduleRefresh()
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

    private fun applyFiltersThenScheduleRefresh() {
        backgroundRefreshJob?.cancel()
        backgroundRefreshJob =
            viewModelScope.launch {
                runCatching {
                    repository.applyCurrentFilters()
                }.onFailure { exception ->
                    Timber.e(exception, "Failed to apply YouTube cache filters")
                }
                refreshInBackground()
            }
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
