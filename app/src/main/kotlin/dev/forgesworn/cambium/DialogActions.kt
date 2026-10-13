package dev.forgesworn.cambium

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

/** Keep every decision visible when Material stacks buttons for enlarged text.
 * ButtonBarLayout can report a height that clips its later children; derive the space from the
 * buttons themselves, then reserve it in both the bar and its scrolling wrapper. */
fun reserveDialogActions(dialog: AlertDialog) {
    val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ?: dialog.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
    val actions = button.parent as? LinearLayout ?: return
    val panel = actions.parent as? View ?: return
    fun reserve() {
        val children = (0 until actions.childCount).map(actions::getChildAt)
            .filter { it.visibility == View.VISIBLE && it.measuredHeight > 0 }
        if (children.isEmpty()) return
        fun margins(view: View): Int {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            return (params?.topMargin ?: 0) + (params?.bottomMargin ?: 0)
        }
        val buttonHeights = children.map { it.measuredHeight + margins(it) }
        val packedHeight = actions.paddingTop + actions.paddingBottom +
            if (actions.orientation == LinearLayout.VERTICAL) buttonHeights.sum() else buttonHeights.max()
        // Laid-out child bounds also cover spacing that the bar adds beyond its measured height.
        val childExtent = children.maxOf { child ->
            child.bottom + ((child.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0)
        } + actions.paddingBottom
        val requiredBarHeight = maxOf(packedHeight, childExtent)
        val requiredPanelHeight = requiredBarHeight + panel.paddingTop + panel.paddingBottom
        if (actions.layoutParams.height != requiredBarHeight) {
            actions.layoutParams = actions.layoutParams.apply { height = requiredBarHeight }
        }
        if (panel.minimumHeight != requiredPanelHeight) panel.minimumHeight = requiredPanelHeight
        if (panel.layoutParams.height != requiredPanelHeight) {
            panel.layoutParams = panel.layoutParams.apply { height = requiredPanelHeight }
        }
    }
    actions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reserve() }
    actions.post { reserve() }
}
