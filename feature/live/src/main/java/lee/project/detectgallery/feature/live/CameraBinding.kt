package lee.project.detectgallery.feature.live

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import lee.project.detectgallery.core.imaging.rotate
import lee.project.detectgallery.core.imaging.toImageFrame
import java.util.concurrent.ExecutorService

/**
 * 프리뷰 + 분석(640px, 최신 프레임만) 바인딩. 분석 프레임은 회전을 풀어서 넘긴다.
 * front = 전면(셀카) 카메라 (C4). 전면 프리뷰는 거울처럼 좌우가 뒤집혀 보이므로 분석 프레임도 뒤집어 넘긴다 →
 * 박스 그리기·탭 좌표·검색용으로 잘라 낸 이미지가 모두 화면에 보이는 그대로다(좌표 변환을 따로 두지 않는다).
 * 고른 카메라가 없으면 onNoCamera. onHasFront 로 전면 카메라가 있는지 알린다(전환 버튼을 그때만 보인다).
 */
internal fun bindCamera(
    context: Context,
    owner: LifecycleOwner,
    view: PreviewView,
    executor: ExecutorService,
    bound: MutableList<UseCase>,   // 이 화면이 묶은 유스케이스. 풀 때 이것만 푼다 (unbindAll 은 새로 들어온 화면 것까지 푼다)
    front: Boolean,
    onHasFront: (Boolean) -> Unit,
    onNoCamera: () -> Unit,
    shouldAnalyze: () -> Boolean,
    onFrame: (LiveIntent.Frame) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        // 초기화가 끝나기 전에 화면을 떠났으면(executor 종료) 묶지 않는다. 묶으면 닫힌 executor 로 프레임이 간다.
        if (executor.isShutdown) return@addListener
        // 카메라 서비스 오류 등으로 초기화가 실패하면 get() 이 예외를 던진다 (메인 스레드 크래시) → "카메라 없음" 과 같이 처리
        val provider = runCatching { future.get() }.getOrElse {
            onNoCamera()
            return@addListener
        }

        // 고른 카메라가 없는 기기(manifest 에서 camera 를 필수로 두지 않았다)에서 bindToLifecycle 은 예외를 던진다
        fun has(s: CameraSelector) = runCatching { provider.hasCamera(s) }.getOrDefault(false)
        val selector =
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        onHasFront(has(CameraSelector.DEFAULT_FRONT_CAMERA))
        if (!has(selector)) {
            onNoCamera()
            return@addListener
        }
        // 프리뷰와 분석의 화면비가 다르면 화면에 보이는 범위가 달라 오버레이 박스가 어긋난다 → 둘 다 4:3
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
            )
            .build()
            .also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                        )
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            // 결과 시트를 보는 동안에는 비트맵 변환부터 건너뛴다 (변환·검출·임베딩 모두 쉼 → 발열·배터리 절약).
            // 프리뷰 영상은 Preview 유스케이스라 계속 움직인다.
            if (!shouldAnalyze()) {
                proxy.close()
                return@setAnalyzer
            }
            val frame = proxy.use {
                it.toBitmap().rotate(it.imageInfo.rotationDegrees, mirror = front)
                    .toImageFrame(maxSide = 640)
            }
            onFrame(LiveIntent.Frame(frame, SystemClock.uptimeMillis()))
        }
        provider.unbind(*bound.toTypedArray())   // 같은 화면이 다시 묶을 때(뷰 재생성) 옛 것만
        bound.clear()
        // 기기가 이 조합(프리뷰 + 분석)을 지원하지 않으면 예외 → 메인 스레드 크래시 대신 "카메라 없음"
        runCatching {
            provider.bindToLifecycle(
                owner,
                selector,
                preview,
                analysis
            )
        }
            .onSuccess { bound += listOf(preview, analysis) }
            .onFailure { onNoCamera() }
    }, ContextCompat.getMainExecutor(context))
}