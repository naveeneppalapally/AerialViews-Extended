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
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository
import com.neilturner.aerialviews.services.getDisplay
import com.neilturner.aerialviews.services.supportsUltraHdOutput
import com.neilturner.aerialviews.utils.DialogHelper
import com.neilturner.aerialviews.utils.MenuStateFragment
import com.neilturner.aerialviews.utils.ToastHelper
import kotlinx.coroutines.launch

class YouTubeSettingsFragment : MenuStateFragment() {
    private val viewModel by viewModels<YouTubeSettingsViewModel>()
    private var progressDialog: AlertDialog? = null
    private val sharedPreferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == YouTubeSourceRepository.KEY_COUNT || key == YouTubeSourceRepository.KEY_ENABLED) {
                updateVideoCount()
                viewModel.refreshCacheSize()
            }
        }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.sources_youtube_settings, rootKey)
        setupPreferences()
        updateVideoCount()
        viewModel.refreshCacheSize()
        if (YouTubeVideoPrefs.enabled && isCountPending()) {
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
            viewModel.cacheSize.collect(::renderCacheWarning)
        }
    }

    override fun onResume() {
        super.onResume()
        updateVideoCount()
        viewModel.refreshCacheSize()
        if (YouTubeVideoPrefs.enabled && isCountPending()) {
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
        val formulaInfoPreference = findPreference<Preference>("yt_formula_info")
        formulaInfoPreference?.summary = getString(R.string.youtube_formula_info_summary)

        configureQualityPreference()

        findPreference<SeekBarPreference>("yt_min_duration")?.apply {
            summaryProvider =
                Preference.SummaryProvider<SeekBarPreference> { preference ->
                    getString(R.string.youtube_min_duration_summary_value, preference.value)
                }
            setOnPreferenceChangeListener { _, newValue ->
                val newMinSeconds = (newValue as Int) * 60
                viewModel.onMinimumDurationChanged(newMinSeconds)
                viewLifecycleOwner.lifecycleScope.launch {
                    ToastHelper.show(requireContext(), R.string.youtube_duration_filter_updating, Toast.LENGTH_SHORT)
                }
                true
            }
        }

        findPreference<SwitchPreference>("yt_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                queueBackgroundRefresh(R.string.youtube_refresh_background_started, immediate = true)
            } else {
                progressDialog?.dismiss()
                progressDialog = null
                updateVideoCount()
                viewModel.refreshCacheSize()
            }
            true
        }

        findPreference<Preference>("yt_refresh_now")?.setOnPreferenceClickListener {
            queueBackgroundRefresh(R.string.youtube_refresh_background_started, immediate = true)
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
        val supportsUltraHd =
            runCatching {
                getDisplay(requireActivity()).supportsUltraHdOutput()
            }.getOrDefault(false)

        if (supportsUltraHd) {
            qualityPreference.setEntries(R.array.youtube_quality_entries_uhd)
            qualityPreference.setEntryValues(R.array.youtube_quality_values_uhd)
        } else {
            qualityPreference.setEntries(R.array.youtube_quality_entries)
            qualityPreference.setEntryValues(R.array.youtube_quality_values)
            if (qualityPreference.value.equals("2160p", ignoreCase = true)) {
                qualityPreference.value = YouTubeSourceRepository.DEFAULT_QUALITY
            }
        }

        qualityPreference.setOnPreferenceChangeListener { _, _ ->
            YouTubeFeature.markQualitySelectionExplicit(requireContext())
            queueBackgroundRefresh(R.string.youtube_refresh_background_started, immediate = true)
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
                if (progressDialog == null) {
                    progressDialog =
                        DialogHelper.progressDialog(
                            requireContext(),
                            getString(R.string.message_media_searching),
                        )
                    progressDialog?.show()
                }
            }

            is RefreshState.Success -> {
                progressDialog?.dismiss()
                progressDialog = null
                YouTubeVideoPrefs.count = state.count.toString()
                updateVideoCount()
                viewModel.refreshCacheSize()
                viewLifecycleOwner.lifecycleScope.launch {
                    ToastHelper.show(
                        requireContext(),
                        getString(R.string.youtube_refresh_success, state.count),
                        Toast.LENGTH_SHORT,
                    )
                }
                viewModel.clearRefreshState()
            }

            RefreshState.Error -> {
                progressDialog?.dismiss()
                progressDialog = null
                viewLifecycleOwner.lifecycleScope.launch {
                    ToastHelper.show(
                        requireContext(),
                        R.string.youtube_refresh_failed,
                        Toast.LENGTH_SHORT,
                    )
                }
                viewModel.refreshCacheSize()
                viewModel.clearRefreshState()
            }
        }
    }

    private fun renderCacheWarning(cacheSize: Int) {
        val warningPreference = findPreference<Preference>("yt_cache_warning") ?: return
        warningPreference.isVisible = YouTubeVideoPrefs.enabled && cacheSize == 0
    }

    private fun updateVideoCount() {
        val targetPreference = findPreference<Preference>("yt_enabled") ?: return
        val cachedCount = YouTubeVideoPrefs.count.toIntOrNull()
        targetPreference.summary =
            if (cachedCount != null && cachedCount >= 0) {
                getString(R.string.videos_count, cachedCount)
            } else {
                getString(R.string.youtube_count_pending_summary)
            }
    }

    private fun isCountPending(): Boolean =
        YouTubeVideoPrefs.count.toIntOrNull()?.let { it < 0 } ?: true

    private fun queueBackgroundRefresh(
        messageResId: Int,
        immediate: Boolean = false,
    ) {
        YouTubeVideoPrefs.count = "-1"
        updateVideoCount()
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
            ToastHelper.show(requireContext(), messageResId, Toast.LENGTH_SHORT)
        }
    }

    private fun queueCategoryRefresh() {
        viewModel.onCategoryChanged()
        viewLifecycleOwner.lifecycleScope.launch {
            ToastHelper.show(requireContext(), R.string.youtube_category_refresh_started, Toast.LENGTH_SHORT)
        }
    }

    companion object {
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
