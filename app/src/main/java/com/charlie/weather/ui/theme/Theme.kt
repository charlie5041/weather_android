package com.charlie.weather.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一路晴的設計 token：淺色毛玻璃風格（ui-ux-pro-max「Weather App」：Glassmorphism、天藍＋琥珀）。
 * 畫面只引用這裡的名稱，不再寫死色碼。對比都以白色或玻璃卡片為底算過（WCAG AA 4.5:1）。
 */
object Palette {
    /** 主要文字：白底 17.9:1 */
    val Ink = Color(0xFF0F172A)
    /** 次要文字：白底 7.6:1 */
    val Ink2 = Color(0xFF475569)
    /** 第三層文字、圖示：白底 4.8:1 */
    val Ink3 = Color(0xFF64748B)
    /** 停用 */
    val InkDisabled = Color(0xFF94A3B8)

    /** 主色（連結、選中、次要按鈕文字）：白字 5.9:1 */
    val Primary = Color(0xFF0369A1)
    val OnPrimary = Color.White
    /** 主色的淺底（選中的分頁、chip） */
    val PrimarySoft = Color(0xFFE0F2FE)

    /** 強調色：每個畫面最多一顆主要按鈕用它，上面放深色字 8:1 */
    val Accent = Color(0xFFF59E0B)
    val OnAccent = Ink

    /** 降雨（圖示、長條）；當文字用 [RainText] */
    val Rain = Color(0xFF0284C7)
    val RainText = Color(0xFF0369A1)
    val RainHeavy = Color(0xFF7C3AED)
    /** 不會下雨／成功：白底 5.0:1 */
    val Dry = Color(0xFF15803D)
    /** 可能下雨／注意：白底 5.0:1 */
    val Warn = Color(0xFFB45309)
    /** 錯誤、刪除：白底 4.8:1 */
    val Danger = Color(0xFFDC2626)
    /** 特報、警告卡片的淺紅底與深紅字（9.1:1） */
    val DangerSoft = Color(0xFFFEE2E2)
    val DangerInk = Color(0xFF991B1B)
    /** 進行中（騎乘、記錄）的淺綠底與深綠字 */
    val LiveSoft = Color(0xFFDCFCE7)
    val LiveInk = Color(0xFF14532D)
    val Live = Color(0xFF16A34A)
    /** 測速照相（底色、圖示）；當文字用 [CameraText] */
    val Camera = Color(0xFFF97316)
    val CameraText = Color(0xFFC2410C)
    val Sun = Color(0xFFD97706)

    /** 頁面底色（出門、足跡、設定等非天氣頁） */
    val PageTop = Color(0xFFE0F2FE)
    val PageBottom = Color(0xFFF0F9FF)
    val AuroraSky = Color(0xFFBAE6FD)
    val AuroraSun = Color(0xFFFDE6B8)

    /** 玻璃卡片 */
    val Glass = Color.White.copy(alpha = 0.62f)
    val GlassStrong = Color.White.copy(alpha = 0.85f)
    val GlassBorder = Color.White.copy(alpha = 0.8f)
    /** 玻璃卡片裡的分隔線、軌道 */
    val Divider = Ink.copy(alpha = 0.08f)
    val Track = Ink.copy(alpha = 0.08f)
    /** 卡片裡的小底塊（圖片佔位、輸入框） */
    val Well = Color(0xFFF1F5F9)
    val MapPlaceholder = Color(0xFFDCEBF7)
    /** 對話框背後的遮罩 */
    val Scrim = Ink.copy(alpha = 0.32f)
}

/** 圓角 */
object Radius {
    val Card = 20.dp
    val Control = 14.dp
    val Small = 10.dp
}

/** 字級：12 標籤、14 次要、16 內文、20 標題、28 大數字（目前溫度另外用 80） */
object TextSize {
    val Label = 12.sp
    val Secondary = 14.sp
    val Body = 16.sp
    val Title = 20.sp
    val Display = 28.sp
}

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = TextSize.Display, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    titleMedium = TextStyle(fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontSize = TextSize.Body, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = TextSize.Secondary, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = TextSize.Label, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)

private val AppColors = lightColorScheme(
    primary = Palette.Primary,
    onPrimary = Palette.OnPrimary,
    primaryContainer = Palette.PrimarySoft,
    onPrimaryContainer = Palette.Primary,
    secondary = Palette.Accent,
    onSecondary = Palette.OnAccent,
    background = Palette.PageBottom,
    onBackground = Palette.Ink,
    surface = Color.White,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Well,
    onSurfaceVariant = Palette.Ink2,
    surfaceContainerHigh = Color.White,
    surfaceContainer = Color.White,
    error = Palette.Danger,
    outline = Palette.Ink3,
    outlineVariant = Palette.Divider,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = AppTypography, content = content)
}

/**
 * 放在玻璃上的文字顏色。天氣頁晚上的背景是深色，卡片外的字（目前溫度、地點）要改用白色；
 * 卡片裡面一律是 [Palette.Ink]。
 */
val LocalOnSky = staticCompositionLocalOf { Palette.Ink }

/** 毛玻璃卡片：半透明白、白色描邊。Android 沒有原生的背景模糊，靠底下的柔和漸層做出玻璃感。 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(Radius.Card), strong: Boolean = false): Modifier =
    clip(shape)
        .background(if (strong) Palette.GlassStrong else Palette.Glass, shape)
        .border(1.dp, Palette.GlassBorder, shape)

/** 非天氣頁的底：淡天藍漸層加兩團柔和的光（天空藍、陽光黃），讓玻璃卡片有東西可以透。 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.PageTop, Palette.PageBottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            val sky = Offset(0f, 0f)
            drawCircle(
                Brush.radialGradient(listOf(Palette.AuroraSky, Palette.AuroraSky.copy(alpha = 0f)), center = sky, radius = size.width * 0.9f),
                radius = size.width * 0.9f,
                center = sky,
            )
            val sun = Offset(size.width, size.height * 0.62f)
            drawCircle(
                Brush.radialGradient(listOf(Palette.AuroraSun, Palette.AuroraSun.copy(alpha = 0f)), center = sun, radius = size.width * 0.8f),
                radius = size.width * 0.8f,
                center = sun,
            )
        }
    }
}

/** 同 [AuroraBackground]，給整頁畫面當底（不透明，疊在其他畫面上也不會透出下面的內容）。 */
fun Modifier.aurora(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Palette.PageTop, Palette.PageBottom)))
    val sky = Offset(0f, 0f)
    drawCircle(
        Brush.radialGradient(listOf(Palette.AuroraSky, Palette.AuroraSky.copy(alpha = 0f)), center = sky, radius = size.width * 0.9f),
        radius = size.width * 0.9f,
        center = sky,
    )
    val sun = Offset(size.width, size.height * 0.62f)
    drawCircle(
        Brush.radialGradient(listOf(Palette.AuroraSun, Palette.AuroraSun.copy(alpha = 0f)), center = sun, radius = size.width * 0.8f),
        radius = size.width * 0.8f,
        center = sun,
    )
}
