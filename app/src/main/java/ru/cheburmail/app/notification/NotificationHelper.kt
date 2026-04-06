package ru.cheburmail.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.cheburmail.app.MainActivity

/**
 * Управление уведомлениями CheburMail.
 *
 * Каналы:
 * - CHANNEL_MESSAGES — "Новые сообщения" (high importance, heads-up)
 * - CHANNEL_SYNC — "Синхронизация" (low importance, persistent для foreground service)
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Создать каналы уведомлений. Вызывать в Application.onCreate().
     * Безопасно вызывать многократно — Android игнорирует повторное создание.
     */
    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Новые сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых входящих сообщениях"
                enableVibration(true)
                setShowBadge(true)
            }

            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                "Синхронизация",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновая синхронизация почты"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(messagesChannel)
            notificationManager.createNotificationChannel(syncChannel)
        }
    }

    /**
     * Показать heads-up уведомление о новом сообщении.
     *
     * @param senderName имя отправителя
     * @param preview превью текста сообщения (первые ~100 символов)
     * @param chatId ID чата для навигации при нажатии
     */
    fun showMessageNotification(senderName: String, preview: String, chatId: String? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            chatId?.let { putExtra(EXTRA_CHAT_ID, it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            chatId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(preview)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("CheburMail")
                    .setContentText("Новое сообщение")
                    .build()
            )
            .build()

        val notificationId = chatId?.hashCode() ?: System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Создать persistent-уведомление для Foreground Service синхронизации.
     * Низкий приоритет, без звука и вибрации.
     *
     * @return Notification для startForeground()
     */
    fun createSyncNotification(): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("CheburMail")
            .setContentText("Синхронизация активна")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val CHANNEL_MESSAGES = "cheburmail_messages"
        const val CHANNEL_SYNC = "cheburmail_sync"
        const val SYNC_NOTIFICATION_ID = 1001
        const val EXTRA_CHAT_ID = "extra_chat_id"
    }
}
