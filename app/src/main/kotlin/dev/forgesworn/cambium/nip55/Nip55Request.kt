package dev.forgesworn.cambium.nip55

/**
 * The extras and payload lifted off an incoming `nostrsigner:` intent, reduced to plain strings.
 * Kept free of `android.content.Intent` so [Nip55Request.from] is unit-testable on the host JVM;
 * the actual `Intent` -> [RawSignerIntent] mapping is a thin function in [SignerActivity].
 */
data class RawSignerIntent(
    val payload: String?,
    val type: String?,
    val id: String?,
    val currentUser: String?,
    val pubkey: String?,
    /** `get_public_key` only: the raw JSON `permissions` extra, if the client sent one. */
    val permissions: String? = null,
)

/** One parsed NIP-55 request. See nips/55.md for the wire contract this mirrors. */
sealed interface Nip55Request {
    val id: String?
    val currentUser: String?

    /** [permissions] is the client's optional pre-authorisation list (NIP-55's `permissions`
     * extra), already parsed -- display-only, see [RequestedPermission]. */
    data class GetPublicKey(
        override val id: String?,
        override val currentUser: String?,
        val permissions: List<RequestedPermission> = emptyList(),
    ) : Nip55Request

    data class SignEvent(
        override val id: String?,
        override val currentUser: String?,
        val eventJson: String,
    ) : Nip55Request

    data class Nip04Encrypt(
        override val id: String?,
        override val currentUser: String?,
        val pubkeyHex: String,
        val plaintext: String,
    ) : Nip55Request

    data class Nip04Decrypt(
        override val id: String?,
        override val currentUser: String?,
        val pubkeyHex: String,
        val ciphertext: String,
    ) : Nip55Request

    data class Nip44Encrypt(
        override val id: String?,
        override val currentUser: String?,
        val pubkeyHex: String,
        val plaintext: String,
    ) : Nip55Request

    data class Nip44Decrypt(
        override val id: String?,
        override val currentUser: String?,
        val pubkeyHex: String,
        val ciphertext: String,
    ) : Nip55Request

    /** [eventJson] is a kind-9734 zap request; see [dev.forgesworn.cambium.nip57.PrivateZap]. No
     * `pubkey` extra -- unlike the crypto methods, the counterparty is derived from the event
     * itself, not passed separately. */
    data class DecryptZapEvent(
        override val id: String?,
        override val currentUser: String?,
        val eventJson: String,
    ) : Nip55Request

    companion object {
        const val TYPE_GET_PUBLIC_KEY = "get_public_key"
        const val TYPE_SIGN_EVENT = "sign_event"
        const val TYPE_NIP04_ENCRYPT = "nip04_encrypt"
        const val TYPE_NIP04_DECRYPT = "nip04_decrypt"
        const val TYPE_NIP44_ENCRYPT = "nip44_encrypt"
        const val TYPE_NIP44_DECRYPT = "nip44_decrypt"
        const val TYPE_DECRYPT_ZAP_EVENT = "decrypt_zap_event"

        /** Null when [raw]'s `type` is unrecognised or a required field (payload/pubkey) is missing. */
        fun from(raw: RawSignerIntent): Nip55Request? {
            return when (raw.type?.lowercase()) {
                TYPE_GET_PUBLIC_KEY -> GetPublicKey(raw.id, raw.currentUser, parseRequestedPermissions(raw.permissions))

                TYPE_SIGN_EVENT -> raw.payload?.takeIf { it.isNotBlank() }?.let { eventJson ->
                    SignEvent(raw.id, raw.currentUser, eventJson)
                }

                TYPE_NIP04_ENCRYPT -> cryptoRequest(raw) { pubkey, payload ->
                    Nip04Encrypt(raw.id, raw.currentUser, pubkey, payload)
                }

                TYPE_NIP04_DECRYPT -> cryptoRequest(raw) { pubkey, payload ->
                    Nip04Decrypt(raw.id, raw.currentUser, pubkey, payload)
                }

                TYPE_NIP44_ENCRYPT -> cryptoRequest(raw) { pubkey, payload ->
                    Nip44Encrypt(raw.id, raw.currentUser, pubkey, payload)
                }

                TYPE_NIP44_DECRYPT -> cryptoRequest(raw) { pubkey, payload ->
                    Nip44Decrypt(raw.id, raw.currentUser, pubkey, payload)
                }

                TYPE_DECRYPT_ZAP_EVENT -> raw.payload?.takeIf { it.isNotBlank() }?.let { eventJson ->
                    DecryptZapEvent(raw.id, raw.currentUser, eventJson)
                }

                else -> null
            }
        }

        private inline fun cryptoRequest(
            raw: RawSignerIntent,
            build: (pubkeyHex: String, payload: String) -> Nip55Request,
        ): Nip55Request? {
            val pubkey = raw.pubkey?.takeIf { it.isNotBlank() } ?: return null
            val payload = raw.payload?.takeIf { it.isNotBlank() } ?: return null
            return build(pubkey, payload)
        }
    }
}
