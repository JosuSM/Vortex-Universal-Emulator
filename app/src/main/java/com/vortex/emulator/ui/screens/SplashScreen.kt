package com.vortex.emulator.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortex.emulator.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

@Composable
fun VortexSplashScreen(onFinished: () -> Unit) {

    // ── Master timeline (triggers navigation) ──
    LaunchedEffect(Unit) {
        delay(3200L)
        onFinished()
    }

    // ── Phase animations ──
    val ringProgress = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0f) }
    val logoAlpha = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val subtitleAlpha = remember { Animatable(0f) }
    val versionAlpha = remember { Animatable(0f) }
    val glowPulse = rememberInfiniteTransition(label = "glow")
    val glowAlpha = glowPulse.animateFloat(
        initialValue = 0.3f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "glowAlpha"
    )
    val ringRotation = glowPulse.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "ringRotation"
    )

    LaunchedEffect(Unit) {
        // Phase 1: rings expand
        ringProgress.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(400)
        // Phase 2: logo appears
        logoAlpha.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(300)
        logoScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 200f))
    }
    LaunchedEffect(Unit) {
        delay(1000)
        // Phase 3: title
        titleAlpha.animateTo(1f, tween(500))
    }
    LaunchedEffect(Unit) {
        delay(1400)
        // Phase 4: subtitle
        subtitleAlpha.animateTo(1f, tween(500))
    }
    LaunchedEffect(Unit) {
        delay(1800)
        // Phase 5: version
        versionAlpha.animateTo(1f, tween(400))
    }

    // ── Particle seeds (stable across recomposition) ──
    val particles = remember {
        List(60) {
            Particle(
                angle = Random.nextFloat() * 360f,
                radius = Random.nextFloat(),
                speed = 0.2f + Random.nextFloat() * 0.8f,
                size = 1f + Random.nextFloat() * 2.5f,
                alpha = 0.2f + Random.nextFloat() * 0.6f
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0F1A2E),
                        Color(0xFF0A0E1A),
                        Color(0xFF050810)
                    ),
                    radius = 900f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // ── Background: animated rings + particles ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val maxR = minOf(size.width, size.height) * 0.42f
            val progress = ringProgress.value
            val rot = ringRotation.value

            // Starfield particles
            particles.forEach { p ->
                val effectiveR = (p.radius * maxR * 1.6f) * progress
                val angle = p.angle + rot * p.speed * 0.05f
                val rad = Math.toRadians(angle.toDouble())
                val px = cx + effectiveR * cos(rad).toFloat()
                val py = cy + effectiveR * sin(rad).toFloat()
                if (px in 0f..size.width && py in 0f..size.height) {
                    drawCircle(
                        color = VortexCyanLight.copy(alpha = p.alpha * progress),
                        radius = p.size,
                        center = Offset(px, py)
                    )
                }
            }

            // Concentric vortex rings
            val ringCount = 5
            for (i in 0 until ringCount) {
                val fraction = (i + 1f) / ringCount
                val radius = maxR * fraction * progress
                val strokeW = (3f - i * 0.4f).coerceAtLeast(0.8f)
                val alpha = (0.5f - i * 0.08f).coerceAtLeast(0.08f) * progress

                rotate(rot * (if (i % 2 == 0) 1f else -0.6f), Offset(cx, cy)) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                VortexCyan.copy(alpha = alpha),
                                VortexPurple.copy(alpha = alpha * 0.6f),
                                VortexMagenta.copy(alpha = alpha * 0.3f),
                                Color.Transparent,
                                Color.Transparent,
                                VortexCyan.copy(alpha = alpha)
                            ),
                            center = Offset(cx, cy)
                        ),
                        startAngle = 0f,
                        sweepAngle = 280f,
                        useCenter = false,
                        topLeft = Offset(cx - radius, cy - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }
            }

            // Central glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        VortexCyan.copy(alpha = 0.15f * glowAlpha.value),
                        VortexPurple.copy(alpha = 0.05f * glowAlpha.value),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = maxR * 0.5f
                ),
                radius = maxR * 0.5f,
                center = Offset(cx, cy)
            )
        }

        // ── Logo + text layer ──
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // "V" Logo
            Box(
                modifier = Modifier
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
                contentAlignment = Alignment.Center
            ) {
                // Outer glow ring
                Canvas(modifier = Modifier.size(120.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                VortexCyan.copy(alpha = 0.3f * glowAlpha.value),
                                Color.Transparent
                            ),
                            radius = r
                        ),
                        radius = r
                    )
                    drawCircle(
                        color = VortexCyan.copy(alpha = 0.6f),
                        radius = r * 0.55f,
                        style = Stroke(width = 2f)
                    )
                }
                // The "V" letter
                Text(
                    text = "V",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    color = VortexCyan,
                    modifier = Modifier.alpha(logoAlpha.value)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // "VORTEX" title
            Text(
                text = "VORTEX",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 12.sp,
                color = VortexCyan,
                modifier = Modifier.alpha(titleAlpha.value)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // "EMULATOR" subtitle
            Text(
                text = "E M U L A T O R",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 6.sp,
                color = VortexOnSurfaceVariant,
                modifier = Modifier.alpha(subtitleAlpha.value)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Version badge
            Text(
                text = "v2.2-Nova",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = VortexPurple.copy(alpha = 0.7f),
                modifier = Modifier.alpha(versionAlpha.value)
            )
        }
    }
}

private data class Particle(
    val angle: Float,
    val radius: Float,
    val speed: Float,
    val size: Float,
    val alpha: Float
)
