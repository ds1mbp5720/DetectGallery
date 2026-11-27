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

    /**
     * 겹치는 사진 판정에 쓰는 dHash 의 1 인 비트 하한(상한은 64 − 이 값). 밝기 차이가 없는 사진끼리 거리 ≤ MAX_HAMMING 으로
     * 묶이지 않게 하는 최소값 = MAX_HAMMING + 1. 휴리스틱 — 실기기의 검은 화면·야경으로 보정 (hasDetail, C9·C10)
     */
    const val DUPLICATE_MIN_DETAIL_BITS = 6
    const val SMILING = 0.7f
    const val GROUP_MIN_FACES = 3
    const val SELFIE_MIN_FACE_AREA = 0.12f
    const val DARK_MAX_LUMA = 50f        // 0..255
    const val BLURRY_MAX_LAPLACIAN_VAR = 60f

    // 카메라 분석 프레임(640px)이 이보다 흐리면 검색에 쓰지 않는다 (C5). 사진용(위, 1024px)과 해상도가 달라 따로 둔다. 출발점
    const val LIVE_BLURRY_MAX_LAPLACIAN_VAR = 40f

    // 얼굴 품질 (C3). 이보다 옆을 보거나 작으면 저품질 → 새 인물·인물 중심 계산에 쓰지 않는다. 출발점, 실기기에서 조정
    const val FACE_MAX_YAW = 35f          // 도. ML Kit headEulerAngleY (좌우로 고개 돌림)
    const val FACE_GOOD_MIN_PX = 80       // 1024px 썸네일에서 얼굴 상자의 짧은 변

    // 추억 묶기 (C12). 출발점 — 실기기 갤러리로 보정
    const val MOMENT_GAP_MS = 4 * 60 * 60 * 1000L        // 앞 사진과 이보다 떨어지면 다른 일
    const val MOMENT_JUMP_KM = 50.0                       // 연달아 찍은 두 사진이 이보다 멀면 다른 일(이동)
    const val MOMENT_AWAY_KM = 30.0                       // 집에서 이보다 멀면 "멀리 나감" → 여행 합치기 대상
    const val MOMENT_TRIP_GAP_MS = 36 * 60 * 60 * 1000L  // 멀리 나간 묶음끼리 이 안으로 이어지면 한 여행
    const val MOMENT_TRIP_MAX_MS =
        14 * 24 * 60 * 60 * 1000L   // 한 여행의 최대 길이 (집을 잘못 정했을 때 몇 달이 이어지지 않게)
    const val MOMENT_TRIP_HOP_KM =
        100.0                  // 이어 붙일 두 묶음의 사이 거리 상한 (제주 여행 → 부산 출장을 가른다)
    const val MOMENT_HOME_WINDOW_MS = 365L * 24 * 60 * 60 * 1000   // 집은 최근 1년 사진으로 정한다 (이사)
    const val MOMENT_MIN_PHOTOS = 8
    const val MOMENT_HOME_MIN_SHARE = 0.2                 // 가장 많이 찍힌 칸이 이 비율 이상일 때만 집으로 본다
    const val MOMENT_FILL_NEAR_MS = 10 * 60 * 1000L       // 위치 메우기: 이 안이면 그대로
    const val MOMENT_FILL_MAX_MS = 30 * 60 * 1000L        // 이 안이면 장면이 비슷할 때만
    const val MOMENT_FILL_SCENE = 0.6f                    // 장면 코사인

    /** 민감도 슬라이더 끝(±1)에서 기준이 움직이는 폭. 보정한 값 ±0.1 안에서만 조절하게 한다. */
    const val SENSITIVITY_RANGE = 0.1f
}

/**
 * 보정한 기준에 사용자 민감도를 반영한 임계값. 느슨하게(+) = 기준을 낮춘다 → 결과가 많아진다.
 * 0.05~0.95 로 묶어 극단값에서 "전부" 또는 "하나도 안" 나오는 일을 막는다.
 */
fun effectiveThreshold(base: Float, sensitivity: Float): Float =
    (base - sensitivity.coerceIn(-1f, 1f) * Thresholds.SENSITIVITY_RANGE).coerceIn(0.05f, 0.95f)