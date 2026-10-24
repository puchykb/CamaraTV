package com.pux.camaratv

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** When motion alerts may be shown: always, only at night, or never, plus a temporary pause. */
object AlertSettings {

    enum class Mode { ALWAYS, NIGHT, OFF }

    private const val NIGHT_START_HOUR = 20
    private const val NIGHT_END_HOUR = 8

    private fun prefs(context: Context) = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun mode(context: Context): Mode =
        runCatching { Mode.valueOf(prefs(context).getString("mode", null) ?: Mode.ALWAYS.name) }.getOrDefault(Mode.ALWAYS)

    fun pausedUntil(context: Context) = prefs(context).getLong("paused_until", 0)

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString("mode", mode.name).putLong("paused_until", 0).apply()
        if (mode == Mode.OFF) MotionService.stop(context) else MotionService.start(context)
    }

    fun pause(context: Context, millis: Long) {
        prefs(context).edit().putLong("paused_until", System.currentTimeMillis() + millis).apply()
    }

    fun shouldAlert(context: Context): Boolean {
        if (System.currentTimeMillis() < pausedUntil(context)) return false
        return when (mode(context)) {
            Mode.ALWAYS -> true
            Mode.NIGHT -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= NIGHT_START_HOUR || it < NIGHT_END_HOUR }
            Mode.OFF -> false
        }
    }

    /** Human-readable current state, e.g. "Pausados hasta las 15:30". */
    fun describe(context: Context): String {
        val until = pausedUntil(context)
        if (mode(context) != Mode.OFF && System.currentTimeMillis() < until) {
            return "Pausados hasta las " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(until))
        }
        return when (mode(context)) {
            Mode.ALWAYS -> "Siempre activados"
            Mode.NIGHT -> "Solo de noche (20:00 a 8:00)"
            Mode.OFF -> "Desactivados"
        }
    }
}
