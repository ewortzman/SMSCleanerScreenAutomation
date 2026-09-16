package com.example.smscleaner

/**
 * Everything in this file is a best-effort guess about Google Messages' current UI,
 * NOT verified against a live device or the app's actual view hierarchy/resource IDs
 * (I have no way to inspect that from here). Treat every value below as a starting
 * point to tune, not a known-correct fact.
 *
 * Workflow to tune these:
 *   1. Open the long thread in Google Messages.
 *   2. Long-press one message so the selection toolbar with "N selected" appears.
 *   3. Hit "Dump current screen tree to Logcat" in this app.
 *   4. `adb logcat -s SMSCleanerTree` and look at the dumped node tree.
 *   5. Update the values below to match what you actually see (text, contentDescription,
 *      className, resource-id).
 *
 * If you're on Samsung Messages instead of Google Messages, also change TARGET_PACKAGE
 * here AND android:packageNames in res/xml/accessibility_service_config.xml.
 */
object Selectors {

    const val TARGET_PACKAGE = "com.google.android.apps.messaging"
    // const val TARGET_PACKAGE = "com.samsung.android.messaging" // Samsung Messages alternative

    /** Matches toolbar text like "134 selected" / "1 selected". */
    val SELECTION_COUNT_REGEX = Regex("""(\d+)\s+selected""", RegexOption.IGNORE_CASE)

    /** Text/content-description candidates for the trash/delete icon shown once in selection mode. */
    val DELETE_ACTION_TEXT_CANDIDATES = listOf("Delete", "Delete messages", "Delete conversation")

    /** Text on the confirmation dialog's destructive button. */
    val CONFIRM_BUTTON_TEXT_CANDIDATES = listOf("Delete", "Ok", "OK", "Confirm")

    /** Class name fragments used to identify the scrollable message list container. */
    val RECYCLER_CLASS_FRAGMENTS = listOf("RecyclerView", "ListView")

    /** Class name fragment used to identify a per-row selection checkbox, if exposed. */
    const val CHECKBOX_CLASS_FRAGMENT = "CheckBox"
}
