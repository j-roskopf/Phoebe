package com.phoebe.app.feature.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val WallTop = Color(0xFFDB9BD7)
private val WallBottom = Color(0xFFA877B6)
private val TvShellHighlight = Color(0xFF8D94E8)
private val TvShellMidLight = Color(0xFF646AA8)
private val TvShellMidDark = Color(0xFF434778)
private val TvShellDeepShadow = Color(0xFF1E203A)
private val TvBlueDeepExtrusionColor = Color(0xFF181A30)
private val NeonPipeEdge = Color(0xFFFF5CE2)
private val NeonPipeCenter = Color(0xFFFFAEEB)
private val NeonGlowColor = Color(0x30FF5CE2)
private val HoleHoleDarkColor = Color(0xFF0F1122)
private val HoleDeepColor = Color(0xFF070812)
private val PinkControlLightReal = Color(0xFFFF82E8)
private val PinkControlMainReal = Color(0xFFE25BCA)
private val PinkControlDarkReal = Color(0xFFAD2794)
private val LedHousingRing = Color(0xFF111324)
private val LedBaseShadow = Color(0x90000000)
private val RubyDarkRoot = Color(0xFF5A001E)
private val RubyMidGlow = Color(0xFFD61868)
private val RubyHotTip = Color(0xFFFF48A5)
private val SpecularHardWhite = Color(0xFFFFFFFF)
private val SpecularSoftWhite = Color(0x60FFFFFF)
private val CausticPinkRim = Color(0xFFFF8CE0)
private val KnobSpunSpec = Color(0xFFFFFFFF)
private val KnobSpunMid = Color(0xFFE862D0)
private val KnobSpunDark = Color(0xFF7A0D63)
private val SilkscreenInk = Color(0xFF1E203D)
private val ChromeDark = Color(0xFF2B2F3D)
private val ChromeMid = Color(0xFF8E96A8)
private val ChromeSpec = Color(0xFFFFFFFF)
private val MetalFootDark = Color(0xFF202330)
private val MetalFootMid = Color(0xFF6E7588)
private val MetalFootLight = Color(0xFFC5CCDE)
private val RubberPadDark = Color(0xFF0A0B10)
private val BezelInnerReal = Color(0xFF232647)

private val RetroTvStageWidth = 452.dp
private val RetroTvStageHeight = 472.dp

internal val GlassBarrelShape: Shape = object : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { 14.dp.toPx() }
        val bow = with(density) { 3.2.dp.toPx() }
        return Outline.Generic(
            createBarrelPath(
                width = size.width,
                height = size.height,
                cornerRadius = r,
                bow = bow,
            ),
        )
    }
}

@Composable
internal fun RetroTvVisualizerFrame(
    modifier: Modifier = Modifier,
    screenContent: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(colors = listOf(WallTop, WallBottom))),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints {
            val density = LocalDensity.current
            val scale = with(density) {
                min(
                    maxWidth.toPx() / RetroTvStageWidth.toPx(),
                    maxHeight.toPx() / RetroTvStageHeight.toPx(),
                ).coerceAtLeast(0f)
            }
            Box(
                modifier = Modifier.size(
                    width = RetroTvStageWidth * scale,
                    height = RetroTvStageHeight * scale,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .size(RetroTvStageWidth, RetroTvStageHeight)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    RetroTvFrameContent(screenContent = screenContent)
                }
            }
        }
    }
}

@Composable
private fun RetroTvFrameContent(screenContent: @Composable BoxScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(110.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawAntennaAssembly()
            }
        }

        Box(
            modifier = Modifier
                .width(370.dp)
                .height(270.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = (-6).dp, y = 92.dp)
                    .size(width = 440.dp, height = 100.dp),
            ) {
                val masterAlpha = 0.30f
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFCCD5FF).copy(alpha = masterAlpha * 0.5f),
                            Color(0xFFCCD5FF).copy(alpha = masterAlpha * 0.15f),
                            Color.Transparent,
                        ),
                        center = Offset(x = size.width * 0.5f, y = 15.dp.toPx()),
                        radius = size.width * 0.56f,
                    ),
                    topLeft = Offset(x = 10.dp.toPx(), y = 10.dp.toPx()),
                    size = Size(width = size.width - 20.dp.toPx(), height = 88.dp.toPx()),
                )
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFF2F5FF).copy(alpha = masterAlpha),
                            Color(0xFFCCD5FF).copy(alpha = masterAlpha * 0.45f),
                            Color.Transparent,
                        ),
                        center = Offset(x = size.width * 0.5f, y = 20.dp.toPx()),
                        radius = size.width * 0.32f,
                    ),
                    topLeft = Offset(x = size.width * 0.18f, y = 16.dp.toPx()),
                    size = Size(width = size.width * 0.64f, height = 56.dp.toPx()),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = 8.dp, y = 12.dp)
                    .drawBehind {
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(TvBlueDeepExtrusionColor, Color(0xFF090A14)),
                                start = Offset(x = size.width, y = 0f),
                                end = Offset(x = 0f, y = size.height),
                            ),
                            cornerRadius = CornerRadius(x = 36.dp.toPx()),
                        )
                    },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val r = 36.dp.toPx()
                        val cr = CornerRadius(x = r)
                        val w = size.width
                        val h = size.height

                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.0f to TvShellHighlight,
                                    0.25f to TvShellMidLight,
                                    0.65f to TvShellMidDark,
                                    1.0f to TvShellDeepShadow,
                                ),
                                start = Offset.Zero,
                                end = Offset(x = w, y = h),
                            ),
                            cornerRadius = cr,
                        )

                        val chamferW = 2.dp.toPx()
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.White.copy(alpha = 0.85f),
                                    0.35f to Color.White.copy(alpha = 0.15f),
                                    0.6f to Color.Transparent,
                                    0.85f to Color.Black.copy(alpha = 0.6f),
                                    1.0f to Color.Black.copy(alpha = 0.9f),
                                ),
                                start = Offset.Zero,
                                end = Offset(x = w, y = h),
                            ),
                            topLeft = Offset(x = chamferW / 2f, y = chamferW / 2f),
                            size = Size(width = w - chamferW, height = h - chamferW),
                            cornerRadius = CornerRadius(x = r - chamferW / 2f),
                            style = Stroke(width = chamferW),
                        )
                    }
                    .padding(all = 14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawInnerFaceAndNeon()
                        }
                        .padding(all = 16.dp),
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(weight = 1f)
                                .fillMaxHeight()
                                .padding(top = 2.dp, bottom = 4.dp, start = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(all = 12.dp)
                                    .fillMaxSize()
                                    .clip(shape = GlassBarrelShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                screenContent()
                            }

                            Spacer(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .drawWithCache {
                                        val w = size.width
                                        val h = size.height
                                        val pad = 12.dp.toPx()
                                        val glassW = w - pad * 2
                                        val glassH = h - pad * 2
                                        val housingPath = createBarrelPath(
                                            width = w,
                                            height = h,
                                            cornerRadius = 20.dp.toPx(),
                                            bow = 4.5.dp.toPx(),
                                        )
                                        val housingPathOffset = createBarrelPath(
                                            width = w,
                                            height = h,
                                            cornerRadius = 20.dp.toPx(),
                                            bow = 4.5.dp.toPx(),
                                            offsetX = 2.dp.toPx(),
                                            offsetY = 2.dp.toPx(),
                                        )
                                        val glassPath = createBarrelPath(
                                            width = glassW,
                                            height = glassH,
                                            cornerRadius = 14.dp.toPx(),
                                            bow = 3.2.dp.toPx(),
                                            offsetX = pad,
                                            offsetY = pad,
                                        )
                                        val bezelHighlightBrush = Brush.linearGradient(
                                            colorStops = arrayOf(
                                                0.0f to Color.White.copy(alpha = 0.35f),
                                                0.4f to Color.Transparent,
                                            ),
                                            start = Offset.Zero,
                                            end = Offset(x = w * 0.5f, y = h * 0.5f),
                                        )
                                        val bezelStroke = Stroke(width = 1.dp.toPx())
                                        val cornerShadowBrush = Brush.linearGradient(
                                            colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent),
                                            start = Offset(x = pad, y = pad),
                                            end = Offset(x = pad + glassW * 0.45f, y = pad + glassH * 0.45f),
                                        )
                                        val glareStripeBrush = Brush.linearGradient(
                                            colorStops = arrayOf(
                                                0.0f to Color.White.copy(alpha = 0.18f),
                                                0.16f to Color.White.copy(alpha = 0.14f),
                                                0.165f to Color.Transparent,
                                                0.2f to Color.White.copy(alpha = 0.05f),
                                                0.28f to Color.Transparent,
                                            ),
                                            start = Offset(x = pad, y = pad),
                                            end = Offset(x = pad + glassW, y = pad + glassH),
                                        )
                                        val rimLightBrush = Brush.linearGradient(
                                            colorStops = arrayOf(
                                                0.0f to Color.Transparent,
                                                0.65f to Color.Transparent,
                                                1.0f to Color.White.copy(alpha = 0.3f),
                                            ),
                                            start = Offset(x = pad, y = pad),
                                            end = Offset(x = pad + glassW, y = pad + glassH),
                                        )
                                        val rimStroke = Stroke(width = 1.5.dp.toPx())

                                        onDrawBehind {
                                            clipPath(path = glassPath, clipOp = ClipOp.Difference) {
                                                drawPath(path = housingPathOffset, color = Color.Black.copy(alpha = 0.85f))
                                            }
                                            clipPath(path = glassPath, clipOp = ClipOp.Difference) {
                                                drawPath(path = housingPath, color = BezelInnerReal)
                                                drawPath(path = housingPath, brush = bezelHighlightBrush, style = bezelStroke)
                                            }
                                            drawPath(path = glassPath, brush = cornerShadowBrush)
                                            drawPath(path = glassPath, brush = glareStripeBrush)
                                            drawPath(path = glassPath, brush = rimLightBrush, style = rimStroke)
                                        }
                                    },
                            )
                        }

                        Spacer(modifier = Modifier.width(width = 16.dp))

                        Column(
                            modifier = Modifier
                                .width(width = 48.dp)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(modifier = Modifier.height(height = 2.dp))
                            Canvas(modifier = Modifier.size(size = 16.dp)) {
                                drawLedIndicator()
                            }
                            Spacer(modifier = Modifier.height(height = 8.dp))
                            Canvas(modifier = Modifier.size(size = 44.dp)) {
                                drawMachinedDial()
                            }
                            Spacer(modifier = Modifier.height(height = 6.dp))
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(height = 42.dp),
                            ) {
                                drawButtonPair()
                            }
                            Spacer(modifier = Modifier.height(height = 10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(height = 124.dp)
                                    .clip(shape = RoundedCornerShape(size = 6.dp))
                                    .background(color = TvShellDeepShadow),
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawSpeakerGrille()
                                }
                            }
                            Spacer(modifier = Modifier.weight(weight = 1f))
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(alignment = Alignment.BottomCenter)
                    .offset(y = 44.dp)
                    .width(width = 300.dp)
                    .height(height = 56.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawStandAssembly()
                }
            }
        }
    }
}

private fun createBarrelPath(
    width: Float,
    height: Float,
    cornerRadius: Float,
    bow: Float,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
): Path {
    val right = offsetX + width
    val bottom = offsetY + height
    return Path().apply {
        moveTo(x = offsetX + cornerRadius, y = offsetY)
        quadraticTo(x1 = offsetX + width / 2f, y1 = offsetY - bow, x2 = right - cornerRadius, y2 = offsetY)
        arcTo(
            rect = Rect(left = right - 2 * cornerRadius, top = offsetY, right = right, bottom = offsetY + 2 * cornerRadius),
            startAngleDegrees = 270f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        quadraticTo(x1 = right + bow, y1 = offsetY + height / 2f, x2 = right, y2 = bottom - cornerRadius)
        arcTo(
            rect = Rect(left = right - 2 * cornerRadius, top = bottom - 2 * cornerRadius, right = right, bottom = bottom),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        quadraticTo(x1 = offsetX + width / 2f, y1 = bottom + bow, x2 = offsetX + cornerRadius, y2 = bottom)
        arcTo(
            rect = Rect(left = offsetX, top = bottom - 2 * cornerRadius, right = offsetX + 2 * cornerRadius, bottom = bottom),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        quadraticTo(x1 = offsetX - bow, y1 = offsetY + height / 2f, x2 = offsetX, y2 = offsetY + cornerRadius)
        arcTo(
            rect = Rect(left = offsetX, top = offsetY, right = offsetX + 2 * cornerRadius, bottom = offsetY + 2 * cornerRadius),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        close()
    }
}

private fun DrawScope.drawAntennaAssembly() {
    val mountBaseWidth = size.width * 0.28f
    val mountBaseHeight = 18.dp.toPx()
    val mountTopLeft = Offset(x = (size.width - mountBaseWidth) / 2f, y = size.height - mountBaseHeight)
    val leftSocket = Offset(x = mountTopLeft.x + mountBaseWidth * 0.22f, y = size.height - mountBaseHeight * 0.35f)
    val rightSocket = Offset(x = mountTopLeft.x + mountBaseWidth * 0.78f, y = size.height - mountBaseHeight * 0.35f)
    val antennaTopLeft = Offset(x = 12.dp.toPx(), y = 8.dp.toPx())
    val antennaTopRight = Offset(x = size.width - 12.dp.toPx(), y = 8.dp.toPx())
    val baseR = 4.2.dp.toPx()
    val tipR = 1.6.dp.toPx()

    drawRoundRect(
        color = TvBlueDeepExtrusionColor,
        topLeft = mountTopLeft.copy(y = mountTopLeft.y + 3.dp.toPx()),
        size = Size(width = mountBaseWidth, height = mountBaseHeight),
        cornerRadius = CornerRadius(x = 6.dp.toPx()),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(colors = listOf(TvShellHighlight, TvShellMidLight, TvShellDeepShadow)),
        topLeft = mountTopLeft,
        size = Size(width = mountBaseWidth, height = mountBaseHeight),
        cornerRadius = CornerRadius(x = 6.dp.toPx()),
    )

    listOf(leftSocket, rightSocket).forEach { socketCenter ->
        drawOval(
            color = HoleDeepColor,
            topLeft = Offset(x = socketCenter.x - baseR * 1.5f, y = mountTopLeft.y + 1.dp.toPx()),
            size = Size(width = baseR * 3f, height = baseR * 1.8f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(ChromeSpec, ChromeMid, ChromeDark),
                center = Offset(x = socketCenter.x - 1.dp.toPx(), y = mountTopLeft.y + 3.dp.toPx()),
                radius = baseR * 1.3f,
            ),
            radius = baseR * 1.15f,
            center = Offset(x = socketCenter.x, y = mountTopLeft.y + 4.5.dp.toPx()),
        )
        drawArc(
            color = Color.Black.copy(alpha = 0.65f),
            startAngle = 190f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(
                x = socketCenter.x - baseR * 1.15f,
                y = mountTopLeft.y + 4.5.dp.toPx() - baseR * 1.15f,
            ),
            size = Size(width = baseR * 2.3f, height = baseR * 2.3f),
            style = Stroke(width = 2.dp.toPx()),
        )
    }

    drawChromeRod(start = leftSocket.copy(y = leftSocket.y - 1.dp.toPx()), end = antennaTopLeft, baseR = baseR, tipR = tipR)
    drawChromeRod(start = rightSocket.copy(y = rightSocket.y - 1.dp.toPx()), end = antennaTopRight, baseR = baseR, tipR = tipR)

    drawRoundRect(
        color = NeonPipeEdge,
        topLeft = Offset(x = mountTopLeft.x + 6.dp.toPx(), y = mountTopLeft.y - 2.dp.toPx()),
        size = Size(width = mountBaseWidth - 12.dp.toPx(), height = 4.dp.toPx()),
        cornerRadius = CornerRadius(x = 2.dp.toPx()),
    )
}

private fun DrawScope.drawChromeRod(
    start: Offset,
    end: Offset,
    baseR: Float,
    tipR: Float,
) {
    val angle = atan2(y = end.y - start.y, x = end.x - start.x)
    val perpAngle = angle + (PI / 2).toFloat()
    val cosP = cos(perpAngle)
    val sinP = sin(perpAngle)
    val p1 = Offset(x = start.x + cosP * baseR, y = start.y + sinP * baseR)
    val p2 = Offset(x = start.x - cosP * baseR, y = start.y - sinP * baseR)
    val p3 = Offset(x = end.x - cosP * tipR, y = end.y - sinP * tipR)
    val p4 = Offset(x = end.x + cosP * tipR, y = end.y + sinP * tipR)
    val rodPath = Path().apply {
        moveTo(x = p1.x, y = p1.y)
        lineTo(x = p2.x, y = p2.y)
        lineTo(x = p3.x, y = p3.y)
        lineTo(x = p4.x, y = p4.y)
        close()
    }

    drawPath(
        path = rodPath,
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to ChromeDark,
                0.35f to ChromeMid,
                0.47f to ChromeSpec,
                0.53f to ChromeSpec,
                0.65f to ChromeMid,
                1.0f to ChromeDark,
            ),
            start = p1,
            end = p2,
        ),
    )
    drawOval(
        color = Color(0xAA000000),
        topLeft = Offset(x = start.x - baseR * 1.1f, y = start.y - baseR * 0.4f),
        size = Size(width = baseR * 2.2f, height = baseR * 0.8f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(ChromeSpec, ChromeMid, ChromeDark),
            center = Offset(x = end.x - tipR * 0.4f, y = end.y - tipR * 0.4f),
            radius = tipR * 2.2f,
        ),
        radius = tipR * 1.8f,
        center = end,
    )
}

private fun DrawScope.drawInnerFaceAndNeon() {
    val w = size.width
    val h = size.height
    val outerCorner = 24.dp.toPx()
    val innerCorner = 18.dp.toPx()
    val trenchW = 7.dp.toPx()

    drawRoundRect(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color(0xFF0C0D1A),
                0.35f to TvShellDeepShadow,
                0.7f to TvShellMidLight,
                1.0f to Color.White.copy(alpha = 0.45f),
            ),
            start = Offset.Zero,
            end = Offset(x = w, y = h),
        ),
        topLeft = Offset(x = trenchW / 2f, y = trenchW / 2f),
        size = Size(width = w - trenchW, height = h - trenchW),
        cornerRadius = CornerRadius(x = outerCorner - trenchW / 2f),
        style = Stroke(width = trenchW),
    )

    val faceLeft = trenchW
    val faceTop = trenchW
    val faceW = w - trenchW * 2
    val faceH = h - trenchW * 2

    drawRoundRect(
        color = TvShellMidDark,
        topLeft = Offset(x = faceLeft, y = faceTop),
        size = Size(width = faceW, height = faceH),
        cornerRadius = CornerRadius(x = innerCorner),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent),
            start = Offset(x = faceLeft, y = faceTop),
            end = Offset(x = faceLeft + 20.dp.toPx(), y = faceTop + 20.dp.toPx()),
        ),
        topLeft = Offset(x = faceLeft, y = faceTop),
        size = Size(width = faceW, height = faceH),
        cornerRadius = CornerRadius(x = innerCorner),
    )

    val tubeW = 2.5.dp.toPx()
    val neonInset = trenchW / 2f

    drawRoundRect(
        color = NeonGlowColor,
        topLeft = Offset(x = neonInset - 2.dp.toPx(), y = neonInset - 2.dp.toPx()),
        size = Size(
            width = w - (neonInset - 2.dp.toPx()) * 2,
            height = h - (neonInset - 2.dp.toPx()) * 2,
        ),
        cornerRadius = CornerRadius(x = outerCorner),
    )
    drawRoundRect(
        brush = Brush.radialGradient(colors = listOf(NeonPipeCenter, NeonPipeEdge), center = Offset(x = w, y = 0f), radius = w),
        topLeft = Offset(x = neonInset, y = neonInset),
        size = Size(width = w - neonInset * 2, height = h - neonInset * 2),
        cornerRadius = CornerRadius(x = outerCorner - neonInset),
        style = Stroke(width = tubeW, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawLedIndicator() {
    val c = center
    val r = size.minDimension / 2f
    val glassR = r * 0.82f

    drawCircle(
        brush = Brush.radialGradient(colors = listOf(LedBaseShadow, Color.Transparent), center = c.copy(x = c.x - 1f, y = c.y + 2f), radius = r),
        radius = r,
    )
    drawCircle(color = LedHousingRing, radius = r * 0.95f)
    drawCircle(
        brush = Brush.linearGradient(colors = listOf(RubyHotTip, RubyMidGlow, RubyDarkRoot), start = Offset.Zero, end = Offset(x = size.width, y = size.height)),
        radius = glassR,
    )
    drawOval(
        color = SpecularHardWhite,
        topLeft = Offset(x = c.x - glassR * 0.6f, y = c.y - glassR * 0.65f),
        size = Size(width = glassR * 0.75f, height = glassR * 0.4f),
    )
    drawOval(
        color = SpecularSoftWhite,
        topLeft = Offset(x = c.x - glassR * 0.45f, y = c.y - glassR * 0.15f),
        size = Size(width = glassR * 0.45f, height = glassR * 0.2f),
    )
    drawArc(
        color = CausticPinkRim,
        startAngle = 15f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(x = c.x - glassR * 0.88f, y = c.y - glassR * 0.88f),
        size = Size(width = glassR * 1.76f, height = glassR * 1.76f),
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawMachinedDial() {
    val c = center
    val dialR = 17.dp.toPx()

    drawCircle(color = Color(0x60000000), radius = dialR + 3.dp.toPx(), center = c + Offset(x = -2f, y = 4f))
    drawCircle(color = HoleDeepColor, radius = dialR + 1.5.dp.toPx())

    val teethCount = 36
    val toothW = 1.8.dp.toPx()
    val toothH = 3.6.dp.toPx()
    val toothCorner = CornerRadius(x = 0.8.dp.toPx())

    for (i in 0 until teethCount) {
        withTransform(transformBlock = { rotate(degrees = (360f / teethCount) * i, pivot = c) }) {
            drawRoundRect(
                brush = Brush.verticalGradient(colors = listOf(KnobSpunSpec, KnobSpunDark)),
                topLeft = Offset(x = c.x - toothW / 2f, y = c.y - dialR),
                size = Size(width = toothW, height = toothH),
                cornerRadius = toothCorner,
            )
        }
    }

    val faceR = dialR - 1.8.dp.toPx()
    drawCircle(
        brush = Brush.sweepGradient(
            colorStops = arrayOf(
                0.0f to KnobSpunMid,
                0.25f to KnobSpunSpec,
                0.5f to KnobSpunDark,
                0.75f to KnobSpunSpec,
                1.0f to KnobSpunMid,
            ),
        ),
        radius = faceR,
    )
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.4f)),
            start = Offset(x = c.x - faceR, y = c.y - faceR),
            end = Offset(x = c.x + faceR, y = c.y + faceR),
        ),
        radius = faceR,
    )

    val notchW = 2.dp.toPx()
    val notchH = 6.dp.toPx()
    val notchTop = c.y - faceR + 3.dp.toPx()
    drawRoundRect(
        color = HoleDeepColor,
        topLeft = Offset(x = c.x - notchW / 2f, y = notchTop),
        size = Size(width = notchW, height = notchH),
        cornerRadius = CornerRadius(x = 1.dp.toPx()),
    )
    drawLine(
        color = Color.White.copy(alpha = 0.7f),
        start = Offset(x = c.x - notchW / 2f, y = notchTop + notchH),
        end = Offset(x = c.x + notchW / 2f, y = notchTop + notchH),
        strokeWidth = 1.dp.toPx(),
    )
}

private fun DrawScope.drawButtonPair() {
    val baseBtnR = 6.dp.toPx()
    val raisedFaceR = baseBtnR * 1.08f
    val b1X = size.width * 0.25f
    val b2X = size.width * 0.75f
    val bY = 11.dp.toPx()
    val iconY = bY + baseBtnR + 11.dp.toPx()
    val silkStroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)

    drawCircle(color = Color(0x75000000), radius = raisedFaceR * 1.15f, center = Offset(x = b1X - 2.5.dp.toPx(), y = bY + 3.5.dp.toPx()))
    drawCircle(color = HoleDeepColor, radius = baseBtnR + 1.2.dp.toPx(), center = Offset(x = b1X, y = bY))
    drawCircle(color = PinkControlDarkReal, radius = raisedFaceR, center = Offset(x = b1X - 0.5.dp.toPx(), y = bY + 1.dp.toPx()))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(PinkControlLightReal, PinkControlMainReal, PinkControlDarkReal),
            center = Offset(x = b1X - 1.5.dp.toPx(), y = bY - 1.5.dp.toPx()),
            radius = raisedFaceR * 1.4f,
        ),
        radius = raisedFaceR,
        center = Offset(x = b1X, y = bY),
    )

    val dotRadius = 0.35.dp.toPx()
    drawCircle(color = Color.White.copy(alpha = 0.35f), radius = dotRadius, center = Offset(x = b1X, y = bY))

    for (ring in 1..3) {
        val ringR = (raisedFaceR * 0.23f) * ring
        val dots = ring * 6
        for (d in 0 until dots) {
            val theta = (2 * PI / dots) * d
            val cx = b1X + (ringR * cos(theta)).toFloat()
            val cy = bY + (ringR * sin(theta)).toFloat()
            drawCircle(color = Color.White.copy(alpha = 0.3f), radius = dotRadius, center = Offset(x = cx + 0.3f, y = cy - 0.3f))
            drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = dotRadius, center = Offset(x = cx - 0.3f, y = cy + 0.3f))
        }
    }

    drawArc(
        color = SilkscreenInk,
        startAngle = -65f,
        sweepAngle = 310f,
        useCenter = false,
        topLeft = Offset(x = b1X - 3.5.dp.toPx(), y = iconY - 3.5.dp.toPx()),
        size = Size(width = 7.dp.toPx(), height = 7.dp.toPx()),
        style = silkStroke,
    )
    drawLine(
        color = SilkscreenInk,
        start = Offset(x = b1X, y = iconY - 4.dp.toPx()),
        end = Offset(x = b1X, y = iconY - 1.dp.toPx()),
        strokeWidth = 1.2.dp.toPx(),
        cap = StrokeCap.Round,
    )

    drawCircle(color = HoleDeepColor, radius = baseBtnR + 1.5.dp.toPx(), center = Offset(x = b2X, y = bY))
    drawArc(
        color = Color(0x35FFFFFF),
        startAngle = 10f,
        sweepAngle = 160f,
        useCenter = false,
        topLeft = Offset(x = b2X - baseBtnR - 1.5.dp.toPx(), y = bY - baseBtnR - 1.5.dp.toPx()),
        size = Size(width = (baseBtnR + 1.5.dp.toPx()) * 2, height = (baseBtnR + 1.5.dp.toPx()) * 2),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(PinkControlLightReal, PinkControlMainReal, PinkControlDarkReal),
            center = Offset(x = b2X + 2.dp.toPx(), y = bY - 2.dp.toPx()),
            radius = baseBtnR * 1.3f,
        ),
        radius = baseBtnR,
        center = Offset(x = b2X, y = bY),
    )

    val sW = 3.5.dp.toPx()
    val sH = 2.5.dp.toPx()
    val tip = 1.8.dp.toPx()
    val sw = 1.2.dp.toPx()

    drawLine(color = SilkscreenInk, start = Offset(x = b2X - sW, y = iconY - sH), end = Offset(x = b2X + sW, y = iconY + sH), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = SilkscreenInk, start = Offset(x = b2X - sW, y = iconY + sH), end = Offset(x = b2X + sW, y = iconY - sH), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = SilkscreenInk, start = Offset(x = b2X + sW, y = iconY + sH), end = Offset(x = b2X + sW - tip, y = iconY + sH), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = SilkscreenInk, start = Offset(x = b2X + sW, y = iconY + sH), end = Offset(x = b2X + sW, y = iconY + sH - tip), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = SilkscreenInk, start = Offset(x = b2X + sW, y = iconY - sH), end = Offset(x = b2X + sW - tip, y = iconY - sH), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = SilkscreenInk, start = Offset(x = b2X + sW, y = iconY - sH), end = Offset(x = b2X + sW, y = iconY - sH + tip), strokeWidth = sw, cap = StrokeCap.Round)
}

private fun DrawScope.drawSpeakerGrille() {
    val w = size.width
    val h = size.height
    val chamfer = 3.dp.toPx()
    val pT = chamfer + 3.dp.toPx()
    val pL = chamfer + 2.dp.toPx()
    val pB = chamfer + 1.dp.toPx()
    val pR = chamfer + 1.dp.toPx()
    val innerW = w - (pL + pR)
    val innerH = h - (pT + pB)

    drawRoundRect(
        brush = Brush.linearGradient(colors = listOf(HoleDeepColor, TvShellHighlight.copy(alpha = 0.3f)), start = Offset.Zero, end = Offset(x = w, y = h)),
        size = size,
        cornerRadius = CornerRadius(x = 6.dp.toPx()),
        style = Stroke(width = chamfer * 1.5f),
    )
    drawRoundRect(
        color = PinkControlMainReal,
        topLeft = Offset(x = pL, y = pT),
        size = Size(width = innerW, height = innerH),
        cornerRadius = CornerRadius(x = 3.dp.toPx()),
    )
    drawRect(
        brush = Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent), startY = pT, endY = pT + 16.dp.toPx()),
        topLeft = Offset(x = pL, y = pT),
        size = Size(width = innerW, height = 16.dp.toPx()),
    )

    val cols = 6
    val rows = 18
    val sX = innerW / cols
    val sY = innerH / rows
    val hW = sX * 0.64f
    val hH = sY * 0.64f
    val highlightStroke = 0.6.dp.toPx()
    val shadowStroke = 1.dp.toPx()

    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val x = pL + (c * sX) + (sX - hW) / 2f
            val y = pT + (r * sY) + (sY - hH) / 2f
            drawRect(color = HoleDeepColor, topLeft = Offset(x = x, y = y), size = Size(width = hW, height = hH))
            drawLine(color = HoleHoleDarkColor, start = Offset(x = x, y = y), end = Offset(x = x + hW, y = y), strokeWidth = shadowStroke)
            drawLine(color = HoleHoleDarkColor, start = Offset(x = x + hW, y = y), end = Offset(x = x + hW, y = y + hH), strokeWidth = shadowStroke)
            drawLine(color = PinkControlLightReal, start = Offset(x = x, y = y + hH), end = Offset(x = x + hW, y = y + hH), strokeWidth = highlightStroke)
            drawLine(color = PinkControlLightReal, start = Offset(x = x, y = y), end = Offset(x = x, y = y + hH), strokeWidth = highlightStroke)
        }
    }
}

private fun DrawScope.drawStandAssembly() {
    val platW = 260.dp.toPx()
    val platH = 14.dp.toPx()
    val platLeft = (size.width - platW) / 2f
    val platTop = 18.dp.toPx()
    val fRX = 14.dp.toPx()
    val fRY = 6.dp.toPx()
    val fH = 18.dp.toPx()
    val cR = 6.dp.toPx()

    listOf(platLeft + 36.dp.toPx(), platLeft + platW - 36.dp.toPx()).forEach { fx ->
        val tY = platTop + platH - 4.dp.toPx()
        val bY = tY + fH
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xD90A0512), Color(0x550A0512), Color.Transparent),
                center = Offset(x = fx + 3.dp.toPx(), y = bY + 1.dp.toPx()),
                radius = fRX * 1.5f,
            ),
            topLeft = Offset(x = fx - fRX * 1.3f + 3.dp.toPx(), y = bY - fRY * 0.8f),
            size = Size(width = fRX * 2.6f, height = fRY * 2.2f),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to MetalFootDark,
                    0.25f to MetalFootMid,
                    0.45f to MetalFootLight,
                    0.55f to MetalFootLight,
                    0.8f to MetalFootMid,
                    1.0f to MetalFootDark,
                ),
                startX = fx - fRX,
                endX = fx + fRX,
            ),
            topLeft = Offset(x = fx - fRX, y = tY),
            size = Size(width = fRX * 2, height = fH),
        )
        drawOval(color = RubberPadDark, topLeft = Offset(x = fx - fRX, y = bY - fRY), size = Size(width = fRX * 2, height = fRY * 2))
        drawOval(
            brush = Brush.verticalGradient(colors = listOf(MetalFootLight, MetalFootDark)),
            topLeft = Offset(x = fx - fRX, y = tY - fRY),
            size = Size(width = fRX * 2, height = fRY * 2),
        )
    }

    drawRoundRect(
        color = TvBlueDeepExtrusionColor,
        topLeft = Offset(x = platLeft - 4.dp.toPx(), y = platTop + 5.dp.toPx()),
        size = Size(width = platW, height = platH),
        cornerRadius = CornerRadius(x = cR),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(colors = listOf(TvShellHighlight, TvShellMidLight, TvShellDeepShadow)),
        topLeft = Offset(x = platLeft, y = platTop),
        size = Size(width = platW, height = platH),
        cornerRadius = CornerRadius(x = cR),
    )
    drawLine(
        color = Color.White.copy(alpha = 0.5f),
        start = Offset(x = platLeft + cR, y = platTop + 1.dp.toPx()),
        end = Offset(x = platLeft + platW - cR, y = platTop + 1.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Round,
    )

    val slW = platW * 0.32f
    val slH = 5.dp.toPx()
    val slL = platLeft + (platW - slW) / 2f
    val slT = platTop + 4.dp.toPx()

    drawRoundRect(color = HoleDeepColor, topLeft = Offset(x = slL, y = slT), size = Size(width = slW, height = slH), cornerRadius = CornerRadius(x = slH / 2f))
    drawRoundRect(
        color = TvShellHighlight.copy(alpha = 0.6f),
        topLeft = Offset(x = slL, y = slT + 1.dp.toPx()),
        size = Size(width = slW, height = slH),
        cornerRadius = CornerRadius(x = slH / 2f),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawLine(
        color = Color.Black.copy(alpha = 0.7f),
        start = Offset(x = slL + 2.dp.toPx(), y = slT),
        end = Offset(x = slL + slW - 2.dp.toPx(), y = slT),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round,
    )
}
