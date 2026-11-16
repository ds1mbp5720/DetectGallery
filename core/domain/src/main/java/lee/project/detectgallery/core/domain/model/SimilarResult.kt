package lee.project.detectgallery.core.domain.model

data class PhotoMatch(val mediaId: Long, val score: Float)

sealed interface SimilarResult {
    data class Matches(val items: List<PhotoMatch>) : SimilarResult
    data object NoFaceInQuery : SimilarResult
    data object FaceModelUnavailable : SimilarResult // 얼굴 모델을 아직 안 넣음 (MVP). "얼굴 없음" 과 구분
    data object FacesDisabled : SimilarResult        // 사용자가 인물 기능을 껐음

    /** 쿼리 사진에 얼굴이 여럿. 누구를 찾을지 사용자가 고른다 (모두로 찾으면 그중 누구든 나온 사진이 섞인다). */
    data class MultipleFaces(val faces: List<DetectedFace>) : SimilarResult
    data object UnreadableQuery : SimilarResult      // 쿼리 사진을 열지 못함 ("결과 없음" 과 구분)
}