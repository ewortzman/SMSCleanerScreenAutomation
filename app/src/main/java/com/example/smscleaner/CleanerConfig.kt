package com.example.smscleaner

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared, persisted config/state between MainActivity and CleanerAccessibilityService.
 * Both run in the same process, but the service can outlive the activity, so state
 * lives in SharedPreferences rather than a plain in-memory singleton.
 */
object CleanerConfig {

    private const val PREFS = "cleaner_prefs"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var isRunning: Boolean
        get() = prefs.getBoolean("isRunning", false)
        set(value) = prefs.edit().putBoolean("isRunning", value).apply()

    var dryRun: Boolean
        get() = prefs.getBoolean("dryRun", true)
        set(value) = prefs.edit().putBoolean("dryRun", value).apply()

    var batchSize: Int
        get() = prefs.getInt("batchSize", 1000)
        set(value) = prefs.edit().putInt("batchSize", value).apply()

    var sessionCap: Int
        get() = prefs.getInt("sessionCap", 1000)
        set(value) = prefs.edit().putInt("sessionCap", value).apply()

    var autoContinue: Boolean
        get() = prefs.getBoolean("autoContinue", false)
        set(value) = prefs.edit().putBoolean("autoContinue", value).apply()

    var totalDeletedThisSession: Int
        get() = prefs.getInt("totalDeletedThisSession", 0)
        set(value) = prefs.edit().putInt("totalDeletedThisSession", value).apply()

    var lastStatus: String
        get() = prefs.getString("lastStatus", "idle") ?: "idle"
        set(value) = prefs.edit().putString("lastStatus", value).apply()

    /** One-shot flag: MainActivity sets this true, the service dumps the tree then clears it. */
    var dumpRequested: Boolean
        get() = prefs.getBoolean("dumpRequested", false)
        set(value) = prefs.edit().putBoolean("dumpRequested", value).apply()

    /** Messages from this date and earlier are eligible for deletion. Stored as LocalDate.toEpochDay(). */
    var targetDateEpochDay: Long
        get() = prefs.getLong("targetDateEpochDay", java.time.LocalDate.now().minusMonths(3).toEpochDay())
        set(value) = prefs.edit().putLong("targetDateEpochDay", value).apply()

    /**
     * True once the seek phase has scrolled back far enough to reach [targetDateEpochDay].
     * Persisted so stopping/restarting the service doesn't re-run the (slow) seek phase
     * unnecessarily. Reset to false by MainActivity whenever the target date changes.
     */
    var seekComplete: Boolean
        get() = prefs.getBoolean("seekComplete", false)
        set(value) = prefs.edit().putBoolean("seekComplete", value).apply()

    fun resetSessionCounters() {
        totalDeletedThisSession = 0
    }
}
