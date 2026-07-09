package dev.forgesworn.cambium

import android.Manifest
import android.app.AlertDialog
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
import dev.forgesworn.cambium.databinding.ActivityMainBinding
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.BunkerUriParser
import dev.forgesworn.cambium.pairing.BunkerUriResult
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.pairing.QrPairingScan
import dev.forgesworn.cambium.pairing.QrScanResult
import dev.forgesworn.cambium.service.HeartwoodKeepAliveService
import dev.forgesworn.cambium.signer.HeartwoodClient
import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import dev.forgesworn.cambium.signer.RustNostrHeartwoodClient
import dev.forgesworn.cambium.signer.npubDisplay
import kotlinx.coroutines.launch

/**
 * Status screen: paired or not, scan-or-paste-a-bunker-URI pairing flow, and connection details
 * once paired.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pairingStore: PairingStore

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

        binding.pairButton.setOnClickListener { onPairClicked() }
        binding.scanButton.setOnClickListener { onScanClicked() }
        binding.unpairButton.setOnClickListener { onUnpairClicked() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val pairing = pairingStore.current()
        if (pairing == null) {
            binding.statusValue.text = getString(R.string.status_unpaired)
            binding.pairingSection.isVisible = true
            binding.pairedSection.isVisible = false
        } else {
            binding.statusValue.text = getString(R.string.status_paired)
            binding.pairingSection.isVisible = false
            binding.pairedSection.isVisible = true
            binding.signerValue.text = npubDisplay(pairing.signerPubkeyHex)
            binding.relaysValue.text = pairing.relays.joinToString("\n")
            setKeepAliveToggleChecked(pairingStore.isKeepAliveEnabled())
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

        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            setKeepAliveEnabled(true)
        }
    }

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
                    pairingStore.save(bunkerUri)
                    // Discard any session held for a previous pairing so the next real request
                    // reconnects fresh against what was just saved.
                    HeartwoodSession.shutdown()
                    binding.bunkerInput.text?.clear()
                    render()
                }
                is HeartwoodResult.Failure -> showPairingError(errorMessage(result.error))
            }
        }
    }

    private fun onUnpairClicked() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_confirm_title)
            .setMessage(R.string.unpair_confirm_body)
            .setPositiveButton(R.string.unpair_button) { _, _ ->
                pairingStore.clear()
                HeartwoodKeepAliveService.stop(this)
                lifecycleScope.launch { HeartwoodSession.shutdown() }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
