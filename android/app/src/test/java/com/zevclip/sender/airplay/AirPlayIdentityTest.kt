package com.zevclip.sender.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayIdentityTest {
    @Test
    fun generatesStableSignableIdentityFromPrivateKey() {
        val identity = AirPlayIdentity.generate()
        val restored = AirPlayIdentity.fromPrivateKey(identity.pairingId, identity.privateKey)
        val message = "verify persistent identity".toByteArray(Charsets.UTF_8)
        val signature = CryptoPrimitives.ed25519Sign(restored.privateKey, message)

        assertEquals(identity.pairingId, restored.pairingId)
        assertArrayEquals(identity.privateKey, restored.privateKey)
        assertArrayEquals(identity.publicKey, restored.publicKey)
        assertTrue(CryptoPrimitives.ed25519Verify(identity.publicKey, message, signature))
    }
}
