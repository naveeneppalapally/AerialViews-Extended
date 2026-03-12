package com.neilturner.aerialviews.ui.sources

import android.os.Bundle
import androidx.preference.ListPreference
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
    }

    override fun onResume() {
        super.onResume()
        synchronizeSourceModePreference()
        configureYouTubeMixWeightPreference()
    }

    private fun configureSourceModePreference() {
        val preference = findPreference<ListPreference>(KEY_SOURCE_MODE) ?: return
        preference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        preference.setOnPreferenceChangeListener { _, newValue ->
            applySourceMode(newValue as? String ?: SOURCE_MODE_COMBINED)
            configureYouTubeMixWeightPreference()
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

        if (YouTubeVideoPrefs.enabled) {
            YouTubeFeature.markCountPending()
            YouTubeFeature.requestImmediateRefresh(requireContext(), forceSearchRefresh = true)
        }
    }

    private fun setDefaultAerialProvidersEnabled(enabled: Boolean) {
        AppleVideoPrefs.enabled = enabled
        AmazonVideoPrefs.enabled = enabled
        Comm1VideoPrefs.enabled = enabled
        Comm2VideoPrefs.enabled = enabled
    }

    private fun configureYouTubeMixWeightPreference() {
        val preference = findPreference<ListPreference>(YouTubeSourceRepository.KEY_MIX_WEIGHT) ?: return
        if (YouTubeVideoPrefs.enabled) {
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
