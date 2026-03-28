package com.neilturner.aerialviews.ui.settings

import android.os.Bundle
import androidx.preference.Preference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.enums.OverlayType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.MenuStateFragment
import com.neilturner.aerialviews.utils.SlotHelper

class OverlaysWeatherFragment : MenuStateFragment() {
    private var locationPreference: Preference? = null
    private var statusPreference: Preference? = null

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_overlays_weather, rootKey)
    }

    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("Weather", this)
        updateSummary()
    }

    private fun updateSummary() {
        if (locationPreference == null) {
            locationPreference = findPreference("weather_location_name")
        }
        if (statusPreference == null) {
            statusPreference = findPreference("weather_overlay_status")
        }

        locationPreference?.summary =
            GeneralPrefs.weatherLocationName.ifEmpty {
                getString(R.string.location_not_set)
            }

        val assignedSlot =
            SlotHelper
                .slotPrefs(requireContext())
                .firstOrNull { it.pref == OverlayType.WEATHER1 }

        statusPreference?.summary =
            if (assignedSlot != null) {
                getString(R.string.weather_overlay_status_assigned, assignedSlot.label)
            } else {
                getString(R.string.weather_overlay_status_unassigned)
            }
    }
}
