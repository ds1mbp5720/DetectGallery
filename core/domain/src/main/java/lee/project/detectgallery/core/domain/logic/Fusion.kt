package lee.project.detectgallery.core.domain.logic

/**
 * Reciprocal Rank Fusion. 점수 척도가 다른 순위 목록을 순위만으로 합친다.
 * score(d) = Σ 1 / (k + rank_i(d)), rank 는 1부터. k = 60 은 관례값.
 */
fun rrf(lists: List<List<Long>>, k: Int = 60): List<Long> {
    val score = HashMap<Long, Double>()
    for (list in lists) list.forEachIndexed { i, id ->
        score[id] = (score[id] ?: 0.0) + 1.0 / (k + i + 1)
    }
    return score.entries.sortedByDescending { it.value }.map { it.key }
}