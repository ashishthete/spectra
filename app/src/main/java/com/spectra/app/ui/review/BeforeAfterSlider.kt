package com.spectra.app.ui.review

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors

@Composable
fun BeforeAfterSlider(
    beforeBitmap: Bitmap?,
    afterBitmap: Bitmap?,
    modifier: Modifier = Modifier
) {
    if (beforeBitmap == null || afterBitmap == null) return

    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    val beforeImage = remember(beforeBitmap) { beforeBitmap.asImageBitmap() }
    val afterImage = remember(afterBitmap) { afterBitmap.asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    splitFraction = (change.position.x / size.width).coerceIn(0.05f, 0.95f)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height
            val splitX = canvasW * splitFraction

            val imgW = afterImage.width.toFloat()
            val imgH = afterImage.height.toFloat()
            val scale = maxOf(canvasW / imgW, canvasH / imgH)
            val drawW = imgW * scale
            val drawH = imgH * scale
            val offsetX = (canvasW - drawW) / 2f
            val offsetY = (canvasH - drawH) / 2f
            val dstSize = IntSize(drawW.toInt(), drawH.toInt())
            val dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt())

            clipRect(right = splitX) {
                drawImage(
                    image = beforeImage,
                    dstSize = dstSize,
                    dstOffset = dstOffset
                )
            }

            clipRect(left = splitX) {
                drawImage(
                    image = afterImage,
                    dstSize = dstSize,
                    dstOffset = dstOffset
                )
            }

            drawLine(
                color = Color.White,
                start = Offset(splitX, 0f),
                end = Offset(splitX, canvasH),
                strokeWidth = 2.dp.toPx()
            )

            val handleRadius = 14.dp.toPx()
            drawCircle(
                color = Color.White,
                radius = handleRadius,
                center = Offset(splitX, canvasH / 2f)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = handleRadius - 2.dp.toPx(),
                center = Offset(splitX, canvasH / 2f)
            )

            val arrowSize = 6.dp.toPx()
            val arrowY = canvasH / 2f
            val leftPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(splitX - arrowSize * 1.5f, arrowY)
                lineTo(splitX - arrowSize * 0.5f, arrowY - arrowSize)
                lineTo(splitX - arrowSize * 0.5f, arrowY + arrowSize)
                close()
            }
            val rightPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(splitX + arrowSize * 1.5f, arrowY)
                lineTo(splitX + arrowSize * 0.5f, arrowY - arrowSize)
                lineTo(splitX + arrowSize * 0.5f, arrowY + arrowSize)
                close()
            }
            drawPath(leftPath, Color.White)
            drawPath(rightPath, Color.White)
        }

        Text(
            text = "BEFORE",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Text(
            text = "AFTER",
            color = HudColors.accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
