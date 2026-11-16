package lee.project.detectgallery.core.ui.theme

import androidx.compose.ui.graphics.Color

/** 흰 배경 + 회색 단계 + 파란 강조 하나. 화면에서 색을 새로 만들지 않고 MaterialTheme.colorScheme 의 역할 이름만 쓴다. */
internal object LensPalette {
    val Blue500 = Color(0xFF3182F6)
    val Blue400 = Color(0xFF4C9AFF)
    val Blue600 = Color(0xFF1B64DA)
    val Blue50 = Color(0xFFE8F3FF)
    val BlueDark = Color(0xFF15294A)

    val Grey900 = Color(0xFF191F28)
    val Grey700 = Color(0xFF4E5968)
    val Grey600 = Color(0xFF6B7684)
    val Grey200 = Color(0xFFE5E8EB)
    val Grey100 = Color(0xFFF2F4F6)
    val Grey50 = Color(0xFFF9FAFB)
    val Red500 = Color(0xFFF04452)   // 다크 전용 (오류 문구가 놓이는 대화상자 #1B1B1F 위 4.62:1)
    val Red600 = Color(0xFFD22030)   // 라이트 (흰 바탕 5.26:1). #F04452 는 3.71:1 이라 오류 문구에 못 쓴다

    val DarkBg = Color(0xFF101013)
    val DarkSurface = Color(0xFF1B1B1F)
    val DarkSurface2 = Color(0xFF2A2A30)
    val DarkLine = Color(0xFF3A3A42)
    val DarkText = Color(0xFFF2F2F5)
    val DarkText2 = Color(0xFF9E9EA6)
}