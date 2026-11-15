package com.universaltranslator.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight edge-interpolation cleanup for flat comic speech balloons.
 * This is not neural inpainting; it reconstructs a smooth approximation
 * from pixels just outside the detected text rectangle.
 */
object ComicPatch {
    fun create(source: Bitmap, textBounds: Rect): Bitmap {
        val left = textBounds.left.coerceIn(0, source.width - 1)
        val top = textBounds.top.coerceIn(0, source.height - 1)
        val right = textBounds.right.coerceIn(left + 1, source.width)
        val bottom = textBounds.bottom.coerceIn(top + 1, source.height)
        val width = right - left
        val height = bottom - top
        val margin = 3
        val patch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = left + x
                val sy = top + y
                val sampleTop = source.getPixel(sx, (top - margin).coerceAtLeast(0))
                val sampleBottom = source.getPixel(sx, (bottom + margin - 1).coerceAtMost(source.height - 1))
                val sampleLeft = source.getPixel((left - margin).coerceAtLeast(0), sy)
                val sampleRight = source.getPixel((right + margin - 1).coerceAtMost(source.width - 1), sy)

                val ty = (y + margin).toFloat() / (height + 2 * margin).coerceAtLeast(1)
                val tx = (x + margin).toFloat() / (width + 2 * margin).coerceAtLeast(1)
                val vertical = mix(sampleTop, sampleBottom, ty)
                val horizontal = mix(sampleLeft, sampleRight, tx)
                val vWeight = 1f / (min(y + margin, height - 1 - y + margin) + 1f)
                val hWeight = 1f / (min(x + margin, width - 1 - x + margin) + 1f)
                patch.setPixel(x, y, mix(vertical, horizontal, hWeight / (vWeight + hWeight)))
            }
        }
        return patch
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(a) * (1f - u) + Color.red(b) * u).toInt(),
            (Color.green(a) * (1f - u) + Color.green(b) * u).toInt(),
            (Color.blue(a) * (1f - u) + Color.blue(b) * u).toInt()
        )
    }
}
