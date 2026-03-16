package com.neilturner.aerialviews.ui.sources

import android.os.Bundle
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
    private var progressDialog: AlertDialog? = null
    private var refreshInProgress = false
    private val sharedPreferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                YouTubeSourceRepository.KEY_COUNT -> {
                    val cacheCount = YouTubeVideoPrefs.count.toIntOrNull()
                    updateVideoCount()
                    updateCacheCountPreference(cacheCount)
                    if (refreshInProgress && cacheCount != null && cacheCount >= 0) {
                        markRefreshComplete(cacheCount)
                    }
                    viewModel.refreshCacheSize()
                }
                YouTubeSourceRepository.KEY_ENABLED -> {
                    updateVideoCount()
                    updateCacheCountPreference(YouTubeVideoPrefs.count.toIntOrNull())
                    if (!YouTubeVideoPrefs.enabled) {
                        refreshInProgress = false
                    }
                    viewModel.refreshCacheSize()
                }
            }
        }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.sources_youtube_settings, rootKey)
        setupPreferences()
        updateVideoCount()
        updateCacheCountPreference(YouTubeVideoPrefs.count.toIntOrNull())
        viewModel.refreshCacheSize()
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
            YouTubeFeature.repository(requireContext()).cacheLoadingProgress.collect { progress ->
                if (progress != null) {
                    val (current, target) = progress
                    updateCacheCountPreference(current)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateVideoCount()
        updateCacheCountPreference(YouTubeVideoPrefs.count.toIntOrNull())
        viewModel.refreshCacheSize()
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
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroyView()
    }

    private fun setupPreferences() {
        configureQualityPreference()

        findPreference<SwitchPreference>("yt_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                queueBackgroundRefresh(R.string.youtube_refresh_started, immediate = true)
            } else {
                progressDialog?.dismiss()
                progressDialog = null
                refreshInProgress = false
                updateVideoCount()
                updateCacheCountPreference(YouTubeVideoPrefs.count.toIntOrNull())
                viewModel.refreshCacheSize()
            }
            true
        }

        findPreference<Preference>("yt_refresh_now")?.setOnPreferenceClickListener {
            queueBackgroundRefresh(R.string.youtube_refresh_started, immediate = true)
            true
        }

        CATEGORY_PREFERENCE_KEYS.forEach { key ->
            findPreference<SwitchPreference>(key)?.setOnPreferenceChangeListener { _, _ ->
                view?.post { queueCategoryRefresh() } ?: queueCategoryRefresh()
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

        // Reset to default if current value is not supported by this display
        val supportedValues = qualityPreference.entryValues?.map { it.toString() }.orEmpty()
        if (qualityPreference.value !in supportedValues) {
            qualityPreference.value = YouTubeSourceRepository.DEFAULT_QUALITY
        }

        qualityPreference.setOnPreferenceChangeListener { _, _ ->
            YouTubeFeature.markQualitySelectionExplicit(requireContext())
            queueBackgroundRefresh(R.string.youtube_refresh_started, immediate = true)
            true
        }
        qualityPreference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    private fun renderRefreshState(state: RefreshState) {
        when (state) {
            RefreshState.Idle -> {
                progressDialog?.dismiss()
                progressDialog = null
            }

            RefreshState.Loading -> {
                markRefreshInProgress()
                if (progressDialog == null) {
                    progressDialog =
                        DialogHelper.progressDialog(
                            requireContext(),
                            getString(R.string.youtube_refresh_dialog_message),
                        )
                    progressDialog?.show()
                }
            }

            is RefreshState.Success -> {
                progressDialog?.dismiss()
                progressDialog = null
                YouTubeVideoPrefs.count = state.count.toString()
                updateVideoCount()
                updateCacheCountPreference(state.count)
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
                progressDialog?.dismiss()
                progressDialog = null
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

    private fun updateVideoCount() {
        val targetPreference = findPreference<Preference>("yt_enabled") ?: return
        val cachedCount = YouTubeVideoPrefs.count.toIntOrNull()
        targetPreference.summary =
            if (cachedCount != null && cachedCount >= 0 && cachedCount < YOUTUBE_LIBRARY_TARGET_COUNT) {
                getString(R.string.youtube_cache_loading_overlay, cachedCount, YOUTUBE_LIBRARY_TARGET_COUNT)
            } else if (cachedCount != null && cachedCount >= 0) {
                getString(R.string.videos_count, cachedCount)
            } else {
                getString(R.string.youtube_count_pending_summary)
            }
        updateCacheCountPreference(cachedCount)
    }

    private fun isCountPending(): Boolean =
        YouTubeVideoPrefs.count.toIntOrNull()?.let { it < 0 } ?: true

    private fun queueBackgroundRefresh(
        messageResId: Int,
        immediate: Boolean = false,
    ) {
        markRefreshInProgress()
        viewModel.refreshCacheSize()
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

    private fun queueCategoryRefresh() {
        markRefreshInProgress()
        viewModel.onCategoryChanged()
        viewLifecycleOwner.lifecycleScope.launch {
            ToastHelper.show(requireContext(), R.string.youtube_category_refresh_started, Toast.LENGTH_LONG)
        }
    }

    private fun updateCacheCountPreference(cachedCount: Int?) {
        val cacheCountPreference = findPreference<Preference>(PREFERENCE_CACHE_COUNT) ?: return
        cacheCountPreference.summary =
            if (cachedCount != null && cachedCount >= 0 && cachedCount < YOUTUBE_LIBRARY_TARGET_COUNT) {
                getString(R.string.youtube_cache_loading_overlay, cachedCount, YOUTUBE_LIBRARY_TARGET_COUNT)
            } else if (cachedCount != null && cachedCount >= 0) {
                getString(R.string.youtube_cache_count_summary, cachedCount)
            } else {
                getString(R.string.youtube_cache_count_pending)
            }
    }

    private fun markRefreshInProgress() {
        refreshInProgress = true
        updateCacheCountPreference(null)
    }

    private fun markRefreshComplete(cachedCount: Int) {
        refreshInProgress = false
        updateCacheCountPreference(cachedCount)
    }

    private fun markRefreshFailed() {
        refreshInProgress = false
        updateCacheCountPreference(YouTubeVideoPrefs.count.toIntOrNull())
    }

    companion object {
        private const val PREFERENCE_CACHE_COUNT = "yt_cache_count"
        private const val YOUTUBE_LIBRARY_TARGET_COUNT = 200
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
