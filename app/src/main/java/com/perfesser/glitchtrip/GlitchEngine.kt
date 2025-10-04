package com.perfesser.glitchtrip

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.*

data class GlitchParams(
    val rgbShift: Float,
    val blockJitter: Int,
    val noise: Float,
    val scanlines: Float,
    val waveAmp: Float,
    val waveFreq: Float,
    val pixelSort: Float,
    val aberration: Float,
    val crush: Float,
    val saturation: Float,
    val hue: Float,
    val brightness: Float,
    val orbs: List<Orb>,
    val spamMode: Boolean = false
)

object GlitchEngine {

    fun applyAll(src: Bitmap, p: GlitchParams): Bitmap {
        // Work at manageable size for speed; then scale back to original
        val maxW = 1600
        val scale = min(1f, maxW.toFloat() / src.width)
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(base)
        c.drawBitmap(src, null, Rect(0,0,w,h), null)

        var bmp = base

        // 1) RGB channel split + chromatic aberration
        bmp = rgbSplit(bmp, (p.rgbShift * scale).toInt(), p.aberration)

        // 2) Wave displacement with draggable orbs as attractors
        bmp = waveDisplace(bmp, p.waveAmp * scale, p.waveFreq, p.orbs)

        // 3) Block jitter
        if (p.blockJitter > 0) bmp = blockJitter(bmp, p.blockJitter)

        // 4) Pixel sort (simple lightness-based partial sort)
        if (p.pixelSort > 0.01f) bmp = pixelSort(bmp, p.pixelSort)

        // 5) Film tweaks: scanlines + noise
        bmp = filmFX(bmp, p.scanlines, p.noise)

        // 6) Color grading: crush, sat, hue, brightness (+ optional spam-mode pink push)
        val hueBoost = if (p.spamMode) p.hue + 30f else p.hue
        val satBoost = if (p.spamMode) p.saturation * 1.1f else p.saturation
        bmp = grade(bmp, p.crush, satBoost, hueBoost, p.brightness)

        // 7) optional SPAM overlay watermark when spamMode
        if (p.spamMode) {
            bmp = overlaySpam(bmp)
        }

        // Scale back if needed
        if (abs(scale - 1f) > 0.0001f) {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val cc = Canvas(out)
            cc.drawBitmap(bmp, null, Rect(0,0,src.width, src.height), null)
            bmp.recycle()
            return out
        }
        return bmp
    }

    private fun overlaySpam(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val out = src.copy(src.config, true)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = (min(w, h) * 0.08f)
        paint.color = Color.argb(90, 255, 64, 160)
        paint.style = Paint.Style.FILL
        val msg = "🥫 SPAM ART 🥫"
        val tw = paint.measureText(msg)
        var y = paint.textSize * 1.2f
        var toggle = false
        while (y < h) {
            var x = if (toggle) -tw/2 else 0f
            while (x < w) {
                c.drawText(msg, x, y, paint)
                x += tw * 1.5f
            }
            y += paint.textSize * 1.8f
            toggle = !toggle
        }
        return out
    }

    private fun rgbSplit(src: Bitmap, shift: Int, aberration: Float): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun drawChannel(dx: Int, dy: Int, colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            val m = Matrix()
            m.setTranslate(dx.toFloat(), dy.toFloat())
            c.drawBitmap(src, m, paint)
        }

        drawChannel(shift, -shift, LightingColorFilter(0x00FF0000, 0x00000000))
        drawChannel(0, 0, LightingColorFilter(0x0000FF00, 0x00000000))
        drawChannel(-shift, shift, LightingColorFilter(0x000000FF, 0x00000000))

        if (aberration > 0f) {
            val paintAb = Paint(Paint.ANTI_ALIAS_FLAG)
            paintAb.alpha = (aberration * 80).toInt().coerceIn(0, 255)
            val scale = 1f + aberration * 0.02f
            val m = Matrix()
            m.setScale(scale, scale, w/2f, h/2f)
            c.drawBitmap(src, m, paintAb)
        }

        return out
    }

    private fun waveDisplace(src: Bitmap, amp: Float, freq: Float, orbs: List<Orb>): Bitmap {
        if (amp < 1f || freq <= 0f) return src
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val inPixels = IntArray(w*h)
        val outPixels = IntArray(w*h)
        src.getPixels(inPixels, 0, w, 0, 0, w, h)

        val orbPx = orbs.map { Pair(it.x * w, it.y * h) }

        for (y in 0 until h) {
            for (x in 0 until w) {
                var dx = 0.0
                var dy = 0.0
                for ((ox, oy) in orbPx) {
                    val rx = x - ox
                    val ry = y - oy
                    val r = sqrt(rx*rx + ry*ry)
                    val a = amp * sin(r / max(6f, 120f / (1f + freq)))
                    val nx = -ry / (r + 1e-3) * a
                    val ny = rx / (r + 1e-3) * a
                    dx += nx
                    dy += ny
                }
                val sx = (x + dx).roundToInt().coerceIn(0, w - 1)
                val sy = (y + dy).roundToInt().coerceIn(0, h - 1)
                outPixels[y*w + x] = inPixels[sy*w + sx]
            }
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun blockJitter(src: Bitmap, size: Int): Bitmap {
        val w = src.width; val h = src.height
        val out = src.copy(src.config, true)
        val c = Canvas(out)
        val rnd = java.util.Random()
        val paint = Paint()

        val step = max(4, size)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val bw = min(step, w - x)
                val bh = min(step, h - y)
                val dx = x + rnd.nextInt(step) - step/2
                val dy = y + rnd.nextInt(step) - step/2
                val srcRect = Rect(x, y, x + bw, y + bh)
                val dstRect = Rect(
                    (dx).coerceIn(0, w - bw),
                    (dy).coerceIn(0, h - bh),
                    (dx + bw).coerceIn(bw, w),
                    (dy + bh).coerceIn(bh, h)
                )
                c.drawBitmap(src, srcRect, dstRect, paint)
                x += step
            }
            y += step
        }
        return out
    }

    private fun pixelSort(src: Bitmap, amount: Float): Bitmap {
        val w = src.width; val h = src.height
        val out = src.copy(src.config, true)
        val row = IntArray(w)
        for (y in 0 until h step 2) { // every other row for speed
            out.getPixels(row, 0, w, 0, y, w, 1)
            var i = 0
            while (i < w) {
                val seg = min((20 + amount * 180).toInt(), w - i)
                java.util.Arrays.sort(row, i, i + seg)
                i += seg + 3
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun filmFX(src: Bitmap, scan: Float, noiseAmt: Float): Bitmap {
        val w = src.width; val h = src.height
        val out = src.copy(src.config, true)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (scan > 0f) {
            paint.color = Color.argb((scan*40).toInt().coerceIn(0,255), 0, 0, 0)
            var y = 0f
            while (y < h) {
                c.drawLine(0f, y, w.toFloat(), y, paint)
                y += 2f
            }
        }
        if (noiseAmt > 0f) {
            val rnd = java.util.Random()
            val px = IntArray(w*h)
            out.getPixels(px, 0, w, 0, 0, w, h)
            val nScale = (noiseAmt * 60).toInt()
            for (i in px.indices) {
                val a = px[i] ushr 24 and 0xFF
                var r = px[i] ushr 16 and 0xFF
                var g = px[i] ushr 8 and 0xFF
                var b = px[i] and 0xFF
                val n = rnd.nextInt(nScale*2+1) - nScale
                r = (r + n).coerceIn(0,255)
                g = (g + n).coerceIn(0,255)
                b = (b + n).coerceIn(0,255)
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(px, 0, w, 0, 0, w, h)
        }
        return out
    }

    private fun grade(src: Bitmap, crush: Float, sat: Float, hue: Float, bright: Float): Bitmap {
        val w = src.width; val h = src.height
        val out = src.copy(src.config, true)
        val px = IntArray(w*h)
        out.getPixels(px, 0, w, 0, 0, w, h)

        val hueRad = hue / 180f * Math.PI
        val cosH = cos(hueRad).toFloat()
        val sinH = sin(hueRad).toFloat()

        for (i in px.indices) {
            val a = px[i] ushr 24 and 0xFF
            var r = (px[i] ushr 16 and 0xFF) / 255f
            var g = (px[i] ushr 8 and 0xFF) / 255f
            var b = (px[i] and 0xFF) / 255f

            r = (r + bright).coerceIn(0f,1f)
            g = (g + bright).coerceIn(0f,1f)
            b = (b + bright).coerceIn(0f,1f)

            val l = (r + g + b) / 3f
            r = l + (r - l) * sat
            g = l + (g - l) * sat
            b = l + (b - l) * sat

            val nr = r * cosH - g * sinH
            val ng = r * sinH + g * cosH
            r = nr.coerceIn(0f,1f); g = ng.coerceIn(0f,1f)

            if (crush > 0f) {
                fun crushFun(x: Float): Float {
                    val mid = 0.5f
                    val k = 1f + crush * 2f
                    return (k * (x - mid) + mid).coerceIn(0f,1f)
                }
                r = crushFun(r); g = crushFun(g); b = crushFun(b)
            }

            px[i] = (a shl 24) or
                    ((r*255f).toInt().coerceIn(0,255) shl 16) or
                    ((g*255f).toInt().coerceIn(0,255) shl 8) or
                    ((b*255f).toInt().coerceIn(0,255))
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}

// I/O helpers
suspend fun loadBitmap(context: Context, uri: Uri): Bitmap {
    val src = context.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it)
    }
    return src.copy(Bitmap.Config.ARGB_8888, true)
}

fun saveBitmap(context: Context, bmp: Bitmap, fmt: ExportFormat, fileName: String? = null) {
    val (mime, compress) = when (fmt) {
        ExportFormat.PNG -> Pair("image/png", Bitmap.CompressFormat.PNG)
        ExportFormat.JPEG -> Pair("image/jpeg", Bitmap.CompressFormat.JPEG)
        ExportFormat.WEBP -> Pair("image/webp", Bitmap.CompressFormat.WEBP_LOSSY)
    }
    val name = fileName ?: "glitchtrip_${System.currentTimeMillis()}"
    val display = if (fmt == ExportFormat.PNG) "$name.png" else if (fmt == ExportFormat.JPEG) "$name.jpg" else "$name.webp"

    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, display)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GlitchTrip")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
    if (uri != null) {
        resolver.openOutputStream(uri).use { out ->
            bmp.compress(compress, 95, out)
        }
        cv.clear()
        cv.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, cv, null, null)
    }
}

fun shareBitmap(context: Context, bmp: Bitmap, fmt: ExportFormat) {
    // Save to cache then share
    val file = java.io.File(context.cacheDir, "share_${System.currentTimeMillis()}.${if (fmt==ExportFormat.PNG) "png" else if (fmt==ExportFormat.JPEG) "jpg" else "webp"}")
    val (mime, compress) = when (fmt) {
        ExportFormat.PNG -> Pair("image/png", Bitmap.CompressFormat.PNG)
        ExportFormat.JPEG -> Pair("image/jpeg", Bitmap.CompressFormat.JPEG)
        ExportFormat.WEBP -> Pair("image/webp", Bitmap.CompressFormat.WEBP_LOSSY)
    }
    java.io.FileOutputStream(file).use { fos -> bmp.compress(compress, 95, fos) }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share glitched image"))
}