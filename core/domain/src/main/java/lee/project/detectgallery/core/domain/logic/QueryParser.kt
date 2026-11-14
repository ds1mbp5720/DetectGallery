package lee.project.detectgallery.core.domain.logic

import lee.project.detectgallery.core.domain.model.ParsedQuery
import lee.project.detectgallery.core.domain.port.QueryVocabulary
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class QueryParser @Inject constructor(
    private val vocab: QueryVocabulary,
) {
    var today: () -> LocalDate = { LocalDate.now() }      // 테스트에서 고정
    var zone: ZoneId = ZoneId.systemDefault()

    suspend fun parse(raw: String): ParsedQuery {
        val names = vocab.personNames()
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        val persons = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        val labelGroups = mutableListOf<List<String>>()
        val rest = mutableListOf<String>()
        // "작년 3월" 처럼 연·월이 따로 오므로 조각을 모았다가 마지막에 합친다
        var year: Int? = null
        var month: Int? = null

        for (token in tokens) {
            // 원형을 먼저 본다. 조사부터 떼면 "고양이" 가 "고양" 이 되어 별칭을 못 찾는다.
            val forms = listOf(token, stripParticle(token)).distinct()
            if (forms.any { it in STOPWORDS }) continue
            val name = forms.firstOrNull { it in names }
            val date = forms.firstNotNullOfOrNull { parseDatePart(it, today()) }
            val aliased = if (date == null) {
                forms.firstNotNullOfOrNull { f -> vocab.labelsForAlias(f).takeIf { it.isNotEmpty() } }
            } else null
            when {
                name != null && aliased != null -> { ambiguous += name; labelGroups += aliased }
                name != null -> persons += name
                date != null -> { date.year?.let { year = it }; date.month?.let { month = it } }
                aliased != null -> labelGroups += aliased
                else -> rest += forms.last()       // 문장 검색에는 조사를 뗀 형태
            }
        }
        return ParsedQuery(
            personNames = persons,
            labelGroups = labelGroups.distinct(),
            ambiguousNames = ambiguous.distinct(),
            semantic = rest.takeIf { it.isNotEmpty() }?.joinToString(" "),
            dateRange = dateRange(year, month, today(), zone),
        )
    }

    private companion object {
        val STOPWORDS = setOf("사진", "사진들", "찾아줘", "보여줘", "나온")
    }
}

private val PARTICLES = listOf("에서", "으로", "이랑", "랑", "하고", "와", "과", "의", "을", "를", "이", "가", "은", "는", "에", "로")

/** 조사를 뗀다. "이", "가" 같은 한 글자 단어가 사라지지 않도록 2글자 이상 남을 때만. */
fun stripParticle(t: String): String =
    PARTICLES.firstOrNull { t.endsWith(it) && t.length - it.length >= 2 }?.let { t.dropLast(it.length) } ?: t

data class DatePart(val year: Int?, val month: Int?)

private val DATE_TOKEN = Regex("""^(?:(\d{4})년)?(?:(\d{1,2})월)?$""")

/** "올해", "작년", "2025년", "3월", "2025년3월" → 연·월 조각. 날짜가 아니면 null. */
fun parseDatePart(t: String, today: LocalDate): DatePart? = when (t) {
    "올해" -> DatePart(today.year, null)
    "작년" -> DatePart(today.year - 1, null)
    else -> DATE_TOKEN.matchEntire(t)?.destructured?.let { (y, mo) ->
        val month = mo.toIntOrNull()
        when {
            y.isEmpty() && mo.isEmpty() -> null
            month != null && month !in 1..12 -> null
            else -> DatePart(y.toIntOrNull(), month)
        }
    }
}

/** 연·월 → [from, to) epoch ms. 연도가 없으면 올해, 월이 없으면 1년 전체. 둘 다 없으면 null. */
fun dateRange(year: Int?, month: Int?, today: LocalDate, zone: ZoneId): LongRange? {
    if (year == null && month == null) return null
    val from = LocalDate.of(year ?: today.year, month ?: 1, 1)
    val to = if (month == null) from.plusYears(1) else from.plusMonths(1)
    return from.atStartOfDay(zone).toInstant().toEpochMilli() until to.atStartOfDay(zone).toInstant().toEpochMilli()
}
// ponytail: 규칙 기반. "지난 여름", "3일 전" 이 필요해지면 규칙을 늘리거나 소형 LLM 파싱으로 교체.