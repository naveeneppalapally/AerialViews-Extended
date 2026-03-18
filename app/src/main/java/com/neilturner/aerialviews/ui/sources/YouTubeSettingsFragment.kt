package com.neilturner.aerialviews.ui.sources

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository
import com.neilturner.aerialviews.services.getDisplay
import com.neilturner.aerialviews.services.supportsUltraHdOutput
import com.neilturner.aerialviews.services.supports1440pOutput
import com.neilturner.aerialviews.utils.DialogHelper
import com.neilturner.aerialviews.utils.MenuStateFragment
import com.neilturner.aerialviews.utils.ToastHelper
import kotlinx.coroutines.launch

class YouTubeSettingsFragment : MenuStateFragment() {
    private val viewModel by viewModels<YouTubeSettingsViewModel>()
    private var refreshInProgress = false
    private var transientCategoryMessage: String? = null
    private var transientCategoryMessageUntilElapsedMs: Long = 0L
    private var transientCategoryRemainingCount: Int? = null
    private val clearTransientCategoryMessageRunnable =
        Runnable {
            transientCategoryMessage = null
            transientCategoryRemainingCount = null
            transientCategoryMessageUntilElapsedMs = 0L
            if (isAdded) {
                renderSettingsState(viewModel.settingsUiState.value)
            }
        }
    private val sharedPreferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                YouTubeSourceRepository.KEY_COUNT -> {
                    viewModel.refreshCacheSize()
                }
                YouTubeSourceRepository.KEY_ENABLED -> {
                    if (!YouTubeVideoPrefs.enabled) {
                        refreshInProgress = false
                        viewModel.setDisplayedCacheSize(0)
                        updateVideoCount(staticCount = 0)
                        updateCacheCountPreference(cachedCount = 0, stage = YouTubeRefreshStage.IDLE, transientMessage = null)
                    } else {
                        viewModel.refreshCacheSize()
                    }
                }
                in CATEGORY_PREFERENCE_KEYS -> {
                    val changedToEnabled =
                        PreferenceManager
                            .getDefaultSharedPreferences(requireContext())
                            .getBoolean(key, true)
                    Log.d(TAG, "Category pref persisted: key=$key enabled=$changedToEnabled")
                    view?.post { queueCategoryRefresh(changedToEnabled, showStartedToast = false) }
                        ?: queueCategoryRefresh(changedToEnabled, showStartedToast = false)
                }
            }
        }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.sources_youtube_settings, rootKey)
        setupPreferences()
        if (YouTubeVideoPrefs.enabled) {
            viewModel.refreshCacheSize()
        } else {
            viewModel.setDisplayedCacheSize(0)
        }
        if (YouTubeVideoPrefs.enabled && isCountPending()) {
            markRefreshInProgress()
            viewModel.refreshIfCachePending()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.refreshState.collect { state ->
                renderRefreshState(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settingsUiState.collect { state ->
                renderSettingsState(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            YouTubeFeature.repository(requireContext()).cacheFullEvent.collect { isFull ->
                if (isFull) {
                    ToastHelper.show(
                        requireContext(),
                        R.string.youtube_cache_full_toast,
                        Toast.LENGTH_LONG,
                    )
                    YouTubeFeature.repository(requireContext()).consumeCacheFullEvent()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is YouTubeSettingsViewModel.YouTubeSettingsEvent.CategoryRemoved -> {
                        val removedMessage =
                            getString(R.string.youtube_videos_removed_toast, event.removedCount, event.remainingCount)
                        transientCategoryMessage = removedMessage
                        transientCategoryMessageUntilElapsedMs = SystemClock.elapsedRealtime() + CATEGORY_MESSAGE_DURATION_MS
                        transientCategoryRemainingCount = event.remainingCount
                        view.removeCallbacks(clearTransientCategoryMessageRunnable)
                        view.postDelayed(clearTransientCategoryMessageRunnable, CATEGORY_MESSAGE_DURATION_MS)
                        Log.i(
                            TAG,
                            "Showing category-removed toast: removed=${event.removedCount}, " +
                                "remainingAfterRemoval=${event.remainingCount}",
                        )
                        updateVideoCount(staticCount = event.remainingCount)
                        updateCacheCountPreference(
                            cachedCount = event.remainingCount,
                            stage = YouTubeRefreshStage.EXTRACTING,
                            transientMessage = removedMessage,
                        )
                        ToastHelper.show(
                            requireContext(),
                            removedMessage,
                            Toast.LENGTH_LONG,
                        )
                    }
                    is YouTubeSettingsViewModel.YouTubeSettingsEvent.CategoryAdded -> {
                        val addedMessage =
                            getString(R.string.youtube_videos_added_toast, event.addedCount, event.totalCount)
                        transientCategoryMessage = addedMessage
                        transientCategoryMessageUntilElapsedMs = SystemClock.elapsedRealtime() + CATEGORY_MESSAGE_DURATION_MS
                        transientCategoryRemainingCount = event.totalCount
                        view.removeCallbacks(clearTransientCategoryMessageRunnable)
                        view.postDelayed(clearTransientCategoryMessageRunnable, CATEGORY_MESSAGE_DURATION_MS)
                        updateVideoCount(staticCount = event.totalCount)
                        updateCacheCountPreference(
                            cachedCount = event.totalCount,
                            stage = YouTubeRefreshStage.EXTRACTING,
                            transientMessage = addedMessage,
                        )
                        ToastHelper.show(
                            requireContext(),
                            addedMessage,
                            Toast.LENGTH_LONG,
                        )
                    }
                    YouTubeSettingsViewModel.YouTubeSettingsEvent.AllCategoriesDisabled -> {
                        ToastHelper.show(
                            requireContext(),
                            R.string.youtube_no_categories_selected_toast,
                            Toast.LENGTH_LONG,
                        )
                    }
                    YouTubeSettingsViewModel.YouTubeSettingsEvent.LibraryFullOnCategory -> {
                        ToastHelper.show(
                            requireContext(),
                            R.string.youtube_cache_full_on_category_toast,
                            Toast.LENGTH_LONG,
                        )
                    }
                    YouTubeSettingsViewModel.YouTubeSettingsEvent.RefreshAlreadyInProgress -> {
                        ToastHelper.show(
                            requireContext(),
                            R.string.youtube_refresh_already_in_progress_toast,
                            Toast.LENGTH_SHORT,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (YouTubeVideoPrefs.enabled) {
            viewModel.refreshCacheSize()
        } else {
            viewModel.setDisplayedCacheSize(0)
        }
        if (YouTubeVideoPrefs.enabled && isCountPending()) {
            markRefreshInProgress()
            viewModel.refreshIfCachePending()
        }
    }

    override fun onStart() {
        super.onStart()
        PreferenceManager
            .getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(sharedPreferenceListener)
    }

    override fun onStop() {
        PreferenceManager
            .getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(sharedPreferenceListener)
        super.onStop()
    }

    override fun onDestroyView() {
        view?.removeCallbacks(clearTransientCategoryMessageRunnable)
        super.onDestroyView()
    }

    private fun setupPreferences() {
        configureQualityPreference()
        configurePlaybackLengthPreferences()

        findPreference<SwitchPreference>("yt_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val existingCount =
                        runCatching {
                            YouTubeFeature.repository(requireContext()).getCacheSize()
                        }.getOrDefault(0)
                    if (existingCount > 0) {
                        refreshInProgress = false
                        viewModel.setDisplayedCacheSize(existingCount)
                        updateVideoCount(staticCount = existingCount)
                        updateCacheCountPreference(
                            cachedCount = existingCount,
                            stage = YouTubeRefreshStage.IDLE,
                            transientMessage = null,
                        )
                        // Keep cache warm but avoid a forced full rebuild when enabling with existing data.
                        queueBackgroundRefresh(R.string.youtube_refresh_started, immediate = false)
                    } else {
                        viewModel.setDisplayedCacheSize(0)
                        queueBackgroundRefresh(R.string.youtube_rebuilding_library, immediate = true)
                    }
                }
            } else {
                refreshInProgress = false
                viewModel.setDisplayedCacheSize(0)
                updateVideoCount(staticCount = 0)
                updateCacheCountPreference(cachedCount = 0, stage = YouTubeRefreshStage.IDLE, transientMessage = null)
            }
            true
        }

        findPreference<Preference>("yt_refresh_now")?.setOnPreferenceClickListener {
            viewModel.refreshNow()
            true
        }

        CATEGORY_PREFERENCE_KEYS.forEach { key ->
            findPreference<SwitchPreference>(key)?.setOnPreferenceChangeListener { _, newValue ->
                val changedToEnabled =
                    when (newValue) {
                        is Boolean -> newValue
                        is String -> newValue.toBooleanStrictOrNull() ?: newValue.toBoolean()
                        else -> null
                    }
                Log.d(
                    TAG,
                    "Category toggle changed: key=$key newValue=$newValue parsedEnabled=$changedToEnabled",
                )
                // Persist first; sharedPreferenceListener will trigger category refresh from committed state.
                true
            }
        }
    }

    private fun configureQualityPreference() {
        val qualityPreference = findPreference<ListPreference>("yt_quality") ?: return
        val display =
            runCatching { getDisplay(requireActivity()) }.getOrNull()
        val supportsUltraHd =
            display?.let { runCatching { it.supportsUltraHdOutput() }.getOrDefault(false) } ?: false
        val supports1440p =
            display?.let { runCatching { it.supports1440pOutput() }.getOrDefault(false) } ?: false

        when {
            supportsUltraHd -> {
                qualityPreference.setEntries(R.array.youtube_quality_entries_uhd)
                qualityPreference.setEntryValues(R.array.youtube_quality_values_uhd)
            }
            supports1440p -> {
                qualityPreference.setEntries(R.array.youtube_quality_entries)
                qualityPreference.setEntryValues(R.array.youtube_quality_values)
            }
            else -> {
                qualityPreference.setEntries(R.array.youtube_quality_entries_1080p)
                qualityPreference.setEntryValues(R.array.youtube_quality_values_1080p)
            }
        }

        // Reset to highest supported value if current value is not supported by this display,
        // or if it is 720p (the old hardcoded default — Highest Available is always better)
        val supportedValues = qualityPreference.entryValues?.map { it.toString() }.orEmpty()
        val currentValue = qualityPreference.value
        if (currentValue !in supportedValues || currentValue == "720p") {
            val highestSupported = supportedValues.firstOrNull() ?: "best"
            qualityPreference.value = highestSupported
            Log.d(TAG, "Reset quality to $highestSupported (was: $currentValue, not optimal for this display)")
        }

        qualityPreference.setOnPreferenceChangeListener { _, _ ->
            YouTubeFeature.markQualitySelectionExplicit(requireContext())
            queueBackgroundRefresh(R.string.youtube_rebuilding_library, immediate = true)
            true
        }
        qualityPreference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    private fun configurePlaybackLengthPreferences() {
        val modePreference = findPreference<ListPreference>("yt_playback_length_mode")
        val maxMinutesPreference = findPreference<ListPreference>("yt_playback_max_minutes")
        modePreference?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        fun applyMaxMinutesState(mode: String?) {
            val useFullPlayback = mode?.trim()?.equals("full", ignoreCase = true) == true
            maxMinutesPreference ?: return
            maxMinutesPreference.isEnabled = !useFullPlayback
            if (useFullPlayback) {
                maxMinutesPreference.summaryProvider = null
                maxMinutesPreference.summary = getString(R.string.youtube_playback_max_minutes_disabled_summary)
            } else {
                maxMinutesPreference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        }

        applyMaxMinutesState(modePreference?.value)
        modePreference?.setOnPreferenceChangeListener { _, newValue ->
            val mode = (newValue as? String).orEmpty()
            applyMaxMinutesState(mode)
            true
        }
    }

    private fun renderRefreshState(state: RefreshState) {
        when (state) {
            RefreshState.Idle -> {
            }

            RefreshState.Loading -> {
                markRefreshInProgress()
            }

            is RefreshState.Success -> {
                YouTubeVideoPrefs.count = state.count.toString()
                updateVideoCount()
                markRefreshComplete(state.count)
                viewModel.refreshCacheSize()
                viewLifecycleOwner.lifecycleScope.launch {
                    ToastHelper.show(
                        requireContext(),
                        getString(R.string.youtube_refresh_success, state.count),
                        Toast.LENGTH_LONG,
                    )
                }
                viewModel.clearRefreshState()
            }

            RefreshState.Error -> {
                markRefreshFailed()
                viewLifecycleOwner.lifecycleScope.launch {
                    ToastHelper.show(
                        requireContext(),
                        R.string.youtube_refresh_failed,
                        Toast.LENGTH_LONG,
                    )
                }
                viewModel.refreshCacheSize()
                viewModel.clearRefreshState()
            }
        }
    }

    private fun renderSettingsState(state: YouTubeSettingsUiState) {
        if (!YouTubeVideoPrefs.enabled) {
            refreshInProgress = false
            updateVideoCount(staticCount = 0)
            updateCacheCountPreference(
                cachedCount = 0,
                stage = YouTubeRefreshStage.IDLE,
                transientMessage = null,
            )
            return
        }

        refreshInProgress = state.isRefreshing
        val liveCount =
            state.progress
                ?.current
                ?.takeIf { progressCount ->
                    progressCount >= 0 && state.stage != YouTubeRefreshStage.IDLE
                }
        val transientMessage = currentTransientCategoryMessage()
        val forcedCategoryCount = currentTransientCategoryCount()
        val targetCount = state.progress?.total
        val effectiveLiveCount = if (forcedCategoryCount != null) null else liveCount
        val effectiveStaticCount =
            when {
                forcedCategoryCount != null -> forcedCategoryCount
                state.isRefreshing && liveCount == null -> null
                else -> state.cacheCount
            }
        val counterValueForSummary = effectiveLiveCount ?: effectiveStaticCount
        updateVideoCount(liveCount = effectiveLiveCount, staticCount = effectiveStaticCount)
        updateCacheCountPreference(
            cachedCount = counterValueForSummary,
            stage = state.stage,
            targetCount = targetCount ?: YOUTUBE_LIBRARY_TARGET_COUNT,
            transientMessage = transientMessage,
        )
    }

    private fun updateVideoCount(liveCount: Int? = null, staticCount: Int? = null) {
        val targetPreference = findPreference<Preference>("yt_enabled") ?: return

        val displayCount =
            when {
                liveCount != null -> liveCount
                staticCount != null -> staticCount
                refreshInProgress -> null
                !YouTubeVideoPrefs.enabled -> 0
                else -> YouTubeVideoPrefs.count.toIntOrNull()
            }

        targetPreference.summary =
            if (refreshInProgress && liveCount == null && staticCount == null) {
                getString(R.string.youtube_cache_count_pending)
            } else if (displayCount != null && displayCount >= 0) {
                getString(R.string.videos_count, displayCount)
            } else {
                null
            }
    }

    private fun isCountPending(): Boolean =
        YouTubeVideoPrefs.count.toIntOrNull()?.let { it < 0 } ?: true

    private fun queueBackgroundRefresh(
        messageResId: Int,
        immediate: Boolean = false,
    ) {
        markRefreshInProgress()
        viewModel.setDisplayedCacheSize(0)
        view?.post {
            if (immediate) {
                viewModel.scheduleBackgroundRefresh(delayMs = 0L)
            } else {
                viewModel.scheduleBackgroundRefresh()
            }
        } ?: run {
            if (immediate) {
                viewModel.scheduleBackgroundRefresh(delayMs = 0L)
            } else {
                viewModel.scheduleBackgroundRefresh()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ToastHelper.show(requireContext(), messageResId, Toast.LENGTH_LONG)
        }
    }

    private fun queueCategoryRefresh(
        changedToEnabled: Boolean? = null,
        showStartedToast: Boolean = false,
    ) {
        markRefreshInProgress()
        viewModel.onCategoryChanged(changedToEnabled)
        if (showStartedToast) {
            viewLifecycleOwner.lifecycleScope.launch {
                ToastHelper.show(requireContext(), R.string.youtube_category_refresh_started, Toast.LENGTH_LONG)
            }
        }
    }

    private fun updateCacheCountPreference(
        cachedCount: Int?,
        stage: YouTubeRefreshStage,
        targetCount: Int = YOUTUBE_LIBRARY_TARGET_COUNT,
        transientMessage: String? = currentTransientCategoryMessage(),
    ) {
        val cacheCountPreference = findPreference<Preference>(PREFERENCE_CACHE_COUNT) ?: return
        cacheCountPreference.summary =
            when {
                !transientMessage.isNullOrBlank() -> transientMessage
                stage == YouTubeRefreshStage.SEARCHING -> getString(R.string.youtube_refresh_searching)
                stage != YouTubeRefreshStage.IDLE &&
                    cachedCount != null &&
                    cachedCount >= 0 &&
                    cachedCount < targetCount ->
                    getString(R.string.youtube_cache_loading_overlay, cachedCount, targetCount)
                cachedCount != null && cachedCount >= 0 ->
                    getString(R.string.youtube_cache_count_summary, cachedCount)
                stage != YouTubeRefreshStage.IDLE -> getString(R.string.youtube_cache_count_pending)
                else -> null
            }
    }

    private fun currentTransientCategoryMessage(): String? {
        if (transientCategoryMessageUntilElapsedMs <= 0L) {
            return null
        }
        val now = SystemClock.elapsedRealtime()
        if (now > transientCategoryMessageUntilElapsedMs) {
            transientCategoryMessage = null
            transientCategoryRemainingCount = null
            transientCategoryMessageUntilElapsedMs = 0L
            return null
        }
        return transientCategoryMessage
    }

    private fun currentTransientCategoryCount(): Int? {
        currentTransientCategoryMessage() ?: return null
        return transientCategoryRemainingCount
    }

    private fun markRefreshInProgress() {
        refreshInProgress = true
        updateCacheCountPreference(null, YouTubeRefreshStage.FINALIZING)
    }

    private fun markRefreshComplete(cachedCount: Int) {
        refreshInProgress = false
        updateCacheCountPreference(cachedCount, YouTubeRefreshStage.IDLE)
    }

    private fun markRefreshFailed() {
        refreshInProgress = false
        updateCacheCountPreference(YouTubeVideoPrefs.count.toIntOrNull(), YouTubeRefreshStage.IDLE)
    }

    companion object {
        private const val TAG = "YouTubeSettingsFragment"
        private const val PREFERENCE_CACHE_COUNT = "yt_cache_count"
        private const val YOUTUBE_LIBRARY_TARGET_COUNT = 200
        private const val CATEGORY_MESSAGE_DURATION_MS = 4500L
        private val CATEGORY_PREFERENCE_KEYS =
            listOf(
                "yt_category_nature",
                "yt_category_animals",
                "yt_category_drone",
                "yt_category_ocean",
                "yt_category_space",
                "yt_category_cities",
                "yt_category_weather",
                "yt_category_winter",
            )
    }
}
