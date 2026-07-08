package dev.forgesworn.cambium

import android.app.AlertDialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.forgesworn.cambium.databinding.ActivityMainBinding
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.BunkerUriParser
import dev.forgesworn.cambium.pairing.BunkerUriResult
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.HeartwoodClient
import dev.forgesworn.cambium.signer.HeartwoodError
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.RustNostrHeartwoodClient
import dev.forgesworn.cambium.signer.npubDisplay
import kotlinx.coroutines.launch

/**
 * Status screen: paired or not, paste-a-bunker-URI pairing flow, and connection details once
 * paired. QR scanning is a later milestone (see design doc M-something) -- paste only for now.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pairingStore: PairingStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pairingStore = PairingStore(this)

        binding.pairButton.setOnClickListener { onPairClicked() }
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
        }
    }

    private fun onPairClicked() {
        val raw = binding.bunkerInput.text?.toString().orEmpty()
        when (val parsed = BunkerUriParser.parse(raw)) {
            is BunkerUriResult.Invalid -> showPairingError(parsed.reason)
            is BunkerUriResult.Valid -> connectAndSave(parsed.uri)
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
            val client: HeartwoodClient = RustNostrHeartwoodClient()
            val result = client.connect(bunkerUri.toUriString(), clientKeys.secretKeyHex)
            client.disconnect()

            binding.pairingProgress.isVisible = false
            binding.pairButton.isEnabled = true

            when (result) {
                is HeartwoodResult.Success -> {
                    pairingStore.save(bunkerUri)
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
