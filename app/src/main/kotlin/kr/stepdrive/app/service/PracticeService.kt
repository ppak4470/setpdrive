package kr.stepdrive.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.stepdrive.app.MainActivity
import kr.stepdrive.app.R
import kr.stepdrive.app.StepDriveApp
import kr.stepdrive.app.session.Phase
import kr.stepdrive.app.diag.SignalCheck
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * Keeps the practice session alive while the car's navigation is in front.
 * The session itself runs in the application container; this service only holds a foreground notification
 * (with the latest coaching line) so the system does not kill the process, and tapping it brings the app back.
 */
class PracticeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watch == null) watch = scope.launch {
            (application as StepDriveApp).container.session.state.collect { s ->
                val title = when (s.phase) {
                    Phase.PARKING -> "주차 체크리스트 · ${s.parkingStep.short}"
                    Phase.READY -> "출발 준비"
                    Phase.DRIVING -> "연습 주행 중"
                    else -> "스텝드라이브"
                }
                val text = when (s.phase) {
                    Phase.PARKING -> s.parkingPrompt?.text ?: s.parkingStep.spoken
                    Phase.DRIVING -> s.lastCoachText.ifBlank { "안내가 나오면 여기에 보여요" }
                    Phase.REPORT -> "주행이 끝났어요. 눌러서 결과를 보세요"
                    else -> "기어를 D로 바꾸면 시작돼요"
                }
                notificationManager().notify(NOTIFICATION_ID, buildNotification(title, text))
                if (s.phase == Phase.DRIVING && s.lastCoachText.isNotBlank()) SignalCheck.ok("알림서비스", "알림 문구 갱신: ${s.lastCoachText}")
                if (s.phase == Phase.IDLE) stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startInForeground(): Boolean = runCatching {
            val n = buildNotification("연습 주행 중", "스텝드라이브가 뒤에서 코칭하고 있어요")
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, n)
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) SignalCheck.ok("알림서비스", "포그라운드 서비스 실행, 알림 표시") else SignalCheck.fallback("알림서비스", "서비스는 실행됐지만 알림 권한이 없음. 앱 설정에서 알림 허용 여부를 확인하세요")
        }.onFailure { Log.w(TAG, "startForeground failed: $it"); SignalCheck.fail("알림서비스", it.toString()) }.isSuccess

    private fun buildNotification(title: String, text: String): Notification {
        val nm = notificationManager()
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "연습 주행", NotificationManager.IMPORTANCE_LOW).apply { description = "연습 주행 중 코칭 상태" })
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .build()
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() { watch?.cancel(); scope.cancel(); super.onDestroy() }

    companion object {
        private const val TAG = "PracticeService"
        private const val CHANNEL = "practice"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = runCatching {
            context.startForegroundService(Intent(context, PracticeService::class.java))
        }.onFailure { Log.w(TAG, "start failed: $it") }

        fun stop(context: Context) = runCatching {
            // A stop request must not create the service. Queuing a STOP start-intent just before
            // startForegroundService can stop the new service before it enters the foreground.
            context.stopService(Intent(context, PracticeService::class.java))
        }.onFailure { Log.w(TAG, "stop failed: $it") }
    }
}
