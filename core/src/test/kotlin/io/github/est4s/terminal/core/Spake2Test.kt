package io.github.est4s.terminal.core

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(text: String): ByteArray {
    val clean = text.filterNot { it.isWhitespace() }.removePrefix("0x")
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private fun scalar(text: String) = BigInteger(1, hex(text))

class P256Test {
    @Test
    fun `the generator times the group order is the point at infinity`() {
        assertTrue(P256.multiply(P256.G, P256.N).infinity)
        assertFalse(P256.multiply(P256.G, P256.N - BigInteger.ONE).infinity)
    }

    @Test
    fun `SPAKE2's M and N decompress onto the curve`() {
        assertTrue(P256.onCurve(SPAKE2_M))
        assertTrue(P256.onCurve(SPAKE2_N))
        assertContentEquals(
            hex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"),
            P256.encodeCompressed(SPAKE2_M),
        )
    }

    @Test
    fun `points round-trip through the uncompressed encoding`() {
        val p = P256.multiply(P256.G, BigInteger.valueOf(12345))
        assertEquals(p, P256.decode(P256.encode(p)))
        assertEquals(65, P256.encode(p).size)
    }

    @Test
    fun `points off the curve, at infinity or badly encoded are refused`() {
        val p = P256.encode(P256.multiply(P256.G, BigInteger.TEN))
        p[64] = (p[64] + 1).toByte()
        assertNull(P256.decode(p))
        assertNull(P256.decode(ByteArray(65)))
        assertNull(P256.decode(byteArrayOf(4, 1, 2)))
        assertNull(P256.decode(ByteArray(0)))
    }

    @Test
    fun `adding a point to itself is doubling it`() {
        val p = P256.multiply(P256.G, BigInteger.valueOf(7))
        assertEquals(P256.multiply(p, BigInteger.TWO), P256.add(p, p))
        assertTrue(P256.add(p, P256.negate(p)).infinity)
    }
}

class Spake2Test {
    // RFC 9382, appendix B: the first vector (A='server', B='client').
    private val w = scalar("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f")
    private val x = scalar("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729")
    private val y = scalar("dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be")
    private val a = "server".toByteArray()
    private val b = "client".toByteArray()

    @Test
    fun `RFC 9382's test vector, its shares, transcript, keys and confirmations`() {
        val partyA = Spake2(Spake2.Role.A, w, random = x)
        val partyB = Spake2(Spake2.Role.B, w, random = y)
        assertContentEquals(
            hex(
                "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470" +
                    "f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c",
            ),
            partyA.share,
        )
        assertContentEquals(
            hex(
                "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676" +
                    "d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7",
            ),
            partyB.share,
        )
        val keysA = assertNotNull(partyA.finish(partyB.share, a, b))
        val keysB = assertNotNull(partyB.finish(partyA.share, a, b))
        assertContentEquals(
            hex(
                "06000000000000007365727665720600000000000000636c69656e744100000" +
                    "00000000004a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751" +
                    "470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c4" +
                    "1000000000000000406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf43" +
                    "67a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a" +
                    "1e0b741000000000000000412af7e89717850671913e6b469ace67bd90a4df8ce45c2a" +
                    "f19010175e37eed69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db2" +
                    "77b715d332520000000000000002ee57912099d31560b3a44b1184b9b4866e904c49d1" +
                    "2ac5042c97dca461b1a5f",
            ),
            keysA.transcript,
        )
        assertContentEquals(hex("0e0672dc86f8e45565d338b0540abe69"), keysA.ke)
        assertContentEquals(keysA.ke, keysB.ke)
        assertContentEquals(hex("58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0"), keysA.confirmA)
        assertContentEquals(hex("d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b"), keysA.confirmB)
        assertTrue(keysB.checkPeer(keysA.mine))
        assertTrue(keysA.checkPeer(keysB.mine))
    }

    @Test
    fun `RFC 9382's vector with no identities`() {
        val partyA = Spake2(
            Spake2.Role.A, scalar("7bf46c454b4c1b25799527d896508afd5fc62ef4ec59db1efb49113063d70cca"),
            random = scalar("8cef65df64bb2d0f83540c53632de911b5b24b3eab6cc74a97609fd659e95473"),
        )
        val pB = hex(
            "04589f13218822710d98d8b2123a079041052d9941b9cf88c6617ddb2fcc0494" +
                "662eea8ba6b64692dc318250030c6af045cb738bc81ba35b043c3dcb46adf6f58d",
        )
        val keys = assertNotNull(partyA.finish(pB, ByteArray(0), ByteArray(0)))
        assertContentEquals(hex("fc6374762ba5cf11f4b2caa08b2cd1b9"), keys.ke)
        assertContentEquals(hex("dfb4db8d48ae5a675963ea5e6c19d98d4ea028d8e898dad96ea19a80ade95dca"), keys.confirmA)
        assertContentEquals(hex("d0f0609d1613138d354f7e95f19fb556bf52d751947241e8c7118df5ef0ae175"), keys.confirmB)
    }

    @Test
    fun `with random secrets both sides agree when the password is the same`() {
        val one = Spake2(Spake2.Role.A, w)
        val two = Spake2(Spake2.Role.B, w)
        val k1 = assertNotNull(one.finish(two.share, a, b))
        val k2 = assertNotNull(two.finish(one.share, a, b))
        assertContentEquals(k1.ke, k2.ke)
        assertTrue(k1.checkPeer(k2.mine))
        assertTrue(k2.checkPeer(k1.mine))
    }

    @Test
    fun `a different password gives keys that don't confirm`() {
        val one = Spake2(Spake2.Role.A, w)
        val two = Spake2(Spake2.Role.B, w + BigInteger.ONE)
        val k1 = assertNotNull(one.finish(two.share, a, b))
        val k2 = assertNotNull(two.finish(one.share, a, b))
        assertFalse(k1.ke.contentEquals(k2.ke))
        assertFalse(k1.checkPeer(k2.mine))
        assertFalse(k2.checkPeer(k1.mine))
    }

    @Test
    fun `different identities give keys that don't confirm`() {
        val one = Spake2(Spake2.Role.A, w)
        val two = Spake2(Spake2.Role.B, w)
        val k1 = assertNotNull(one.finish(two.share, a, b))
        val k2 = assertNotNull(two.finish(one.share, a, "mallory".toByteArray()))
        assertFalse(k1.checkPeer(k2.mine))
    }

    @Test
    fun `different associated data gives keys that don't confirm`() {
        val one = Spake2(Spake2.Role.A, w)
        val two = Spake2(Spake2.Role.B, w)
        val k1 = assertNotNull(one.finish(two.share, a, b, "v1".toByteArray()))
        val k2 = assertNotNull(two.finish(one.share, a, b, "v2".toByteArray()))
        assertContentEquals(k1.ke, k2.ke)
        assertFalse(k1.checkPeer(k2.mine))
    }

    @Test
    fun `a share that isn't a point on the curve is refused`() {
        val one = { Spake2(Spake2.Role.A, w) }
        assertNull(one().finish(ByteArray(65), a, b))
        assertNull(one().finish(byteArrayOf(4), a, b))
        // The other side's w*N alone makes K the point at infinity.
        assertNull(one().finish(P256.encode(P256.multiply(SPAKE2_N, w)), a, b))
    }

    @Test
    fun `a side finishes once`() {
        val one = Spake2(Spake2.Role.A, w)
        val two = Spake2(Spake2.Role.B, w)
        one.finish(two.share, a, b)
        assertFailsWith<IllegalStateException> { one.finish(two.share, a, b) }
    }

    @Test
    fun `a pairing code becomes w through HKDF, reduced mod the order`() {
        val w1 = spake2Password("12345678".toByteArray())
        assertEquals(w1, spake2Password("12345678".toByteArray()))
        assertTrue(w1 < P256.N && w1.signum() > 0)
        assertFalse(w1 == spake2Password("12345679".toByteArray()))
    }
}
