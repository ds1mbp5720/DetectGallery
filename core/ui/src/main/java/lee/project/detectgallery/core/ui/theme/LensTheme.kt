package lee.project.detectgallery.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lee.project.detectgallery.core.ui.theme.LensPalette as P

// surfaceContainer* 와 inverse* 를 꼭 채운다. 비워 두면 Material 기본값(보라 기운)이 시트·대화상자·메뉴·스낵바에 그대로 나온다.
private val Light = lightColorScheme(
    // 흰 글자 대비 4.5:1 이상: #3182F6 은 약 3.7:1 이라 칩·버튼 글자에 못 쓴다 → 한 단계 진한 파랑
    primary = P.Blue600, onPrimary = Color.White,
    primaryContainer = P.Blue50, onPrimaryContainer = P.Blue600,
    secondary = P.Grey600, onSecondary = Color.White,
    secondaryContainer = P.Grey100, onSecondaryContainer = P.Grey900,
    tertiaryContainer = P.Blue50, onTertiaryContainer = P.Blue600,
    background = Color.White, onBackground = P.Grey900,
    surface = Color.White, onSurface = P.Grey900,
    // 보조 글자는 회색 채움(#F2F4F6) 위에도 쓰인다(세그먼트·검색창 안내·인물 카드). Grey600 은 그 위에서 4.19:1 이라 한 단계 진하게.
    surfaceVariant = P.Grey100, onSurfaceVariant = P.Grey700,
    surfaceTint = Color.White,                 // 높이(elevation)에 따른 색 섞임을 없앤다
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = P.Grey50,
    surfaceContainer = P.Grey50,
    surfaceContainerHigh = Color.White,        // 대화상자
    surfaceContainerHighest = P.Grey100,       // 채움 카드
    // outline 은 꺼진 Switch 의 테두리·동그라미 색이다. 회색 카드 위에서도 UI 요소 기준 3:1 이상이어야 한다 (Grey400 은 1.82:1).
    outline = P.Grey600, outlineVariant = P.Grey200,
    error = P.Red600, onError = Color.White,
    // 스낵바 등 반전 색. 비우면 Material 기본(보라 기운)이 나온다.
    inverseSurface = P.Grey900, inverseOnSurface = Color.White, inversePrimary = P.Blue400,
)

private val Dark = darkColorScheme(
    primary = P.Blue400, onPrimary = P.DarkBg,      // 밝은 파랑 위에는 어두운 글자 (흰 글자는 약 2.9:1)
    primaryContainer = P.BlueDark, onPrimaryContainer = P.Blue400,
    secondary = P.DarkText2, onSecondary = P.DarkBg,
    secondaryContainer = P.DarkSurface2, onSecondaryContainer = P.DarkText,
    tertiaryContainer = P.BlueDark, onTertiaryContainer = P.Blue400,
    background = P.DarkBg, onBackground = P.DarkText,
    surface = P.DarkBg, onSurface = P.DarkText,
    surfaceVariant = P.DarkSurface2, onSurfaceVariant = P.DarkText2,
    surfaceTint = P.DarkBg,
    surfaceContainerLowest = P.DarkBg,
    surfaceContainerLow = P.DarkSurface,
    surfaceContainer = P.DarkSurface,
    surfaceContainerHigh = P.DarkSurface,
    surfaceContainerHighest = P.DarkSurface2,
    outline = P.DarkText2, outlineVariant = P.DarkLine,
    error = P.Red500, onError = Color.White,
    inverseSurface = P.DarkText, inverseOnSurface = P.DarkBg, inversePrimary = P.Blue600,
)

// 글꼴은 시스템 기본(한글은 Noto Sans CJK 계열). 굵기·크기·자간만 바꾼다. 글꼴 파일을 넣는 방법은 "하지 않은 것" 절.
private val LensTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleSmall = titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        labelMedium = labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    )
}

private val LensShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),       // 칩 (검색창은 직접 지정). 기본 Button 은 둥근 모양이라 여기 영향을 받지 않는다
    medium = RoundedCornerShape(16.dp),      // 카드·현황 줄
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),  // 바텀시트
)

/** 앱 전체 테마. 동적 색(배경화면 색)은 쓰지 않는다: 기기마다 색이 달라지면 회색 단계·파란 강조의 대비가 무너진다. */
@Composable
fun DetectGallery(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = LensTypography,
        shapes = LensShapes,
        content = content,
    )
}