package lee.project.detectgallery.core.domain.logic.logic


import lee.project.detectgallery.core.domain.logic.FaceAnalysisState.*
import lee.project.detectgallery.core.domain.logic.faceAnalysisState
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceAnalysisStateTest {
    private fun s(
        model: Boolean = true, enabled: Boolean = true, gallery: Int? = 1000,
        scene: Int = 1000, faces: Int = 1000, indexing: Boolean = false, persons: Int = 5,
    ) = faceAnalysisState(model, enabled, gallery, scene, faces, indexing, persons)

    @Test fun `모델이 없으면 꺼짐보다 먼저 모델 없음`() = assertEquals(ModelMissing, s(model = false, enabled = false))
    @Test fun `꺼져 있으면 꺼짐`() = assertEquals(Disabled, s(enabled = false))
    @Test fun `장면보다 얼굴이 적으면 얼굴만 남음`() = assertEquals(FacesCatchingUp(100, 500), s(scene = 500, faces = 100))
    @Test fun `첫 분석 중 인물 0명은 얼굴 없음이 아니라 분석 중`() =
        assertEquals(Analyzing(300, 1000), s(scene = 300, faces = 300, indexing = true, persons = 0))
    @Test fun `갤러리 수를 모르면 분석 중`() = assertEquals(Analyzing(0, null), s(gallery = null, scene = 0, faces = 0, persons = 0))
    @Test fun `다 분석했는데 인물이 없으면 얼굴 없음`() = assertEquals(NoFacesFound, s(persons = 0))
    @Test fun `다 분석했고 인물이 있으면 정상`() = assertEquals(Ready, s())
}