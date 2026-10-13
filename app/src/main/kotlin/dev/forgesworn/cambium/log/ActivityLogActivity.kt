package dev.forgesworn.cambium.log

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.applock.AppLockPrompt
import dev.forgesworn.cambium.applock.AppLockStore
import dev.forgesworn.cambium.databinding.ActivityLogBinding
import dev.forgesworn.cambium.databinding.ItemActivityLogEntryBinding
import dev.forgesworn.cambium.displayNameFor
import dev.forgesworn.cambium.reserveDialogActions
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
 *
 * Gated behind the same app lock as `MainActivity` (see [AppLockPrompt.requiresAuthenticationNow]),
 * checked in [onResume] with a locked-state screen replacing the content exactly as there: the
 * log is metadata only, but "which apps used which identity, when" is still exactly what the lock
 * exists to keep from a casual phone-holder, and a gate that covers every screen but this one
 * would be a false promise. The manifest keeps this activity out of recents for the same reason.
 */
class ActivityLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var store: ActivityLogStore
    private lateinit var appLockStore: AppLockStore
    private var allEntries = emptyList<ActivityLogEntry>()
    private var filter = ActivityLogFilter()
    private var configuringFilters = false
    private var identityLabelsByKey = emptyMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ActivityLogStore.getInstance(this)
        appLockStore = AppLockStore(this)

        binding.logToggle.isChecked = store.isEnabled()
        binding.logToggle.setOnCheckedChangeListener { _, checked -> store.setEnabled(checked) }
        binding.clearButton.setOnClickListener { onClearClicked() }
        binding.shareReportButton.setOnClickListener { reviewReport() }
        binding.unlockButton.setOnClickListener { promptUnlock() }
        binding.backButton.setOnClickListener { finish() }

        // As in MainActivity, the lock check lives in onResume (which always runs right after
        // onCreate, before anything is drawn) -- render() is deliberately not called here, so a
        // locked phone never even reads the log into memory.
    }

    override fun onResume() {
        super.onResume()
        if (AppLockPrompt.requiresAuthenticationNow(this, appLockStore)) {
            showLocked()
        } else {
            binding.lockedSection.isVisible = false
            binding.contentSection.isVisible = true
            render()
        }
    }

    /** Mirrors `MainActivity.showLocked`: the prompt is triggered immediately; `unlockButton` is
     * the manual retry if it gets dismissed or fails. */
    private fun showLocked() {
        binding.contentSection.isVisible = false
        binding.lockedSection.isVisible = true
        promptUnlock()
    }

    private fun promptUnlock() {
        AppLockPrompt.authenticate(
            activity = this,
            title = getString(R.string.app_lock_prompt_title),
            onSuccess = {
                appLockStore.recordAuthenticated()
                binding.lockedSection.isVisible = false
                binding.contentSection.isVisible = true
                render()
            },
            onFailure = {
                // Stay locked; unlockButton lets the user retry without leaving the activity.
            },
        )
    }

    private fun render() {
        allEntries = store.entries()
        configureFilters()
        renderFiltered()
    }

    private fun configureFilters() {
        configuringFilters = true
        val apps: List<String?> = listOf(null) + allEntries.map { it.callingPackage }.distinct().sorted()
        val identityGroups = ActivityLogIdentity.groupsFor(allEntries)
        val identities: List<String?> = listOf(null) + identityGroups.map { it.key }
        val groupLabels = identityGroups.map { group ->
            val label = group.label ?: getString(R.string.diagnostics_identity_unnamed)
            if (group.isLegacy) getString(R.string.diagnostics_identity_older, label) else label
        }
        val duplicates = groupLabels.groupingBy { it }.eachCount()
        val occurrences = mutableMapOf<String, Int>()
        val identityLabels = listOf(getString(R.string.diagnostics_all_identities)) + groupLabels.map { label ->
            val number = occurrences.getOrDefault(label, 0) + 1
            occurrences[label] = number
            if (duplicates.getValue(label) > 1) getString(R.string.diagnostics_identity_numbered, label, number) else label
        }
        identityLabelsByKey = identityGroups.zip(identityLabels.drop(1)).associate { (group, label) -> group.key to label }
        val outcomes: List<ActivityLogEntry.Outcome?> = listOf(null) + ActivityLogEntry.Outcome.entries
        filter = filter.copy(
            callingPackage = filter.callingPackage?.takeIf { it in apps },
            identityLabel = null,
            identityKey = filter.identityKey?.takeIf { it in identities },
        )
        configureSpinner(binding.appFilter, apps.map { it?.let { name ->
            "${packageManager.displayNameFor(name)} ($name)"
        } ?: getString(R.string.diagnostics_all_apps) }, apps.indexOf(filter.callingPackage)) {
            filter = filter.copy(callingPackage = apps[it])
            renderFiltered()
        }
        configureSpinner(binding.identityFilter, identityLabels, identities.indexOf(filter.identityKey)) {
            filter = filter.copy(identityKey = identities[it])
            renderFiltered()
        }
        configureSpinner(binding.outcomeFilter, outcomes.map {
            it?.let { outcome -> getString(outcomeLabel(outcome)) } ?: getString(R.string.diagnostics_all_outcomes)
        }, outcomes.indexOf(filter.outcome)) {
            filter = filter.copy(outcome = outcomes[it])
            renderFiltered()
        }
        configuringFilters = false
    }

    private fun configureSpinner(spinner: Spinner, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        spinner.onItemSelectedListener = null
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(selected.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!configuringFilters) onSelect(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun renderFiltered() {
        val entries = filter.apply(allEntries)
        binding.logEmpty.isVisible = entries.isEmpty()
        binding.logEmpty.setText(if (allEntries.isEmpty()) R.string.activity_log_empty else R.string.diagnostics_filtered_empty)
        binding.shareReportButton.isEnabled = entries.isNotEmpty()
        binding.logContainer.removeAllViews()
        for (entry in entries) {
            val row = ItemActivityLogEntryBinding.inflate(layoutInflater, binding.logContainer, false)
            row.logTimestampValue.text = TIMESTAMP_FORMATTER.format(
                Instant.ofEpochMilli(entry.timestampMillis).atZone(ZoneId.systemDefault())
            )
            row.logAppValue.text = packageManager.displayNameFor(entry.callingPackage)
            row.logMethodValue.text = entry.eventKind?.let { kind -> "${entry.method} (kind $kind)" } ?: entry.method
            row.logIdentityValue.text = ActivityLogIdentity.keyFor(entry)?.let(identityLabelsByKey::get)
                ?: entry.identityLabel ?: NO_IDENTITY_PLACEHOLDER
            row.logOutcomeValue.text = getString(outcomeLabel(entry.outcome))
            row.logOutcomeValue.setTextColor(ContextCompat.getColor(this, when (entry.outcome) {
                ActivityLogEntry.Outcome.SIGNED, ActivityLogEntry.Outcome.ANSWERED_FROM_CACHE -> R.color.accent
                ActivityLogEntry.Outcome.REJECTED_POLICY, ActivityLogEntry.Outcome.REJECTED_USER -> R.color.warning
                ActivityLogEntry.Outcome.FAILED -> R.color.danger
            }))
            val diagnostics = buildList {
                entry.failureCategory?.let { add(getString(failureLabel(it))) }
                entry.durationMillis?.let { add(getString(R.string.diagnostics_duration, it.coerceAtLeast(0))) }
            }
            row.logDiagnosticsValue.isVisible = diagnostics.isNotEmpty()
            row.logDiagnosticsValue.text = diagnostics.joinToString(" · ")
            binding.logContainer.addView(row.root)
        }
    }

    private fun reviewReport() {
        if (AppLockPrompt.requiresAuthenticationNow(this, appLockStore)) {
            showLocked()
            return
        }
        val report = DiagnosticReport.format(
            filter.apply(allEntries),
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            Build.VERSION.SDK_INT,
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diagnostics_report_title)
            .setMessage(report)
            .setPositiveButton(R.string.diagnostics_share) { _, _ ->
                if (AppLockPrompt.requiresAuthenticationNow(this, appLockStore)) {
                    showLocked()
                } else {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_report_title))
                        putExtra(Intent.EXTRA_TEXT, report)
                    }, getString(R.string.diagnostics_share_chooser)))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        reserveDialogActions(dialog)
    }

    private fun failureLabel(category: ActivityLogEntry.FailureCategory): Int = when (category) {
        ActivityLogEntry.FailureCategory.NOT_CONNECTED -> R.string.diagnostics_failure_not_connected
        ActivityLogEntry.FailureCategory.BUSY -> R.string.diagnostics_failure_busy
        ActivityLogEntry.FailureCategory.TIMEOUT -> R.string.diagnostics_failure_timeout
        ActivityLogEntry.FailureCategory.INVALID_INPUT -> R.string.diagnostics_failure_invalid_input
        ActivityLogEntry.FailureCategory.POLICY_REFUSAL -> R.string.diagnostics_failure_policy
        ActivityLogEntry.FailureCategory.DECRYPTION_FAILED -> R.string.diagnostics_failure_decrypt
        ActivityLogEntry.FailureCategory.PROTOCOL -> R.string.diagnostics_failure_protocol
        ActivityLogEntry.FailureCategory.OTHER -> R.string.diagnostics_failure_other
    }

    private fun onClearClicked() {
        MaterialAlertDialogBuilder(this)
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
