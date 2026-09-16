package com.example.smscleaner

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.time.LocalDate

/**
 * Polls the foreground window ~every second and drives a simple, stateless-per-tick
 * loop:
 *   1. Seek phase (until CleanerConfig.seekComplete): scroll up reading date-divider
 *      headers until one at or before the target date is visible -- no selecting or
 *      deleting yet.
 *   2. Clean phase: enter selection mode -> select visible unselected rows -> scroll up
 *      -> repeat until CleanerConfig.batchSize is reached -> delete -> repeat.
 *
 * "Stateless-per-tick" means each tick re-reads the actual screen to decide what to do
 * next, rather than trusting an internal state machine. That makes it resilient to
 * being paused/resumed/interrupted (screen off, app switched away, etc.) -- worst case
 * it just re-evaluates and picks up wherever the UI actually is.
 *
 * IMPORTANT: the node-matching logic in Selectors.kt/DateSeek.kt is an unverified best
 * guess about Google Messages' UI. Use "Dump current screen tree to Logcat" in the app
 * + `adb logcat -s SMSCleanerTree` to check it against your actual device and fix any
 * mismatches.
 */
class CleanerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SMSCleaner"
        private const val DUMP_TAG = "SMSCleanerTree"

        private const val TICK_DELAY_MS = 900L
        private const val SETTLE_DELAY_MS = 500L
        private const val SCROLL_SETTLE_DELAY_MS = 700L
        private const val DELETE_SETTLE_DELAY_MS = 1500L

        // Seek phase safety valves -- see tickSeek().
        private const val SEEK_NO_DATE_LIMIT = 300 // ~4-5 min of scrolling with no divider recognized at all
        private const val SEEK_STALL_LIMIT = 5     // same oldest-visible-date seen this many ticks in a row
    }

    private val handler = Handler(Looper.getMainLooper())

    // In-memory only (not persisted): tracks a delete tap awaiting its confirmation dialog.
    // If the service process dies mid-flow, this resets, which just means a stray dialog
    // wouldn't get auto-confirmed -- see README "Known limitations".
    private var pendingConfirmClick = false
    private var pendingDeleteCount = 0

    // In-memory only: seek-phase progress tracking, reset whenever seeking (re)starts.
    private var seekLastOldestDate: LocalDate? = null
    private var seekStallStreak = 0
    private var seekNoDateStreak = 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            val delay = try {
                tick()
            } catch (t: Throwable) {
                Log.e(TAG, "tick() crashed, stopping for safety", t)
                CleanerConfig.isRunning = false
                CleanerConfig.lastStatus = "Stopped: exception ${t.message}"
                TICK_DELAY_MS
            }
            handler.postDelayed(this, delay)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CleanerConfig.init(applicationContext)
        Log.d(TAG, "Service connected")
        handler.post(tickRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: driven by the poll loop, not discrete events. Google
        // Messages doesn't fire events we can reliably key a multi-step flow off of.
    }

    override fun onInterrupt() {}

    /** Returns the delay (ms) before the next tick. */
    private fun tick(): Long {
        if (CleanerConfig.dumpRequested) {
            dumpTree()
            CleanerConfig.dumpRequested = false
            return TICK_DELAY_MS
        }

        if (!CleanerConfig.isRunning) {
            return TICK_DELAY_MS
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != Selectors.TARGET_PACKAGE) {
            status("Waiting for Google Messages to be in the foreground...")
            return TICK_DELAY_MS
        }

        val recycler = root.findFirst { it.classNameContainsAny(Selectors.RECYCLER_CLASS_FRAGMENTS) }
        if (recycler == null) {
            status("Can't find the message list container -- selectors need tuning (see Selectors.kt).")
            return TICK_DELAY_MS
        }

        if (!CleanerConfig.seekComplete) {
            return tickSeek(recycler)
        }

        val selectedCount = readSelectionCount(root)

        // A confirmation dialog is expected right after we tap delete: selection mode
        // has ended (selectedCount == null again) but we're still waiting on a confirm tap.
        if (pendingConfirmClick) {
            val confirmButton = root.findFirst { it.isClickable && it.matchesAnyText(Selectors.CONFIRM_BUTTON_TEXT_CANDIDATES) }
            if (confirmButton == null) {
                status("Waiting for delete confirmation dialog...")
                return SETTLE_DELAY_MS
            }
            status("Confirming delete of ~$pendingDeleteCount messages.")
            click(confirmButton)
            CleanerConfig.totalDeletedThisSession += pendingDeleteCount
            pendingConfirmClick = false
            pendingDeleteCount = 0

            if (!CleanerConfig.autoContinue && CleanerConfig.totalDeletedThisSession >= CleanerConfig.sessionCap) {
                CleanerConfig.isRunning = false
                status("Session cap reached (${CleanerConfig.totalDeletedThisSession} deleted this session). Stopped -- verify results, then hit Start again to continue.")
            }
            return DELETE_SETTLE_DELAY_MS
        }

        return when {
            selectedCount == null -> {
                val firstRow = findMessageRows(recycler).firstOrNull()
                if (firstRow == null) {
                    status("No message rows visible to long-press.")
                } else {
                    status("Entering selection mode (long-press first visible message).")
                    longClick(firstRow)
                }
                SETTLE_DELAY_MS
            }
            selectedCount < CleanerConfig.batchSize -> {
                val rows = findMessageRows(recycler)
                var newlySelected = 0
                for (row in rows) {
                    if (!isRowSelected(row)) {
                        click(row)
                        newlySelected++
                    }
                }
                status("Selected ~$selectedCount/${CleanerConfig.batchSize} so far ($newlySelected new this screen). Scrolling up.")
                scrollUp(recycler)
                SCROLL_SETTLE_DELAY_MS
            }
            else -> {
                if (CleanerConfig.dryRun) {
                    status("[DRY RUN] Would delete $selectedCount messages now. Backing out of selection instead.")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    SETTLE_DELAY_MS
                } else {
                    val deleteAction = root.findFirst { it.isClickable && it.matchesAnyText(Selectors.DELETE_ACTION_TEXT_CANDIDATES) }
                    if (deleteAction == null) {
                        status("Can't find the delete/trash action -- stopping for safety. Tune Selectors.kt.")
                        CleanerConfig.isRunning = false
                    } else {
                        status("Tapping delete for $selectedCount selected messages.")
                        click(deleteAction)
                        pendingDeleteCount = selectedCount
                        pendingConfirmClick = true
                    }
                    SETTLE_DELAY_MS
                }
            }
        }
    }

    /**
     * Scrolls upward (further into the past) reading date-divider headers until one at
     * or before the target date is visible, then flips CleanerConfig.seekComplete so
     * subsequent ticks fall through to the normal select/delete loop. Never selects or
     * deletes anything itself.
     *
     * Two safety valves, since date-divider recognition is unverified (see DateSeek.kt):
     *   - SEEK_NO_DATE_LIMIT: give up if no divider is ever recognized on screen.
     *   - SEEK_STALL_LIMIT: if the same oldest-visible date keeps showing up across
     *     several ticks, scrolling isn't making progress (most likely the true top of
     *     the thread) -- treat that as "reached the target" rather than looping forever.
     */
    private fun tickSeek(recycler: AccessibilityNodeInfo): Long {
        val target = LocalDate.ofEpochDay(CleanerConfig.targetDateEpochDay)
        val rows = findMessageRows(recycler)
        val oldestVisible = findOldestVisibleDate(recycler, rows)

        if (oldestVisible == null) {
            seekNoDateStreak++
            if (seekNoDateStreak >= SEEK_NO_DATE_LIMIT) {
                status("Seek: never recognized a date-divider after $seekNoDateStreak tries -- stopping for safety. Tune DateSeek.kt.")
                CleanerConfig.isRunning = false
                resetSeekTracking()
                return TICK_DELAY_MS
            }
            status("Seeking to $target... no date divider recognized on screen yet ($seekNoDateStreak/$SEEK_NO_DATE_LIMIT).")
            scrollUp(recycler)
            return SCROLL_SETTLE_DELAY_MS
        }

        seekNoDateStreak = 0

        if (!oldestVisible.isAfter(target)) {
            status("Seek reached target date $target (oldest visible divider: $oldestVisible). Switching to delete phase.")
            CleanerConfig.seekComplete = true
            resetSeekTracking()
            return SETTLE_DELAY_MS
        }

        if (oldestVisible == seekLastOldestDate) {
            seekStallStreak++
        } else {
            seekStallStreak = 0
            seekLastOldestDate = oldestVisible
        }

        if (seekStallStreak >= SEEK_STALL_LIMIT) {
            status("Seek: no scroll progress past $oldestVisible for $seekStallStreak ticks (likely reached top of thread) -- switching to delete phase.")
            CleanerConfig.seekComplete = true
            resetSeekTracking()
            return SETTLE_DELAY_MS
        }

        status("Seeking to $target... oldest visible divider: $oldestVisible")
        scrollUp(recycler)
        return SCROLL_SETTLE_DELAY_MS
    }

    private fun resetSeekTracking() {
        seekLastOldestDate = null
        seekStallStreak = 0
        seekNoDateStreak = 0
    }

    /**
     * Looks for date-divider text among [recycler]'s descendants, excluding anything
     * inside a message row (so message *content* that happens to look like a date, e.g.
     * a text literally saying "Monday", isn't mistaken for a divider). Returns the
     * oldest date found on screen, since that's how far back this screen actually
     * reaches.
     */
    private fun findOldestVisibleDate(recycler: AccessibilityNodeInfo, rows: List<AccessibilityNodeInfo>): LocalDate? {
        val excluded = HashSet<AccessibilityNodeInfo>()
        for (row in rows) {
            row.selfAndDescendants().forEach { excluded.add(it) }
        }
        var oldest: LocalDate? = null
        for (node in recycler.selfAndDescendants()) {
            if (node in excluded || node.isClickable) continue
            val text = node.text?.toString() ?: continue
            val parsed = DateSeek.parse(text) ?: continue
            if (oldest == null || parsed.isBefore(oldest)) oldest = parsed
        }
        return oldest
    }

    private fun readSelectionCount(root: AccessibilityNodeInfo): Int? {
        val node = root.findFirst { Selectors.SELECTION_COUNT_REGEX.containsMatchIn(it.textOrDesc()) }
        val match = node?.let { Selectors.SELECTION_COUNT_REGEX.find(it.textOrDesc()) }
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun findMessageRows(recycler: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val directChildren = (0 until recycler.childCount).mapNotNull { recycler.getChild(it) }
        val visible = directChildren.filter { it.isVisibleToUser }
        if (visible.isNotEmpty()) return visible
        // Fallback for implementations that nest rows differently than direct children.
        return recycler.findAll { it.isClickable && it.isVisibleToUser }
    }

    private fun isRowSelected(row: AccessibilityNodeInfo): Boolean {
        val checkbox = row.findFirst { it.classNameContainsAny(listOf(Selectors.CHECKBOX_CLASS_FRAGMENT)) }
        if (checkbox != null) return checkbox.isChecked
        return row.isChecked || row.isSelected
    }

    private fun status(msg: String) {
        Log.d(TAG, msg)
        CleanerConfig.lastStatus = msg
    }

    private fun dumpTree() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.d(DUMP_TAG, "rootInActiveWindow is null (screen off, or no active window).")
            return
        }
        Log.d(DUMP_TAG, "==== DUMP START (package=${root.packageName}) ====")
        dumpNode(root, 0)
        Log.d(DUMP_TAG, "==== DUMP END ====")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString()?.take(40)
        val desc = node.contentDescription?.toString()?.take(40)
        Log.d(
            DUMP_TAG,
            "$indent${node.className} id=${node.viewIdResourceName} text=$text desc=$desc " +
                "clickable=${node.isClickable} checked=${node.isChecked} bounds=${node.boundsInScreenRect()}"
        )
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, depth + 1) }
        }
    }
}
