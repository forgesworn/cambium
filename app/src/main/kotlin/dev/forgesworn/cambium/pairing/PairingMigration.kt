package dev.forgesworn.cambium.pairing

/**
 * Pure transform from Cambium 0.2.x's single-pairing schema to 0.3.0's list-of-pairings schema,
 * kept separate from [PairingStore] so the transform itself is JVM-testable without Android's
 * EncryptedSharedPreferences -- [PairingStore] only has to read the old flat keys into [Legacy],
 * call [migrate], and persist the result.
 *
 * The migrated pairing gets no label (`null` -- [PairingStore]'s callers fall back to a truncated
 * npub for display, same as any other unlabelled pairing). Every previously-approved package
 * migrates bound to the migrated pairing's identity, since it was the only identity that existed;
 * denied packages carry no binding at all, since denial was never identity-specific.
 */
object PairingMigration {

    /** Everything 0.2.x stored under the old flat keys. */
    data class Legacy(
        val signerPubkeyHex: String,
        val relays: List<String>,
        val secret: String?,
        val clientSecretKeyHex: String,
        val clientPublicKeyHex: String,
        val approvedPackages: Set<String>,
        val deniedPackages: Set<String>,
    )

    data class Migrated(
        val pairings: List<Pairing>,
        val permissions: Map<String, AppPermission>,
    )

    fun migrate(legacy: Legacy): Migrated {
        val pairing = Pairing(
            signerPubkeyHex = legacy.signerPubkeyHex,
            relays = legacy.relays,
            secret = legacy.secret,
            clientSecretKeyHex = legacy.clientSecretKeyHex,
            clientPublicKeyHex = legacy.clientPublicKeyHex,
            label = null,
        )
        val permissions = legacy.approvedPackages.associateWith {
            AppPermission(AppPermissionState.APPROVED, pairing.signerPubkeyHex)
        } + legacy.deniedPackages.associateWith {
            AppPermission(AppPermissionState.DENIED, boundIdentityPubkeyHex = null)
        }
        return Migrated(pairings = listOf(pairing), permissions = permissions)
    }
}
