package tw.timecam

import android.graphics.*
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 模擬 24dp 狀態列 + 48dp 三鍵導覽列（相機在測試環境中不會啟動，預覽區以白底顯示） */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class InsetsPreviewTest {
    @Test fun render() {
        val W = 800; val H = 1720; val SB = 48; val NB = 96
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val v = a.window.decorView
        fun lay() { v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)); v.layout(0, 0, W, H) }
        lay()
        ViewCompat.dispatchApplyWindowInsets(v, WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, SB, 0, NB)).build())
        ShadowLooper.idleMainLooper(); lay(); ShadowLooper.idleMainLooper(); lay()
        val top = a.findViewById<View>(R.id.top); val ctrl = a.findViewById<View>(R.id.ctrl); val shutter = a.findViewById<View>(R.id.shutter)
        assertTrue("工具列需避開狀態列", top.paddingTop >= SB)
        assertTrue("控制列需避開導覽列", ctrl.paddingBottom >= NB)
        val loc = IntArray(2); shutter.getLocationInWindow(loc)
        assertTrue("快門不可被導覽列遮住", loc[1] + shutter.height <= H - NB)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val p = Paint().apply { color = 0x99FF00FF.toInt() }; c.drawRect(0f, 0f, W.toFloat(), SB.toFloat(), p); c.drawRect(0f, (H - NB).toFloat(), W.toFloat(), H.toFloat(), p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f }
        c.drawText("模擬狀態列 24dp", 16f, 36f, t); c.drawText("◁   ○   □   模擬三鍵導覽列 48dp", 16f, H - NB / 2f + 10, t)
        File("../previews").apply { mkdirs() }.let { d -> File(d, "insets-1-主畫面.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
