package io.github.est4s.terminal.core

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A point of P-256 in affine coordinates; [x] null is the point at infinity. */
data class EcPoint(val x: BigInteger?, val y: BigInteger?) {
    val infinity get() = x == null
}

/**
 * NIST P-256 (secp256r1) arithmetic on [BigInteger]s, for SPAKE2: the
 * JDK's `ECDH` only gives a shared x, never a sum of points. Not
 * constant-time (BigInteger isn't): fine for a pairing that uses each
 * secret scalar once (see [Spake2]).
 */
object P256 {
    val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A = P - BigInteger.valueOf(3)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    val G = EcPoint(
        BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
        BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16),
    )
    val INFINITY = EcPoint(null, null)
    private val THREE = BigInteger.valueOf(3)

    fun onCurve(p: EcPoint): Boolean {
        val x = p.x ?: return false
        val y = p.y!!
        if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return false
        return y.modPow(BigInteger.TWO, P) == (x.modPow(THREE, P) + A * x + B).mod(P)
    }

    fun negate(p: EcPoint): EcPoint = if (p.infinity) p else EcPoint(p.x, (P - p.y!!).mod(P))

    fun add(p: EcPoint, q: EcPoint): EcPoint {
        if (p.infinity) return q
        if (q.infinity) return p
        val (x1, y1) = p.x!! to p.y!!
        val (x2, y2) = q.x!! to q.y!!
        val slope = if (x1 == x2) {
            if ((y1 + y2).mod(P).signum() == 0) return INFINITY
            (THREE * x1 * x1 + A) * (BigInteger.TWO * y1).modInverse(P)
        } else {
            (y2 - y1) * (x2 - x1).mod(P).modInverse(P)
        }.mod(P)
        val x3 = (slope * slope - x1 - x2).mod(P)
        return EcPoint(x3, (slope * (x1 - x3) - y1).mod(P))
    }

    fun multiply(p: EcPoint, k: BigInteger): EcPoint {
        var result = INFINITY
        var addend = p
        val scalar = k.mod(N)
        for (i in 0 until scalar.bitLength()) {
            if (scalar.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    /** SEC1 uncompressed: 04, x, y (65 bytes). */
    fun encode(p: EcPoint): ByteArray {
        require(!p.infinity) { "the point at infinity has no encoding" }
        return byteArrayOf(4) + p.x!!.fixed(32) + p.y!!.fixed(32)
    }

    fun encodeCompressed(p: EcPoint): ByteArray =
        byteArrayOf(if (p.y!!.testBit(0)) 3 else 2) + p.x!!.fixed(32)

    /** A point from SEC1 (uncompressed or compressed); null if it isn't one on the curve. */
    fun decode(bytes: ByteArray): EcPoint? {
        val point = when {
            bytes.size == 65 && bytes[0] == 4.toByte() ->
                EcPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
            bytes.size == 33 && (bytes[0] == 2.toByte() || bytes[0] == 3.toByte()) -> {
                val x = BigInteger(1, bytes.copyOfRange(1, 33))
                if (x >= P) return null
                // P ≡ 3 (mod 4), so a square root is a power.
                val ySquared = (x.modPow(THREE, P) + A * x + B).mod(P)
                var y = ySquared.modPow((P + BigInteger.ONE).shiftRight(2), P)
                if (y.testBit(0) != (bytes[0] == 3.toByte())) y = (P - y).mod(P)
                EcPoint(x, y)
            }
            else -> return null
        }
        return point.takeIf { onCurve(it) }
    }
}

internal fun BigInteger.fixed(size: Int): ByteArray {
    val bytes = toByteArray()
    return when {
        bytes.size == size -> bytes
        bytes.size > size -> bytes.copyOfRange(bytes.size - size, bytes.size)
        else -> ByteArray(size - bytes.size) + bytes
    }
}

/** RFC 9382's M and N for P-256 (section 6). */
val SPAKE2_M: EcPoint = P256.decode(hexBytes("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))!!
val SPAKE2_N: EcPoint = P256.decode(hexBytes("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))!!

internal fun hexBytes(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.takeIf { it.isNotEmpty() } ?: ByteArray(32), "HmacSHA256"))
        doFinal(data)
    }

/** HKDF-SHA256 (RFC 5869); an empty [salt] is 32 zero bytes. */
internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hmacSha256(salt, ikm)
    val out = ByteArrayOutputStream()
    var block = ByteArray(0)
    var i = 1
    while (out.size() < length) {
        block = hmacSha256(prk, block + info + byteArrayOf(i++.toByte()))
        out.write(block)
    }
    return out.toByteArray().copyOf(length)
}

/** The keys a [Spake2] run gives: [ke] is the shared secret; the confirmations prove each side has it. */
class Spake2Keys internal constructor(
    val transcript: ByteArray,
    val ke: ByteArray,
    val confirmA: ByteArray,
    val confirmB: ByteArray,
    private val role: Spake2.Role,
) {
    /** The confirmation this side sends. */
    val mine get() = if (role == Spake2.Role.A) confirmA else confirmB

    /** Whether the other side's confirmation is right (in constant time). */
    fun checkPeer(confirmation: ByteArray): Boolean =
        MessageDigest.isEqual(confirmation, if (role == Spake2.Role.A) confirmB else confirmA)
}

/**
 * SPAKE2 (RFC 9382), suite P256-SHA256-HKDF-HMAC, cofactor 1. Both
 * sides know [w] (from a short code, [spake2Password], or a pair's key);
 * each sends its [share] and gets [Spake2Keys] from the other's, the
 * identities and the associated data (in the confirmation keys), which
 * both must agree on. The share doesn't depend on them, so it can be
 * sent before the other side's identity is known. Someone who doesn't know w can only
 * guess it once per run (no offline guessing from what they saw).
 * [random] fixes the secret scalar, for the RFC's test vectors only.
 */
class Spake2(
    private val role: Role,
    private val w: BigInteger,
    random: BigInteger? = null,
    rng: SecureRandom = SecureRandom(),
) {
    enum class Role { A, B }

    private val secret = random ?: randomScalar(rng)
    private val mask = if (role == Role.A) SPAKE2_M else SPAKE2_N
    private val peerMask = if (role == Role.A) SPAKE2_N else SPAKE2_M
    private var finished = false

    val share: ByteArray = P256.encode(P256.add(P256.multiply(P256.G, secret), P256.multiply(mask, w)))

    /** The keys from the other side's share; null if that isn't a usable point. Once per run. */
    fun finish(peerShare: ByteArray, idA: ByteArray, idB: ByteArray, aad: ByteArray = ByteArray(0)): Spake2Keys? {
        check(!finished) { "SPAKE2 runs once" }
        finished = true
        val peer = P256.decode(peerShare) ?: return null
        val k = P256.multiply(P256.add(peer, P256.negate(P256.multiply(peerMask, w))), secret)
        if (k.infinity) return null
        val (pA, pB) = if (role == Role.A) share to P256.encode(peer) else P256.encode(peer) to share
        val tt = ByteArrayOutputStream().apply {
            for (part in listOf(idA, idB, pA, pB, P256.encode(k), w.fixed(32))) {
                write(littleEndian(part.size.toLong()))
                write(part)
            }
        }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(tt)
        val ke = hash.copyOfRange(0, 16)
        val ka = hash.copyOfRange(16, 32)
        val kc = hkdf(ka, ByteArray(0), "ConfirmationKeys".toByteArray() + aad, 32)
        return Spake2Keys(
            tt, ke, hmacSha256(kc.copyOfRange(0, 16), tt), hmacSha256(kc.copyOfRange(16, 32), tt), role,
        )
    }

    private fun littleEndian(value: Long) = ByteArray(8) { (value shr (8 * it)).toByte() }

    private fun randomScalar(rng: SecureRandom): BigInteger {
        while (true) {
            val k = BigInteger(1, ByteArray(32).also(rng::nextBytes))
            if (k.signum() > 0 && k < P256.N) return k
        }
    }
}

/** SPAKE2's w from a password: HKDF-SHA256 to 64 bits more than the order, reduced (RFC 9382, 3.2). */
fun spake2Password(password: ByteArray, label: String = "mynx-screen pairing code"): BigInteger {
    val w = BigInteger(1, hkdf(password, "mynx-screen".toByteArray(), label.toByteArray(), 40)).mod(P256.N)
    return if (w.signum() == 0) BigInteger.ONE else w
}
