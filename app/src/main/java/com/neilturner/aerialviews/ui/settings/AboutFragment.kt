@file:Suppress("SameReturnValue")

package com.neilturner.aerialviews.ui.settings

import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.preference.Preference
import com.neilturner.aerialviews.BuildConfig
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.utils.getPackageInfoCompat
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.MenuStateFragment
import java.text.DateFormat
import java.util.Date

class AboutFragment : MenuStateFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        updateSummary()
    }

    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("About", this)
        updateSummary()
    }

    private fun updateSummary() {
        val context = context ?: return
        val version = findPreference<Preference>("about_version")
        val date = findPreference<Preference>("about_date")
        val packageInfo =
            runCatching {
                context.packageManager.getPackageInfoCompat(context.packageName, 0)
            }.getOrNull()

        version?.summary = buildVersionSummary(packageInfo)
        date?.title = getString(buildDateTitle(packageInfo))
        date?.summary = buildDateSummary(packageInfo)
    }

    private fun buildVersionSummary(packageInfo: PackageInfo?): String {
        val appName = getString(R.string.app_name)
        val versionCode = packageInfo?.longVersionCode ?: BuildConfig.VERSION_CODE.toLong()
        return "$appName ${BuildConfig.VERSION_NAME} ($versionCode)"
    }

    private fun buildDateTitle(packageInfo: PackageInfo?): Int =
        if (packageInfo != null && packageInfo.firstInstallTime != packageInfo.lastUpdateTime) {
            R.string.about_last_updated_title
        } else {
            R.string.about_installed_title
        }

    private fun buildDateSummary(packageInfo: PackageInfo?): String {
        val lastUpdate = packageInfo?.lastUpdateTime ?: BuildConfig.BUILD_TIME.toLong()
        val dateFormat = DateFormat.getDateTimeInstance()
        val date = Date(lastUpdate)
        return dateFormat.format(date)
    }
}
