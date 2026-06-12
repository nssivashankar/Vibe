package me.lucky.vibe

import android.app.Notification
import android.media.AudioManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager

class NotificationListenerService : NotificationListenerService() {
    companion object {
        private const val DIALER_SUFFIX = ".dialer"
    }

    private lateinit var prefs: Preferences
    private lateinit var vibrator: Vibrator
    private var telecomManager: TelecomManager? = null
    private var audioManager: AudioManager? = null
    
    private val activeKeys = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
        vibrator = Vibrator(this)
        telecomManager = getSystemService(TelecomManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        
        val n = sbn.notification
        val pkg = sbn.packageName ?: ""
        val key = sbn.key
        
        val isWhatsApp = pkg.contains("whatsapp", ignoreCase = true)
        val isTelegram = pkg.contains("telegram", ignoreCase = true) || pkg.contains("challegram", ignoreCase = true)
        val isDialer = pkg.endsWith(DIALER_SUFFIX) || pkg == telecomManager?.defaultDialerPackage
        val isCallCategory = n.category == Notification.CATEGORY_CALL
        
        if (prefs.isFilterPackageNames && !isWhatsApp && !isTelegram && !isDialer && !isCallCategory) return

        if (!sbn.isOngoing) {
            activeKeys.remove(key)
            return
        }

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lowercase() ?: ""
        val hasChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        
        val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val isInCall = audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION
        
        val hasAnswer = hasAnswerAction(n)
        
        // --- RINGING / CALLING DETECTION ---
        val isRinging = hasAnswer || title.contains("ringing") || text.contains("ringing") || 
                        title.contains("calling") || text.contains("calling") ||
                        title.contains("incoming") || text.contains("incoming") ||
                        title.contains("outgoing") || text.contains("outgoing")

        // --- ANSWERED DETECTION ---
        val hasTimerText = text.contains(":") || title.contains(":")
        val isOngoingText = title.contains("ongoing") || text.contains("ongoing")
        
        val isAnswered = if (isDialer && !isWhatsApp && !isTelegram) {
            // For stock dialers, the chronometer is the most reliable signal for answered calls.
            hasChronometer
        } else {
            // For VoIP apps, we need to be more flexible with text and audio mode checks.
            !isRinging && (hasChronometer || isOngoingText || hasTimerText || (isInCall && (isWhatsApp || isTelegram || isCallCategory)))
        }

        if (isAnswered) {
            if (!activeKeys.contains(key)) {
                activeKeys.add(key)
                if (prefs.isVibeAtStart) {
                    vibrator.vibrate()
                }
            }
        } else {
            activeKeys.remove(key)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        if (activeKeys.remove(sbn.key)) {
            if (prefs.isVibeAtEnd) {
                vibrator.vibrate()
            }
        }
    }

    private fun hasAnswerAction(n: Notification): Boolean {
        val actions = n.actions ?: return false
        return actions.any {
            val actionTitle = it.title?.toString()?.lowercase() ?: ""
            actionTitle.contains("answer") || actionTitle.contains("accept") || 
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.semanticAction == 9)
        }
    }
}
