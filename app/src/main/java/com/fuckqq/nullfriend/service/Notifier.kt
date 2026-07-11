package com.fuckqq.nullfriend.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.ui.DetectorActivity
import com.fuckqq.nullfriend.util.Log

class Notifier(private val context: Context) {

    init {
        ensureChannel()
    }

    fun notifyRemovals(ownerUin: String, removed: List<FriendEntry>) {
        if (removed.isEmpty()) return
        try {
            ensureChannel()
            val title = if (removed.size == 1) {
                "检测到好友从列表消失：${removed[0].displayName()}"
            } else {
                "检测到 ${removed.size} 位好友从列表消失"
            }
            val names = removed.take(3).joinToString("、") { it.displayName() }
            val more = if (removed.size > 3) " 等" else ""
            val body = names + more

            val intent = Intent(context, DetectorActivity::class.java).apply {
                action = "com.fuckqq.nullfriend.OPEN_DETECTOR"
                putExtra(DetectorActivity.EXTRA_OWNER_UIN, ownerUin)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pi = PendingIntent.getActivity(
                context,
                ownerUin.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.e("notify failed", t)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "好友消失提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "去TM的单向好友：好友列表减少时提醒（可在模块内关闭）"
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "friend_deletion"
        private const val NOTIFICATION_ID = 10086
    }
}
