package com.fuckqq.nullfriend.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.hook.SettingsInjectHook
import com.fuckqq.nullfriend.ui.DetectorActivity
import com.fuckqq.nullfriend.util.Log

/**
 * 好友消失通知。
 *
 * 参考 QAuxiliary ExfriendManager.doNotifyDelFlAndSave：
 *  - 不用 NotificationCompat（不支持 setSmallIcon(Bitmap)）
 *  - smallIcon 用 Icon.createWithBitmap，运行时画，避免 QQ 进程找不到模块资源 resId
 *  - 裸 Notification.Builder + nm.notify，跳过 compat 的权限检查（QQ 已有通知权限即可发）
 *  - 点击跳 QQ 内面板（独立进程的 DetectorActivity 看不到 QQ 进程数据）
 */
class Notifier(private val context: Context) {

    init {
        ensureChannel()
    }

    @Suppress("DEPRECATION")
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

            // 点击打开 QQ 内面板（独立进程打开 DetectorActivity 看不到 QQ 进程数据）
            val intent = Intent().apply {
                component = ComponentName(
                    Constants.QQ_PACKAGE,
                    "com.tencent.mobileqq.activity.SplashActivity"
                )
                action = SettingsInjectHook.ACTION_OPEN_PANEL
                putExtra(SettingsInjectHook.EXTRA_OPEN_PANEL, true)
                putExtra("open_nullfriend", "1")
                putExtra(DetectorActivity.EXTRA_OWNER_UIN, ownerUin)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                ownerUin.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(buildSmallIcon())
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
                    .setSmallIcon(buildSmallIcon())
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
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
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * 运行时绘制 smallIcon（信号青方块），避免 QQ 进程找不到模块资源 resId。
     * 返回 Icon（API 23+），低版本回退到系统 drawable。
     */
    private fun buildSmallIcon(): Icon {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Icon.createWithBitmap(drawIconBitmap())
        } else {
            @Suppress("DEPRECATION")
            Icon.createWithResource(context, android.R.drawable.ic_dialog_alert)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun drawIconBitmap(): Bitmap {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 透明底（通知栏会着色，用白色填充让 silhouette 可见）
        paint.color = 0xFFFFFFFF.toInt()
        // 信号青方块
        val left = size * 0.3f
        val top = size * 0.3f
        val right = size * 0.7f
        val bottom = size * 0.7f
        canvas.drawRect(left, top, right, bottom, paint)
        return bmp
    }

    companion object {
        private const val CHANNEL_ID = "friend_deletion"
        private const val NOTIFICATION_ID = 10086
    }
}
