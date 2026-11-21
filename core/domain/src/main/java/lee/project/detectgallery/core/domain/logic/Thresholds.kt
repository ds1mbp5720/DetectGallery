package lee.project.detectgallery.core.domain.logic

/**
 * 판정 임계값의 단일 출처. 값은 모델마다 달라서 출발점일 뿐이다.
 * 같은/다른 쌍 20~50개씩으로 점수 분포를 보고 다시 정한다 (13절 임계값 보정).
 */
object Thresholds {
    const val SCENE = 0.5f

    // FaceNet-512 기준 출발점: deepface 의 코사인 거리 기준 0.30 → 유사도 0.7. 128차원 모델이면 거리 0.40 → 0.6.
    // 주의: 모델 파일 저장소 코드는 같은 0.3/0.4 를 "유사도 하한" 으로 쓴다. 그 숫자를 그대로 옮기면 거의 모두 한 사람이 된다.
    // 0.6 그대로 512차원 모델을 쓰면 느슨해서 다른 사람이 한 인물로 섞인다.
    const val PERSON = 0.7f
    const val SEMANTIC = 0.2f            // CLIP 텍스트↔이미지 점수는 낮게 분포한다
    const val DUPLICATE_MAX_HAMMING = 5

    const val SMILING = 0.7f
    const val GROUP_MIN_FACES = 3
    const val SELFIE_MIN_FACE_AREA = 0.12f
    const val DARK_MAX_LUMA = 50f        // 0..255
    const val BLURRY_MAX_LAPLACIAN_VAR = 60f

    // 얼굴 품질 (C3). 이보다 옆을 보거나 작으면 저품질 → 새 인물·인물 중심 계산에 쓰지 않는다. 출발점, 실기기에서 조정
    const val FACE_MAX_YAW = 35f          // 도. ML Kit headEulerAngleY (좌우로 고개 돌림)
    const val FACE_GOOD_MIN_PX = 80       // 1024px 썸네일에서 얼굴 상자의 짧은 변

    /** 민감도 슬라이더 끝(±1)에서 기준이 움직이는 폭. 보정한 값 ±0.1 안에서만 조절하게 한다. */
    const val SENSITIVITY_RANGE = 0.1f
}

/**
 * 보정한 기준에 사용자 민감도를 반영한 임계값. 느슨하게(+) = 기준을 낮춘다 → 결과가 많아진다.
 * 0.05~0.95 로 묶어 극단값에서 "전부" 또는 "하나도 안" 나오는 일을 막는다.
 */
fun effectiveThreshold(base: Float, sensitivity: Float): Float =
    (base - sensitivity.coerceIn(-1f, 1f) * Thresholds.SENSITIVITY_RANGE).coerceIn(0.05f, 0.95f)