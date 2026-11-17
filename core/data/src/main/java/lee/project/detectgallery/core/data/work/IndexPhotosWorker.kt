package lee.project.detectgallery.core.data.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lee.project.detectgallery.core.domain.port.IndexTrigger
import lee.project.detectgallery.core.domain.usecase.IndexNewPhotosUseCase
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class IndexPhotosWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val indexNewPhotos: IndexNewPhotosUseCase,
) : CoroutineWorker(context, params) {

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun doWork(): Result {
        val triggered =
            triggeredContentUris.isNotEmpty() || triggeredContentAuthorities.isNotEmpty()
        var finished = false
        var foreground: Boolean? =
            null   // null = 아직 시도 안 함, true = 진행 알림 중, false = 시작 못 함(백그라운드 시작)
        try {
            // 권한이 없으면 MediaStore 가 빈 목록을 준다 → 색인 전체가 "갤러리에서 사라진 사진" 으로 지워진다.
            // 권한을 다시 받으면 처음부터 수십 분 재색인이 된다. 권한이 없을 땐 아무것도 하지 않는다.
            if (!hasGalleryAccess(applicationContext)) {
                finished = true
                return Result.success()
            }
            // WorkManager 는 작업 하나를 약 10분 뒤 멈추고 나중에 다시 실행한다.
            // 사진별로 진행이 저장되므로(IndexNewPhotosUseCase) 다시 실행되면 남은 사진부터 이어 간다.
            runLock.withLock {   // 즉시·주기·새 사진 작업이 겹쳐도 하나씩
                indexNewPhotos { done, total ->
                    setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                    // 큰 분석이고 앱이 화면에 보이면 포그라운드로: 진행 알림이 뜨고 10분 제한 없이 끝까지 돈다.
                    // 이미 포그라운드면 숫자만 갱신. 앱이 안 보이면 시도하지 않고(null 유지) 다음 배치에 다시 본다
                    // → 백그라운드에서 시작한 분석도 사용자가 앱을 열면 그때부터 알림이 뜬다.
                    if (foreground == true ||
                        (foreground == null && total >= IndexNotifications.FOREGROUND_MIN_PHOTOS && IndexNotifications.appVisible())
                    ) {
                        foreground = try {
                            setForeground(
                                IndexNotifications.progress(
                                    applicationContext,
                                    done,
                                    total
                                )
                            )
                            true
                        } catch (e: CancellationException) {
                            throw e   // CancellationException 도 IllegalStateException 의 하위라 먼저 내보낸다
                        } catch (e: IllegalStateException) {
                            // 보인다고 확인한 직후 앱이 내려간 경합 등: Android 12+ 가 막는다(ForegroundServiceStartNotAllowedException)
                            Log.i("Index", "포그라운드 시작 불가, 알림 없이 진행", e)
                            false
                        }
                    }
                }
            }
            if (foreground == true) IndexNotifications.done(applicationContext)
            finished = true
            return Result.success()
        } catch (e: CancellationException) {
            throw e                                  // 10분 제한 등으로 멈춤. 아래 finally 에서 이어 갈 작업을 건다
        } catch (e: Throwable) {
            // Throwable: OutOfMemoryError 가 빠져나가면 Result.failure 가 되고, 뒤에 붙여 둔 새 사진 감지 작업까지
            // 함께 실패 처리돼 다음 앱 실행 전까지 감지가 멈춘다.
            Log.w("Index", "색인 실패, 나중에 재시도", e)   // MediaStore 조회 실패 등
            // 트리거 작업을 retry 하면 "다음 사진 변경"까지 기다린다 → 대신 finally 에서 1분 뒤 실행을 건다
            return if (triggered) Result.success() else Result.retry()
        } finally {
            if (triggered) {
                // 콘텐츠 URI 트리거는 한 번 발동하면 끝난다. 어떻게 끝나든(성공·실패·중단·권한 없음) 다음 감지를 다시 건다.
                // APPEND_OR_REPLACE: 지금 실행 중인 자신을 취소하지 않고 그 뒤에 붙인다.
                IndexScheduler.armNewPhotoTrigger(
                    applicationContext,
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                )
                if (!finished) IndexScheduler.resumeSoon(applicationContext)
            }
        }
    }

    companion object {
        private val runLock = Mutex()
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
    }
}

internal fun hasGalleryAccess(context: Context): Boolean {
    val perms = when {
        Build.VERSION.SDK_INT >= 34 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return perms.any {
        ContextCompat.checkSelfPermission(
            context,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }
}

object IndexScheduler {
    /** 세 작업 모두에 붙여 진행률을 한 곳에서 본다. */
    const val TAG = "gallery-index"
    private const val NOW = "index-now"
    private const val PERIODIC = "index-periodic"
    private const val ON_NEW_PHOTO = "index-on-new-photo"

    /**
     * @param afterCurrent true 면 이미 도는 색인이 있어도 그 뒤에 한 번 더 붙인다.
     *   사진 선택을 바꿨거나 접근 범위가 바뀐 경우: 도는 중인 색인은 이미 목록을 읽어 두어서 새 사진을 모른다.
     *   false(앱 복귀 등) 면 이미 대기·실행 중일 때 아무것도 하지 않는다.
     */
    fun schedule(context: Context, afterCurrent: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val nowPolicy =
            if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        wm.enqueueUniqueWork(
            NOW,
            nowPolicy,
            OneTimeWorkRequestBuilder<IndexPhotosWorker>().addTag(TAG).build()
        )
        // 충전 중 6시간마다
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<IndexPhotosWorker>(6, TimeUnit.HOURS)
                .addTag(TAG)
                .setConstraints(
                    Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true)
                        .build()
                )
                .build(),
        )
        // 새 사진 감지. KEEP: 이미 걸려 있거나 실행 중이면 건드리지 않는다 (REPLACE 는 실행 중인 색인을 취소한다)
        armNewPhotoTrigger(context, ExistingWorkPolicy.KEEP)
    }

    /** 실패·중단된 색인을 1분 뒤 이어서 한다. 사진 변경을 기다리지 않는다. */
    fun resumeSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<IndexPhotosWorker>().addTag(TAG)
                .setInitialDelay(1, TimeUnit.MINUTES).build(),
        )
    }

    /** 새 사진이 생기면 10초 뒤 실행되는 1회성 작업. */
    fun armNewPhotoTrigger(context: Context, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ON_NEW_PHOTO, policy,
            OneTimeWorkRequestBuilder<IndexPhotosWorker>()
                .addTag(TAG)
                .setConstraints(
                    Constraints.Builder()
                        .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                        .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
                        .build(),
                ).build(),
        )
    }
}

/** 화면에서 "다시 색인" 이 필요할 때 (인물 기능 켜기 등). 도는 색인이 있으면 그 뒤에 붙인다. */
class WorkManagerIndexTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) : IndexTrigger {
    override fun requestIndex() = IndexScheduler.schedule(context, afterCurrent = true)
}