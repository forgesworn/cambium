package dev.forgesworn.cambium

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import dev.forgesworn.cambium.applock.AppLockPrompt
import dev.forgesworn.cambium.applock.AppLockStore
import dev.forgesworn.cambium.databinding.ActivityMainBinding
import dev.forgesworn.cambium.databinding.ItemConnectedAppBinding
import dev.forgesworn.cambium.databinding.ItemPairingBinding
import dev.forgesworn.cambium.databinding.ItemUnlockBoardBinding
import dev.forgesworn.cambium.log.ActivityLogActivity
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.AppPermission
import dev.forgesworn.cambium.pairing.ApprovalLifetime
import dev.forgesworn.cambium.pairing.PermissionScopeEditor
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.BunkerUriParser
import dev.forgesworn.cambium.pairing.BunkerUriResult
import dev.forgesworn.cambium.pairing.Pairing
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.pairing.QrPairingScan
import dev.forgesworn.cambium.pairing.QrScanResult
import dev.forgesworn.cambium.service.HeartwoodKeepAliveService
import dev.forgesworn.cambium.signer.HeartwoodClient
import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import dev.forgesworn.cambium.signer.RustNostrHeartwoodClient
import dev.forgesworn.cambium.signer.ConnectionHealth
import dev.forgesworn.cambium.signer.ConnectionStatus
import dev.forgesworn.cambium.signer.ConnectionFailure
import dev.forgesworn.cambium.signer.RelayUpdateStatus
import dev.forgesworn.cambium.signer.displayLabel
import dev.forgesworn.cambium.signer.npubDisplay
import dev.forgesworn.cambium.unlock.UnlockActivity
import dev.forgesworn.cambium.unlock.UnlockCoordinator
import dev.forgesworn.cambium.unlock.UnlockEnrolActivity
import dev.forgesworn.cambium.unlock.UnlockEnrolment
import dev.forgesworn.cambium.unlock.UnlockNotifications
import dev.forgesworn.cambium.unlock.UnlockStore
import kotlinx.coroutines.launch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Status screen: paired or not, scan-or-paste-a-bunker-URI pairing flow for one or more signers,
 * and per-pairing connection details.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pairingStore: PairingStore
    private lateinit var appLockStore: AppLockStore
    private lateinit var unlockStore: UnlockStore
    private val manualConnections = mutableSetOf<String>()

    // Registered as fields (not inside onCreate) per the ActivityResult contract: this must
    // happen before the activity reaches STARTED, and works even though `binding` -- referenced
    // only inside these callbacks, which fire well after onCreate -- isn't ready yet here.
    private val scanLauncher = registerForActivityResult(ScanContract()) { result -> onScanResult(result) }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else binding.cameraHint.isVisible = true
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            setKeepAliveEnabled(true)
        } else {
            binding.notificationHint.isVisible = true
            setKeepAliveToggleChecked(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pairingStore = PairingStore(this)
        appLockStore = AppLockStore(this)
        unlockStore = UnlockStore(this)

        binding.pairButton.setOnClickListener { onPairClicked() }
        binding.scanButton.setOnClickListener { onScanClicked() }
        binding.unpairAllButton.setOnClickListener { onUnpairAllClicked() }
        binding.activityLogButton.setOnClickListener { startActivity(Intent(this, ActivityLogActivity::class.java)) }
        binding.unlockButton.setOnClickListener { promptUnlock() }
        binding.unlockBatteryButton.setOnClickListener {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")),
            )
        }
        // A board asking to be unlocked shows on its row while this screen is open.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                UnlockCoordinator.requests.collect { if (binding.contentSection.isVisible) renderUnlockBoards() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                HeartwoodSession.health.collect {
                    if (binding.contentSection.isVisible) renderPairingsList(pairingStore.pairings())
                }
            }
        }

        // The actual lock check happens in onResume, which always runs right after onCreate too
        // (onCreate -> onStart -> onResume, nothing is drawn before onResume completes) -- no
        // separate check needed here, and calling render() here first would just be redundant
        // work immediately superseded by whatever onResume decides.
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

    /** Shown instead of [ActivityMainBinding.contentSection] whenever app lock is on, the device
     * still has a credential enrolled, and the grace window has expired -- see
     * [AppLockPrompt.requiresAuthenticationNow]. Triggers the system prompt immediately;
     * `unlockButton` is the manual retry if that prompt gets dismissed or fails. */
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
        renderAppLockToggle()
        val pairings = pairingStore.pairings()
        binding.statusValue.text = if (pairings.isEmpty()) {
            getString(R.string.status_unpaired)
        } else {
            resources.getQuantityString(R.plurals.status_paired_count, pairings.size, pairings.size)
        }
        binding.pairedSection.isVisible = pairings.isNotEmpty()
        renderUnlockBoards()
        if (pairings.isNotEmpty()) {
            renderPairingsList(pairings)
            setKeepAliveToggleChecked(pairingStore.isKeepAliveEnabled())
            // A denial only ever turns this hint on (see notificationPermissionLauncher); nothing
            // previously turned it back off if the user granted the permission from system
            // Settings instead of the in-app prompt and returned here via onResume.
            if (hasNotificationPermission()) {
                binding.notificationHint.isVisible = false
            }
            renderConnectedApps(pairings)
        }
    }

    /** Reflects the stored toggle when the device has a credential enrolled; forces the switch
     * off and disabled, with a static hint, when it does not -- matches the fail-open reasoning
     * in [AppLockPrompt.requiresAuthenticationNow]: the toggle itself must never claim to be on
     * when there is nothing Cambium could actually prompt the user with. */
    private fun renderAppLockToggle() {
        val available = AppLockPrompt.canAuthenticate(this)
        binding.appLockUnavailableHint.isVisible = !available
        binding.appLockToggle.isEnabled = available
        setAppLockToggleChecked(available && appLockStore.isEnabled())
    }

    /** Sets the switch's checked state without re-triggering [onAppLockToggled]. */
    private fun setAppLockToggleChecked(checked: Boolean) {
        binding.appLockToggle.setOnCheckedChangeListener(null)
        binding.appLockToggle.isChecked = checked
        binding.appLockToggle.setOnCheckedChangeListener { _, isChecked -> onAppLockToggled(isChecked) }
    }

    private fun onAppLockToggled(enabled: Boolean) {
        appLockStore.setEnabled(enabled)
        if (enabled) {
            // The user just interacted with this screen, proving presence -- starting the grace
            // window now avoids an immediate, redundant re-prompt on the very next resume.
            appLockStore.recordAuthenticated()
        }
    }

    /** One row per pairing, each with its own confirm-gated unpair action. */
    private fun renderPairingsList(pairings: List<Pairing>) {
        binding.pairingsListContainer.removeAllViews()
        for (pairing in pairings.sortedBy { it.displayLabel() }) {
            val row = ItemPairingBinding.inflate(layoutInflater, binding.pairingsListContainer, false)
            row.pairingLabelValue.text = pairing.displayLabel()
            row.pairingNpubValue.text = npubDisplay(pairing.signerPubkeyHex)
            row.pairingRelaysValue.text = pairing.relays.joinToString("\n")
            val health = HeartwoodSession.health.value[pairing.signerPubkeyHex] ?: ConnectionHealth()
            row.pairingHealthValue.text = healthDescription(health)
            val busy = pairing.signerPubkeyHex in manualConnections ||
                health.status == ConnectionStatus.CHECKING || health.status == ConnectionStatus.RECONNECTING
            row.pairingTestButton.isEnabled = !busy
            row.pairingReconnectButton.isEnabled = !busy
            row.pairingTestButton.setOnClickListener { checkConnection(pairing, reconnect = false) }
            row.pairingReconnectButton.setOnClickListener { checkConnection(pairing, reconnect = true) }
            row.pairingRenameButton.setOnClickListener { renameIdentity(pairing) }
            row.pairingUnpairButton.setOnClickListener { onUnpairRowClicked(pairing) }
            val unlockSetUp = unlockStore.enrolments().any { it.signerPubkeyHex == pairing.signerPubkeyHex }
            row.pairingUnlockButton.setText(if (unlockSetUp) R.string.unlock_setup_again_button else R.string.unlock_setup_button)
            row.pairingUnlockButton.setOnClickListener {
                startActivity(UnlockEnrolActivity.intent(this, pairing.signerPubkeyHex))
            }
            binding.pairingsListContainer.addView(row.root)
        }
    }

    private fun healthDescription(health: ConnectionHealth): String = buildList {
        add(getString(when (health.status) {
            ConnectionStatus.UNKNOWN -> R.string.connection_unknown
            ConnectionStatus.CHECKING -> R.string.connection_checking
            ConnectionStatus.RECONNECTING -> R.string.connection_reconnecting
            ConnectionStatus.CONNECTED -> R.string.connection_answered
            ConnectionStatus.UNAVAILABLE -> R.string.connection_unavailable
        }))
        add(health.lastSuccessfulContactMillis?.let { getString(R.string.connection_last_contact, dateTime(it)) }
            ?: getString(R.string.connection_never_contacted))
        health.errorCategory?.let {
            add(getString(when (it) {
                ConnectionFailure.TIMEOUT -> R.string.connection_error_timeout
                ConnectionFailure.NOT_CONNECTED -> R.string.connection_error_disconnected
                ConnectionFailure.REFUSED -> R.string.connection_error_refused
                ConnectionFailure.INVALID_INPUT -> R.string.connection_error_input
                ConnectionFailure.PROTOCOL -> R.string.connection_error_protocol
                ConnectionFailure.BUSY -> R.string.connection_error_busy
            }))
        }
        when (health.relayUpdate) {
            RelayUpdateStatus.UPDATED -> add(getString(R.string.relay_update_applied))
            RelayUpdateStatus.UNSUPPORTED -> add(getString(R.string.relay_update_unsupported))
            RelayUpdateStatus.UNAVAILABLE -> add(getString(R.string.relay_update_failed))
            RelayUpdateStatus.INVALID_RESPONSE -> add(getString(R.string.relay_update_invalid))
            else -> Unit
        }
    }.joinToString("\n")

    private fun checkConnection(pairing: Pairing, reconnect: Boolean) {
        if (!manualConnections.add(pairing.signerPubkeyHex)) return
        renderPairingsList(pairingStore.pairings())
        lifecycleScope.launch {
            try {
                val current = pairingStore.pairings().firstOrNull { it.signerPubkeyHex == pairing.signerPubkeyHex }
                    ?: return@launch
                if (reconnect) HeartwoodSession.reconnect(current) else HeartwoodSession.testConnection(current)
            } finally {
                manualConnections.remove(pairing.signerPubkeyHex)
                if (binding.contentSection.isVisible) renderPairingsList(pairingStore.pairings())
            }
        }
    }

    private fun renameIdentity(pairing: Pairing) {
        val inputLayout = TextInputLayout(this).apply { hint = getString(R.string.rename_identity_hint) }
        val input = TextInputEditText(inputLayout.context).apply { setText(pairing.label); maxLines = 2 }
        inputLayout.addView(input)
        val pad = (20 * resources.displayMetrics.density).toInt()
        inputLayout.setPadding(pad, 0, pad, 0)
        MaterialAlertDialogBuilder(this).setTitle(R.string.rename_identity).setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save_button) { _, _ ->
                if (!pairingStore.renamePairing(pairing.signerPubkeyHex, input.text?.toString())) changedToast()
                render()
            }.show().also(::reserveDialogActions)
    }

    private fun onUnpairRowClicked(pairing: Pairing) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.unpair_row_confirm_title, pairing.displayLabel()))
            .setMessage(R.string.unpair_row_confirm_body)
            .setPositiveButton(R.string.unpair_button) { _, _ ->
                pairingStore.removePairing(pairing.signerPubkeyHex)
                lifecycleScope.launch { HeartwoodSession.shutdown(pairing.signerPubkeyHex) }
                // The keep-alive service's own ping loop already stops itself once it next finds
                // pairings() empty (see HeartwoodKeepAliveService), but that could be up to a
                // full PING_INTERVAL_MILLIS away -- stopping it here immediately avoids leaving a
                // "keeping your signer warm" notification showing with nothing left to keep warm.
                if (pairingStore.pairings().isEmpty() && !unlockStore.hasEnrolments()) {
                    HeartwoodKeepAliveService.stop(this)
                }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onUnpairAllClicked() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unpair_all_confirm_title)
            .setMessage(R.string.unpair_all_confirm_body)
            .setPositiveButton(R.string.unpair_all_button) { _, _ ->
                pairingStore.clearAll()
                // Boards set up for phone unlock are kept (they are forgotten one by one, below),
                // and the service keeps listening for them.
                if (unlockStore.hasEnrolments()) {
                    HeartwoodKeepAliveService.start(this)
                } else {
                    HeartwoodKeepAliveService.stop(this)
                }
                lifecycleScope.launch { HeartwoodSession.shutdownAll() }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Boards this phone can unlock, each with a Forget action and, while it is asking, Unlock. */
    private fun renderUnlockBoards() {
        val boards = unlockStore.enrolments().sortedBy { it.boardLabel }
        binding.unlockSection.isVisible = boards.isNotEmpty()
        binding.unlockBoardsContainer.removeAllViews()
        for (board in boards) {
            val row = ItemUnlockBoardBinding.inflate(layoutInflater, binding.unlockBoardsContainer, false)
            row.boardLabelValue.text = board.boardLabel
            row.boardDetailValue.text = resources.getQuantityString(
                R.plurals.unlock_board_detail,
                board.relays.size,
                board.id,
                board.relays.size,
            )
            val asking = UnlockCoordinator.currentRequest(board.id) != null
            row.boardStatusValue.isVisible = asking
            row.boardStatusValue.setText(R.string.unlock_board_asking)
            row.boardUnlockButton.isVisible = asking
            row.boardUnlockButton.setOnClickListener { startActivity(UnlockActivity.intent(this, board.id)) }
            row.boardForgetButton.setOnClickListener { onForgetBoardClicked(board) }
            binding.unlockBoardsContainer.addView(row.root)
        }
        binding.unlockBatteryButton.isVisible = boards.isNotEmpty() && !UnlockEnrolActivity.isIgnoringBatteryOptimisations(this)
    }

    private fun onForgetBoardClicked(board: UnlockEnrolment) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.unlock_forget_confirm_title, board.boardLabel))
            .setMessage(getString(R.string.unlock_forget_confirm_body, board.id))
            .setPositiveButton(R.string.unlock_forget_button) { _, _ ->
                unlockStore.remove(board.id)
                UnlockCoordinator.forget(board.id)
                UnlockNotifications.cancelRequest(this, board.id)
                lifecycleScope.launch { UnlockCoordinator.sync(this@MainActivity) }
                if (!unlockStore.hasEnrolments() && (!pairingStore.isKeepAliveEnabled() || pairingStore.pairings().isEmpty())) {
                    HeartwoodKeepAliveService.stop(this)
                }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The approved/denied package list, with a "Forget" action per row that returns it to "ask".
     * An approved row also shows which paired identity it is bound to. */
    private fun renderConnectedApps(pairings: List<Pairing>) {
        val permissions = pairingStore.allPermissions().toList().sortedBy { (packageName, _) -> packageName }

        binding.connectedAppsEmpty.isVisible = permissions.isEmpty()
        binding.connectedAppsContainer.removeAllViews()

        for ((packageName, permission) in permissions) {
            val row = ItemConnectedAppBinding.inflate(layoutInflater, binding.connectedAppsContainer, false)
            row.appPackageValue.text = packageManager.displayNameFor(packageName)
            row.appStateValue.text = getString(
                when (permission.state) {
                    AppPermissionState.APPROVED -> R.string.connected_apps_state_approved
                    AppPermissionState.DENIED -> R.string.connected_apps_state_denied
                }
            )
            row.appStateValue.setTextColor(ContextCompat.getColor(this,
                if (permission.state == AppPermissionState.APPROVED) R.color.accent else R.color.danger,
            ))
            val boundIdentity = permission.boundIdentityPubkeyHex?.let { hex -> pairings.firstOrNull { it.signerPubkeyHex == hex } }
            row.appIdentityValue.isVisible = boundIdentity != null
            if (boundIdentity != null) {
                row.appIdentityValue.text = boundIdentity.displayLabel()
            }
            if (!permission.isActive()) row.appStateValue.setText(R.string.approval_expired)
            row.appPermissionDetail.isVisible = permission.state == AppPermissionState.APPROVED
            row.appPermissionDetail.text = listOf(
                permission.expiresAtMillis?.let { getString(R.string.approval_until, dateTime(it)) }
                    ?: getString(R.string.approval_no_expiry),
                PermissionScopeEditor.summary(this, permission.scope),
            ).joinToString("\n")
            row.editAppButton.isVisible = permission.state == AppPermissionState.APPROVED
            row.editAppButton.setOnClickListener { editAppPermission(packageName, permission) }
            row.forgetButton.setOnClickListener {
                pairingStore.forget(packageName)
                renderConnectedApps(pairings)
            }
            binding.connectedAppsContainer.addView(row.root)
        }
    }

    private fun editAppPermission(packageName: String, permission: AppPermission) {
        val actions = listOf(R.string.app_edit_identity, R.string.app_edit_scope, R.string.app_edit_duration)
        MaterialAlertDialogBuilder(this).setTitle(packageManager.displayNameFor(packageName))
            .setItems(actions.map(::getString).toTypedArray()) { _, index ->
                when (index) {
                    0 -> changeAppIdentity(packageName)
                    1 -> PermissionScopeEditor.show(this, permission.scope) { scope ->
                        val current = pairingStore.permission(packageName)
                        val identity = current?.boundIdentityPubkeyHex
                        if (identity == null || !pairingStore.updateApproval(packageName, identity, scope = scope, expected = permission)) changedToast()
                        render()
                    }
                    2 -> changeAppLifetime(packageName, permission)
                }
            }.show()
    }

    private fun changeAppIdentity(packageName: String) {
        val permission = pairingStore.permission(packageName)
        if (permission?.state != AppPermissionState.APPROVED) { changedToast(); return }
        val identities = pairingStore.pairings().sortedBy { it.displayLabel() }
        var selection = identities.indexOfFirst { it.signerPubkeyHex == permission.boundIdentityPubkeyHex }.coerceAtLeast(0)
        if (identities.isEmpty()) { changedToast(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.app_edit_identity)
            .setSingleChoiceItems(identities.map { it.displayLabel() }.toTypedArray(), selection) { _, which -> selection = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save_button) { _, _ ->
                val chosen = identities[selection]
                MaterialAlertDialogBuilder(this).setTitle(R.string.app_edit_identity)
                    .setMessage(getString(R.string.app_rebind_confirm, packageManager.displayNameFor(packageName), chosen.displayLabel()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save_button) { _, _ ->
                        if (!pairingStore.rebindApproval(packageName, chosen.signerPubkeyHex, expected = permission)) changedToast()
                        render()
                    }.show().also(::reserveDialogActions)
            }.show().also(::reserveDialogActions)
    }

    private fun changeAppLifetime(packageName: String, permission: AppPermission) {
        val durations = listOf(ApprovalLifetime.ONCE, ApprovalLifetime.HOUR, ApprovalLifetime.DAY, ApprovalLifetime.ALWAYS)
        val labels = listOf(R.string.approval_every_request, R.string.approval_hour, R.string.approval_day, R.string.approval_always).map(::getString)
        MaterialAlertDialogBuilder(this).setTitle(R.string.app_edit_duration)
            .setItems(labels.toTypedArray()) { _, which ->
                val lifetime = durations[which]
                val identity = pairingStore.pairings().firstOrNull { it.signerPubkeyHex == permission.boundIdentityPubkeyHex }
                if (identity == null) { changedToast(); return@setItems }
                MaterialAlertDialogBuilder(this).setTitle(R.string.app_edit_duration)
                    .setMessage(if (lifetime == ApprovalLifetime.ONCE)
                        getString(R.string.app_ask_again_confirm, packageManager.displayNameFor(packageName))
                    else getString(R.string.app_update_duration_confirm, labels[which], identity.displayLabel()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save_button) { _, _ ->
                        if (!pairingStore.changeApprovalLifetimeIfCurrent(packageName, permission, lifetime)) changedToast()
                        render()
                    }.show().also(::reserveDialogActions)
            }.show()
    }

    private fun changedToast() = Toast.makeText(this, R.string.approval_changed, Toast.LENGTH_LONG).show()

    private fun dateTime(millis: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

    /** Sets the switch's checked state without re-triggering [onKeepAliveToggled]. */
    private fun setKeepAliveToggleChecked(checked: Boolean) {
        binding.keepAliveToggle.setOnCheckedChangeListener(null)
        binding.keepAliveToggle.isChecked = checked
        binding.keepAliveToggle.setOnCheckedChangeListener { _, isChecked -> onKeepAliveToggled(isChecked) }
    }

    private fun onKeepAliveToggled(enabled: Boolean) {
        binding.notificationHint.isVisible = false
        if (!enabled) {
            setKeepAliveEnabled(false)
            return
        }

        if (hasNotificationPermission()) {
            setKeepAliveEnabled(true)
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** `POST_NOTIFICATIONS` is a runtime permission only from API 33 (Tiramisu) on; older
     * platforms show the ongoing notification without asking. */
    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun setKeepAliveEnabled(enabled: Boolean) {
        pairingStore.setKeepAliveEnabled(enabled)
        if (enabled) {
            HeartwoodKeepAliveService.start(this)
        } else {
            HeartwoodKeepAliveService.stop(this)
        }
    }

    private fun onPairClicked() {
        binding.cameraHint.isVisible = false
        val raw = binding.bunkerInput.text?.toString().orEmpty()
        when (val parsed = BunkerUriParser.parse(raw)) {
            is BunkerUriResult.Invalid -> showPairingError(parsed.reason)
            is BunkerUriResult.Valid -> connectAndSave(parsed.uri)
        }
    }

    private fun onScanClicked() {
        binding.cameraHint.isVisible = false
        binding.pairingError.isVisible = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.qr_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        scanLauncher.launch(options)
    }

    private fun onScanResult(result: ScanIntentResult) {
        when (val evaluated = QrPairingScan.evaluate(result.contents)) {
            is QrScanResult.Accepted -> {
                binding.pairingError.isVisible = false
                binding.bunkerInput.setText(evaluated.uri.toUriString())
                // One tap total: scanning a valid QR pairs immediately, no separate Pair press.
                connectAndSave(evaluated.uri)
            }
            is QrScanResult.Rejected -> showPairingError(evaluated.message)
            QrScanResult.Cancelled -> Unit
        }
    }

    private fun connectAndSave(bunkerUri: BunkerUri) {
        binding.pairingError.isVisible = false
        binding.pairingProgress.isVisible = true
        binding.pairButton.isEnabled = false

        // Generated (or read back) up front so the exact key we test the connection with is the
        // one that ends up persisted -- see PairingStore.ensureClientKeys.
        val clientKeys = pairingStore.ensureClientKeys()
        val label = binding.labelInput.text?.toString()

        lifecycleScope.launch {
            // A disposable client, deliberately not the shared HeartwoodSession: this is a
            // one-off validation of a URI the user just pasted, before anything is persisted.
            val client: HeartwoodClient = RustNostrHeartwoodClient()
            val result = client.connect(bunkerUri.toUriString(), clientKeys.secretKeyHex)
            client.disconnect()

            binding.pairingProgress.isVisible = false
            binding.pairButton.isEnabled = true

            when (result) {
                is HeartwoodResult.Success -> {
                    pairingStore.addPairing(bunkerUri, label)
                    // Discard any session held for this identity so the next real request
                    // reconnects fresh against what was just saved (relevant on a re-pair; a
                    // brand new identity has no session to discard yet).
                    HeartwoodSession.shutdown(bunkerUri.signerPubkeyHex)
                    HeartwoodSession.recordPairingContact(bunkerUri.signerPubkeyHex)
                    binding.bunkerInput.text?.clear()
                    binding.labelInput.text?.clear()
                    render()
                }
                is HeartwoodResult.Failure -> showPairingError(errorMessage(result.error))
            }
        }
    }

    private fun showPairingError(message: String) {
        binding.pairingError.text = message
        binding.pairingError.isVisible = true
    }

    private fun errorMessage(error: HeartwoodError): String = when (error) {
        HeartwoodError.NotConnected -> getString(R.string.error_not_paired)
        HeartwoodError.Busy -> getString(R.string.error_busy)
        HeartwoodError.Timeout -> getString(R.string.error_timeout)
        is HeartwoodError.InvalidInput -> error.message
        is HeartwoodError.Protocol -> getString(R.string.error_generic, error.message)
    }
}
