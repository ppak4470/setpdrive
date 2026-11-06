package kr.stepdrive.app.data

import android.content.Context

/**
 * Small, synchronous memory that survives the process being killed while the car's navigation is in front:
 * who was unlocked (and when), and whether a practice session was running. Used to put the driver back where
 * they were instead of on the profile screen, and to tell the self-check log whether the process restarted.
 */
class SessionMemory(context: Context) {
    private val p = context.getSharedPreferences("stepdrive_session", Context.MODE_PRIVATE)

    var activeProfileId: String?
        get() = p.getString("active", null)
        set(v) { p.edit().putString("active", v).apply() }

    var unlockedAtMs: Long
        get() = p.getLong("unlockedAt", 0L)
        set(v) { p.edit().putLong("unlockedAt", v).apply() }

    /** True from course start until the trip ends or the driver stops it. */
    var sessionRunning: Boolean
        get() = p.getBoolean("running", false)
        set(v) { p.edit().putBoolean("running", v).apply() }

    /** The driver stays unlocked for this long after the last PIN entry (the car is their own space). */
    fun stillUnlocked(nowMs: Long = System.currentTimeMillis()): Boolean = activeProfileId != null && nowMs - unlockedAtMs < KEEP_UNLOCKED_MS

    companion object { const val KEEP_UNLOCKED_MS = 2 * 60 * 60 * 1000L }
}
