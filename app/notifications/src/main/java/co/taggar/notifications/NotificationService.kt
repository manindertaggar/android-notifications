package com.Tag

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import co.taggar.notifications.R
import co.taggar.notifications.Tag
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service for handling Firebase Cloud Messaging notifications.
 * This class processes incoming notifications with different templates
 * such as large images, conversations, big text, and inbox styles.
 */
class NotificationService : FirebaseMessagingService() {

    /**
     * Called when a message is received from FCM.
     * @param remoteMessage The received message from FCM.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(Tag(), "Message received from: ${remoteMessage.from}")

        // Validate message data
        if (!remoteMessage.data.containsKey("type") || remoteMessage.data["type"] != "ANDP") {
            Log.d(Tag(), "Ignoring irrelevant notification")
            return
        }

        val notificationId = remoteMessage.data["id"]?.toIntOrNull() ?: generateNotificationId()
        val title = remoteMessage.data["title"]
        val message = remoteMessage.data["message"]
        val color = remoteMessage.data["color"]?.let { parseColor(it) } ?: defaultColor()
        val deeplink = remoteMessage.data["deeplink"]
        val template = remoteMessage.data["template"]

        // Handle notification based on the template type
        when (template) {
            "LARGE" -> remoteMessage.data["image"]?.let {
                handleLargeImageNotification(notificationId, title, message, it, deeplink, color)
            }

            "CONVERSATION" -> remoteMessage.data["conversation"]?.let {
                handleConversationNotification(notificationId, title, it, color)
            }

            "BIG_TEXT" -> handleBigTextNotification(notificationId, title, message, deeplink, color)

            "INBOX" -> remoteMessage.data["lines"]?.let {
                handleInboxStyleNotification(notificationId, title, message, it, color)
            }

            else -> sendDefaultNotification(notificationId, title, message, deeplink, color)
        }

        // Handle any attached actions (buttons)
        handleNotificationActions(notificationId, remoteMessage.data["buttons"])
    }

    /**
     * Handles large image notifications using a coroutine to download the image asynchronously.
     * @param notificationId ID of the notification to be displayed.
     * @param title Title of the notification.
     * @param message Content of the notification.
     * @param imageUrl URL of the image to be downloaded.
     * @param deeplink Optional deeplink URL for user interaction.
     * @param color Custom color for notification display.
     */
    private fun handleLargeImageNotification(
        notificationId: Int,
        title: String?,
        message: String?,
        imageUrl: String,
        deeplink: String?,
        color: Int
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = downloadImage(imageUrl)
            bitmap?.let {
                sendLargeImageNotification(notificationId, title, message, it, deeplink, color)
            }
        }
    }

    /**
     * Downloads an image from a URL asynchronously.
     * @param url The URL of the image to download.
     * @return The downloaded bitmap or null if download fails.
     */
    private suspend fun downloadImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
        return@withContext try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()
            BitmapFactory.decodeStream(connection.inputStream)
        } catch (e: IOException) {
            Log.e(Tag(), "Failed to download image: $e")
            null
        }
    }

    /**
     * Sends a large image notification once the image is successfully downloaded.
     * @param notificationId ID of the notification.
     * @param title Notification title.
     * @param message Notification content.
     * @param bitmap The downloaded image.
     * @param deeplink Optional deeplink for the notification.
     * @param color Custom color for the notification.
     */
    private fun sendLargeImageNotification(
        notificationId: Int,
        title: String?,
        message: String?,
        bitmap: Bitmap,
        deeplink: String?,
        color: Int
    ) {
        val notificationBuilder =
            createBaseNotificationBuilder(notificationId, title, message, deeplink, color)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))

        notify(notificationId, notificationBuilder)
    }

    /**
     * Handles conversation-style notifications, parsing the provided JSON for conversation data.
     * @param notificationId ID of the notification.
     * @param title Title of the notification.
     * @param conversationJson JSON string representing the conversation messages.
     * @param color Custom color for the notification.
     */
    private fun handleConversationNotification(
        notificationId: Int,
        title: String?,
        conversationJson: String,
        color: Int
    ) {
        val messagingStyle = NotificationCompat.MessagingStyle("Me")
        val messages = parseConversationMessages(conversationJson)

        messages.forEach { messagingStyle.addMessage(it) }

        val notificationBuilder =
            createBaseNotificationBuilder(notificationId, title, null, null, color)
                .setStyle(messagingStyle)

        notify(notificationId, notificationBuilder)
    }

    /**
     * Handles big text-style notifications.
     * @param notificationId ID of the notification.
     * @param title Title of the notification.
     * @param message Content of the notification.
     * @param deeplink Optional deeplink URL.
     * @param color Custom color for the notification.
     */
    private fun handleBigTextNotification(
        notificationId: Int,
        title: String?,
        message: String?,
        deeplink: String?,
        color: Int
    ) {
        val notificationBuilder =
            createBaseNotificationBuilder(notificationId, title, message, deeplink, color)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))

        notify(notificationId, notificationBuilder)
    }

    /**
     * Handles inbox-style notifications.
     * @param notificationId ID of the notification.
     * @param title Title of the notification.
     * @param message Content of the notification.
     * @param linesJson JSON string representing the lines to be added in the inbox style.
     * @param color Custom color for the notification.
     */
    private fun handleInboxStyleNotification(
        notificationId: Int,
        title: String?,
        message: String?,
        linesJson: String,
        color: Int
    ) {
        val inboxStyle = NotificationCompat.InboxStyle()
        parseInboxLines(linesJson).forEach { inboxStyle.addLine(it) }

        val notificationBuilder =
            createBaseNotificationBuilder(notificationId, title, message, null, color)
                .setStyle(inboxStyle)

        notify(notificationId, notificationBuilder)
    }

    /**
     * Sends the default notification when no specific template is defined.
     * @param notificationId ID of the notification.
     * @param title Title of the notification.
     * @param message Content of the notification.
     * @param deeplink Optional deeplink URL.
     * @param color Custom color for the notification.
     */
    private fun sendDefaultNotification(
        notificationId: Int,
        title: String?,
        message: String?,
        deeplink: String?,
        color: Int
    ) {
        val notificationBuilder =
            createBaseNotificationBuilder(notificationId, title, message, deeplink, color)
        notify(notificationId, notificationBuilder)
    }

    /**
     * Adds actions (buttons) to the notification if they are provided.
     * @param notificationId ID of the notification.
     * @param buttonsJson JSON string representing the buttons and their actions.
     */
    private fun handleNotificationActions(notificationId: Int, buttonsJson: String?) {
        buttonsJson?.let {
            val buttons = parseNotificationButtons(it)
            val notificationBuilder =
                createBaseNotificationBuilder(notificationId, null, null, null, defaultColor())

            buttons.forEach { (text, deeplink) ->
                notificationBuilder.addAction(0, text, getDeeplinkIntent(deeplink))
            }

            notify(notificationId, notificationBuilder)
        }
    }

    // Other helper methods, such as `parseConversationMessages`, `parseInboxLines`, and `parseNotificationButtons` go here...

    /**
     * Parses a color string into an integer value.
     * @param colorHex The hex color string.
     * @return The parsed color, or a default color if parsing fails.
     */
    private fun parseColor(colorHex: String): Int {
        return try {
            Color.parseColor(colorHex)
        } catch (e: IllegalArgumentException) {
            defaultColor()
        }
    }

    /**
     * Returns the default notification color.
     * @return The default color resource value.
     */
    private fun defaultColor(): Int {
        return ContextCompat.getColor(this, R.color.default_notification_color)
    }

    /**
     * Creates a base notification builder with common settings applied.
     * @param notificationId ID of the notification.
     * @param title Title of the notification.
     * @param message Content of the notification.
     * @param deeplink Optional deeplink URL.
     * @param color Custom color for the notification.
     * @return A configured NotificationCompat.Builder instance.
     */
    private fun createBaseNotificationBuilder(
        notificationId: Int,
        title: String?,
        message: String?,
        deeplink: String?,
        color: Int
    ): NotificationCompat.Builder {
        val channelId = getChannelId()
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setColor(color)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        deeplink?.let { builder.setContentIntent(getDeeplinkIntent(it)) }

        if (isSoundOn()) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }

        return builder
    }

    /**
     * Notifies the user by displaying the notification.
     * @param notificationId ID of the notification.
     * @param builder The notification builder to build and display the notification.
     */
    private fun notify(notificationId: Int, builder: NotificationCompat.Builder) {
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Deletes the notification by its ID.
     * @param notificationId ID of the notification to delete.
     */
    fun deleteNotification(notificationId: Int) {
        NotificationManagerCompat.from(this).cancel(notificationId)
    }

    /**
     * Generates a unique notification ID based on the current system time.
     * @return A unique notification ID.
     */
    private fun generateNotificationId(): Int {
        return (System.currentTimeMillis() and 0xFFFFFFF).toInt()
    }

    /**
     * Returns the default notification channel ID.
     * @return The default channel ID string.
     */
    private fun getChannelId(): String {
        return "default_channel_id"
    }

    /**
     * Checks if the notification sound should be enabled based on user preferences.
     * @return True if sound is enabled, false otherwise.
     */
    private fun isSoundOn(): Boolean {
        // Implement actual logic to check user settings/preferences
        return true
    }

    /**
     * Creates a pending intent for opening a deeplink URL.
     * @param deeplink The deeplink URL to open.
     * @return A PendingIntent for the deeplink.
     */
    private fun getDeeplinkIntent(deeplink: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deeplink))
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Parses a JSON string of conversation messages and returns a list of `NotificationCompat.MessagingStyle.Message`.
     * The expected format is an array of objects with "text", "timestamp", and "sender" fields.
     *
     * Example JSON:
     * [
     *   { "text": "Hello", "timestamp": 1634567890123, "sender": "John" },
     *   { "text": "How are you?", "timestamp": 1634567890456, "sender": "Me" }
     * ]
     *
     * @param conversationJson The JSON string representing the conversation.
     * @return A list of `NotificationCompat.MessagingStyle.Message`.
     */
    private fun parseConversationMessages(conversationJson: String): List<NotificationCompat.MessagingStyle.Message> {
        val messages = mutableListOf<NotificationCompat.MessagingStyle.Message>()

        try {
            val jsonArray = JSONArray(conversationJson)

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val text = jsonObject.getString("text")
                val timestamp = jsonObject.getLong("timestamp")
                val senderName = jsonObject.getString("sender")

                // Create a Person object for the sender
                val person = Person.Builder().setName(senderName).build()

                // Create a Message object
                val message = NotificationCompat.MessagingStyle.Message(text, timestamp, person)
                messages.add(message)
            }
        } catch (e: Exception) {
            Log.e("NotificationService", "Error parsing conversation messages: ${e.message}")
        }

        return messages
    }

    /**
     * Parses a JSON string of inbox lines and returns a list of strings for the `NotificationCompat.InboxStyle`.
     *
     * Example JSON:
     * [
     *   "Line 1",
     *   "Line 2",
     *   "Line 3"
     * ]
     *
     * @param linesJson The JSON string representing the lines of the inbox style notification.
     * @return A list of strings representing each line.
     */
    private fun parseInboxLines(linesJson: String): List<String> {
        val lines = mutableListOf<String>()

        try {
            val jsonArray = JSONArray(linesJson)

            for (i in 0 until jsonArray.length()) {
                lines.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            Log.e("NotificationService", "Error parsing inbox lines: ${e.message}")
        }

        return lines
    }

    /**
     * Parses a JSON string of notification buttons and returns a list of pairs where the first value is
     * the button text and the second value is the deeplink URL.
     *
     * Example JSON:
     * [
     *   { "text": "Open App", "deeplink": "https://example.com/app" },
     *   { "text": "Cancel", "deeplink": "https://example.com/cancel" }
     * ]
     *
     * @param buttonsJson The JSON string representing the buttons.
     * @return A list of pairs (button text, deeplink URL).
     */
    private fun parseNotificationButtons(buttonsJson: String): List<Pair<String, String>> {
        val buttons = mutableListOf<Pair<String, String>>()

        try {
            val jsonArray = JSONArray(buttonsJson)

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val text = jsonObject.getString("text")
                val deeplink = jsonObject.getString("deeplink")
                buttons.add(Pair(text, deeplink))
            }
        } catch (e: Exception) {
            Log.e("NotificationService", "Error parsing notification buttons: ${e.message}")
        }

        return buttons
    }
}