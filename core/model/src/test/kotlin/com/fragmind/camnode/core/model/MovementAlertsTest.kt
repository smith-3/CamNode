package com.fragmind.camnode.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementAlertsTest {
    @Test
    fun movementAlertsResumeAtExpiryButNeverWhileDisabled() {
        val settings = CameraSettings(movementAlertsMutedUntilMillis = 2_000)
        assertFalse(settings.movementAlertsEnabledAt(1_999))
        assertTrue(settings.movementAlertsEnabledAt(2_000))
        assertTrue(settings.movementAlertsEnabledAt(3_000))
        assertFalse(settings.copy(movementAlerts = false).movementAlertsEnabledAt(3_000))
        assertTrue(CameraSettings().movementAlertsEnabledAt(1_000))
    }

    @Test
    fun olderCameraCommandsAndSettingsKeepTheirDefaults() {
        val command = Json.decodeFromString<CameraCommand>("{}")
        assertNull(command.movementAlerts)
        assertNull(command.movementAlertsMutedUntilMillis)
        val settings = Json.decodeFromString<CameraSettings>("{}")
        assertTrue(settings.movementAlerts)
        assertEquals(0L, settings.movementAlertsMutedUntilMillis)
    }

    @Test
    fun olderCameraStatusDoesNotClaimRemoteMovementControl() {
        val status = Json.decodeFromString<CameraStatus>(
            """{"viewers":0,"profile":"MEDIUM","facing":"BACK","rotationDegrees":0}""",
        )
        assertNull(status.movementAlerts)
        assertNull(status.movementAlertsMutedUntilMillis)
        val updated = status.copy(movementAlerts = true, movementAlertsMutedUntilMillis = 3_000)
        assertEquals(updated, Json.decodeFromString<CameraStatus>(Json.encodeToString(updated)))
    }
}
