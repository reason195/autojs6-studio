package cn.dumate.a6studio.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 采集目标 App 期间的轻量前台保活。
 *
 * 实测：在 Studio 里 app_launch 拉起京东这种重应用后，系统会把 Studio 进程回收，
 * 回到 Studio 时状态全丢、采集结果也没了。这里用前台服务把进程钉住，采集结束再关掉。
 * 不做任何网络/后台计算，只是持有前台通知。
 */
class KeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "采集保活", NotificationManager.IMPORTANCE_MIN).apply {
                description = "在采集目标 App 期间保持 AutoJs6 Studio 运行"
                setShowBadge(false)
            }
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("AutoJs6 Studio 正在采集目标 App")
            .setContentText("采集完成后会自动结束")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "a6studio_keepalive"
        const val NOTIFICATION_ID = 4101

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, KeepAliveService::class.java)
                ctx.startForegroundService(i)
            }.onFailure { Log.w("KeepAliveService", "前台保活启动失败", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
                .onFailure { Log.w("KeepAliveService", "前台保活停止失败", it) }
        }
    }
}
