package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPresetTest {
    @Test
    fun matchesPresetOrCustom() {
        NotificationPreset.entries.forEach { assertEquals(it, NotificationPreset.of(it.toggles)) }
        // 標準再打開每日早晨就對不上任何一組
        assertNull(NotificationPreset.of(NotificationPreset.STANDARD.toggles.copy(morning = true)))
    }

    @Test
    fun presetsAreDistinctAndKeepSafetyAlerts() {
        assertEquals(NotificationPreset.entries.size, NotificationPreset.entries.map { it.toggles }.distinct().size)
        NotificationPreset.entries.forEach {
            assertEquals(true, it.toggles.warnings)
            assertEquals(true, it.toggles.routes)
            assertEquals(true, it.toggles.rideRain)
        }
    }
}
