package tw.timecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class TimeAddrPreviewTest {
    private val t = ZonedDateTime.of(2026, 11, 22, 10, 14, 0, 0, Names.TZ)
    private val d = WmData("中和安邦社宅", "A-1023", "B棟", "5F", "浴室漏水",
        "235新北市中和區安邦巷5弄6號", "25.00120, 121.48650（±8m）", tpl = 5, pos = 0, size = 1)

    @Test fun timeAddrHasYellowDividerAndFits() {
        val w = 1600; val h = 1200
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF8FA6B8.toInt(), 0xFF4A5560.toInt(), Shader.TileMode.CLAMP) })
        Watermark.stamp(c, w.toFloat(), h.toFloat(), d, t)
        File("../previews").apply { mkdirs() }.let { dir ->
            File(dir, "6-時間地址.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // 黃分隔線大約在左下時間旁邊
        var yellow = 0
        val x0 = 40; val x1 = w / 2; val y0 = (h * 0.55).toInt(); val y1 = h - 10
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val p = b.getPixel(x, y)
            val r = Color.red(p); val g = Color.green(p); val bl = Color.blue(p)
            if (r > 200 && g > 160 && bl < 80) yellow++
        }
        assertTrue("應畫出黃色分隔線（找到 $yellow 個黃點）", yellow > 20)
    }

    @Test fun extraLineOnlyWhenAsked() {
        assertEquals("", Watermark.extraLine(d.copy(showExtra = false)))
        val s = Watermark.extraLine(d.copy(showExtra = true))
        assertTrue(s.contains("中和安邦社宅"))
        assertTrue(s.contains("A-1023"))
    }

    @Test fun allTemplatesIncludingTimeAddr() {
        assertEquals(6, WmData.TEMPLATES.size)
        assertEquals("時間地址", WmData.TEMPLATES[5])
    }
}
