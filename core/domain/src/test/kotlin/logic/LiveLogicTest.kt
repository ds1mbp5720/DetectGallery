package lee.project.detectgallery.core.domain.logic.logic

import junit.framework.TestCase.assertEquals
import lee.project.detectgallery.core.domain.logic.kindOf
import lee.project.detectgallery.core.domain.logic.pick
import lee.project.detectgallery.core.domain.logic.toImagePoint
import lee.project.detectgallery.core.domain.logic.toViewRect
import lee.project.detectgallery.core.domain.model.Box
import lee.project.detectgallery.core.domain.model.DetectionKind
import lee.project.detectgallery.core.domain.model.LiveDetection
import org.junit.Test

class LiveLogicTest {
    private fun det(l: Float, t: Float, r: Float, b: Float, kind: DetectionKind) =
        LiveDetection(Box(l, t, r, b), kind, "", 1f)

    @Test
    fun `사람 박스 안의 얼굴을 탭하면 얼굴이 골라진다`() {
        val person = det(0.1f, 0.1f, 0.9f, 0.9f, DetectionKind.PERSON)
        val face = det(0.4f, 0.15f, 0.6f, 0.35f, DetectionKind.FACE)
        assertEquals(face, pick(listOf(person, face), 0.5f, 0.2f))
        assertEquals(person, pick(listOf(person, face), 0.5f, 0.8f))
    }

    @Test fun `화면 좌표와 이미지 좌표 왕복 변환이 일치한다`() {
        val box = Box(0.25f, 0.5f, 0.75f, 0.75f)
        val r = toViewRect(box, imageW = 480, imageH = 640, viewW = 1080f, viewH = 2340f)
        val (x, y) = toImagePoint(r[0], r[1], 480, 640, 1080f, 2340f)!!
        assertEquals(0.25f, x, 1e-4f)
        assertEquals(0.5f, y, 1e-4f)
    }

    @Test fun `COCO 음식 라벨은 FOOD 로 묶인다`() {
        assertEquals(DetectionKind.FOOD, kindOf("pizza"))
        assertEquals(DetectionKind.PERSON, kindOf("person"))
        assertEquals(DetectionKind.OBJECT, kindOf("cup"))
    }
}