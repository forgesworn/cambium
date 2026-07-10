package dev.forgesworn.cambium.log

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.databinding.ActivityLogBinding
import dev.forgesworn.cambium.databinding.ItemActivityLogEntryBinding
import dev.forgesworn.cambium.displayNameFor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only view of the on-phone activity log: newest first, an off-toggle (default on -- see
 * [ActivityLogStore]) and a Clear action. Loads every entry into memory and inflates one row per
 * entry -- capped at [ActivityLog.MAX_ENTRIES] (500) -- rather than a `RecyclerView`: this is an
 * occasionally-opened diagnostic screen, not a live feed, so the simplicity of a manual
 * `LinearLayout` outweighs `RecyclerView`'s added adapter/view-holder machinery for now, the same
 * trade-off `MainActivity`'s connected-apps list already makes at a smaller scale.
 */
class ActivityLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var store: ActivityLogStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ActivityLogStore(this)

        binding.logToggle.isChecked = store.isEnabled()
        binding.logToggle.setOnCheckedChangeListener { _, checked -> store.setEnabled(checked) }
        binding.clearButton.setOnClickListener { onClearClicked() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val entries = store.entries()
        binding.logEmpty.isVisible = entries.isEmpty()
        binding.logContainer.removeAllViews()
        for (entry in entries) {
            val row = ItemActivityLogEntryBinding.inflate(layoutInflater, binding.logContainer, false)
            row.logTimestampValue.text = TIMESTAMP_FORMATTER.format(
                Instant.ofEpochMilli(entry.timestampMillis).atZone(ZoneId.systemDefault())
            )
            row.logAppValue.text = packageManager.displayNameFor(entry.callingPackage)
            row.logMethodValue.text = entry.eventKind?.let { kind -> "${entry.method} (kind $kind)" } ?: entry.method
            row.logIdentityValue.text = entry.identityLabel ?: NO_IDENTITY_PLACEHOLDER
            row.logOutcomeValue.text = getString(outcomeLabel(entry.outcome))
            binding.logContainer.addView(row.root)
        }
    }

    private fun onClearClicked() {
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_log_clear_confirm_title)
            .setMessage(R.string.activity_log_clear_confirm_body)
            .setPositiveButton(R.string.activity_log_clear_button) { _, _ ->
                store.clear()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun outcomeLabel(outcome: ActivityLogEntry.Outcome): Int = when (outcome) {
        ActivityLogEntry.Outcome.SIGNED -> R.string.activity_log_outcome_signed
        ActivityLogEntry.Outcome.ANSWERED_FROM_CACHE -> R.string.activity_log_outcome_cached
        ActivityLogEntry.Outcome.REJECTED_POLICY -> R.string.activity_log_outcome_rejected_policy
        ActivityLogEntry.Outcome.REJECTED_USER -> R.string.activity_log_outcome_rejected_user
        ActivityLogEntry.Outcome.FAILED -> R.string.activity_log_outcome_failed
    }

    private companion object {
        // An empty-value placeholder in a table/row context -- see CLAUDE.md's UI copy convention.
        const val NO_IDENTITY_PLACEHOLDER = "--"
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
