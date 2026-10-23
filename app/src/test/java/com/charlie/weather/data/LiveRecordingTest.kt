package com.charlie.weather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRecordingTest {
    private val t0 = 1_760_000_000_000L

    @Test
    fun movingTimeSkipsPauses() {
        val live = LiveRide(null, "足跡記錄中", "", recordedKm = 3.0, recordOnly = true, startedAt = t0, pausedMs = 5 * 60_000L)
        assertEquals(15 * 60_000L, live.movingMs(t0 + 20 * 60_000L))
        // 暫停中時間停住
        val paused = live.copy(paused = true, pausedAt = t0 + 20 * 60_000L)
        assertEquals(15 * 60_000L, paused.movingMs(t0 + 40 * 60_000L))
        assertEquals(0L, LiveRide(null, "行車提醒中", "").movingMs(t0))
    }

    @Test
    fun tooShortOnlyWhileRecording() {
        val live = LiveRide(null, "足跡記錄中", "", recordedKm = 0.2, recordOnly = true, startedAt = t0)
        assertTrue(live.tooShort(t0 + 10 * 60_000L))
        assertFalse(live.copy(recordedKm = 1.0).tooShort(t0 + 10 * 60_000L))
        // 沒有記錄足跡（設定關掉）時不必確認
        assertFalse(LiveRide(null, "行車提醒中", "").tooShort(t0))
    }

    @Test
    fun gpsSignalFromAccuracyAndAge() {
        assertEquals(GpsSignal.SEARCHING, GpsSignal.of(5f, null))
        assertEquals(GpsSignal.SEARCHING, GpsSignal.of(5f, 20_000))
        assertEquals(GpsSignal.GOOD, GpsSignal.of(5f, 1_000))
        assertEquals(GpsSignal.FAIR, GpsSignal.of(20f, 1_000))
        assertEquals(GpsSignal.WEAK, GpsSignal.of(60f, 1_000))
        assertEquals(GpsSignal.WEAK, GpsSignal.of(null, 1_000))
    }

    @Test
    fun climbIgnoresNoiseAndCountsRealClimbs() {
        val noisy = ClimbCounter()
        // 平路上 ±3 公尺的雜訊
        repeat(50) { noisy.add(100.0 + if (it % 2 == 0) 3.0 else -3.0) }
        assertEquals(0.0, noisy.climbM, 0.0)

        val hill = ClimbCounter()
        // 爬 100 公尺、下 100 公尺、再爬 50 公尺
        (0..100).forEach { hill.add(100.0 + it) }
        (0..100).forEach { hill.add(200.0 - it) }
        (0..50).forEach { hill.add(100.0 + it) }
        // 每段最後不到門檻的幾公尺不算，所以略少於 150
        assertEquals(145.0, hill.climbM, 7.0)
        assertTrue(hill.hasAltitude)
    }

    @Test
    fun climbRestartKeepsTotal() {
        val c = ClimbCounter()
        (0..60).forEach { c.add(it.toDouble()) }
        val before = c.climbM
        c.restart()
        // 暫停後在高 300 公尺的地方繼續，不算成爬升
        c.add(300.0)
        assertEquals(before, c.climbM, 0.0)
        assertTrue(c.hasAltitude)
    }
}
