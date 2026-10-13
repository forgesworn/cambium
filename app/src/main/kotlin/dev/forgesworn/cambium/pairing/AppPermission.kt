package dev.forgesworn.cambium.pairing

import kotlinx.serialization.Serializable

/** Local Cambium consent only. Heartwood remains the authority for cryptographic operations.
 * Defaults retain the indefinite, whole-app meaning of approvals written by older releases. */
@Serializable
data class AppPermission(
    val state: AppPermissionState,
    val boundIdentityPubkeyHex: String?,
    val expiresAtMillis: Long? = null,
    val scope: PermissionScope? = null,
    val grantedAtMillis: Long? = null,
) {
    fun isActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        (grantedAtMillis == null || nowMillis >= grantedAtMillis) &&
            (expiresAtMillis == null || nowMillis < expiresAtMillis)

    fun allows(method: String, eventKind: Int? = null, nowMillis: Long = System.currentTimeMillis()): Boolean =
        state == AppPermissionState.APPROVED && isActive(nowMillis) &&
            // Public identity discovery remains usable even for a method-restricted approval.
            (method == "get_public_key" || scope == null || scope.allows(method, eventKind))
}

@Serializable
enum class AppPermissionState { APPROVED, DENIED }

/** An explicit set of allowed methods. Null [signEventKinds] allows all signing kinds;
 * an empty set allows none. An unreadable kind never matches a restricted kind set. */
@Serializable
data class PermissionScope(
    val methods: Set<String>,
    val signEventKinds: Set<Int>? = null,
) {
    fun allows(method: String, eventKind: Int? = null): Boolean =
        method in methods &&
            (method != "sign_event" || signEventKinds == null ||
                (eventKind != null && eventKind in signEventKinds))
}

enum class ApprovalLifetime(private val durationMillis: Long?) {
    ONCE(0), HOUR(3_600_000), DAY(86_400_000), ALWAYS(null);

    /** ONCE has no remembered approval. A clock set back before a timed grant fails closed. */
    fun permission(
        identityPubkeyHex: String,
        scope: PermissionScope? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): AppPermission? {
        if (this == ONCE) return null
        val expiresAt = durationMillis?.let {
            if (nowMillis > Long.MAX_VALUE - it) Long.MAX_VALUE else nowMillis + it
        }
        return AppPermission(
            AppPermissionState.APPROVED,
            identityPubkeyHex,
            expiresAtMillis = expiresAt,
            scope = scope,
            grantedAtMillis = if (durationMillis == null) null else nowMillis,
        )
    }
}
