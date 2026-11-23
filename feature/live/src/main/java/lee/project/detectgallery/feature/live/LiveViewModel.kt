package lee.project.detectgallery.feature.live

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lee.project.detectgallery.core.domain.logic.Thresholds
import lee.project.detectgallery.core.domain.logic.crop
import lee.project.detectgallery.core.domain.logic.isFoodScene
import lee.project.detectgallery.core.domain.logic.laplacianVariance
import lee.project.detectgallery.core.domain.logic.pick
import lee.project.detectgallery.core.domain.model.Box
import lee.project.detectgallery.core.domain.model.DetectionKind
import lee.project.detectgallery.core.domain.model.ImageFrame
import lee.project.detectgallery.core.domain.model.ImageSearchMode
import lee.project.detectgallery.core.domain.model.LiveDetection
import lee.project.detectgallery.core.domain.model.SimilarResult
import lee.project.detectgallery.core.domain.port.LiveDetector
import lee.project.detectgallery.core.domain.port.VisionEncoder
import lee.project.detectgallery.core.domain.usecase.FindSimilarPhotosUseCase
import lee.project.detectgallery.core.domain.usecase.SearchFromDetectionUseCase
import javax.inject.Inject

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val detector: LiveDetector,
    private val findSimilar: FindSimilarPhotosUseCase,
    private val searchFromDetection: SearchFromDetectionUseCase,
    private val vision: VisionEncoder,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveUiState())
    val state = _state.asStateFlow()

    private val latestFrame = MutableStateFlow<ImageFrame?>(null)   // 최신 1장 (미리보기용)
    private var searchJob: Job? = null

    /**
     * 박스는 검출 결과가 비동기로 늦게 오므로 "최신 프레임"이 아니라 그 결과를 만든 프레임의 좌표다.
     * 탭하면 그 프레임을 잘라야 카메라가 움직여도 대상을 빗나가지 않는다 → 최근 몇 장을 타임스탬프로 보관.
     * 프레임은 분석 스레드에서, 탭은 메인 스레드에서 오므로 잠근다.
     */
    private val recentFrames = object : LinkedHashMap<Long, ImageFrame>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageFrame>) =
            size > RECENT_FRAMES
    }

    @Volatile
    private var detectionsTs = 0L

    // 지금 카메라와 바꾼 시각 (C4). 다시 묶기 전까지 옛 카메라 프레임이 더 오고, 검출기 결과도 늦게 오므로 이 둘로 거른다
    @Volatile
    private var currentFront = false

    @Volatile
    private var switchedAt = 0L

    init {
        detector.results
            .onEach { r ->
                if (r.timestampMs < switchedAt) return@onEach   // 전환 전 카메라 프레임의 늦은 결과
                detectionsTs = r.timestampMs
                _state.update { it.copy(detections = r.detections) }
            }
            .launchIn(viewModelScope)

        // 1초에 한 번, 화면 전체로 비슷한 갤러리 사진 미리보기
        latestFrame.filterNotNull().sample(1_000)
            .onEach { f ->
                if (_state.value.selected != null) return@onEach   // 결과를 보는 중에는 미리보기·음식 칩 판단도 쉰다
                // 흐린 프레임(초점 잡는 중·흔들림)은 건너뛰고 지난 띠를 그대로 둔다: 엉뚱한 결과로 깜빡이지 않게 (C5)
                if (withContext(Dispatchers.Default) { laplacianVariance(f) } < Thresholds.LIVE_BLURRY_MAX_LAPLACIAN_VAR) return@onEach
                // 실패해도 다음 주기에 다시 시도한다. 바깥 .catch 는 흐름 자체를 끝내 미리보기가 영영 멈춘다.
                val r = try {
                    findSimilar(f, ImageSearchMode.SCENE, topK = 8)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (r is SimilarResult.Matches) _state.update { it.copy(preview = r.items.map { m -> m.mediaId }) }

                // 같은 주기에 화면 전체 라벨도 본다: COCO 에 없는 음식(한식 등)은 박스가 안 뜨므로 "음식 장면" 칩으로 대신한다
                val food = try {
                    isFoodScene(vision.labels(f))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                _state.update { it.copy(foodScene = food) }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: LiveIntent) {
        when (intent) {
            is LiveIntent.Frame -> {
                // 결과 시트가 떠 있으면 프레임을 버린다 (CameraBinding 의 shouldAnalyze 가 먼저 거르지만 경합 대비).
                // 박스·칩은 탭한 순간 상태로 멈춰 보이고, 시트를 닫으면 다음 프레임부터 재개된다.
                if (_state.value.selected != null) return
                if (intent.front != currentFront) return   // 다시 묶기 전 옛 카메라 프레임 (좌우 반전도 다르다)
                latestFrame.value = intent.frame
                synchronized(recentFrames) { recentFrames[intent.timestampMs] = intent.frame }
                if (_state.value.frameW != intent.frame.width || _state.value.frameH != intent.frame.height) {
                    _state.update {
                        it.copy(
                            frameW = intent.frame.width,
                            frameH = intent.frame.height
                        )
                    }
                }
                detector.submit(intent.frame, intent.timestampMs)
            }

            is LiveIntent.Tap -> {
                // ponytail: 화면이 본 박스와 여기 상태가 한 프레임 다를 수 있다(드묾) → 그때는 아무 일도 안 한다. 다시 누르면 된다
                val target = pick(_state.value.detections, intent.x, intent.y) ?: return
                // ponytail: 객체·얼굴 결과는 서로 다른 프레임에서 올 수 있다. 마지막 결과의 프레임을 쓰고, 없으면 최신.
                viewModelScope.launch {
                    val frame = frameForSearch(target.box) ?: latestFrame.value ?: return@launch
                    search(frame, target)
                }
            }

// 옛 카메라의 박스·프레임·검출기 기억을 모두 비운다. 새 프레임이 오면 다시 채워진다
            is LiveIntent.CameraSwitched -> {
                if (intent.front == currentFront && switchedAt != 0L) return   // 같은 카메라로 다시 그린 것(회전 등)
                currentFront = intent.front
                switchedAt = SystemClock.uptimeMillis()
                synchronized(recentFrames) { recentFrames.clear() }
                latestFrame.value = null
                detector.reset()
                _state.update {
                    it.copy(
                        detections = emptyList(),
                        preview = emptyList(),
                        foodScene = false
                    )
                }
            }

            LiveIntent.DismissResults -> {
                searchJob?.cancel()
                _state.update {
                    it.copy(
                        selected = null,
                        queryCrop = null,
                        results = emptyList(),
                        notice = null,
                        isSearching = false
                    )
                }
            }
            // 화면 전체를 음식 박스로 보고 검색한다 (잘라낸 장면 유사도 + 음식 장면 라벨 순위 병합)
            LiveIntent.SearchFoodScene -> {
                val frame = latestFrame.value ?: return
                search(frame, LiveDetection(Box(0f, 0f, 1f, 1f), DetectionKind.FOOD, "scene", 1f))
            }
        }
    }

    /**
     * 탭한 박스를 잘라 검색할 프레임 (C5). 박스를 만든 프레임이 먼저고, 그 자리가 흐리면(초점 잡는 중·손떨림)
     * 최근 프레임(5장, 0.5초 안팎) 중 그 박스 자리가 가장 선명한 것을 쓴다. 그 사이 대상은 크게 움직이지 않는다.
     * 잠금 안에서는 목록만 복사하고, 자르기·선명도 계산(최대 6번)은 잠금 밖 Default 에서 한다 — 분석 스레드를 막지 않게.
     */
    private suspend fun frameForSearch(box: Box): ImageFrame? {
        val (own, recent) = synchronized(recentFrames) { recentFrames[detectionsTs] to recentFrames.values.toList() }
        return withContext(Dispatchers.Default) {
            if (own != null && laplacianVariance(own.crop(box)) >= Thresholds.LIVE_BLURRY_MAX_LAPLACIAN_VAR) own
            else recent.maxByOrNull { laplacianVariance(it.crop(box)) } ?: own
        }
    }

    private fun search(frame: ImageFrame, target: LiveDetection) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // 무엇으로 검색했는지 보여 줄 썸네일. 카메라가 이미 다른 곳을 비추고 있어도 결과의 기준을 알 수 있다.
            // (검색용 crop 과 여백이 조금 달라도 보여 주기에는 충분하다)
            val thumb = frame.crop(target.box, padding = 0.1f)
            _state.update {
                it.copy(
                    selected = target,
                    queryCrop = thumb,
                    isSearching = true,
                    results = emptyList(),
                    notice = null
                )
            }
            try {
                when (val r = searchFromDetection(frame, target)) {
                    is SimilarResult.Matches -> _state.update {
                        val ids = r.items.map { m -> m.mediaId }
                        it.copy(
                            isSearching = false,
                            results = ids,
                            notice = if (ids.isEmpty()) LiveNotice.NO_RESULTS else null
                        )
                    }

                    SimilarResult.NoFaceInQuery -> _state.update {
                        it.copy(
                            isSearching = false,
                            notice = LiveNotice.NO_FACE
                        )
                    }

                    SimilarResult.FaceModelUnavailable ->
                        _state.update {
                            it.copy(
                                isSearching = false,
                                notice = LiveNotice.FACE_UNAVAILABLE
                            )
                        }

                    SimilarResult.FacesDisabled ->
                        _state.update {
                            it.copy(
                                isSearching = false,
                                notice = LiveNotice.FACES_DISABLED
                            )
                        }
                    // SearchFromDetectionUseCase 가 가장 큰 얼굴로 골라 주므로 오지 않는다
                    is SimilarResult.MultipleFaces, SimilarResult.UnreadableQuery ->
                        _state.update { it.copy(isSearching = false, notice = LiveNotice.FAILED) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSearching = false, notice = LiveNotice.FAILED) }
            }
        }
    }

    override fun onCleared() = detector.close()

    private companion object {
        const val RECENT_FRAMES = 5
    }   // 640×480 ≈ 1.2MB 씩
}