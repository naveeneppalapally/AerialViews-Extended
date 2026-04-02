@file:Suppress("SameReturnValue")

package com.neilturner.aerialviews.ui.settings

import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.neilturner.aerialviews.BuildConfig
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.utils.AppUpdateHelper
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.MenuStateFragment
import com.neilturner.aerialviews.utils.ToastHelper
import com.neilturner.aerialviews.utils.getPackageInfoCompat
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import timber.log.Timber

class AboutFragment : MenuStateFragment() {
    private var updateStatus = AppUpdateHelper.UpdateStatus(
        availability = AppUpdateHelper.Availability.UNCHECKED,
        currentVersion = BuildConfig.VERSION_NAME,
        latestVersion = "",
        releaseUrl = AppUpdateHelper.RELEASES_PAGE_URL,
        checkedAt = 0L,
    )

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        configureUpdatePreference()
        updateSummary()
    }

    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("About", this)
        updateSummary()
        refreshUpdateStatus(force = false)
    }

    private fun updateSummary() {
        val context = context ?: return
        val version = findPreference<Preference>("about_version")
        val date = findPreference<Preference>("about_date")
        val updates = findPreference<Preference>("about_update")
        val packageInfo =
            runCatching {
                context.packageManager.getPackageInfoCompat(context.packageName, 0)
            }.getOrNull()

        version?.summary = buildVersionSummary(packageInfo)
        date?.title = getString(buildDateTitle(packageInfo))
        date?.summary = buildDateSummary(packageInfo)
        bindUpdatePreference(updates, updateStatus)
    }

    private fun configureUpdatePreference() {
        findPreference<Preference>("about_update")?.setOnPreferenceClickListener {
            if (updateStatus.updateAvailable) {
                openReleasePage(updateStatus.releaseUrl)
            } else {
                refreshUpdateStatus(force = true)
            }
            true
        }
    }

    private fun refreshUpdateStatus(force: Boolean) {
        val updatesPreference = findPreference<Preference>("about_update") ?: return
        updateStatus = AppUpdateHelper.cachedStatus(requireContext())
        bindUpdatePreference(updatesPreference, updateStatus, isChecking = updateStatus.availability == AppUpdateHelper.Availability.UNCHECKED)

        if (!AppUpdateHelper.isSupportedFlavor()) {
            return
        }

        if (force) {
            bindUpdatePreference(updatesPreference, updateStatus, isChecking = true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val previousStatus = updateStatus
            val latestStatus = AppUpdateHelper.fetchStatus(requireContext(), force = force)
            updateStatus = latestStatus
            bindUpdatePreference(updatesPreference, latestStatus)

            if (force && !latestStatus.updateAvailable && previousStatus.availability != latestStatus.availability) {
                when (latestStatus.availability) {
                    AppUpdateHelper.Availability.UP_TO_DATE -> {
                        ToastHelper.show(requireContext(), R.string.about_update_up_to_date_summary)
                    }

                    AppUpdateHelper.Availability.ERROR -> {
                        ToastHelper.show(requireContext(), R.string.about_update_error_summary)
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun bindUpdatePreference(
        preference: Preference?,
        status: AppUpdateHelper.UpdateStatus,
        isChecking: Boolean = false,
    ) {
        val updatePreference = preference ?: return
        val supported = AppUpdateHelper.isSupportedFlavor()
        updatePreference.isVisible = supported
        if (!supported) {
            return
        }

        when {
            isChecking -> {
                updatePreference.title = getString(R.string.about_update_title)
                updatePreference.summary = getString(R.string.about_update_checking_summary)
            }

            status.updateAvailable -> {
                updatePreference.title = getString(R.string.about_update_available_title)
                updatePreference.summary = getString(R.string.about_update_available_summary, status.latestVersion)
            }

            status.availability == AppUpdateHelper.Availability.ERROR -> {
                updatePreference.title = getString(R.string.about_update_title)
                updatePreference.summary = getString(R.string.about_update_error_summary)
            }

            status.availability == AppUpdateHelper.Availability.UP_TO_DATE -> {
                updatePreference.title = getString(R.string.about_update_title)
                updatePreference.summary = getString(R.string.about_update_up_to_date_summary)
            }

            status.availability == AppUpdateHelper.Availability.LOCAL_BUILD -> {
                updatePreference.title = getString(R.string.about_update_title)
                updatePreference.summary = getString(R.string.about_update_local_build_summary, status.latestVersion.ifBlank { BuildConfig.VERSION_NAME })
            }

            status.availability == AppUpdateHelper.Availability.NO_RELEASES -> {
                updatePreference.title = getString(R.string.about_update_title)
                updatePreference.summary = getString(R.string.about_update_no_release_summary)
            }

            else -> {
                updatePreference.title = getString(R.string.about_update_title)
                updatePreference.summary = getString(R.string.about_update_check_summary)
            }
        }
    }

    private fun openReleasePage(releaseUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl.ifBlank { AppUpdateHelper.RELEASES_PAGE_URL }))
            startActivity(intent)
        } catch (exception: Exception) {
            Timber.e(exception, "Unable to open release page: %s", exception.message)
            viewLifecycleOwner.lifecycleScope.launch {
                ToastHelper.show(requireContext(), R.string.about_update_open_error)
            }
        }
    }

    private fun buildVersionSummary(packageInfo: PackageInfo?): String {
        val appName = getString(R.string.app_name)
        val versionCode = packageInfo?.longVersionCode ?: BuildConfig.VERSION_CODE.toLong()
        return "$appName ${BuildConfig.VERSION_NAME} (code $versionCode, ${BuildConfig.FLAVOR}.${BuildConfig.BUILD_TYPE})"
    }

    private fun buildDateTitle(packageInfo: PackageInfo?): Int =
        if (packageInfo != null && packageInfo.firstInstallTime != packageInfo.lastUpdateTime) {
            R.string.about_last_updated_title
        } else {
            R.string.about_installed_title
        }

    private fun buildDateSummary(packageInfo: PackageInfo?): String {
        val lastUpdate = packageInfo?.lastUpdateTime?.takeIf { it > 0L } ?: (BuildConfig.BUILD_TIME.toLongOrNull() ?: 0L)
        val dateFormat = DateFormat.getDateTimeInstance()
        val date = Date(lastUpdate.takeIf { it > 0L } ?: System.currentTimeMillis())
        return dateFormat.format(date)
    }
}