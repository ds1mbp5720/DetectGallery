package lee.project.detectgallery.core.domain.logic.logic

import kotlinx.coroutines.test.runTest
import lee.project.detectgallery.core.domain.logic.QueryParser
import lee.project.detectgallery.core.domain.logic.parseDatePart
import lee.project.detectgallery.core.domain.logic.stripParticle
import lee.project.detectgallery.core.domain.port.QueryVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class QueryParserTest {
    private val vocab = object : QueryVocabulary {
        override suspend fun personNames() = setOf("엄마", "하늘")
        override suspend fun labelsForAlias(alias: String) = mapOf(
            "강아지" to listOf("Dog"), "고양이" to listOf("Cat"), "웃는" to listOf("state:smiling"), "하늘" to listOf("Sky"),
        )[alias].orEmpty()
    }
    private val zone = ZoneId.of("Asia/Seoul")
    private val parser = QueryParser(vocab).apply {
        today = { LocalDate.of(2026, 11, 13) }
        this.zone = this@QueryParserTest.zone
    }

    @Test
    fun `인물 날짜 라벨 상태를 나눈다`() = runTest {
        val q = parser.parse("엄마랑 작년 웃는 강아지 사진")
        assertEquals(listOf("엄마"), q.personNames)
        assertEquals(listOf(listOf("state:smiling"), listOf("Dog")), q.labelGroups)   // 단어마다 한 묶음
        assertEquals(LocalDate.of(2025, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), q.dateRange!!.first)
        assertNull(q.semantic)
    }

    @Test fun `사전에 없는 말은 문장 검색용으로만 남긴다`() = runTest {
        val q = parser.parse("스타벅스에서 영수증")
        assertEquals(emptyList<List<String>>(), q.labelGroups)
        assertEquals("스타벅스 영수증", q.semantic)
    }

    @Test fun `작년 3월은 작년의 3월`() = runTest {
        val r = parser.parse("작년 3월").dateRange!!
        assertEquals(LocalDate.of(2025, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli(), r.first)
        assertEquals(LocalDate.of(2025, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli(), r.last + 1)
    }

    @Test fun `없는 달은 날짜로 보지 않는다`() {
        assertNull(parseDatePart("13월", LocalDate.of(2026, 11, 13)))
        assertEquals("이", stripParticle("이"))
    }

    @Test fun `조사처럼 끝나는 단어는 원형을 먼저 본다`() = runTest {
        assertEquals(listOf(listOf("Cat")), parser.parse("고양이 사진").labelGroups)    // "고양" 으로 잘리면 실패
        assertEquals(listOf(listOf("Cat")), parser.parse("고양이가").labelGroups)
    }

    @Test fun `이름이자 라벨인 단어는 필터로 쓰지 않고 둘 다 후보로`() = runTest {
        val q = parser.parse("하늘")
        assertEquals(emptyList<String>(), q.personNames)      // 필터가 되면 하늘 사진이 전부 걸러진다
        assertEquals(listOf("하늘"), q.ambiguousNames)
        assertEquals(listOf(listOf("Sky")), q.labelGroups)
    }
}