package com.charlie.weather.sync

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementDetectorTest {
    private val enter = ActivityTransition.ACTIVITY_TRANSITION_ENTER
    private val exit = ActivityTransition.ACTIVITY_TRANSITION_EXIT

    @Test
    fun startingToMoveCounts() {
        assertTrue(MovementDetector.isMovement(DetectedActivity.IN_VEHICLE, enter))
        assertTrue(MovementDetector.isMovement(DetectedActivity.ON_BICYCLE, enter))
        assertTrue(MovementDetector.isMovement(DetectedActivity.WALKING, enter))
        assertTrue(MovementDetector.isMovement(DetectedActivity.STILL, exit))
    }

    @Test
    fun stoppingDoesNotCount() {
        assertFalse(MovementDetector.isMovement(DetectedActivity.STILL, enter))
        assertFalse(MovementDetector.isMovement(DetectedActivity.IN_VEHICLE, exit))
        assertFalse(MovementDetector.isMovement(DetectedActivity.TILTING, enter))
        assertFalse(MovementDetector.isMovement(DetectedActivity.UNKNOWN, enter))
    }
}
