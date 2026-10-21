package tw.timecam

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 系統列（狀態列、導覽列、瀏海、鍵盤）安全邊距；同時參考根視窗原始 insets，避免被上層吃掉 */
object EdgeInsets {
    fun of(v: View, ins: WindowInsetsCompat, ime: Boolean = false): Insets {
        val t = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val a = ins.getInsets(t)
        val r = ViewCompat.getRootWindowInsets(v)?.getInsets(t) ?: Insets.NONE
        var m = Insets.max(a, r)
        if (ime) {
            val k = Insets.max(ins.getInsets(WindowInsetsCompat.Type.ime()), ViewCompat.getRootWindowInsets(v)?.getInsets(WindowInsetsCompat.Type.ime()) ?: Insets.NONE)
            m = Insets.of(m.left, m.top, m.right, maxOf(m.bottom, k.bottom))
        }
        return m
    }
    /** 在原本 padding 之上加上指定方向的 insets（可重複呼叫） */
    fun pad(v: View, left: Boolean = true, top: Boolean = true, right: Boolean = true, bottom: Boolean = true, ime: Boolean = false, after: ((Insets) -> Unit)? = null) {
        val l0 = v.paddingLeft; val t0 = v.paddingTop; val r0 = v.paddingRight; val b0 = v.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(v) { view, ins ->
            val b = of(view, ins, ime)
            view.setPadding(l0 + if (left) b.left else 0, t0 + if (top) b.top else 0, r0 + if (right) b.right else 0, b0 + if (bottom) b.bottom else 0)
            after?.invoke(b); ins
        }
        if (v.isAttachedToWindow) ViewCompat.requestApplyInsets(v)
        else v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { x.removeOnAttachStateChangeListener(this); ViewCompat.requestApplyInsets(x) }
            override fun onViewDetachedFromWindow(x: View) {}
        })
    }
}
