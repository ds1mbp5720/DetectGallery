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
 * 후면 카메라만 쓴다 (전면은 프리뷰가 좌우 반전돼 박스 좌표를 뒤집어야 한다). 없으면 onNoCamera.
 */
internal fun bindCamera(
    context: Context,
    owner: LifecycleOwner,
    view: PreviewView,
    executor: ExecutorService,
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
        // 후면 카메라가 없는 기기(manifest 에서 camera 를 필수로 두지 않았다)에서 bindToLifecycle 은 예외를 던진다
        val hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        if (!hasBack) {
            onNoCamera()
            return@addListener
        }
        // 프리뷰와 분석의 화면비가 다르면 화면에 보이는 범위가 달라 오버레이 박스가 어긋난다 → 둘 다 4:3
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()
            .also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
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
            val frame = proxy.use { it.toBitmap().rotate(it.imageInfo.rotationDegrees).toImageFrame(maxSide = 640) }
            onFrame(LiveIntent.Frame(frame, SystemClock.uptimeMillis()))
        }
        provider.unbindAll()
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }, ContextCompat.getMainExecutor(context))
}