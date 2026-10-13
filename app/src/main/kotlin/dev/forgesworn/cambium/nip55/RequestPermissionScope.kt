package dev.forgesworn.cambium.nip55

import dev.forgesworn.cambium.pairing.PermissionScope

fun Nip55Request.permissionMethod(): String = when (this) {
    is Nip55Request.GetPublicKey -> Nip55Request.TYPE_GET_PUBLIC_KEY
    is Nip55Request.SignEvent -> Nip55Request.TYPE_SIGN_EVENT
    is Nip55Request.Nip04Encrypt -> Nip55Request.TYPE_NIP04_ENCRYPT
    is Nip55Request.Nip04Decrypt -> Nip55Request.TYPE_NIP04_DECRYPT
    is Nip55Request.Nip44Encrypt -> Nip55Request.TYPE_NIP44_ENCRYPT
    is Nip55Request.Nip44Decrypt -> Nip55Request.TYPE_NIP44_DECRYPT
    is Nip55Request.DecryptZapEvent -> Nip55Request.TYPE_DECRYPT_ZAP_EVENT
}

fun Nip55Request.permissionEventKind(): Int? =
    (this as? Nip55Request.SignEvent)?.let { extractEventKind(it.eventJson) }

/** Restricts future consent to this method, and to this exact kind for a sign request.
 * A malformed signing kind grants no future signing authority. Client-supplied requested
 * permissions are never automatically converted into authority. */
fun Nip55Request.permissionScope(): PermissionScope = PermissionScope(
    methods = setOf(permissionMethod()),
    signEventKinds = if (this is Nip55Request.SignEvent) {
        permissionEventKind()?.let { setOf(it) } ?: emptySet()
    } else null,
)
