package dev.forgesworn.cambium.pairing

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.reserveDialogActions

/** Shared, explicitly confirmed editor for the local convenience filter. */
object PermissionScopeEditor {
    private val operations = listOf(
        "sign_event" to R.string.scope_sign,
        "nip04_encrypt" to R.string.scope_nip04_encrypt,
        "nip04_decrypt" to R.string.scope_nip04_decrypt,
        "nip44_encrypt" to R.string.scope_nip44_encrypt,
        "nip44_decrypt" to R.string.scope_nip44_decrypt,
        "decrypt_zap_event" to R.string.scope_zap,
    )

    fun summary(context: Context, scope: PermissionScope?): String {
        if (scope == null) return context.getString(R.string.approval_scope_all)
        val names = operations.filter { it.first in scope.methods }.map { context.getString(it.second) }
        if (names.isEmpty()) return context.getString(R.string.scope_summary_none)
        val kinds = scope.signEventKinds?.takeIf { "sign_event" in scope.methods }
        return (names + listOfNotNull(kinds?.let {
            context.getString(R.string.scope_summary_kinds, it.sorted().joinToString(", "))
        })).joinToString("\n")
    }

    fun show(context: Context, current: PermissionScope?, onCancel: () -> Unit = {}, onSave: (PermissionScope) -> Unit) {
        var saved = false
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, 0) }
        column.addView(TextView(context).apply { setText(R.string.scope_editor_body) })
        val checks = operations.associate { (method, label) ->
            val box = MaterialCheckBox(context).apply {
                setText(label)
                isChecked = current == null || (method in current.methods &&
                    (method != "sign_event" || current.signEventKinds?.isEmpty() != true))
                minHeight = (48 * resources.displayMetrics.density).toInt()
            }
            column.addView(box)
            method to box
        }
        val kindsLayout = TextInputLayout(context).apply { hint = context.getString(R.string.scope_kinds_label) }
        val kindsInput = TextInputEditText(kindsLayout.context).apply {
            hint = context.getString(R.string.scope_kinds_hint)
            setText(current?.signEventKinds?.sorted()?.joinToString(", "))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 3
        }
        kindsLayout.addView(kindsInput)
        column.addView(kindsLayout)
        kindsLayout.isVisible = checks.getValue("sign_event").isChecked
        checks.getValue("sign_event").setOnCheckedChangeListener { _, checked -> kindsLayout.isVisible = checked }
        val scroll = ScrollView(context).apply {
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.scope_editor_title).setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save_button, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val methods = checks.filterValues { it.isChecked }.keys
                val rawKinds = kindsInput.text?.toString().orEmpty()
                val kinds = if ("sign_event" in methods && rawKinds.isNotBlank()) {
                    val parsed = parseEventKinds(rawKinds)
                    if (parsed == null) {
                        kindsLayout.error = context.getString(R.string.scope_kinds_invalid)
                        // The error adds height below the field. Bring it into view after layout,
                        // including on small screens with enlarged text.
                        kindsLayout.doOnLayout { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
                        return@setOnClickListener
                    }
                    parsed
                } else null
                saved = true
                onSave(PermissionScope(methods, kinds))
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { if (!saved) onCancel() }
        dialog.show()
        reserveDialogActions(dialog)
    }
}
