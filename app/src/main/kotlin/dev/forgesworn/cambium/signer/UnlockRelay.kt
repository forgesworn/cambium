package dev.forgesworn.cambium.signer

import android.util.Log
import dev.forgesworn.cambium.unlock.PhoneUnlock
import dev.forgesworn.cambium.unlock.RawAnnouncement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Alphabet
import rust.nostr.sdk.Client
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.SecretKey
import rust.nostr.sdk.SingleLetterTag
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.nip44Decrypt
import rust.nostr.sdk.nip44Encrypt
import rust.nostr.sdk.Nip44Version
import java.time.Duration

/**
 * The rust-nostr half of phone unlock, next to [RustNostrHeartwoodClient] for the same reason that
 * file exists: nothing outside `signer/` imports `rust.nostr.sdk`, so the rest of the feature
 * (`unlock/`) stays plain Kotlin.
 *
 * Every key made here is thrown away after one use. The relay client has **no signer at all**, so
 * it cannot answer a NIP-42 challenge with a stable key: nothing it does on a relay is tied to
 * this phone beyond its IP address (see the phone unlock design, section 6).
 */
object UnlockNostr {

    /** A one-off enrolment keypair: `(secret hex, public hex)`. */
    fun newEnrolmentKey(): Pair<String, String> {
        val keys = Keys.generate()
        return keys.secretKey().toHex() to keys.publicKey().toHex()
    }

    /** Opens the board's sealed hand-off with the enrolment key, or null if it is not ours. */
    fun openHandOff(enrolSecretHex: String, ephemeralPubkeyHex: String, sealed: String): String? = runCatching {
        nip44Decrypt(SecretKey.parse(enrolSecretHex), PublicKey.parse(ephemeralPubkeyHex), sealed)
    }.getOrNull()

    /**
     * A kind-24136 delivery from a fresh throwaway key, p-tagged to the board's one-time key and
     * NIP-44-encrypted to it. Holding the slot secret inside is the proof, so the author can be
     * anyone, and is never the same twice.
     */
    fun deliveryEvent(boardOneTimePubkeyHex: String, plaintext: String): Event {
        val throwaway = Keys.generate()
        val board = PublicKey.parse(boardOneTimePubkeyHex)
        val content = nip44Encrypt(throwaway.secretKey(), board, plaintext, Nip44Version.V2)
        return EventBuilder(Kind(PhoneUnlock.DELIVERY_KIND.toUShort()), content)
            .tags(listOf(Tag.publicKey(board)))
            .signWithKeys(throwaway)
    }
}

/**
 * One relay connection set, listening for one filter and able to publish. Used twice: the
 * background lock listener (every kind 24135, no author, `p` or `h` filter, matched locally) and
 * the enrolment screen (kind [PhoneUnlock.HANDOFF_KIND] tagged with the one-off rendezvous tag).
 *
 * Mirrors [RustNostrHeartwoodClient]'s rule for native calls: each runs under [NonCancellable] on
 * [Dispatchers.IO], and the long-running notification loop is ended with `shutdown()`, never by
 * cancelling the coroutine that is inside the FFI call.
 */
class RelayWatch private constructor(
    private val tag: String,
    private val onEvent: (RawAnnouncement) -> Unit,
) {
    private val client = Client()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun start(relays: List<String>, filter: Filter) = withContext(NonCancellable + Dispatchers.IO) {
        client.automaticAuthentication(false)
        for (relay in relays) {
            runCatching { client.addRelay(RelayUrl.parse(relay)) }
                .onFailure { Log.w(tag, "relay refused: $relay", it) }
        }
        client.connect()
        client.waitForConnection(CONNECT_WAIT)
        client.subscribe(filter, null)
        scope.launch {
            runCatching {
                client.handleNotifications(object : HandleNotification {
                    override suspend fun handle(relayUrl: RelayUrl, subscriptionId: String, event: Event) {
                        runCatching { onEvent(event.toRaw()) }.onFailure { Log.w(tag, "event handler failed", it) }
                    }

                    override suspend fun handleMsg(relayUrl: RelayUrl, msg: RelayMessage) = Unit
                })
            }.onFailure { Log.w(tag, "notification loop ended", it) }
        }
    }

    /** Publishes to every connected relay; true if at least one accepted it. */
    suspend fun publish(event: Event): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            client.waitForConnection(CONNECT_WAIT)
            client.sendEvent(event).success.isNotEmpty()
        }.onFailure { Log.w(tag, "publish failed", it) }.getOrDefault(false)
    }

    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { client.shutdown() }
        client.close()
    }

    private fun Event.toRaw() = RawAnnouncement(
        eventId = id().toHex(),
        authorHex = author().toHex(),
        createdAt = createdAt().asSecs().toLong(),
        content = content(),
        hint = tags().toVec().firstOrNull { it.asVec().firstOrNull() == PhoneUnlock.HINT_TAG }?.asVec()?.getOrNull(1),
    )

    companion object {
        private val CONNECT_WAIT: Duration = Duration.ofSeconds(10)

        /**
         * Every lock announcement on [relays]. No filter beyond the kind: the relay learns only
         * that this client reads lock announcements, as every Cambium does. Since a minute ago,
         * so a listener that just (re)started does not wait a full announce period; the board
         * repeats every 60 s anyway.
         */
        suspend fun locks(relays: List<String>, onEvent: (RawAnnouncement) -> Unit): RelayWatch =
            RelayWatch("CambiumUnlockListen", onEvent).also {
                it.start(
                    relays,
                    Filter().kind(Kind(PhoneUnlock.ANNOUNCE_KIND.toUShort()))
                        .since(Timestamp.fromSecs((System.currentTimeMillis() / 1000 - 60).toULong())),
                )
            }

        /** The enrolment hand-off for one rendezvous tag. */
        suspend fun handOff(relays: List<String>, rendezvous: String, onEvent: (RawAnnouncement) -> Unit): RelayWatch =
            RelayWatch("CambiumUnlockEnrol", onEvent).also {
                it.start(
                    relays,
                    Filter().kind(Kind(PhoneUnlock.HANDOFF_KIND.toUShort()))
                        .customTag(SingleLetterTag.lowercase(Alphabet.H), rendezvous),
                )
            }
    }
}
