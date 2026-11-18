package com.brewprint.ui

import android.companion.CompanionDeviceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingFailureTest {
    private val notFound = "No compatible scale found. Turn it on and try again."
    private val failed = "Couldn't pair the scale. Try again."

    @Test fun `declining or cancelling the picker says nothing`() {
        assertNull(pairingFailure(CompanionDeviceManager.RESULT_USER_REJECTED, "user_rejected"))
        assertNull(pairingFailure(CompanionDeviceManager.RESULT_CANCELED, "canceled"))
        assertNull(pairingFailure(null, "user_rejected"))
        assertNull(pairingFailure(null, "Cancelled by user"))
    }

    @Test fun `only a search that found nothing suggests turning the scale on`() {
        assertEquals(notFound, pairingFailure(CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT, "discovery_timeout"))
        assertEquals(notFound, pairingFailure(null, "discovery_timeout"))
    }

    @Test fun `any other failure asks to try again`() {
        assertEquals(failed, pairingFailure(CompanionDeviceManager.RESULT_INTERNAL_ERROR, "internal_error"))
        assertEquals(failed, pairingFailure(null, "internal_error"))
    }

    @Test fun `a picker result with no reason is not called a failure`() {
        assertNull(pairingFailure(null, null))
    }
}
