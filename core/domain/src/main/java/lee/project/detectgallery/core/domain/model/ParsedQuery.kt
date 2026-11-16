package lee.project.detectgallery.core.domain.model

// ── 키워드 ──
data class ParsedQuery(
    val personNames: List<String> = emptyList(),
    /**
     * 검색어 단어마다 별칭으로 바꾼 라벨 묶음 (ML Kit 영문 + state:*).
     * "웃는 강아지" → [[state:smiling], [Dog]]. 묶음마다 따로 순위를 매겨 RRF 로 합치므로
     * 두 조건을 다 만족하는 사진이 위로 온다 (한 목록으로 합치면 합집합이 되어 순위가 섞인다).
     */
    val labelGroups: List<List<String>> = emptyList(),
    /**
     * 인물 이름이면서 라벨 별칭인 단어 ("하늘"이라는 이름 + 하늘 라벨).
     * 어느 뜻인지 알 수 없으므로 필터로 쓰지 않고, 그 인물 사진과 라벨 사진을 순위 병합한다.
     */
    val ambiguousNames: List<String> = emptyList(),
    val semantic: String? = null,                // 사전에 없는 남은 말. 문장 임베딩(P3)용
    val dateRange: LongRange? = null,            // epoch millis, [from, to)
)
