package com.example.photostand.p2p.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.photostand.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingStoreSecurityTest {
    private lateinit var context: Context

    @Before
    fun clearPairingState() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `unknown controllers are not authorized before pairing`() {
        val store = PairingStore(context)

        assertFalse(store.isControllerAuthorized("controller-unknown"))
        assertNull(store.getControllerKey("controller-unknown"))
    }

    @Test
    fun `controller registration requires the device secret and cannot replace an existing key`() {
        val store = PairingStore(context)
        val deviceSecret = store.getOrCreateDeviceSecret()

        assertFalse(store.registerAuthenticatedController("controller-a", "Phone", "wrong-key"))
        assertFalse(store.isControllerAuthorized("controller-a"))
        assertTrue(store.registerAuthenticatedController("controller-a", "Phone", deviceSecret))
        assertTrue(store.isControllerAuthorized("controller-a"))
        assertFalse(store.registerAuthenticatedController("controller-a", "Attacker", "replacement-key"))
        assertEquals(deviceSecret, store.getControllerKey("controller-a"))
    }

    @Test
    fun `transfer nonce is durably consumed once`() {
        val store = PairingStore(context)
        val nonce = PairingCrypto.generateNonce()
        val now = System.currentTimeMillis()

        assertTrue(store.consumeTransferNonce("controller-a", nonce, now, Constants.AUTH_CLOCK_SKEW_MS))
        assertFalse(PairingStore(context).consumeTransferNonce(
            "controller-a", nonce, now + 1L, Constants.AUTH_CLOCK_SKEW_MS
        ))
    }
}
