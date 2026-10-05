package com.v2ray.ang.util

/**
 * How close a subscription is to its provider-reported end (Subscription-Userinfo `expire`),
 * shared by the card on Главное and the reminders, so both call the same moment "soon".
 */
object SubscriptionExpiry {

    /** Ordered: a later stage is always more urgent, which is what reminders step through. */
    enum class Stage { NONE, SOON, DAY, EXPIRED }

    const val SOON_DAYS = 3
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** [expireAtSeconds] null or ≤ 0 means no end date ("never expires"): always [Stage.NONE]. */
    fun stage(expireAtSeconds: Long?, now: Long = System.currentTimeMillis()): Stage {
        if (expireAtSeconds == null || expireAtSeconds <= 0) return Stage.NONE
        val left = expireAtSeconds * 1000 - now
        return when {
            left <= 0 -> Stage.EXPIRED
            left <= DAY_MS -> Stage.DAY
            left <= SOON_DAYS * DAY_MS -> Stage.SOON
            else -> Stage.NONE
        }
    }

    /**
     * Whether [stage] is worth a reminder for a subscription added at [addedAtMs]. One that was
     * already inside the "soon" window when it was added — a trial of 3 days, a day, a few hours —
     * gets no "ending soon": its length was known from the start, and that reminder would arrive
     * the moment it was added. Only the end itself is reported, and not for one added already
     * expired. Every other subscription gets all three.
     */
    fun shouldRemind(stage: Stage, addedAtMs: Long, expireAtSeconds: Long): Boolean {
        val endMs = expireAtSeconds * 1000
        val short = isShort(addedAtMs, expireAtSeconds)
        return when (stage) {
            Stage.NONE -> false
            Stage.EXPIRED -> addedAtMs < endMs
            Stage.SOON, Stage.DAY -> !short
        }
    }

    /** Already within the "soon" window when added: a trial of days or hours, its length known from the start. */
    fun isShort(addedAtMs: Long, expireAtSeconds: Long): Boolean =
        addedAtMs >= expireAtSeconds * 1000 - SOON_DAYS * DAY_MS

    /** Whole days left, rounded up: 1.2 days left reads as "2 days". */
    fun daysLeft(expireAtSeconds: Long, now: Long = System.currentTimeMillis()): Int {
        val left = expireAtSeconds * 1000 - now
        return ((left + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(0)
    }
}
