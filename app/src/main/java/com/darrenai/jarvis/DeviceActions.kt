package com.darrenai.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real on-device capabilities: Jarvis can act, not just chat.
 * Local commands resolve instantly with no network; anything else
 * falls through to the LLM backends.
 */
object DeviceActions {

    sealed class Result {
        data class Spoken(val text: String) : Result()
        object NotMine : Result()
    }

    /** Pure intent parser — unit tested. Returns null when the LLM should handle it. */
    fun parseCommand(input: String): LocalIntent? {
        val t = input.trim().lowercase()
        if (Regex("turn on (the )?flashlight|flashlight on|torch on|lights on").containsMatchIn(t))
            return LocalIntent.Flashlight(true)
        if (Regex("turn off (the )?flashlight|flashlight off|torch off|lights off").containsMatchIn(t))
            return LocalIntent.Flashlight(false)
        if (Regex("battery|charge|power level").containsMatchIn(t))
            return LocalIntent.Battery
        if (Regex("what time|current time|tell me the time|^time$").containsMatchIn(t))
            return LocalIntent.Time
        if (Regex("what day is|what('s| is) the date|today'?s date").containsMatchIn(t))
            return LocalIntent.Date
        val remind = Regex("^remind me\\s+(?:to\\s+)?(.+)").find(t)
        if (remind != null) {
            val body = remind.groupValues[1].trim()
            if (body.isNotEmpty()) return LocalIntent.Reminder(body)
        }
        if (Regex("open settings|phone settings").containsMatchIn(t))
            return LocalIntent.OpenSettings
        return null
    }

    sealed class LocalIntent {
        data class Flashlight(val on: Boolean) : LocalIntent()
        object Battery : LocalIntent()
        object Time : LocalIntent()
        object Date : LocalIntent()
        data class Reminder(val text: String) : LocalIntent()
        object OpenSettings : LocalIntent()
    }

    /** Execute a parsed intent against the device. */
    fun execute(ctx: Context, intent: LocalIntent, vault: MemoryVault? = null): String {
        return when (intent) {
            is LocalIntent.Flashlight -> {
                val ok = setTorch(ctx, intent.on)
                if (ok) {
                    if (intent.on) "Flashlight on, sir."
                    else "Flashlight off."
                } else "I couldn't reach the flashlight, sir. The camera permission may be missing."
            }
            LocalIntent.Battery -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val charging = runCatching {
                    val i = ctx.registerReceiver(null,
                        android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                        BatteryManager.BATTERY_STATUS_CHARGING
                }.getOrDefault(false)
                if (pct < 0) "I can't read the battery level on this device, sir."
                else if (charging) "Battery at $pct percent and charging, sir."
                else "Battery at $pct percent, sir."
            }
            LocalIntent.Time -> {
                val now = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                "It is $now, sir."
            }
            LocalIntent.Date -> {
                val today = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
                "Today is $today, sir."
            }
            is LocalIntent.Reminder -> {
                runCatching { vault?.saveNote("reminder", intent.text) }
                "Noted, sir. I will remind you: ${intent.text}."
            }
            LocalIntent.OpenSettings -> {
                runCatching {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "Opening system settings, sir."
            }
        }
    }

    private fun setTorch(ctx: Context, on: Boolean): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull() ?: return false
        cm.setTorchMode(id, on)
        true
    }.getOrDefault(false)

    /** One-line live device snapshot injected into the LLM prompt. */
    fun snapshot(ctx: Context): String = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val time = SimpleDateFormat("EEEE h:mm a", Locale.US).format(Date())
        buildString {
            append("Device: $time WIB")
            if (pct >= 0) append(", battery $pct%")
        }
    }.getOrDefault("")
}
