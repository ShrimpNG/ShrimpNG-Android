package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Profile-Update-Interval` arrives in hours; the app stores minutes. Getting that conversion
 * wrong would silently schedule updates 60× too often or too rarely, so it is pinned down here.
 */
class SubscriptionUpdateIntervalTest {

    @Test
    fun test_hoursBecomeMinutes() {
        assertEquals(180L, SubscriptionProfileParser.parseUpdateIntervalMinutes("3"))
        assertEquals(1440L, SubscriptionProfileParser.parseUpdateIntervalMinutes("24"))
        assertEquals(60L, SubscriptionProfileParser.parseUpdateIntervalMinutes("1"))
    }

    @Test
    fun test_toleratesWhitespaceAndDecimals() {
        assertEquals(180L, SubscriptionProfileParser.parseUpdateIntervalMinutes("  3  "))
        assertEquals(90L, SubscriptionProfileParser.parseUpdateIntervalMinutes("1.5"))
    }

    @Test
    fun test_rejectsMissingOrUnparseable() {
        assertNull(SubscriptionProfileParser.parseUpdateIntervalMinutes(null))
        assertNull(SubscriptionProfileParser.parseUpdateIntervalMinutes(""))
        assertNull(SubscriptionProfileParser.parseUpdateIntervalMinutes("soon"))
    }

    @Test
    fun test_rejectsOutOfRangeSoTheDefaultIsUsedInstead() {
        assertNull(SubscriptionProfileParser.parseUpdateIntervalMinutes("0"))
        assertNull(SubscriptionProfileParser.parseUpdateIntervalMinutes("-5"))
        // Beyond a month is more likely a different unit than a real cadence.
        assertNull(SubscriptionProfileParser.parseUpdateIntervalMinutes("100000"))
    }

    @Test
    fun test_defaultIsThreeHours() {
        assertEquals(180L, SubscriptionProfileParser.DEFAULT_UPDATE_INTERVAL_MINUTES)
    }
}
