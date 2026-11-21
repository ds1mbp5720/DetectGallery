package lee.project.detectgallery.core.domain.logic.logic

import lee.project.detectgallery.core.domain.logic.toggleAlbum
import lee.project.detectgallery.core.domain.logic.visibleSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumSelectionTest {
    private val all = setOf(1L, 2L, 3L)

    @Test
    fun `모든 앨범에서 하나를 빼면 나머지가 선택된다`() {
        assertEquals(setOf(2L, 3L), toggleAlbum(null, 1L, all))
    }

    @Test
    fun `다시 전부 고르면 모든 앨범으로 접는다`() {
        assertNull(toggleAlbum(setOf(2L, 3L), 1L, all))
    }

    @Test
    fun `마지막 하나를 빼면 빈 선택이 된다`() {
        assertEquals(emptySet<Long>(), toggleAlbum(setOf(1L), 1L, all))
    }

    @Test
    fun `사라진 앨범은 빠지고 고른 선택은 접지 않는다`() {
        assertEquals(setOf(2L), visibleSelection(setOf(2L, 9L), all))
        assertNull(visibleSelection(emptySet(), all))
        assertEquals(all, visibleSelection(all, all))   // 고른 앨범 = 지금 앨범 전부여도 "모든 앨범" 이 아니다
    }

    @Test
    fun `고른 앨범이 모두 사라지면 빈 선택으로 보여 다시 고르게 한다`() {
        assertEquals(emptySet<Long>(), visibleSelection(setOf(9L), all))
    }

    @Test
    fun `사라진 앨범이 섞여 있으면 남은 게 전부여도 모든 앨범으로 접지 않는다`() {
        assertEquals(all, visibleSelection(all + 9L, all))
    }


    @Test
    fun `볼 수 있는 앨범이 없으면 고른 앨범을 모든 앨범으로 보이지 않는다`() {
        assertEquals(emptySet<Long>(), visibleSelection(setOf(9L), emptySet()))
    }
}