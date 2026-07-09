package dev.forgesworn.cambium

import android.content.pm.PackageManager

/**
 * The human-readable label for [packageName], falling back to the raw package string on any
 * failure -- a live test against a real device showed this can throw (package-visibility
 * restrictions blocked the label lookup even when the package name itself was known), and callers
 * must never render blank. Shared by `SignerActivity`'s approval sheet and `MainActivity`'s
 * connected-apps list.
 */
fun PackageManager.displayNameFor(packageName: String): String = runCatching {
    getApplicationLabel(getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)
