package lee.project.detectgallery.core.domain.model

/**
 * 사용자 민감도. -1 = 엄격하게, 0 = 개발자가 보정한 기준 그대로, +1 = 느슨하게.
 * person 은 "같은 사람 사진 찾기"(사진 한 장·카메라 얼굴 탭)에만 쓴다. 인물 묶기(인물 화면)는 보정값 그대로라
 * 슬라이더를 움직여도 인물 목록이 바뀌지 않는다. 묶음이 틀렸으면 합치기로 고친다.
 */
data class Sensitivity(val person: Float = 0f, val scene: Float = 0f) {
    fun clamped() = Sensitivity(person.coerceIn(-1f, 1f), scene.coerceIn(-1f, 1f))
}

/**
 * 상단 현황 줄의 원본 값. galleryCount = 분석 대상 사진 수(볼 수 있는 사진 중 고른 앨범), analyzedCount = 지금 검색되는 사진 수.
 * 정리 전에는 analyzedCount 가 더 클 수 있다 (표시 쪽에서 자른다).
 */
data class LibraryStatus(
    val galleryCount: Int,
    val analyzedCount: Int,
    val facesAnalyzed: Int,      // 얼굴 분석까지 마친 사진 수 (≤ analyzedCount). 인물 탭 상태에 쓴다
    val indexing: Boolean,
)