package com.wadio.app.auth

import com.wadio.app.data.WadioDatabase
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.min
import kotlin.math.pow

/**
 * PIN gate. Verifier is stored as an Argon2id PHC string in `meta.vault_pw`.
 * The PIN is never stored in plaintext. Failed attempts trigger a persisted
 * exponential delay capped at 60 seconds (mirrors src/app.rs).
 */
class AuthEngine(private val db: WadioDatabase) {

    @Volatile var unlocked: Boolean = false
        private set

    @Volatile var showPasswordModal: Boolean = false
        private set

    var failedAttempts: Int = 0
        private set
    var lockUntilUnix: Long = 0L
        private set

    private var pressStartTime: Double? = null

    init {
        failedAttempts = db.meta().get("pin_fail_count")?.toIntOrNull() ?: 0
        lockUntilUnix = db.meta().get("pin_lock_until")?.toLongOrNull() ?: 0L
    }

    val hasPin: Boolean get() = db.meta().get("vault_pw") != null

    fun isPressHolder(): Boolean = pressStartTime != null

    fun updateHeaderTouch(isPressed: Boolean, now: Double) {
        if (unlocked) return
        val start = pressStartTime
        if (isPressed && start == null) {
            pressStartTime = now
        } else if (isPressed && start != null && now - start >= 3.0) {
            showPasswordModal = true
            pressStartTime = null
        } else if (!isPressed) {
            pressStartTime = null
        }
    }

    fun dismissPrompt() {
        showPasswordModal = false
        pressStartTime = null
    }

    fun lock() {
        unlocked = false
        showPasswordModal = false
        pressStartTime = null
    }

    fun markUnlockedByHash(hash: String) {
        if (hash.isNotEmpty()) db.meta().set("vault_pw", hash)
        unlocked = true
        showPasswordModal = false
        pressStartTime = null
        resetAttempts()
    }

    fun unlockWithBiometric(): Boolean {
        if (!hasPin) return false
        unlocked = true
        showPasswordModal = false
        pressStartTime = null
        resetAttempts()
        return true
    }

    /** Argon2 verification. Runs on Dispatchers.Default (see controller). */
    fun verify(password: ByteArray): Boolean {
        val savedHash = db.meta().get("vault_pw") ?: return false
        val ok = verifyArgon2(password, savedHash)
        if (ok) {
            unlocked = true
            showPasswordModal = false
            pressStartTime = null
            resetAttempts()
        }
        return ok
    }

    fun recordFailedAttempt() {
        failedAttempts = (failedAttempts + 1).coerceAtMost(Int.MAX_VALUE - 1)
        val delay = (2.0.pow(min(failedAttempts, 6)).toLong()).coerceAtMost(60L)
        val nowUnix = System.currentTimeMillis() / 1000L
        lockUntilUnix = nowUnix + delay
        db.meta().set("pin_fail_count", failedAttempts.toString())
        db.meta().set("pin_lock_until", lockUntilUnix.toString())
    }

    fun resetAttempts() {
        failedAttempts = 0
        lockUntilUnix = 0L
        db.meta().set("pin_fail_count", "0")
        db.meta().set("pin_lock_until", "0")
    }

    companion object {
        // Match argon2 crate defaults: m=19456, t=2, p=1, out=32.
        private const val ITERATIONS = 2
        private const val MEMORY_KIB = 19456
        private const val PARALLELISM = 1
        private const val HASH_LEN = 32

        fun hashPin(pin: String): String {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KIB)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build()
            val gen = Argon2BytesGenerator().apply { init(params) }
            val out = ByteArray(HASH_LEN)
            gen.generateBytes(pin.toByteArray(Charsets.UTF_8), out)
            val b64 = Base64.getEncoder().withoutPadding()
            return "\$argon2id\$v=19\$m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM\$" +
                b64.encodeToString(salt) + "\$" + b64.encodeToString(out)
        }

        fun verifyArgon2(password: ByteArray, phc: String): Boolean {
            return try {
                if (!phc.startsWith("\$argon2id\$")) return false
                val parts = phc.split("\$")
                if (parts.size < 6) return false
                val params = parts[3].split(",").associate {
                    val (k, v) = it.split("=")
                    k to v
                }
                val m = params["m"]?.toIntOrNull() ?: return false
                val t = params["t"]?.toIntOrNull() ?: return false
                val p = params["p"]?.toIntOrNull() ?: return false
                val b64 = Base64.getDecoder()
                val salt = b64.decode(parts[4])
                val expected = b64.decode(parts[5])

                val argon = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withIterations(t)
                    .withMemoryAsKB(m)
                    .withParallelism(p)
                    .withSalt(salt)
                    .build()
                val gen = Argon2BytesGenerator().apply { init(argon) }
                val got = ByteArray(expected.size)
                gen.generateBytes(password, got)
                constantTimeEquals(expected, got)
            } catch (_: Throwable) {
                false
            }
        }

        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }
    }
}
