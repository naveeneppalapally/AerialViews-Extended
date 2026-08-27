package com.neilturner.aerialviews.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.neilturner.aerialviews.BuildConfig
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.databinding.MainActivityBinding
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.ui.helpers.PreferenceHelper
import com.neilturner.aerialviews.ui.screensaver.TestActivity
import com.neilturner.aerialviews.ui.settings.ImportExportFragment
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.ui.helpers.ToastHelper
import com.neilturner.aerialviews.utils.UpdateCheckerHelper
import com.neilturner.aerialviews.utils.UpdateInfo
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity :
    AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private lateinit var binding: MainActivityBinding
    private lateinit var resultLauncher: ActivityResultLauncher<Intent>
    private var fromScreensaver = false
    private var updateDownloadId: Long = -1L
    private var startupUpdatePromptHandled = false
    private var isDownloadReceiverRegistered = false

    private val downloadReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != updateDownloadId) return

                val didLaunchInstaller = UpdateCheckerHelper.installDownloadedApk(this@MainActivity, updateDownloadId)
                if (!didLaunchInstaller) {
                    lifecycleScope.launch {
                        ToastHelper.show(this@MainActivity, R.string.home_update_download_failed)
                    }
                }
                updateDownloadId = -1L
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(binding.container.id, MainFragment())
            }
        } else {
            title = savedInstanceState.getCharSequence("TITLE_TAG")
        }

        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                setTitle(R.string.app_name)
            }
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        resultLauncher =
            registerForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    val exitApp = result.data?.getBooleanExtra("exit_app", false)
                    Timber.i("Exit app now? $exitApp")
                    if (exitApp == true) {
                        fromScreensaver = false
                        finishAndRemoveTask()
                    } else {
                        fromScreensaver = true
                    }
                }
            }
    }

    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("Main", this)
        registerDownloadReceiver()
        lifecycleScope.launch {
            val shouldShowStartupPrompt = handleCustomLaunching()
            if (shouldShowStartupPrompt) {
                maybeShowStartupUpdatePrompt()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterDownloadReceiver()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence("TITLE_TAG", title)
    }

    private fun handleCustomLaunching(): Boolean {
        val fromAppRestart = intent.getBooleanExtra("from_app_restart", false)
        val hasIntentUri = intent.data != null
        val hasValidIntentAndData = hasIntentUri && intent.action == Intent.ACTION_VIEW
        val shouldExitApp =
            GeneralPrefs.startScreensaverOnLaunch &&
                PreferenceHelper.isExitToSettingSet() &&
                !hasIntentUri &&
                !fromAppRestart &&
                !fromScreensaver

        Timber.i(
            "isExitToSettingSet: ${PreferenceHelper.isExitToSettingSet()}, fromScreensaver:$fromScreensaver fromAppRestart:$fromAppRestart, hasIntentUri:$hasIntentUri, startScreensaverOnLaunch:${GeneralPrefs.startScreensaverOnLaunch}",
        )

        if (shouldExitApp) {
            startScreensaver()
            fromScreensaver = false
            return false
        } else if (hasValidIntentAndData) {
            val bundle =
                Bundle().apply {
                    putParcelable("dataUri", intent.data)
                }
            supportFragmentManager.commit {
                replace(
                    binding.container.id,
                    ImportExportFragment().apply {
                        arguments = bundle
                    },
                ).addToBackStack(null)
            }
            fromScreensaver = false
            return false
        }
        fromScreensaver = false
        return true
    }

    fun startAppUpdateDownload(updateInfo: UpdateInfo) {
        runCatching {
            updateDownloadId = UpdateCheckerHelper.enqueueDownload(this, updateInfo)
        }.onSuccess {
            lifecycleScope.launch {
                ToastHelper.show(
                    this@MainActivity,
                    getString(R.string.home_update_download_started, updateInfo.tagName.removePrefix("v")),
                )
            }
        }.onFailure { exception ->
            Timber.e(exception, "UpdateChecker: failed to enqueue home-screen update download")
            lifecycleScope.launch {
                ToastHelper.show(this@MainActivity, R.string.home_update_download_failed)
            }
        }
    }

    private fun maybeShowStartupUpdatePrompt() {
        if (BuildConfig.FLAVOR != "github" || startupUpdatePromptHandled) return

        binding.container.post {
            val mainFragment = supportFragmentManager.findFragmentById(binding.container.id) as? MainFragment ?: return@post
            startupUpdatePromptHandled = true
            mainFragment.maybeShowStartupUpdatePrompt()
        }
    }

    private fun registerDownloadReceiver() {
        if (isDownloadReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        isDownloadReceiverRegistered = true
    }

    private fun unregisterDownloadReceiver() {
        if (!isDownloadReceiverRegistered) return
        runCatching { unregisterReceiver(downloadReceiver) }
        isDownloadReceiverRegistered = false
    }

    fun startScreensaver() {
        fromScreensaver = false
        try {
            val intent = Intent(this, TestActivity::class.java)
            resultLauncher.launch(intent)
        } catch (ex: Exception) {
            Timber.e(ex)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference,
    ): Boolean {
        val fragment =
            supportFragmentManager.fragmentFactory
                .instantiate(
                    classLoader,
                    pref.fragment.toString(),
                ).apply {
                    arguments = pref.extras
                }

        supportFragmentManager
            .commit {
                setCustomAnimations(
                    R.anim.slide_in,
                    R.anim.fade_out,
                    R.anim.fade_in,
                    R.anim.slide_out,
                )
                replace(binding.container.id, fragment)
                    .addToBackStack(null)
            }.apply {
                title = pref.title
            }

        return true
    }
}
