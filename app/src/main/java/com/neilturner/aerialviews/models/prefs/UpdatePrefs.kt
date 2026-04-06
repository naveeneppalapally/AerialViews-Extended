package com.neilturner.aerialviews.models.prefs

import com.chibatching.kotpref.KotprefModel

object UpdatePrefs : KotprefModel() {
    override val kotprefName = "${context.packageName}_preferences"

    var homeUpdatePromptDismissedTag by stringPref("", "home_update_prompt_dismissed_tag")
}