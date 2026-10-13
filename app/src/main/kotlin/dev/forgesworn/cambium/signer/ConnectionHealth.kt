package dev.forgesworn.cambium.signer

/** Last observed contact, never a claim that the network is still online. No payloads or secrets. */
data class ConnectionHealth(
    val status: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val lastSuccessfulContactMillis: Long? = null,
    val errorCategory: ConnectionFailure? = null,
    val relayUpdate: RelayUpdateStatus = RelayUpdateStatus.UNCHECKED,
)

enum class ConnectionStatus { UNKNOWN, CHECKING, RECONNECTING, CONNECTED, UNAVAILABLE }
enum class ConnectionFailure { TIMEOUT, NOT_CONNECTED, REFUSED, INVALID_INPUT, PROTOCOL, BUSY }
enum class RelayUpdateStatus { UNCHECKED, UNCHANGED, UPDATED, UNSUPPORTED, UNAVAILABLE, INVALID_RESPONSE }

internal fun HeartwoodError.safeCategory(): ConnectionFailure = when (this) {
    HeartwoodError.Timeout -> ConnectionFailure.TIMEOUT
    HeartwoodError.NotConnected -> ConnectionFailure.NOT_CONNECTED
    HeartwoodError.Busy -> ConnectionFailure.BUSY
    is HeartwoodError.InvalidInput -> ConnectionFailure.INVALID_INPUT
    is HeartwoodError.Protocol -> if (isPolicyRefusal(this)) ConnectionFailure.REFUSED else ConnectionFailure.PROTOCOL
}
