package com.neilturner.aerialviews.ui.sources

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.prefs.AmazonVideoPrefs
import com.neilturner.aerialviews.models.prefs.AppleVideoPrefs
import com.neilturner.aerialviews.models.prefs.Comm1VideoPrefs
import com.neilturner.aerialviews.models.prefs.Comm2VideoPrefs
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository
import com.neilturner.aerialviews.utils.MenuStateFragment

class SourcesFragment : MenuStateFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.sources, rootKey)
        configureSourceModePreference()
        ensureSourceModeInitialized()
        configureYouTubeMixWeightPreference()
        restoreSourceSummaries()
    }

    override fun onResume() {
        super.onResume()
        synchronizeSourceModePreference()
        configureYouTubeMixWeightPreference()
        restoreSourceSummaries()
    }

    private fun restoreSourceSummaries() {
        restoreSourceSummary("source_apple", R.string.sources_apple_summary)
        restoreSourceSummary("source_amazon", R.string.sources_amazon_summary)
        restoreSourceSummary("source_comm1", R.string.sources_comm1_summary)
        restoreSourceSummary("source_comm2", R.string.sources_comm2_summary)
    }

    private fun restoreSourceSummary(prefKey: String, summaryId: Int) {
        val preference = findPreference<Preference>(prefKey) ?: return
        preference.summary = getString(summaryId)
    }

    private fun configureSourceModePreference() {
        val preference = findPreference<ListPreference>(KEY_SOURCE_MODE) ?: return
        preference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        preference.setOnPreferenceChangeListener { _, newValue ->
            val selectedMode = newValue as? String ?: SOURCE_MODE_COMBINED
            applySourceMode(selectedMode)
            // Use the incoming value directly; shared prefs writes happen after this callback.
            configureYouTubeMixWeightPreference(sourceModeOverride = selectedMode)
            true
        }
    }

    private fun ensureSourceModeInitialized() {
        val sharedPreferences = preferenceManager.sharedPreferences ?: return
        val modePreference = findPreference<ListPreference>(KEY_SOURCE_MODE) ?: return
        if (!sharedPreferences.contains(YouTubeSourceRepository.KEY_MIX_WEIGHT)) {
            sharedPreferences
                .edit()
                .putString(YouTubeSourceRepository.KEY_MIX_WEIGHT, YouTubeSourceRepository.DEFAULT_MIX_WEIGHT)
                .apply()
        }
        if (!sharedPreferences.contains(KEY_SOURCE_MODE)) {
            modePreference.value = SOURCE_MODE_COMBINED
            applySourceMode(SOURCE_MODE_COMBINED)
        } else {
            synchronizeSourceModePreference()
        }
    }

    private fun synchronizeSourceModePreference() {
        val preference = findPreference<ListPreference>(KEY_SOURCE_MODE) ?: return
        val inferredMode = inferSourceMode()
        if (preference.value != inferredMode) {
            preference.value = inferredMode
        }
    }

    private fun inferSourceMode(): String {
        val defaultAerialEnabled = AppleVideoPrefs.enabled && AmazonVideoPrefs.enabled && Comm1VideoPrefs.enabled && Comm2VideoPrefs.enabled
        val defaultAerialDisabled = !AppleVideoPrefs.enabled && !AmazonVideoPrefs.enabled && !Comm1VideoPrefs.enabled && !Comm2VideoPrefs.enabled
        return when {
            !YouTubeVideoPrefs.enabled && defaultAerialEnabled -> SOURCE_MODE_AERIAL
            YouTubeVideoPrefs.enabled && defaultAerialDisabled -> SOURCE_MODE_YOUTUBE
            else -> SOURCE_MODE_COMBINED
        }
    }

    private fun applySourceMode(mode: String) {
        val wasYouTubeEnabled = YouTubeVideoPrefs.enabled

        when (mode) {
            SOURCE_MODE_AERIAL -> {
                setDefaultAerialProvidersEnabled(true)
                YouTubeVideoPrefs.enabled = false
            }

            SOURCE_MODE_YOUTUBE -> {
                setDefaultAerialProvidersEnabled(false)
                YouTubeVideoPrefs.enabled = true
            }

            else -> {
                setDefaultAerialProvidersEnabled(true)
                YouTubeVideoPrefs.enabled = true
            }
        }

        val isYouTubeEnabled = YouTubeVideoPrefs.enabled
        if (isYouTubeEnabled && !wasYouTubeEnabled) {
            // Enabling YouTube source mode should keep current cache when available.
            YouTubeFeature.requestImmediateRefresh(requireContext(), forceSearchRefresh = false)
        }
    }

    private fun setDefaultAerialProvidersEnabled(enabled: Boolean) {
        AppleVideoPrefs.enabled = enabled
        AmazonVideoPrefs.enabled = enabled
        Comm1VideoPrefs.enabled = enabled
        Comm2VideoPrefs.enabled = enabled
    }

    private fun configureYouTubeMixWeightPreference(sourceModeOverride: String? = null) {
        val preference = findPreference<ListPreference>(YouTubeSourceRepository.KEY_MIX_WEIGHT) ?: return
        val sourceMode =
            sourceModeOverride
                ?: preferenceManager.sharedPreferences?.getString(KEY_SOURCE_MODE, SOURCE_MODE_COMBINED)
        val shouldEnable = YouTubeVideoPrefs.enabled && sourceMode == SOURCE_MODE_COMBINED
        if (shouldEnable) {
            preference.isEnabled = true
            preference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        } else {
            preference.isEnabled = false
            preference.summaryProvider = null
            preference.summary = getString(R.string.youtube_mix_weight_disabled_summary)
        }
    }

    companion object {
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val SOURCE_MODE_AERIAL = "aerial"
        private const val SOURCE_MODE_YOUTUBE = "youtube"
        private const val SOURCE_MODE_COMBINED = "combined"
    }
}
