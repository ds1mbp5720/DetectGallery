package lee.project.detectgallery.core.domain.logic

import lee.project.detectgallery.core.domain.model.DetectedFace
import lee.project.detectgallery.core.domain.model.ImageFrame
import lee.project.detectgallery.core.domain.model.Label

/** 상태 태그. ML Kit 라벨과 같은 테이블에 state:* 이름으로 저장해 키워드 검색을 하나로 처리한다. */
object StateTag {
    const val SMILING = "state:smiling"
    const val GROUP = "state:group"
    const val SELFIE = "state:selfie"
    const val DARK = "state:dark"
    const val BLURRY = "state:blurry"

    /** 얼굴에서 계산한 태그. 인물 기능을 끄면 얼굴과 함께 지운다. */
    val FACE_TAGS = setOf(SMILING, GROUP, SELFIE)
}

/** "웃는·단체·셀카·어두운·흔들린" 의 근거. confidence 는 정렬용 강도. */
fun stateTags(frame: ImageFrame, faces: List<DetectedFace>): List<Label> = buildList {
    val maxSmile = faces.maxOfOrNull { it.smiling ?: 0f } ?: 0f
    if (maxSmile >= Thresholds.SMILING) add(Label(StateTag.SMILING, maxSmile))
    // confidence 는 0..1 로 맞춘다. 1 을 넘으면 다른 라벨과 섞어 정렬할 때 단체 사진이 늘 맨 위로 간다.
    if (faces.size >= Thresholds.GROUP_MIN_FACES) add(Label(StateTag.GROUP, minOf(1f, faces.size / 10f)))
    if (faces.size == 1 && faces[0].box.area >= Thresholds.SELFIE_MIN_FACE_AREA) {
        add(Label(StateTag.SELFIE, faces[0].box.area))
    }
    val luma = meanLuma(frame)
    if (luma < Thresholds.DARK_MAX_LUMA) add(Label(StateTag.DARK, 1f - luma / Thresholds.DARK_MAX_LUMA))
    val sharp = laplacianVariance(frame)
    if (sharp < Thresholds.BLURRY_MAX_LAPLACIAN_VAR) add(Label(StateTag.BLURRY, 1f - sharp / Thresholds.BLURRY_MAX_LAPLACIAN_VAR))
}
// ponytail: 고정 임계값 휴리스틱. 사용자 사진에서 오탐이 많으면 설정에 민감도 슬라이더를 둔다.