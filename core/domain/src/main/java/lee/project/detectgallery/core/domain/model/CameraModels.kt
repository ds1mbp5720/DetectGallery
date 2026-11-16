package lee.project.detectgallery.core.domain.model

// ── 실시간 카메라 ──
enum class DetectionKind { FACE, PERSON, FOOD, OBJECT }

data class LiveDetection(
    val box: Box,
    val kind: DetectionKind,
    val label: String,
    val score: Float
)

data class LiveFrameResult(
    val detections: List<LiveDetection>,
    val timestampMs: Long
)
