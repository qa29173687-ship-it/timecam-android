package tw.timecam

import android.content.Context
import android.graphics.*
import android.os.Build
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class WmData(val site: String, val case_: String, val bld: String, val flr: String, val note: String) {
    companion object {
        private val KEYS = listOf("site", "case", "bld", "flr", "note")
        fun load(c: Context): WmData {
            val p = c.getSharedPreferences("wm", Context.MODE_PRIVATE)
            return WmData(p.getString("site", "中和安邦社宅")!!, p.getString("case", "")!!, p.getString("bld", "")!!, p.getString("flr", "")!!, p.getString("note", "")!!)
        }
        fun save(c: Context, key: String, v: String) = c.getSharedPreferences("wm", Context.MODE_PRIVATE).edit().putString(key, v).apply()
    }
}

object Names {
    val TZ: ZoneId = ZoneId.of("Asia/Taipei")
    fun now(): ZonedDateTime = ZonedDateTime.now(TZ)
    fun safe(s: String) = s.trim().replace(Regex("[\\\\/:*?\"<>|\\s]"), "-")
    fun base(t: ZonedDateTime, case_: String): String {
        val b = t.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return if (case_.isNotBlank()) b + "_" + safe(case_) else b
    }
}

/** 與 PWA 相同樣式的浮水印卡片 */
object Watermark {
    const val AC = 0xFFFFB020.toInt()
    private val WD = arrayOf("一", "二", "三", "四", "五", "六", "日")
    private fun tf(w: Int): Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, w, false)
        else if (w >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    private val T800 = tf(800); private val T700 = tf(700); private val T600 = tf(600); private val T500 = tf(500)
    private fun p(t: Typeface, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t; textSize = size; this.color = color }

    private fun wrap(pt: Paint, s: String, mw: Float): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); val ch = String(Character.toChars(cp))
            if (cur.isNotEmpty() && pt.measureText(cur.toString() + ch) > mw) { out.add(cur.toString()); cur.setLength(0) }
            cur.append(ch); i += Character.charCount(cp)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.ifEmpty { listOf("") }
    }

    /** 以照片/影像尺寸自動縮放 */
    fun stamp(c: Canvas, w: Float, h: Float, d: WmData, t: ZonedDateTime) {
        val m = minOf(w, h); draw(c, d, t, m * 0.03f, w * 0.03f, h - m * 0.03f, w * 0.94f)
    }

    fun draw(c: Canvas, d: WmData, t: ZonedDateTime, u: Float, left: Float, bottom: Float, maxW: Float) {
        val pad = u * 1.1f; val bar = u * 0.32f
        val site = d.site.trim()
        val rows = ArrayList<Triple<String, String, String>>()
        if (d.case_.isNotBlank()) rows.add(Triple("#", "案號", d.case_.trim()))
        val bf = listOf(d.bld.trim(), d.flr.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
        if (bf.isNotEmpty()) rows.add(Triple("樓", "棟別樓層", bf))
        if (d.note.isNotBlank()) rows.add(Triple("註", "備註", d.note.trim()))
        val hm = t.format(DateTimeFormatter.ofPattern("HH:mm")); val ss = t.format(DateTimeFormatter.ofPattern(":ss"))
        val dl = t.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "  星期" + WD[t.dayOfWeek.value - 1]
        val pHm = p(T800, 2.7f * u, Color.WHITE); val pSs = p(T600, 1.15f * u, 0xBFFFFFFF.toInt())
        val pD = p(T500, .95f * u, 0xD9FFFFFF.toInt()); val pSite = p(T700, .95f * u, AC)
        val pL = p(T500, .85f * u, 0x8CFFFFFF.toInt()); val pV = p(T600, .95f * u, Color.WHITE)
        val pI = p(T700, .75f * u, AC).apply { textAlign = Paint.Align.CENTER }
        val hmW = pHm.measureText(hm); val ssW = pSs.measureText(ss); val dW = pD.measureText(dl)
        val sW = if (site.isNotEmpty()) pSite.measureText(site) else 0f
        val lW = rows.maxOfOrNull { pL.measureText(it.second) } ?: 0f
        val ic = u * 1.35f; val vx = ic + u * .55f + lW + u * .8f
        val rW = rows.maxOfOrNull { pV.measureText(it.third) } ?: 0f
        val inner = minOf(maxW - pad * 2 - bar, maxOf(hmW + ssW + u * .3f, dW, sW, if (rows.isEmpty()) 0f else vx + rW))
        val lines = rows.map { wrap(pV, it.third, maxOf(u * 4, inner - vx)) }
        val lh = u * 1.5f
        val H = pad * 2 + u * 2.9f + u * 1.35f + (if (site.isNotEmpty()) u * 1.5f else 0f) +
                (if (rows.isEmpty()) 0f else u * 1.0f + lines.sumOf { it.size } * lh)
        val W = inner + pad * 2 + bar; val X = left; val Y = bottom - H
        val rect = RectF(X, Y, X + W, Y + H); val r = u * .9f
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30000000; maskFilter = BlurMaskFilter(u * 1.2f, BlurMaskFilter.Blur.OUTER) })
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA80E1016.toInt() })
        c.save(); c.clipPath(Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) })
        c.drawRect(X, Y, X + bar, Y + H, Paint().apply { color = AC }); c.restore()
        val cx = X + bar + pad; var cy = Y + pad
        if (site.isNotEmpty()) { c.drawText(site, cx, cy + u * .95f, pSite); cy += u * 1.5f }
        c.drawText(hm, cx, cy + u * 2.4f, pHm); c.drawText(ss, cx + hmW + u * .15f, cy + u * 2.4f, pSs); cy += u * 2.9f
        c.drawText(dl, cx, cy + u * .95f, pD); cy += u * 1.35f
        if (rows.isNotEmpty()) {
            c.drawRect(cx, cy + u * .45f, cx + inner, cy + u * .45f + maxOf(1f, u * .06f), Paint().apply { color = 0x29FFFFFF })
            cy += u * 1.0f
            val icBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFB020 }
            rows.forEachIndexed { i, row ->
                val yc = cy + lh / 2
                c.drawRoundRect(RectF(cx, yc - ic / 2, cx + ic, yc + ic / 2), u * .35f, u * .35f, icBg)
                c.drawText(row.first, cx + ic / 2, mid(pI, yc), pI)
                c.drawText(row.second, cx + ic + u * .55f, mid(pL, yc), pL)
                lines[i].forEachIndexed { j, l -> c.drawText(l, cx + vx, mid(pV, yc + j * lh), pV) }
                cy += lines[i].size * lh
            }
        }
    }
    private fun mid(p: Paint, yc: Float) = yc - (p.ascent() + p.descent()) / 2
}
