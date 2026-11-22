package lee.project.detectgallery.core.domain.logic

import lee.project.detectgallery.core.domain.model.Box
import lee.project.detectgallery.core.domain.model.DetectionKind
import lee.project.detectgallery.core.domain.model.Label
import lee.project.detectgallery.core.domain.model.LiveDetection

/** COCO 80 라벨 중 음식 10개. */
val COCO_FOOD = setOf("banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake")

/**
 * 화면 전체 라벨(ML Kit)로 보는 "음식 장면". COCO 에 없는 음식(김치찌개, 비빔밥)은 박스가 안 뜨므로 대신 쓴다.
 * 라벨 이름은 ML Kit label map 에 실제로 있는 것만 남긴다. label_alias.json 의 "음식" 과 같은 목록이어야 한다
 * (다르면 실시간 음식 탭과 "음식" 키워드 결과가 어긋난다).
 */
val FOOD_SCENE_LABELS = setOf("Food", "Cuisine", "Fast food", "Dessert", "Baked goods")

fun isFoodScene(labels: List<Label>, minConfidence: Float = 0.7f): Boolean =
    labels.any { it.name in FOOD_SCENE_LABELS && it.confidence >= minConfidence }

fun kindOf(cocoLabel: String): DetectionKind = when (cocoLabel) {
    "person" -> DetectionKind.PERSON
    in COCO_FOOD -> DetectionKind.FOOD
    else -> DetectionKind.OBJECT
}

/** 탭 위치(0..1)를 포함하는 감지 중 가장 작은 것. 사람 박스 안의 얼굴이 우선된다. */
fun pick(detections: List<LiveDetection>, x: Float, y: Float): LiveDetection? =
    detections.filter { (x to y) in it.box }.minByOrNull { it.box.area }

/**
 * 분석 이미지 좌표(0..1) → PreviewView(FILL_CENTER) 화면 좌표(px) [l, t, r, b].
 * FILL_CENTER 는 화면을 꽉 채우도록 확대하고 넘치는 부분을 가운데 기준으로 자른다.
 */
fun toViewRect(box: Box, imageW: Int, imageH: Int, viewW: Float, viewH: Float): FloatArray {
    val scale = maxOf(viewW / imageW, viewH / imageH)
    val dx = (viewW - imageW * scale) / 2
    val dy = (viewH - imageH * scale) / 2
    return floatArrayOf(
        box.left * imageW * scale + dx, box.top * imageH * scale + dy,
        box.right * imageW * scale + dx, box.bottom * imageH * scale + dy,
    )
}

/** 화면 탭(px) → 분석 이미지 좌표(0..1). 잘려 나간 영역이면 null. */
fun toImagePoint(x: Float, y: Float, imageW: Int, imageH: Int, viewW: Float, viewH: Float): Pair<Float, Float>? {
    val scale = maxOf(viewW / imageW, viewH / imageH)
    val dx = (viewW - imageW * scale) / 2
    val dy = (viewH - imageH * scale) / 2
    val ix = (x - dx) / (imageW * scale)
    val iy = (y - dy) / (imageH * scale)
    return if (ix in 0f..1f && iy in 0f..1f) ix to iy else null
}
// 전면 카메라(셀카 모드, C4)는 프리뷰가 좌우 반전돼 보인다. 분석 프레임을 바인딩에서 같이 뒤집어 넘기므로
// 여기 좌표 변환은 전면·후면이 같다 (box 를 1 - x 로 뒤집지 않는다).