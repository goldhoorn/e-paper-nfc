package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Color

enum class RenderMode(val key: String, val label: String) {
    THRESHOLD("threshold", "Threshold"),
    FLOYD_STEINBERG("floyd", "Floyd–Steinberg dither"),
    ATKINSON("atkinson", "Atkinson dither"),
    BAYER("bayer", "Ordered dither (Bayer)");

    companion object {
        fun fromKey(key: String?): RenderMode {
            return values().firstOrNull { it.key == key } ?: THRESHOLD
        }
    }
}

data class RenderSettings(
    val mode: RenderMode = RenderMode.THRESHOLD,
    val invert: Boolean = false,
    val threshold: Int = 128,
    val soften: Int = 0
)

object ImageRenderer {
    fun render(src: Bitmap, settings: RenderSettings): Bitmap {
        val working = if (src.config == Bitmap.Config.ARGB_8888) src else {
            src.copy(Bitmap.Config.ARGB_8888, false)
        }
        val w = working.width
        val h = working.height
        val pixels = IntArray(w * h)
        working.getPixels(pixels, 0, w, 0, 0, w, h)

        var gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 30 + g * 59 + b * 11) / 100
        }

        val soften = settings.soften.coerceIn(0, MAX_SOFTEN)
        if (soften > 0) {
            gray = gaussianBlur(gray, w, h, soften)
        }

        val bw = when (settings.mode) {
            RenderMode.THRESHOLD -> threshold(gray, settings.threshold)
            RenderMode.FLOYD_STEINBERG -> floydSteinberg(gray, w, h)
            RenderMode.ATKINSON -> atkinson(gray, w, h)
            RenderMode.BAYER -> bayer(gray, w, h)
        }

        val invert = settings.invert
        for (i in bw.indices) {
            if (invert) bw[i] = 255 - bw[i]
        }
        // Close black ink so 1-pixel corner gaps in glyphs fill back in.
        if (soften > 0) {
            morphologicalCloseBlack(bw, w, h)
        }

        val outPx = IntArray(w * h)
        for (i in bw.indices) {
            outPx[i] = if (bw[i] >= 128) Color.WHITE else Color.BLACK
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    private fun threshold(gray: IntArray, cutoff: Int): IntArray {
        val c = cutoff.coerceIn(0, 255)
        val out = IntArray(gray.size)
        for (i in gray.indices) {
            out[i] = if (gray[i] >= c) 255 else 0
        }
        return out
    }

    private fun floydSteinberg(src: IntArray, w: Int, h: Int): IntArray {
        val g = src.copyOf()
        val out = IntArray(g.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = g[i]
                val neu = if (old >= 128) 255 else 0
                out[i] = neu
                val err = old - neu
                if (x + 1 < w) g[i + 1] += err * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) g[i + w - 1] += err * 3 / 16
                    g[i + w] += err * 5 / 16
                    if (x + 1 < w) g[i + w + 1] += err / 16
                }
            }
        }
        return out
    }

    private fun atkinson(src: IntArray, w: Int, h: Int): IntArray {
        val g = src.copyOf()
        val out = IntArray(g.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = g[i]
                val neu = if (old >= 128) 255 else 0
                out[i] = neu
                val spread = (old - neu) / 8
                if (x + 1 < w) g[i + 1] += spread
                if (x + 2 < w) g[i + 2] += spread
                if (y + 1 < h) {
                    if (x > 0) g[i + w - 1] += spread
                    g[i + w] += spread
                    if (x + 1 < w) g[i + w + 1] += spread
                }
                if (y + 2 < h) g[i + w * 2] += spread
            }
        }
        return out
    }

    private fun bayer(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val limit = (BAYER8[y and 7][x and 7] * 255) / 64
                out[i] = if (src[i] > limit) 255 else 0
            }
        }
        return out
    }

    private fun gaussianBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val kernel = gaussianKernel(radius)
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        val r = radius
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0
                var wsum = 0
                for (k in -r..r) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    val wk = kernel[k + r]
                    acc += src[row + xx] * wk
                    wsum += wk
                }
                tmp[row + x] = acc / wsum
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0
                var wsum = 0
                for (k in -r..r) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    val wk = kernel[k + r]
                    acc += tmp[yy * w + x] * wk
                    wsum += wk
                }
                out[y * w + x] = acc / wsum
            }
        }
        return out
    }

    private fun gaussianKernel(radius: Int): IntArray {
        val sigma = 0.3f * (radius - 1) + 0.8f
        val twoSigmaSq = 2f * sigma * sigma
        val k = IntArray(radius * 2 + 1)
        for (i in -radius..radius) {
            val w = Math.exp((-(i * i).toFloat() / twoSigmaSq).toDouble()).toFloat()
            k[i + radius] = (w * 256f).toInt().coerceAtLeast(1)
        }
        return k
    }

    /** Dilate then erode black (0) pixels — fills 1px notches without fattening strokes much. */
    private fun morphologicalCloseBlack(bw: IntArray, w: Int, h: Int) {
        val dilated = dilateBlack(bw, w, h)
        val closed = erodeBlack(dilated, w, h)
        System.arraycopy(closed, 0, bw, 0, bw.size)
    }

    private fun dilateBlack(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size) { 255 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var black = false
                loop@ for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        if (src[yy * w + xx] < 128) {
                            black = true
                            break@loop
                        }
                    }
                }
                out[y * w + x] = if (black) 0 else 255
            }
        }
        return out
    }

    private fun erodeBlack(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var allBlack = true
                loop@ for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        if (src[yy * w + xx] >= 128) {
                            allBlack = false
                            break@loop
                        }
                    }
                }
                out[y * w + x] = if (allBlack) 0 else 255
            }
        }
        return out
    }

    const val MAX_SOFTEN = 8

    private val BAYER8 = arrayOf(
        intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
        intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
        intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
        intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
        intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
        intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
        intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
        intArrayOf(63, 31, 55, 23, 61, 29, 53, 21)
    )
}
