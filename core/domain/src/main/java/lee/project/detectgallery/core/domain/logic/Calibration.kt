package lee.project.detectgallery.core.domain.logic

/**
 * 같은 쌍 점수는 임계값 이상, 다른 쌍 점수는 미만이 되도록 할 때 맞히는 개수가 가장 많은 임계값.
 * 동률이면 더 엄격한(큰) 값. 다른 사람이 섞이는 것이 같은 사람이 빠지는 것보다 나쁘기 때문.
 */
fun bestThreshold(same: List<Float>, diff: List<Float>): Float {
    require(same.isNotEmpty() && diff.isNotEmpty()) { "같은 쌍과 다른 쌍이 모두 필요하다" }
    return (same + diff).distinct().maxWith(
        compareBy<Float> { t -> same.count { it >= t } + diff.count { it < t } }.thenBy { it },
    )
}