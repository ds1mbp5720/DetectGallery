package lee.project.detectgallery.core.domain.logic

/**
 * 인물 탭이 보여 줄 얼굴 분석 상태.
 * 장면(검색)과 얼굴은 같은 패스에서 분석하지만 얼굴만 빠지는 경우가 있다: 얼굴 모델이 없음, 기능이 꺼짐,
 * 모델을 나중에 넣었거나 기능을 다시 켜서 얼굴만 남음. 모든 탭 위 현황 줄은 검색(장면) 기준이라 이 차이를 보이지 않는다.
 */
sealed interface FaceAnalysisState {
    data object ModelMissing : FaceAnalysisState            // facenet.tflite 없음 → 얼굴 단계를 건너뜀
    data object Disabled : FaceAnalysisState                // 사용자가 인물 기능을 끔
    /** 장면은 분석됐는데 얼굴만 남음 */
    data class FacesCatchingUp(val done: Int, val total: Int) : FaceAnalysisState
    /** 사진 분석 자체가 진행 중이거나 남음. total == null 은 갤러리 수를 아직 모름 */
    data class Analyzing(val done: Int, val total: Int?) : FaceAnalysisState
    data object NoFacesFound : FaceAnalysisState            // 다 분석했는데 인물이 없음
    data object Ready : FaceAnalysisState
}

/**
 * 판정 순서가 곧 우선순위다: 모델 없음 > 꺼짐 > 얼굴만 남음 > 사진 분석 중 > 얼굴 없음 > 정상.
 * 첫 분석 중에는 장면·얼굴이 같이 늘어 facesAnalyzed == sceneAnalyzed 이므로, indexing·남은 사진으로
 * "아직 분석 중" 을 가려야 인물 0명을 "얼굴 없음" 으로 성급히 판정하지 않는다.
 */
fun faceAnalysisState(
    modelAvailable: Boolean,
    enabled: Boolean,
    galleryCount: Int?,
    sceneAnalyzed: Int,
    facesAnalyzed: Int,
    indexing: Boolean,
    personCount: Int,
): FaceAnalysisState = when {
    !modelAvailable -> FaceAnalysisState.ModelMissing
    !enabled -> FaceAnalysisState.Disabled
    facesAnalyzed < sceneAnalyzed -> FaceAnalysisState.FacesCatchingUp(facesAnalyzed, sceneAnalyzed)
    galleryCount == null || indexing || sceneAnalyzed < galleryCount -> FaceAnalysisState.Analyzing(sceneAnalyzed, galleryCount)
    personCount == 0 -> FaceAnalysisState.NoFacesFound
    else -> FaceAnalysisState.Ready
}
// ponytail: 분석에 계속 실패하는 사진이 있으면 두 경우가 생긴다. 실패 수를 따로 세게 되면 그때 나눈다.
// - 새 사진이 계속 실패: sceneAnalyzed < galleryCount 가 남아 "분석 중" (인물 0명일 때만 보임).
// - 장면은 분석됐던 사진이 얼굴 단계에서 계속 실패(markFailed 는 indexVersion 만 내린다): facesAnalyzed < sceneAnalyzed 가
//   남아 "얼굴만 남음" → 인물이 있어도 "얼굴 분석 N-1 / N장" 진행 줄이 계속 보인다.