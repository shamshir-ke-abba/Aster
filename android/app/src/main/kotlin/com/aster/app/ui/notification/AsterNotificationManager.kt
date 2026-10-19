package com.aster.app.ui.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aster.app.MainActivity
import com.aster.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production-ready notification manager for Aster messaging.
 *
 * Handles:
 * - Dedicated notification channels with sensible defaults and user control.
 * - Active-conversation suppression (no alerts for the chat currently being viewed).
 * - Multi-message aggregation and grouping per conversation.
 * - Privacy-conscious lock screen masking (VISIBILITY_PRIVATE).
 * - Safe behavior when notifications are disabled or permissions are denied.
 */
@Singleton
class AsterNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val CHANNEL_ID_MESSAGES = "aster_messages"
        const val GROUP_KEY_ASTER_MESSAGES = "com.aster.app.MESSAGES"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val SUMMARY_NOTIFICATION_ID = 1001
        private const val TAG = "AsterNotificationMgr"
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    var previewPolicy: NotificationPreviewPolicy = NotificationPreviewPolicy.SHOW_PREVIEW

    /**
     * Creates the notification channel on Android 8.0 (API 26) and above.
     */
    fun initChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Aster Messages"
            val descriptionText = "Incoming direct and constellation messages"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID_MESSAGES, name, importance).apply {
                description = descriptionText
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel '$CHANNEL_ID_MESSAGES' initialized")
        }
    }

    /**
     * Marks the conversation currently being viewed on-screen, or null when navigated away.
     */
    fun setActiveConversation(conversationId: String?) {
        _activeConversationId.value = conversationId
        if (conversationId != null) {
            // Dismiss existing notification for this conversation since the user has opened it
            dismissNotification(conversationId)
        }
    }

    fun getActiveConversation(): String? = _activeConversationId.value

    var isAppForeground: Boolean = true

    /**
     * Dispatches an in-app system notification for an incoming direct or group message.
     *
     * Suppresses alerts if the user is actively viewing the conversation while the app is in the foreground,
     * or if system notifications are disabled.
     */
    fun showMessageNotification(
        conversationId: String,
        conversationTitle: String,
        isGroup: Boolean,
        senderDisplayName: String,
        messageText: String,
        messageTimestamp: Long
    ) {
        // 1. Suppression check: actively viewed conversation while in foreground
        if (isAppForeground && getActiveConversation() == conversationId) {
            Log.d(TAG, "Suppressed notification for actively viewed conversation: $conversationId")
            return
        }

        // 2. Permission / system settings check
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.d(TAG, "System notifications are disabled; skipping notification display")
            return
        }

        // 3. Build Deep-Link PendingIntent
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Format Title & Content based on Group vs Direct and Privacy Policy
        val title = if (isGroup) {
            conversationTitle
        } else {
            senderDisplayName
        }

        val displayText = when (previewPolicy) {
            NotificationPreviewPolicy.SHOW_PREVIEW -> {
                if (isGroup) "$senderDisplayName: $messageText" else messageText
            }
            NotificationPreviewPolicy.HIDE_PREVIEW_NAME_ONLY -> {
                if (isGroup) "$senderDisplayName sent a message" else "New message"
            }
        }

        val notificationId = conversationId.hashCode()

        // 5. Individual Conversation Notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(displayText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(displayText)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY_ASTER_MESSAGES)
            .setWhen(messageTimestamp)

        try {
            notificationManager.notify(notificationId, builder.build())

            // 6. Post summary notification for clean drawer aggregation
            val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Aster Messages")
                .setContentText("New messages in your constellations")
                .setGroup(GROUP_KEY_ASTER_MESSAGES)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryBuilder.build())
            Log.d(TAG, "Posted notification for conversation $conversationId (id=$notificationId)")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException posting notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting notification: ${e.message}", e)
        }
    }

    /**
     * Dismisses the notification for a specific conversation when opened.
     */
    fun dismissNotification(conversationId: String) {
        try {
            notificationManager.cancel(conversationId.hashCode())
        } catch (_: Exception) {}
    }

    /**
     * Dismisses all Aster notifications.
     */
    fun dismissAllNotifications() {
        try {
            notificationManager.cancelAll()
        } catch (_: Exception) {}
    }
}
