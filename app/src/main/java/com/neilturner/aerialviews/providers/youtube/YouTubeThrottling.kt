package com.neilturner.aerialviews.providers.youtube

import timber.log.Timber

/**
 * Circuit breaker for YouTube's anonymous-access bot gate
 * (SignInConfirmNotBotException / "Sign in to confirm that you're not a bot").
 *
 * The gate is IP-based and temporary, but hammering the player endpoint while
 * blocked only extends it — and every blocked extraction burns seconds of
 * black-screen loading. While the breaker is open the repository serves
 * cached URLs (even expiring ones) and skips refresh extractions entirely.
 */
object YouTubeThrottling {
    private const val TAG = "YouTubeThrottle"
    private const val COOLDOWN_MS = 45L * 60L * 1000L

    @Volatile
    private var blockedUntilMs: Long = 0L

    fun noteBotBlock() {
        blockedUntilMs = System.currentTimeMillis() + COOLDOWN_MS
        Timber.tag(TAG).w("YouTube bot gate hit, pausing extractions for 45 minutes")
    }

    fun isBlocked(): Boolean = System.currentTimeMillis() < blockedUntilMs

    fun remainingCooldownMs(): Long =
        (blockedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
}
