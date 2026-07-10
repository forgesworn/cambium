package dev.forgesworn.cambium

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import dev.forgesworn.cambium.applock.AppLockPrompt
import dev.forgesworn.cambium.applock.AppLockStore
import dev.forgesworn.cambium.databinding.ActivityMainBinding
import dev.forgesworn.cambium.databinding.ItemConnectedAppBinding
import dev.forgesworn.cambium.databinding.ItemPairingBinding
import dev.forgesworn.cambium.log.ActivityLogActivity
import dev.forgesworn.cambium.pairing.AppPermissionState
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
import dev.forgesworn.cambium.signer.displayLabel
import dev.forgesworn.cambium.signer.npubDisplay
import kotlinx.coroutines.launch

/**
 * Status screen: paired or not, scan-or-paste-a-bunker-URI pairing flow for one or more signers,
 * and per-pairing connection details.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pairingStore: PairingStore
    private lateinit var appLockStore: AppLockStore

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

        binding.pairButton.setOnClickListener { onPairClicked() }
        binding.scanButton.setOnClickListener { onScanClicked() }
        binding.unpairAllButton.setOnClickListener { onUnpairAllClicked() }
        binding.activityLogButton.setOnClickListener { startActivity(Intent(this, ActivityLogActivity::class.java)) }
        binding.unlockButton.setOnClickListener { promptUnlock() }

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
            row.pairingUnpairButton.setOnClickListener { onUnpairRowClicked(pairing) }
            binding.pairingsListContainer.addView(row.root)
        }
    }

    private fun onUnpairRowClicked(pairing: Pairing) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.unpair_row_confirm_title, pairing.displayLabel()))
            .setMessage(R.string.unpair_row_confirm_body)
            .setPositiveButton(R.string.unpair_button) { _, _ ->
                pairingStore.removePairing(pairing.signerPubkeyHex)
                lifecycleScope.launch { HeartwoodSession.shutdown(pairing.signerPubkeyHex) }
                // The keep-alive service's own ping loop already stops itself once it next finds
                // pairings() empty (see HeartwoodKeepAliveService), but that could be up to a
                // full PING_INTERVAL_MILLIS away -- stopping it here immediately avoids leaving a
                // "keeping your signer warm" notification showing with nothing left to keep warm.
                if (pairingStore.pairings().isEmpty()) {
                    HeartwoodKeepAliveService.stop(this)
                }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onUnpairAllClicked() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_all_confirm_title)
            .setMessage(R.string.unpair_all_confirm_body)
            .setPositiveButton(R.string.unpair_all_button) { _, _ ->
                pairingStore.clearAll()
                HeartwoodKeepAliveService.stop(this)
                lifecycleScope.launch { HeartwoodSession.shutdownAll() }
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
            val boundIdentity = permission.boundIdentityPubkeyHex?.let { hex -> pairings.firstOrNull { it.signerPubkeyHex == hex } }
            row.appIdentityValue.isVisible = boundIdentity != null
            if (boundIdentity != null) {
                row.appIdentityValue.text = boundIdentity.displayLabel()
            }
            row.forgetButton.setOnClickListener {
                pairingStore.forget(packageName)
                renderConnectedApps(pairings)
            }
            binding.connectedAppsContainer.addView(row.root)
        }
    }

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
        HeartwoodError.Timeout -> getString(R.string.error_timeout)
        is HeartwoodError.InvalidInput -> error.message
        is HeartwoodError.Protocol -> getString(R.string.error_generic, error.message)
    }
}
