package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TimerStatus {
    READY,      // Ready to start working
    FOCUSING,   // Active focus countdown
    BREAKING    // Timer elapsed, alarm ringing, prompt to take break
}

class TimerViewModel(application: Application) : AndroidViewModel(application) {

    private val PREFS_NAME = "standup_prefs"
    private val KEY_MINUTES = "selected_minutes"
    private val KEY_SOUND = "sound_enabled"
    private val KEY_VIBRATE = "vibration_enabled"
    private val KEY_COUNT = "completed_count"
    private val KEY_COUNT_DATE = "completed_count_date"
    private val KEY_HISTORY = "completed_history_logs"
    private val CHANNEL_ID = "standup_timer_channel"

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(TimerStatus.READY)
    val status: StateFlow<TimerStatus> = _status.asStateFlow()

    private val _presetMinutes = MutableStateFlow(30)
    val presetMinutes: StateFlow<Int> = _presetMinutes.asStateFlow()

    private val _customInputText = MutableStateFlow("30")
    val customInputText: StateFlow<String> = _customInputText.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(0L)
    val secondsRemaining: StateFlow<Long> = _secondsRemaining.asStateFlow()

    private val _totalDurationSeconds = MutableStateFlow(0L)
    val totalDurationSeconds: StateFlow<Long> = _totalDurationSeconds.asStateFlow()

    private val _startTimeFormatted = MutableStateFlow<String?>(null)
    val startTimeFormatted: StateFlow<String?> = _startTimeFormatted.asStateFlow()

    private val _targetTimeFormatted = MutableStateFlow<String?>(null)
    val targetTimeFormatted: StateFlow<String?> = _targetTimeFormatted.asStateFlow()

    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _vibrateEnabled = MutableStateFlow(true)
    val vibrateEnabled: StateFlow<Boolean> = _vibrateEnabled.asStateFlow()

    private val _completedSessionsToday = MutableStateFlow(0)
    val completedSessionsToday: StateFlow<Int> = _completedSessionsToday.asStateFlow()

    // History stats list (persisted in SP)
    private val _sessionHistory = MutableStateFlow<List<String>>(emptyList())
    val sessionHistory: StateFlow<List<String>> = _sessionHistory.asStateFlow()

    // Real-time ticking Clock
    private val _currentTimeFormatted = MutableStateFlow("")
    val currentTimeFormatted: StateFlow<String> = _currentTimeFormatted.asStateFlow()

    private var countDownTimer: CountDownTimer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var autoStopJob: Job? = null
    private var clockTickerJob: Job? = null

    init {
        val savedMins = prefs.getInt(KEY_MINUTES, 30)
        _presetMinutes.value = savedMins
        _customInputText.value = savedMins.toString()

        _soundEnabled.value = prefs.getBoolean(KEY_SOUND, true)
        _vibrateEnabled.value = prefs.getBoolean(KEY_VIBRATE, true)

        loadCompletedSessionsToday()
        loadHistoryLogs()
        startClockTicker()
        createNotificationChannel()
    }

    private fun startClockTicker() {
        clockTickerJob?.cancel()
        clockTickerJob = viewModelScope.launch {
            val clockFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
            while (isActive) {
                _currentTimeFormatted.value = clockFormat.format(Date())
                delay(1000)
            }
        }
    }

    private fun loadCompletedSessionsToday() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val savedDate = prefs.getString(KEY_COUNT_DATE, "")
        if (savedDate == todayStr) {
            _completedSessionsToday.value = prefs.getInt(KEY_COUNT, 0)
        } else {
            _completedSessionsToday.value = 0
            _sessionHistory.value = emptyList()
            prefs.edit()
                .putString(KEY_COUNT_DATE, todayStr)
                .putInt(KEY_COUNT, 0)
                .putString(KEY_HISTORY, "")
                .apply()
        }
    }

    private fun loadHistoryLogs() {
        val rawLogs = prefs.getString(KEY_HISTORY, "") ?: ""
        if (rawLogs.isNotEmpty()) {
            _sessionHistory.value = rawLogs.split("||").filter { it.isNotBlank() }
        } else {
            _sessionHistory.value = emptyList()
        }
    }

    private fun addHistoryLog(log: String) {
        val newList = ArrayList(_sessionHistory.value)
        newList.add(0, log) // Add newest at start
        _sessionHistory.value = newList
        val joined = newList.joinToString("||")
        prefs.edit().putString(KEY_HISTORY, joined).apply()
    }

    fun clearHistory() {
        _completedSessionsToday.value = 0
        _sessionHistory.value = emptyList()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit()
            .putInt(KEY_COUNT, 0)
            .putString(KEY_COUNT_DATE, todayStr)
            .putString(KEY_HISTORY, "")
            .apply()
    }

    fun setPresetMinutes(minutes: Int) {
        if (_status.value == TimerStatus.READY) {
            val clamped = minutes.coerceIn(1, 180)
            _presetMinutes.value = clamped
            _customInputText.value = clamped.toString()
            prefs.edit().putInt(KEY_MINUTES, clamped).apply()
        }
    }

    fun setCustomInputText(text: String) {
        if (_status.value == TimerStatus.READY) {
            _customInputText.value = text
            text.toIntOrNull()?.let {
                val clamped = it.coerceIn(1, 180)
                _presetMinutes.value = clamped
                prefs.edit().putInt(KEY_MINUTES, clamped).apply()
            }
        }
    }

    fun toggleSound() {
        val nextValue = !_soundEnabled.value
        _soundEnabled.value = nextValue
        prefs.edit().putBoolean(KEY_SOUND, nextValue).apply()
    }

    fun toggleVibrate() {
        val nextValue = !_vibrateEnabled.value
        _vibrateEnabled.value = nextValue
        prefs.edit().putBoolean(KEY_VIBRATE, nextValue).apply()
    }

    fun startSession() {
        val minutes = _presetMinutes.value
        val durationMs = minutes * 60 * 1000L
        _totalDurationSeconds.value = minutes * 60L
        _secondsRemaining.value = minutes * 60L

        val now = Date()
        val format = SimpleDateFormat("h:mm a", Locale.getDefault()) // User requested h:mm AM/PM format
        _startTimeFormatted.value = format.format(now)
        _targetTimeFormatted.value = format.format(Date(now.time + durationMs))

        _status.value = TimerStatus.FOCUSING

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _secondsRemaining.value = (millisUntilFinished + 500) / 1000
            }

            override fun onFinish() {
                _secondsRemaining.value = 0
                triggerAlarm()
            }
        }.start()
    }

    fun resetSession() {
        countDownTimer?.cancel()
        countDownTimer = null
        stopAlarmSignalOnly()
        autoStopJob?.cancel()
        autoStopJob = null
        _status.value = TimerStatus.READY
        _secondsRemaining.value = 0
        _startTimeFormatted.value = null
        _targetTimeFormatted.value = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val context = getApplication<Application>()
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

    private fun showBreakNotification() {
        val context = getApplication<Application>()
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

    private fun triggerAlarm() {
        _status.value = TimerStatus.BREAKING

        // Create log record
        val startStr = _startTimeFormatted.value ?: ""
        val nowStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val durationMins = _presetMinutes.value
        val historyEntry = "$startStr - $nowStr ($durationMins mins Session)"
        addHistoryLog(historyEntry)

        // Increment count
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val currentCount = _completedSessionsToday.value + 1
        _completedSessionsToday.value = currentCount
        prefs.edit()
            .putString(KEY_COUNT_DATE, todayStr)
            .putInt(KEY_COUNT, currentCount)
            .apply()

        showBreakNotification()

        val context = getApplication<Application>()

        if (_soundEnabled.value) {
            try {
                val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtone = RingtoneManager.getRingtone(context, alertUri)
                ringtone?.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (_vibrateEnabled.value) {
            try {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(
                        longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000),
                        intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE),
                        -1
                    ))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000), -1)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        autoStopJob?.cancel()
        autoStopJob = viewModelScope.launch {
            delay(8000)
            stopAlarmSignalOnly()
        }
    }

    private fun stopAlarmSignalOnly() {
        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        ringtone = null

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        vibrator = null
    }

    override fun onCleared() {
        super.onCleared()
        countDownTimer?.cancel()
        clockTickerJob?.cancel()
        stopAlarmSignalOnly()
        autoStopJob?.cancel()
    }
}
