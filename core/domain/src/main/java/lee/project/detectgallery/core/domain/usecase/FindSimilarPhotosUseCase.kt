package lee.project.detectgallery.core.domain.usecase

import lee.project.detectgallery.core.domain.logic.*
import lee.project.detectgallery.core.domain.model.*
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.PhotoSource
import lee.project.detectgallery.core.domain.port.SettingsRepository
import lee.project.detectgallery.core.domain.port.VisionEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 기준값은 Thresholds(개발자 보정값)에 사용자 민감도를 반영해 정한다. 실시간 카메라 탭·미리보기도 여기를 거치므로
 * 슬라이더가 모든 이미지 검색에 함께 적용된다. 겹치는 사진(dHash)은 정확한 판정이라 민감도를 쓰지 않는다.
 *
 * 호출자는 ViewModel(메인 스레드)이다. 1만 장 전수 비교·해시 계산은 수십 ms 라
 * 카메라 미리보기(1초마다)에서 메인 스레드로 돌리면 화면이 끊긴다 → 계산은 Default 로 옮긴다.
 */
class FindSimilarPhotosUseCase @Inject constructor(
    private val vision: VisionEncoder,
    private val index: PhotoIndexRepository,
    private val source: PhotoSource,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(
        query: ImageFrame,
        mode: ImageSearchMode,
        topK: Int = 100
    ): SimilarResult {
        val s = settings.currentSensitivity()
        return when (mode) {
            ImageSearchMode.SCENE -> {
                val q = vision.scene(query)
                val candidates = index.sceneVectors()
                val min = effectiveThreshold(Thresholds.SCENE, s.scene)
                SimilarResult.Matches(cpu { rankByBestMatch(listOf(q), candidates, min, topK) })
            }

            ImageSearchMode.PERSON -> when {
                // 모델 없음이 먼저(faceAnalysisState·인물 탭과 같은 순서): 모델이 없으면 인물 탭 스위치가 막혀 "켜세요" 는 갈 곳이 없다
                !vision.facesAvailable -> SimilarResult.FaceModelUnavailable
                !settings.facesEnabledNow() -> SimilarResult.FacesDisabled
                else -> {
                    val faces = vision.faces(query)
                    when (faces.size) {
                        0 -> SimilarResult.NoFaceInQuery
                        1 -> byFace(faces.single(), topK)
                        // 여럿이면 모두로 찾지 않는다 (그중 누구든 나온 사진이 섞인다). 화면에서 고른 뒤 byFace.
                        else -> SimilarResult.MultipleFaces(faces)
                    }
                }
            }

            ImageSearchMode.DUPLICATE -> {
                val hashes = index.dHashes()
                SimilarResult.Matches(cpu {
                    val q = dHash(query)
                    hashes.mapNotNull { (id, h) ->
                        val d = hamming(q, h)
                        if (d <= Thresholds.DUPLICATE_MAX_HAMMING) PhotoMatch(
                            id,
                            1f - d / 64f
                        ) else null
                    }.sortedByDescending { it.score }.take(topK)
                })
            }
        }
    }

    /** 고른 얼굴 하나로 같은 사람 사진을 찾는다. */
    suspend fun byFace(face: DetectedFace, topK: Int = 100): SimilarResult {
        if (!settings.facesEnabledNow()) return SimilarResult.FacesDisabled   // 얼굴 고르는 사이 꺼졌을 수 있다
        val candidates = index.faceVectors()
        val min = effectiveThreshold(Thresholds.PERSON, settings.currentSensitivity().person)
        return SimilarResult.Matches(cpu {
            rankByBestMatch(
                listOf(face.vector),
                candidates,
                min,
                topK
            )
        })
    }

    private suspend fun <T> cpu(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    /** 사진 선택기 URI 로 검색. */
    suspend operator fun invoke(uri: String, mode: ImageSearchMode): SimilarResult {
        val frame = source.load(uri) ?: return SimilarResult.UnreadableQuery
        return invoke(frame, mode)
    }
}