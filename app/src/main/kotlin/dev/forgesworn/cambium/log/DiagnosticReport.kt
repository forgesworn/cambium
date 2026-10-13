package dev.forgesworn.cambium.log

/** Local filters compare stored metadata exactly and keep the original ordering. */
data class ActivityLogFilter(
    val callingPackage: String? = null,
    val identityLabel: String? = null,
    val outcome: ActivityLogEntry.Outcome? = null,
    val identityKey: String? = null,
) {
    fun apply(entries: List<ActivityLogEntry>): List<ActivityLogEntry> = entries.filter { entry ->
        (callingPackage == null || entry.callingPackage == callingPackage) &&
            (identityLabel == null || entry.identityLabel == identityLabel) &&
            (identityKey == null || ActivityLogIdentity.keyFor(entry) == identityKey) &&
            (outcome == null || entry.outcome == outcome)
    }
}

/**
 * Explicit allowlist export boundary. App names and identity labels stay on the phone; numbered
 * aliases keep grouping useful without exporting arbitrary user-supplied text. No entry is
 * serialised wholesale, so a future field cannot silently become part of a shared report.
 */
object DiagnosticReport {
    private val METHODS = setOf(
        "get_public_key", "sign_event", "nip04_encrypt", "nip04_decrypt", "nip44_encrypt",
        "nip44_decrypt", "decrypt_zap_event", "ping", "get_relays",
    )
    private val VERSION = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9.]+)?")

    fun format(entries: List<ActivityLogEntry>, appVersion: String, androidApi: Int): String {
        val capped = entries.take(ActivityLog.MAX_ENTRIES)
        val apps = capped.map { it.callingPackage }.distinct().withIndex().associate { it.value to "app-${it.index + 1}" }
        val identities = capped.mapNotNull(ActivityLogIdentity::keyFor).distinct().withIndex()
            .associate { it.value to "identity-${it.index + 1}" }
        return buildString {
            appendLine("Cambium diagnostic report v1")
            appendLine("Cambium version: ${appVersion.takeIf { VERSION.matches(it) } ?: "unknown"}")
            appendLine("Android API: ${androidApi.takeIf { it in 1..100 } ?: "unknown"}")
            appendLine("Entries: ${capped.size}")
            appendLine("App and identity names are replaced with report-local aliases.")
            appendLine("Older entries without a private identity reference are grouped by their recorded label.")
            appendLine("Times are UTC epoch milliseconds; duration is measured on the phone.")
            appendLine("time_ms | app | identity | method | kind | outcome | failure | duration_ms")
            for (entry in capped) {
                append(entry.timestampMillis.coerceAtLeast(0))
                append(" | ${apps.getValue(entry.callingPackage)}")
                append(" | ${ActivityLogIdentity.keyFor(entry)?.let { identities.getValue(it) } ?: "none"}")
                append(" | ${entry.method.takeIf { it in METHODS } ?: "unknown"}")
                append(" | ${entry.eventKind?.takeIf { it >= 0 } ?: "none"}")
                append(" | ${entry.outcome.name}")
                append(" | ${entry.failureCategory?.name ?: "none"}")
                appendLine(" | ${entry.durationMillis?.coerceAtLeast(0) ?: "unknown"}")
            }
        }
    }
}
