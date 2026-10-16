package com.example.gentlenudge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.gentlenudge.data.model.NudgeTask
import com.example.gentlenudge.deepdive.DeepDiveManager
import com.example.gentlenudge.notification.NudgeNotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = GentleNudgeApp::class)
class NotificationSoundChannelTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = shadowOf(notificationManager)
    }

    @Test
    fun testV3ChannelsCreatedWithHighImportanceAndSound() {
        NudgeNotificationHelper.createNotificationChannels(context)

        val gentleChannel = notificationManager.getNotificationChannel(NudgeNotificationHelper.GENTLE_CHANNEL_ID)
        assertNotNull("Gentle v3 channel must be created", gentleChannel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, gentleChannel.importance)
        assertNotNull("Gentle v3 channel must have a sound URI", gentleChannel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, gentleChannel.audioAttributes.usage)
        assertTrue("Gentle v3 channel must have vibration enabled", gentleChannel.shouldVibrate())

        val ringtoneChannel = notificationManager.getNotificationChannel(NudgeNotificationHelper.FULL_RINGTONE_CHANNEL_ID)
        assertNotNull("Full ringtone v3 channel must be created", ringtoneChannel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ringtoneChannel.importance)
        assertNotNull("Full ringtone v3 channel must have a sound URI", ringtoneChannel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, ringtoneChannel.audioAttributes.usage)
        assertTrue("Full ringtone v3 channel must have vibration enabled", ringtoneChannel.shouldVibrate())
    }

    @Test
    fun testObsoleteChannelsCleanedUp() {
        // Pre-create obsolete channels as if from older APK versions
        val legacyGentleV1 = NotificationChannel("gentle_nudge_channel", "Old Gentle", NotificationManager.IMPORTANCE_DEFAULT)
        val legacyGentleV2 = NotificationChannel("gentle_nudge_channel_v2", "Old Gentle v2", NotificationManager.IMPORTANCE_LOW)
        val legacyFullV1 = NotificationChannel("full_ringtone_nudge_channel", "Old Full", NotificationManager.IMPORTANCE_DEFAULT)
        val legacyFullV2 = NotificationChannel("full_ringtone_nudge_channel_v2", "Old Full v2", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(legacyGentleV1)
        notificationManager.createNotificationChannel(legacyGentleV2)
        notificationManager.createNotificationChannel(legacyFullV1)
        notificationManager.createNotificationChannel(legacyFullV2)

        assertNotNull(notificationManager.getNotificationChannel("gentle_nudge_channel"))
        assertNotNull(notificationManager.getNotificationChannel("gentle_nudge_channel_v2"))

        // Run channel migration
        NudgeNotificationHelper.createNotificationChannels(context)

        assertNull("Legacy gentle v1 must be deleted", notificationManager.getNotificationChannel("gentle_nudge_channel"))
        assertNull("Legacy gentle v2 must be deleted", notificationManager.getNotificationChannel("gentle_nudge_channel_v2"))
        assertNull("Legacy full ringtone v1 must be deleted", notificationManager.getNotificationChannel("full_ringtone_nudge_channel"))
        assertNull("Legacy full ringtone v2 must be deleted", notificationManager.getNotificationChannel("full_ringtone_nudge_channel_v2"))

        // Ensure active v3 channels remain present
        assertNotNull(notificationManager.getNotificationChannel(NudgeNotificationHelper.GENTLE_CHANNEL_ID))
        assertNotNull(notificationManager.getNotificationChannel(NudgeNotificationHelper.FULL_RINGTONE_CHANNEL_ID))
    }

    @Test
    fun testShowNudgeNotificationSmallNudgeUsesGentleChannel() {
        val task = NudgeTask(
            id = 501L,
            title = "Water the plants",
            dateLabel = "Today",
            timeLabel = "3:00 PM",
            soundType = "Small nudge",
            isDone = false
        )

        val posted = NudgeNotificationHelper.showNudgeNotification(context, task)
        assertTrue("Notification must be posted", posted)

        val notification = shadowNotificationManager.getNotification(task.id.toInt())
        assertNotNull("Posted notification must exist", notification)
        assertEquals(NudgeNotificationHelper.GENTLE_CHANNEL_ID, notification.channelId)

        val channel = notificationManager.getNotificationChannel(notification.channelId)
        assertNotNull("Channel for notification must exist", channel)
        assertNotNull("Channel must have sound configured", channel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
    }

    @Test
    fun testShowNudgeNotificationFullRingtoneUsesRingtoneChannel() {
        val task = NudgeTask(
            id = 502L,
            title = "Boarding flight",
            dateLabel = "Today",
            timeLabel = "5:00 PM",
            soundType = "Full ringtone",
            isDone = false
        )

        val posted = NudgeNotificationHelper.showNudgeNotification(context, task)
        assertTrue("Notification must be posted", posted)

        val notification = shadowNotificationManager.getNotification(task.id.toInt())
        assertNotNull("Posted notification must exist", notification)
        assertEquals(NudgeNotificationHelper.FULL_RINGTONE_CHANNEL_ID, notification.channelId)

        val channel = notificationManager.getNotificationChannel(notification.channelId)
        assertNotNull("Channel for notification must exist", channel)
        assertNotNull("Channel must have alarm sound configured", channel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
    }

    @Test
    fun testDeepDiveNotificationsTargetCorrectChannelsWithSound() {
        // Deep Dive One Shot
        DeepDiveManager.onAlarmFired(context, "One Shot")
        val oneShotNotification = shadowNotificationManager.getNotification(DeepDiveManager.DEEP_DIVE_NOTIFICATION_ID)
        assertNotNull("Deep Dive completion notification must be posted", oneShotNotification)
        assertEquals(NudgeNotificationHelper.GENTLE_CHANNEL_ID, oneShotNotification.channelId)
        val gentleChannel = notificationManager.getNotificationChannel(oneShotNotification.channelId)
        assertNotNull(gentleChannel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, gentleChannel.audioAttributes.usage)

        // Deep Dive Full Ringtone
        DeepDiveManager.onAlarmFired(context, "Full Ringtone")
        val ringtoneNotification = shadowNotificationManager.getNotification(DeepDiveManager.DEEP_DIVE_NOTIFICATION_ID)
        assertNotNull("Deep Dive completion notification must be posted", ringtoneNotification)
        assertEquals(NudgeNotificationHelper.FULL_RINGTONE_CHANNEL_ID, ringtoneNotification.channelId)
        val ringtoneChannel = notificationManager.getNotificationChannel(ringtoneNotification.channelId)
        assertNotNull(ringtoneChannel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, ringtoneChannel.audioAttributes.usage)
    }

    @Test
    fun testCustomRingtoneFallbackWhenInaccessible() {
        val task = NudgeTask(
            id = 503L,
            title = "Wake up call",
            dateLabel = "Today",
            timeLabel = "7:00 AM",
            soundType = "Full ringtone",
            ringtoneUri = "content://nonexistent/provider/sound.mp3",
            ringtoneTitle = "Missing Sound",
            isDone = false
        )

        val posted = NudgeNotificationHelper.showNudgeNotification(context, task)
        assertTrue("Notification should post using fallback sound", posted)

        val notification = shadowNotificationManager.getNotification(task.id.toInt())
        assertNotNull(notification)
        // Inaccessible URI falls back to FULL_RINGTONE_CHANNEL_ID
        assertEquals(NudgeNotificationHelper.FULL_RINGTONE_CHANNEL_ID, notification.channelId)
        val channel = notificationManager.getNotificationChannel(notification.channelId)
        assertNotNull("Fallback channel must have alarm sound", channel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
    }
}
