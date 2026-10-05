package com.v2ray.ang.util

import com.v2ray.ang.util.SubscriptionExpiry.Stage
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionExpiryTest {
    private val now = 1_800_000_000_000L
    private val hour = 3600L
    private fun at(secondsFromNow: Long) = now / 1000 + secondsFromNow

    @Test
    fun stages() {
        assertEquals(Stage.NONE, SubscriptionExpiry.stage(null, now))
        assertEquals(Stage.NONE, SubscriptionExpiry.stage(0, now))
        assertEquals(Stage.NONE, SubscriptionExpiry.stage(at(4 * 24 * hour), now))
        assertEquals(Stage.SOON, SubscriptionExpiry.stage(at(3 * 24 * hour), now))
        assertEquals(Stage.SOON, SubscriptionExpiry.stage(at(25 * hour), now))
        assertEquals(Stage.DAY, SubscriptionExpiry.stage(at(24 * hour), now))
        assertEquals(Stage.DAY, SubscriptionExpiry.stage(at(1), now))
        assertEquals(Stage.EXPIRED, SubscriptionExpiry.stage(at(0), now))
        assertEquals(Stage.EXPIRED, SubscriptionExpiry.stage(at(-200 * 24 * hour), now))
    }

    @Test
    fun daysLeftRoundsUp() {
        assertEquals(3, SubscriptionExpiry.daysLeft(at(3 * 24 * hour), now))
        assertEquals(2, SubscriptionExpiry.daysLeft(at(25 * hour), now))
        assertEquals(1, SubscriptionExpiry.daysLeft(at(1), now))
    }

    @Test
    fun trialsGetOnlyTheEnd() {
        val day = 24 * hour * 1000
        // A month-long subscription added long ago: everything.
        val end = at(2 * 24 * hour)
        val longAgo = now - 30 * day
        assertEquals(true, SubscriptionExpiry.shouldRemind(Stage.SOON, longAgo, end))
        assertEquals(true, SubscriptionExpiry.shouldRemind(Stage.DAY, longAgo, end))
        assertEquals(true, SubscriptionExpiry.shouldRemind(Stage.EXPIRED, longAgo, end))
        // A 3-day trial added just now, a 6-hour one too: no "soon", only the end.
        for (trialEnd in listOf(at(3 * 24 * hour), at(6 * hour))) {
            assertEquals(false, SubscriptionExpiry.shouldRemind(Stage.SOON, now, trialEnd))
            assertEquals(false, SubscriptionExpiry.shouldRemind(Stage.DAY, now, trialEnd))
            assertEquals(true, SubscriptionExpiry.shouldRemind(Stage.EXPIRED, now, trialEnd))
        }
        // Added after it had already ended: nothing to announce.
        assertEquals(false, SubscriptionExpiry.shouldRemind(Stage.EXPIRED, now, at(-hour)))
    }
}
