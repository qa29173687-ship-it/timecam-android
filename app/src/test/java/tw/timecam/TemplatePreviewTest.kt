package tw.timecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class TemplatePreviewTest {
    private val t = ZonedDateTime.of(2026, 10, 21, 10, 14, 2, 0, Names.TZ)
    private val d = WmData("中和安邦社宅", "A-1023", "B棟", "5F", "浴室漏水",
        "（範例）新北市中和區範例路 123 號", "25.00000, 121.50000 (±8m)")

    private fun bg(w: Int, h: Int): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF8FA6B8.toInt(), 0xFF4A5560.toInt(), Shader.TileMode.CLAMP) })
        c.drawRect(w * .3f, h * .2f, w * .7f, h * .6f, Paint().apply { color = 0xFFD9D4C7.toInt() })
        c.drawRect(0f, h * .6f, w.toFloat(), h.toFloat(), Paint().apply { color = 0xFF6E6A62.toInt() })
        c.drawText("範例背景 SAMPLE", 20f, 40f, Paint().apply { color = Color.WHITE; textSize = 28f })
        return b to c
    }

    @Test fun renderAll() {
        val out = File(System.getProperty("previewDir") ?: "build/template-previews").apply { mkdirs() }
        WmData.TEMPLATES.forEachIndexed { i, name ->
            val (b, c) = bg(1600, 1200); Watermark.stamp(c, 1600f, 1200f, d.copy(tpl = i), t)
            File(out, "${i + 1}-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        listOf(1 to "右下", 2 to "左上", 3 to "右上").forEach { (pos, n) ->
            val (b, c) = bg(1600, 1200); Watermark.stamp(c, 1600f, 1200f, d.copy(tpl = 0, pos = pos, size = if (pos == 2) 0 else 2), t)
            File(out, "pos-$n.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
