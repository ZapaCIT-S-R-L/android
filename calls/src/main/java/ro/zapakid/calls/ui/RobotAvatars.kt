@file:Suppress("MagicNumber") // drawing proportions of the robot face

package ro.zapakid.calls.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Friendly robot faces, one colour each; drawn, so they scale to any size and need no image files. */
object RobotAvatars {
    const val COUNT = 8

    internal val colors =
        listOf(
            Color(0xFFFF6A3D),
            Color(0xFF2F6BFF),
            Color(0xFF14A37F),
            Color(0xFF8A4DFF),
            Color(0xFFD6336C),
            Color(0xFFF59F00),
            Color(0xFF0CA5B0),
            Color(0xFF5C7CFA)
        )
}

/**
 * A robot avatar. [speaking] draws a soft, pulsing green ring around it; [ringing] makes rings ripple outwards
 * like a ringing phone. Decorative: the name next to it is what TalkBack reads.
 */
@Composable
fun RobotAvatar(
    index: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    speaking: Boolean = false,
    ringing: Boolean = false,
    sparkles: Boolean = false
) {
    val color = RobotAvatars.colors[Math.floorMod(index, RobotAvatars.COUNT)]
    val ring = Color(LocalPalette.current.speakingRing)
    val transition = rememberInfiniteTransition(label = "avatar")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400), RepeatMode.Restart),
        label = "pulse"
    )
    Box(modifier = modifier.size(size * 1.4f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size * 1.4f)) {
            val radius = size.toPx() / 2
            if (ringing) {
                for (wave in 0..1) {
                    val t = (pulse + wave * 0.5f) % 1f
                    drawCircle(color.copy(alpha = 0.35f * (1 - t)), radius = radius * (1f + 0.4f * t))
                }
            }
            if (speaking) {
                val width = 4.dp.toPx() + 3.dp.toPx() * pulse
                drawCircle(ring, radius = radius + width, style = Stroke(width))
            }
            drawCircle(color, radius = radius)
            robotFace(radius)
            if (sparkles) sparkles(radius, color)
        }
    }
}

private fun DrawScope.robotFace(radius: Float) {
    val c = center
    val headW = radius * 1.1f
    val headH = radius * 0.85f
    val top = c.y - headH * 0.45f
    // antenna
    drawLine(Color.White, Offset(c.x, top), Offset(c.x, top - radius * 0.28f), strokeWidth = radius * 0.06f)
    drawCircle(Color.White, radius = radius * 0.08f, center = Offset(c.x, top - radius * 0.32f))
    // head
    drawRoundRect(
        Color.White,
        topLeft = Offset(c.x - headW / 2, top),
        size = Size(headW, headH),
        cornerRadius = CornerRadius(radius * 0.25f)
    )
    // eyes and smile
    val eye = radius * 0.11f
    val eyeY = top + headH * 0.38f
    drawCircle(Color(0xFF141A2B), eye, Offset(c.x - headW * 0.22f, eyeY))
    drawCircle(Color(0xFF141A2B), eye, Offset(c.x + headW * 0.22f, eyeY))
    drawArc(
        Color(0xFF141A2B),
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(c.x - headW * 0.22f, top + headH * 0.42f),
        size = Size(headW * 0.44f, headH * 0.32f),
        style = Stroke(radius * 0.06f)
    )
}

/** Small four-point sparkles around the robot: the kids' look. */
private fun DrawScope.sparkles(radius: Float, color: Color) {
    val spots = listOf(Offset(-1.15f, -0.9f) to 0.16f, Offset(1.1f, -1.05f) to 0.12f, Offset(1.2f, 0.75f) to 0.1f)
    for ((spot, scale) in spots) {
        val c = center + Offset(spot.x * radius, spot.y * radius)
        val r = radius * scale
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = r / 3)
        drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = r / 3)
    }
}
