package com.example

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StandUpWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_START_TIMER = "com.example.ACTION_START_TIMER"
        const val ACTION_RESET_TIMER = "com.example.ACTION_RESET_TIMER"
        const val ACTION_TIMER_EXPIRED = "com.example.ACTION_TIMER_EXPIRED"
        const val ACTION_WIDGET_UPDATE = "com.example.ACTION_WIDGET_UPDATE"

        const val PREFS_NAME = "standup_prefs"
        const val KEY_MINUTES = "selected_minutes"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_VIBRATE = "vibration_enabled"

        const val KEY_TIMER_STATUS = "timer_status_persisted"
        const val KEY_TIMER_START_TIME = "timer_start_time_persisted"
        const val KEY_TIMER_TARGET_TIME = "timer_target_time_persisted"
        const val KEY_TIMER_DURATION_SECS = "timer_duration_secs_persisted"

        const val CHANNEL_ID = "standup_channel_widget"

        private var activeRingtone: Ringtone? = null
        private var activeVibrator: Any? = null // Using Any? to handle clean cast across old/new APIs

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val status = prefs.getString(KEY_TIMER_STATUS, "READY") ?: "READY"
            val targetTimeMs = prefs.getLong(KEY_TIMER_TARGET_TIME, 0L)
            
            val views = RemoteViews(context.packageName, R.layout.standup_widget)

            // Container click takes user to Main App
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mainPendingIntent = PendingIntent.getActivity(
                context, 1001, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_container, mainPendingIntent)

            // Initialize Button broadcast intents
            val startIntent = Intent(context, StandUpWidgetProvider::class.java).apply {
                action = ACTION_START_TIMER
            }
            val startPendingIntent = PendingIntent.getBroadcast(
                context, 2001, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_start, startPendingIntent)

            val resetIntent = Intent(context, StandUpWidgetProvider::class.java).apply {
                action = ACTION_RESET_TIMER
            }
            val resetPendingIntent = PendingIntent.getBroadcast(
                context, 2002, resetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_reset, resetPendingIntent)

            when (status) {
                "FOCUSING" -> {
                    val remainingMs = targetTimeMs - System.currentTimeMillis()
                    val minutesRemaining = (remainingMs + 59999) / (60 * 1000)

                    views.setTextViewText(R.id.widget_status_pill, "WORKING")
                    views.setInt(R.id.widget_status_pill, "setBackgroundResource", R.drawable.status_pill_focus)
                    views.setTextColor(R.id.widget_status_pill, android.graphics.Color.parseColor("#3B82F6"))

                    if (remainingMs > 0) {
                        views.setTextViewText(R.id.widget_time_display, "Focusing: ${minutesRemaining}m left")
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                        views.setTextViewText(R.id.widget_target_notice, "Interval ends at ${timeFormat.format(Date(targetTimeMs))}")
                    } else {
                        views.setTextViewText(R.id.widget_time_display, "Time to stretch!")
                        views.setTextViewText(R.id.widget_target_notice, "Tap Got It to reset alert")
                    }

                    views.setViewVisibility(R.id.widget_btn_start, View.GONE)
                    views.setViewVisibility(R.id.widget_btn_reset, View.VISIBLE)
                    views.setTextViewText(R.id.widget_btn_reset, "Reset")
                }
                "BREAKING" -> {
                    views.setTextViewText(R.id.widget_status_pill, "STRETCH")
                    views.setInt(R.id.widget_status_pill, "setBackgroundResource", R.drawable.status_pill_break)
                    views.setTextColor(R.id.widget_status_pill, android.graphics.Color.parseColor("#EF4444"))

                    views.setTextViewText(R.id.widget_time_display, "Time to stretch!")
                    views.setTextViewText(R.id.widget_target_notice, "Stand up and walk around!")

                    views.setViewVisibility(R.id.widget_btn_start, View.GONE)
                    views.setViewVisibility(R.id.widget_btn_reset, View.VISIBLE)
                    views.setTextViewText(R.id.widget_btn_reset, "Got It")
                }
                else -> { // READY
                    val defaultMinutes = prefs.getInt(KEY_MINUTES, 30)

                    views.setTextViewText(R.id.widget_status_pill, "READY")
                    views.setInt(R.id.widget_status_pill, "setBackgroundResource", R.drawable.status_pill_ready)
                    views.setTextColor(R.id.widget_status_pill, android.graphics.Color.parseColor("#10B981"))

                    views.setTextViewText(R.id.widget_time_display, "Begin desk session")
                    views.setTextViewText(R.id.widget_target_notice, "Tap Begin to start a ${defaultMinutes}m slot")

                    views.setViewVisibility(R.id.widget_btn_start, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_btn_reset, View.GONE)
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun triggerWidgetUpdate(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, StandUpWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        when (action) {
            ACTION_START_TIMER -> {
                val minutes = prefs.getInt(KEY_MINUTES, 30)
                val durationMs = minutes * 60 * 1000L
                val targetTimeMs = System.currentTimeMillis() + durationMs

                prefs.edit()
                    .putString(KEY_TIMER_STATUS, "FOCUSING")
                    .putLong(KEY_TIMER_START_TIME, System.currentTimeMillis())
                    .putLong(KEY_TIMER_TARGET_TIME, targetTimeMs)
                    .putLong(KEY_TIMER_DURATION_SECS, minutes * 60L)
                    .apply()

                scheduleSystemAlarm(context, targetTimeMs)
                scheduleNextMinuteTick(context)
                triggerWidgetUpdate(context)
            }
            ACTION_RESET_TIMER -> {
                prefs.edit()
                    .putString(KEY_TIMER_STATUS, "READY")
                    .putLong(KEY_TIMER_START_TIME, 0L)
                    .putLong(KEY_TIMER_TARGET_TIME, 0L)
                    .putLong(KEY_TIMER_DURATION_SECS, 0L)
                    .apply()

                cancelSystemAlarm(context)
                cancelMinuteTick(context)
                stopRingtoneAndVibration()
                triggerWidgetUpdate(context)
            }
            ACTION_TIMER_EXPIRED -> {
                val status = prefs.getString(KEY_TIMER_STATUS, "READY") ?: "READY"
                if (status == "FOCUSING") {
                    prefs.edit()
                        .putString(KEY_TIMER_STATUS, "BREAKING")
                        .apply()

                    val startMs = prefs.getLong(KEY_TIMER_START_TIME, 0L)
                    val durationMins = (prefs.getLong(KEY_TIMER_DURATION_SECS, 1800L) / 60).toInt()
                    val format = SimpleDateFormat("h:mm a", Locale.getDefault())
                    val startStr = if (startMs > 0L) format.format(Date(startMs)) else ""
                    val nowStr = format.format(Date())
                    val historyEntry = "$startStr - $nowStr ($durationMins mins Session)"
                    
                    val currentHistory = prefs.getString("completed_history_logs", "") ?: ""
                    val updatedHistory = if (currentHistory.isEmpty()) historyEntry else "$historyEntry||$currentHistory"
                    
                    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val countDate = prefs.getString("completed_count_date", "") ?: ""
                    val currentCount = if (countDate == todayStr) prefs.getInt("completed_count", 0) else 0
                    val updatedCount = currentCount + 1

                    prefs.edit()
                        .putString("completed_history_logs", updatedHistory)
                        .putString("completed_count_date", todayStr)
                        .putInt("completed_count", updatedCount)
                        .apply()

                    val soundEnabled = prefs.getBoolean(KEY_SOUND, true)
                    val vibrateEnabled = prefs.getBoolean(KEY_VIBRATE, true)

                    showBreakNotification(context)
                    playAlarmAlerts(context, soundEnabled, vibrateEnabled)
                    cancelMinuteTick(context)
                    triggerWidgetUpdate(context)
                }
            }
            ACTION_WIDGET_UPDATE -> {
                triggerWidgetUpdate(context)
                if (prefs.getString(KEY_TIMER_STATUS, "READY") == "FOCUSING") {
                    val targetTimeMs = prefs.getLong(KEY_TIMER_TARGET_TIME, 0L)
                    if (System.currentTimeMillis() < targetTimeMs) {
                        scheduleNextMinuteTick(context)
                    }
                }
            }
        }
    }

    private fun scheduleSystemAlarm(context: Context, triggerTimeMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, StandUpWidgetProvider::class.java).apply {
            action = ACTION_TIMER_EXPIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            2002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
        }
    }

    private fun cancelSystemAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, StandUpWidgetProvider::class.java).apply {
            action = ACTION_TIMER_EXPIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            2002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun scheduleNextMinuteTick(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, StandUpWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_UPDATE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            2003,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerTime = System.currentTimeMillis() + 60000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    private fun cancelMinuteTick(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, StandUpWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_UPDATE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            2003,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_name)
            val descriptionText = context.getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showBreakNotification(context: Context) {
        createNotificationChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.take_break_title))
            .setContentText(context.getString(R.string.take_break_desc))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1001, builder.build())
    }

    private fun playAlarmAlerts(context: Context, sound: Boolean, vibrate: Boolean) {
        stopRingtoneAndVibration()

        if (sound) {
            try {
                val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                activeRingtone = RingtoneManager.getRingtone(context, alertUri)
                activeRingtone?.play()
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }

        if (vibrate) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                activeVibrator = vibrator

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(
                        longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000),
                        intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE),
                        -1
                    ))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000), -1)
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopRingtoneAndVibration()
        }, 8000)
    }

    private fun stopRingtoneAndVibration() {
        try {
            activeRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        activeRingtone = null

        try {
            val vibrator = activeVibrator
            if (vibrator is Vibrator) {
                vibrator.cancel()
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        activeVibrator = null
    }
}
