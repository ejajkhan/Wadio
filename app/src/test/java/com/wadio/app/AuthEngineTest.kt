package com.wadio.app

import com.wadio.app.auth.AuthEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthEngineTest {

    @Test
    fun argon2_hash_and_verify() {
        val phc = AuthEngine.hashPin("1234")
        assertTrue(phc.startsWith("\$argon2id\$"))
        assertTrue(AuthEngine.verifyArgon2("1234".toByteArray(), phc))
        assertFalse(AuthEngine.verifyArgon2("9999".toByteArray(), phc))
        assertFalse("the PIN must not be stored in plaintext: $phc".contains("1234"))
    }

    @Test
    fun argon2_different_salt_per_hash() {
        val a = AuthEngine.hashPin("1234")
        val b = AuthEngine.hashPin("1234")
        assertTrue(a != b)
        assertTrue(AuthEngine.verifyArgon2("1234".toByteArray(), a))
        assertTrue(AuthEngine.verifyArgon2("1234".toByteArray(), b))
    }
}
