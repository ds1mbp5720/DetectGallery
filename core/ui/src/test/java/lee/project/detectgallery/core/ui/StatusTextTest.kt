package lee.project.detectgallery.core.ui

import lee.project.detectgallery.core.ui.components.StatusText
import lee.project.detectgallery.core.ui.components.statusText
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTextTest {
    @Test fun `첫 값 전에는 확인 중이고 막대는 비어 있다`() {
        assertEquals(StatusText("갤러리 확인 중", "", 0f), statusText(null, 0, indexing = false))
    }

    @Test fun `남은 사진 수를 천 단위로 보여 준다`() {
        val t = statusText(12345, 8210, indexing = false)
        assertEquals("사진 12,345장 · 분석 8,210장", t.main)
        assertEquals("대기 4,135장", t.badge)
        assertEquals(0.665f, t.ratio, 0.001f)
    }

    @Test fun `분석 중이면 남은 수 대신 분석 중`() {
        assertEquals("분석 중", statusText(100, 40, indexing = true).badge)
    }

    @Test fun `정리 전 분석 수가 더 커도 사진 수로 자른다`() {
        val t = statusText(100, 120, indexing = false)
        assertEquals("사진 100장 · 분석 100장", t.main)
        assertEquals("모두 분석됨", t.badge)
        assertEquals(1f, t.ratio, 0f)
    }

    @Test fun `사진이 없으면 0 으로 나누지 않는다`() {
        val t = statusText(0, 0, indexing = false)
        assertEquals("사진 없음", t.badge)
        assertEquals(0f, t.ratio, 0f)
    }

    @Test fun `선택한 사진만 허용이면 고른 사진 수라고 쓴다`() {
        assertEquals("고른 사진 12장 · 분석 12장", statusText(12, 12, indexing = false, partial = true).main)
    }
    @Test fun `고른 앨범만 분석하면 고른 앨범이라고 쓰고 부분 접근이 우선한다`() {
        assertEquals("고른 앨범 300장 · 분석 120장", statusText(300, 120, indexing = false, albums = true).main)
        assertEquals("고른 사진 12장 · 분석 12장", statusText(12, 12, indexing = false, partial = true, albums = true).main)
    }
}