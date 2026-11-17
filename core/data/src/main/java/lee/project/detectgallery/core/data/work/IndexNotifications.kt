package lee.project.detectgallery.core.data.work

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import lee.project.detectgallery.core.data.R
import java.text.NumberFormat
import java.util.Locale

/**
 * 큰 분석(첫 분석·재분석)의 진행 알림과 완료 알림. 작은 분석(새 사진 몇 장)은 알리지 않는다.
 * 채널을 둘로 나눠 사용자가 시스템 설정에서 완료 알림만 끌 수 있게 한다 (앱 안 토글은 두지 않는다).
 */
internal object IndexNotifications {
    /** 이번에 분석할 사진이 이 장 수 이상일 때만 포그라운드 + 진행 알림. 그보다 적으면 몇 초면 끝나 알림이 소음이다. */
    const val FOREGROUND_MIN_PHOTOS = 200

    private const val CHANNEL_PROGRESS = "index_progress"
    private const val CHANNEL_DONE = "index_done"
    private const val ID_PROGRESS = 1001
    private const val ID_DONE = 1002

    /**
     * 앱이 지금 화면에 보이는가. 보일 때만 포그라운드로 바꾼다.
     * 백그라운드에서 setForeground 를 불러 거부당하면, WorkManager 가 작업을 이미 "포그라운드" 로 표시한 채
     * 서비스 없이 돌 수 있다(10분 중단도 무시) → 예외에 기대지 말고 먼저 거른다. 예외 처리는 경합 대비로 남긴다.
     */
    fun appVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /** WorkManager 의 setForeground 에 넘길 진행 알림. 배치마다 다시 불러 숫자를 갱신한다. */
    fun progress(context: Context, done: Int, total: Int): ForegroundInfo {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_index)
            .setContentTitle("사진 분석 중")
            .setContentText("이번 분석 ${fmt(done)} / ${fmt(total)}장")   // 이번 실행 기준. 현황 줄(장면)·인물 탭(얼굴)의 누적 숫자와 다를 수 있다
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Android 12+ 는 포그라운드 알림을 10초 늦게 보여 줄 수 있다 → 시작하자마자 보이게
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .build()
        val type =
            if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC         // minSdk 30 이라 이 상수는 항상 있다
        return ForegroundInfo(ID_PROGRESS, notification, type)
    }

    /** 큰 분석이 끝났을 때 한 번. 알림 권한이 없으면 보내지 않는다. */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun done(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_index)
            .setContentTitle("사진 분석이 끝났어요")
            .setContentText("누르면 앱에서 바로 찾아볼 수 있어요.")   // 인물 기능이 꺼졌거나 모델이 없을 수 있어 "인물" 을 약속하지 않는다
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_DONE, notification)
    }

    /** 채널 만들기는 같은 id 면 아무 일도 하지 않으므로 매번 불러도 된다. 이름·설명은 시스템 알림 설정에 그대로 보인다. */
    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "사진 분석 진행", NotificationManager.IMPORTANCE_LOW).apply {
                description = "처음 분석처럼 오래 걸리는 분석의 진행 상황 (소리 없음)"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "사진 분석 완료", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "오래 걸린 분석이 끝났을 때 한 번"
            },
        )
    }

    /** 알림을 누르면 앱을 연다. :core:data 는 MainActivity 를 모르므로 실행 인텐트를 패키지 이름으로 찾는다. */
    private fun openApp(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun fmt(n: Int): String = NumberFormat.getIntegerInstance(Locale.KOREA).format(n)
}