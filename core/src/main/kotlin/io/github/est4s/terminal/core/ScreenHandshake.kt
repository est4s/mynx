package io.github.est4s.terminal.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** This phone on the link: its id ([ScreenPairs.selfId]) and the name the other phone shows. */
data class ScreenMe(val id: String, val name: String)

/** A handshake one side refused, and why (for people). */
class ScreenRefused(val reason: RefusalReason, message: String) : IOException(message)

/** A finished handshake: who the other phone is, the pair's long-term key and the encrypted link. */
class ScreenLinked(
    val peerId: String,
    val peerName: String,
    val mode: HelloMode,
    val pairKey: ByteArray,
    val channel: ScreenChannel,
)

internal const val WRONG_CODE = "wrong code: pairing failed (check the code the host shows)"
internal const val STALE_PAIR = "the host doesn't know this phone any more: pair again (mynx screen pair CODE)"

private fun identity(role: ScreenRole, id: String, name: String) = "${role.word}\n$id\n$name".toByteArray()

private fun aad(mode: HelloMode) = "mynx-screen $SCREEN_PROTOCOL ${mode.name.lowercase()}".toByteArray()

// w: the code's, or for a paired phone its key's (high entropy, so SPAKE2 only adds forward secrecy).
private fun password(mode: HelloMode, secret: ByteArray) =
    spake2Password(secret, if (mode == HelloMode.PAIR) "mynx-screen pairing code" else "mynx-screen pair key")

private fun pairKey(mode: HelloMode, secret: ByteArray, keys: Spake2Keys): ByteArray =
    if (mode == HelloMode.RESUME) secret
    else hkdf(keys.ke, MessageDigest.getInstance("SHA-256").digest(keys.transcript), "mynx-screen pair key".toByteArray(), 32)

private fun refuse(out: OutputStream, reason: RefusalReason, text: String): Nothing {
    runCatching { writePlainFrame(out, ScreenMessage.Refusal(reason, text)) }
    throw ScreenRefused(reason, text)
}

/**
 * The screen's side of the handshake (SPAKE2's B): Hello with its
 * share, the host's Welcome (its share and confirmation, checked
 * here), then its own Confirm. [secret] is the code typed (pairing) or
 * the pair's key (coming back to [hostId]). Throws [ScreenRefused]
 * (wrong code, the host's refusal) or an [IOException].
 */
fun joinHost(
    input: InputStream,
    out: OutputStream,
    me: ScreenMe,
    mode: HelloMode,
    secret: ByteArray,
    hostId: String? = null,
): ScreenLinked {
    val spake = Spake2(Spake2.Role.B, password(mode, secret))
    writePlainFrame(out, ScreenMessage.Hello(SCREEN_PROTOCOL, me.id, me.name, mode, spake.share))
    val welcome = when (val answer = readPlainFrame(input)) {
        is ScreenMessage.Welcome -> answer
        is ScreenMessage.Refusal -> throw ScreenRefused(answer.reason, answer.text)
        else -> throw ScreenProtocolException("expected the host's Welcome")
    }
    if (welcome.version != SCREEN_PROTOCOL) {
        refuse(out, RefusalReason.VERSION, "the host speaks version ${welcome.version}, this phone $SCREEN_PROTOCOL: update mynx on both")
    }
    if (hostId != null && welcome.id != hostId) {
        refuse(out, RefusalReason.NOT_PAIRED, "another phone answered at the host's address")
    }
    val keys = spake.finish(
        welcome.share, identity(ScreenRole.HOST, welcome.id, welcome.name), identity(ScreenRole.SCREEN, me.id, me.name), aad(mode),
    )
    if (keys == null || !keys.checkPeer(welcome.confirm)) {
        if (mode == HelloMode.PAIR) refuse(out, RefusalReason.WRONG_CODE, WRONG_CODE)
        refuse(out, RefusalReason.NOT_PAIRED, STALE_PAIR)
    }
    writePlainFrame(out, ScreenMessage.Confirm(keys.mine))
    return ScreenLinked(
        welcome.id, welcome.name, mode, pairKey(mode, secret, keys),
        ScreenChannel(sessionKeys(keys.ke, keys.transcript), ScreenRole.SCREEN),
    )
}

/**
 * The host's side (SPAKE2's A): a Hello, then [secretFor] gives the
 * secret for it (the code shown, or that phone's pair key) or throws
 * [ScreenRefused], which is sent on. A Confirm that doesn't check out
 * (a wrong code) throws [ScreenRefused] with [RefusalReason.WRONG_CODE],
 * so the host can count it as the code's one try.
 */
fun acceptScreen(
    input: InputStream,
    out: OutputStream,
    me: ScreenMe,
    secretFor: (ScreenMessage.Hello) -> ByteArray,
): ScreenLinked {
    val hello = readPlainFrame(input) as? ScreenMessage.Hello ?: throw ScreenProtocolException("expected a Hello")
    if (hello.version != SCREEN_PROTOCOL) {
        refuse(out, RefusalReason.VERSION, "this host speaks version $SCREEN_PROTOCOL, the screen ${hello.version}: update mynx on both")
    }
    if (!SCREEN_ID.matches(hello.id)) throw ScreenProtocolException("a Hello without a phone's id")
    val secret = try {
        secretFor(hello)
    } catch (e: ScreenRefused) {
        refuse(out, e.reason, e.message ?: "refused")
    }
    val spake = Spake2(Spake2.Role.A, password(hello.mode, secret))
    val keys = spake.finish(
        hello.share, identity(ScreenRole.HOST, me.id, me.name), identity(ScreenRole.SCREEN, hello.id, hello.name), aad(hello.mode),
    ) ?: throw ScreenProtocolException("a share that isn't a point")
    writePlainFrame(out, ScreenMessage.Welcome(SCREEN_PROTOCOL, me.id, me.name, spake.share, keys.mine))
    val failed = if (hello.mode == HelloMode.PAIR) RefusalReason.WRONG_CODE else RefusalReason.NOT_PAIRED
    val confirm = try {
        readPlainFrame(input)
    } catch (e: IOException) {
        // A screen that leaves here has seen the Welcome: the code's try is used up.
        throw ScreenRefused(failed, "the screen left before confirming")
    }
    when (confirm) {
        is ScreenMessage.Confirm -> if (!keys.checkPeer(confirm.confirm)) {
            refuse(out, failed, if (failed == RefusalReason.WRONG_CODE) WRONG_CODE else STALE_PAIR)
        }
        is ScreenMessage.Refusal -> throw ScreenRefused(confirm.reason, confirm.text)
        else -> throw ScreenRefused(failed, "expected the screen's Confirm")
    }
    return ScreenLinked(
        hello.id, hello.name, hello.mode, pairKey(hello.mode, secret, keys),
        ScreenChannel(sessionKeys(keys.ke, keys.transcript), ScreenRole.HOST),
    )
}
