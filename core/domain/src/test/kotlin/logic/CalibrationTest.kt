package lee.project.detectgallery.core.domain.logic.logic

import junit.framework.TestCase.assertEquals
import lee.project.detectgallery.core.domain.logic.bestThreshold
import lee.project.detectgallery.core.domain.logic.effectiveThreshold
import org.junit.Test

class CalibrationTest {
    @Test
    fun `두 분포를 가장 잘 가르는 값`() {
        // 0.65: 같은 쌍 3 + 다른 쌍 3 = 6 (유일한 최대)
        assertEquals(0.65f, bestThreshold(same = listOf(0.8f, 0.7f, 0.65f), diff = listOf(0.3f, 0.5f, 0.62f)))
    }

    @Test fun `동률이면 더 엄격한 값`() {
        // 0.9: 1 + 2 = 3, 0.5: 2 + 1 = 3, 0.6: 1 + 1 = 2, 0.4: 2 + 0 = 2 → 0.9 와 0.5 동률, 큰 값
        assertEquals(0.9f, bestThreshold(same = listOf(0.9f, 0.5f), diff = listOf(0.6f, 0.4f)))
    }

    @Test fun `민감도는 보정한 기준을 ±0_1 안에서만 움직인다`() {
        assertEquals(0.6f, effectiveThreshold(0.6f, 0f), 1e-6f)     // 가운데 = 보정값 그대로
        assertEquals(0.5f, effectiveThreshold(0.6f, 1f), 1e-6f)     // 느슨하게 → 기준을 낮춘다
        assertEquals(0.7f, effectiveThreshold(0.6f, -1f), 1e-6f)    // 엄격하게 → 기준을 높인다
        assertEquals(0.5f, effectiveThreshold(0.6f, 5f), 1e-6f)     // 범위 밖 입력은 ±1 로
        assertEquals(0.05f, effectiveThreshold(0.1f, 1f), 1e-6f)    // 하한 0.05
    }
}