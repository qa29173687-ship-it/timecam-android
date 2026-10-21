package tw.timecam

import android.content.Context
import android.graphics.*
import android.location.Location
import android.os.Build
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 定位狀態（由 MainActivity 更新） */
object Geo {
    @Volatile var status = "定位中…"
    @Volatile var addr = ""
    @Volatile var addrStatus = ""
    @Volatile var loc: Location? = null
    fun coords(l: Location) = String.format(Locale.US, "%.5f, %.5f (±%dm)", l.latitude, l.longitude, l.accuracy.toInt())
}

data class WmData(
    val site: String, val case_: String, val bld: String, val flr: String, val note: String,
    val addr: String = "", val geo: String = "", val tpl: Int = 0, val pos: Int = 0, val size: Int = 1,
) {
    companion object {
        val TEMPLATES = listOf("標準卡片", "工程紀錄", "簡約", "大時間", "藍色商務")
        val POS = listOf("左下", "右下", "左上", "右上")
        val SIZES = listOf("小", "中", "大")
        fun prefs(c: Context) = c.getSharedPreferences("wm", Context.MODE_PRIVATE)
        fun load(c: Context): WmData {
            val p = prefs(c)
            val manual = p.getString("addr", "")!!.trim()
            val l = Geo.loc
            val addr = if (!p.getBoolean("showAddr", true)) "" else manual.ifBlank {
                Geo.addr.ifBlank { if (l != null) Geo.addrStatus.ifBlank { "地址查詢中…" } else Geo.status }
            }
            val geo = if (!p.getBoolean("showGeo", true)) "" else l?.let { Geo.coords(it) } ?: Geo.status
            return WmData(p.getString("site", "中和安邦社宅")!!, p.getString("case", "")!!, p.getString("bld", "")!!,
                p.getString("flr", "")!!, p.getString("note", "")!!, addr, geo,
                p.getInt("tpl", 0), p.getInt("pos", 0), p.getInt("size", 1))
        }
        fun save(c: Context, key: String, v: String) = prefs(c).edit().putString(key, v).apply()
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

/** 浮水印樣板（預覽、照片、錄影共用） */
object Watermark {
    const val AC = 0xFFFFB020.toInt()
    private val WD = arrayOf("一", "二", "三", "四", "五", "六", "日")
    private fun tf(w: Int): Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, w, false)
        else if (w >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    private val T800 by lazy { tf(800) }; private val T700 by lazy { tf(700) }
    private val T600 by lazy { tf(600) }; private val T500 by lazy { tf(500) }
    private fun p(t: Typeface, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t; textSize = size; this.color = color }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun mid(p: Paint, yc: Float) = yc - (p.ascent() + p.descent()) / 2

    data class Row(val icon: String, val label: String, val value: String)
    private fun rows(d: WmData): List<Row> {
        val r = ArrayList<Row>()
        if (d.case_.isNotBlank()) r.add(Row("#", "案號", d.case_.trim()))
        val bf = listOf(d.bld.trim(), d.flr.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
        if (bf.isNotEmpty()) r.add(Row("樓", "棟別樓層", bf))
        if (d.note.isNotBlank()) r.add(Row("註", "備註", d.note.trim()))
        if (d.addr.isNotBlank()) r.add(Row("址", "地址", d.addr.trim()))
        if (d.geo.isNotBlank()) r.add(Row("經", "經緯度", d.geo.trim()))
        return r
    }
    private fun hm(t: ZonedDateTime) = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    private fun hms(t: ZonedDateTime) = t.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    private fun ss(t: ZonedDateTime) = t.format(DateTimeFormatter.ofPattern(":ss"))
    private fun dl(t: ZonedDateTime) = t.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "  星期" + WD[t.dayOfWeek.value - 1]

    fun wrap(pt: Paint, s: String, mw: Float): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); val ch = String(Character.toChars(cp))
            if (cur.isNotEmpty() && pt.measureText(cur.toString() + ch) > mw) { out.add(cur.toString()); cur.setLength(0) }
            cur.append(ch); i += Character.charCount(cp)
        }
        if (cur.isNotEmpty()) out.add(cur.toString()); return out.ifEmpty { listOf("") }
    }

    /** 以照片／影像尺寸自動縮放 */
    fun stamp(c: Canvas, w: Float, h: Float, d: WmData, t: ZonedDateTime) {
        val m = minOf(w, h); render(c, d, t, m * 0.03f, RectF(0f, 0f, w, h), m * 0.03f)
    }

    /** 在 area 內依位置與大小放置樣板 */
    fun render(c: Canvas, d: WmData, t: ZonedDateTime, base: Float, area: RectF, margin: Float) {
        val u = base * floatArrayOf(0.8f, 1f, 1.3f)[d.size.coerceIn(0, 2)]
        val maxW = area.width() - margin * 2
        val tpl = d.tpl.coerceIn(0, 4)
        val s = draw(tpl, null, d, t, u, maxW)
        val x = if (d.pos == 1 || d.pos == 3) area.right - margin - s.x else area.left + margin
        val y = if (d.pos >= 2) area.top + margin else area.bottom - margin - s.y
        c.save(); c.translate(x, y); draw(tpl, c, d, t, u, maxW); c.restore()
    }

    private fun draw(tpl: Int, c: Canvas?, d: WmData, t: ZonedDateTime, u: Float, maxW: Float): PointF = when (tpl) {
        1 -> board(c, d, t, u, maxW); 2 -> minimal(c, d, t, u, maxW); 3 -> bigTime(c, d, t, u, maxW); 4 -> blue(c, d, t, u, maxW)
        else -> card(c, d, t, u, maxW)
    }

    // 0 標準卡片
    private fun card(c: Canvas?, d: WmData, t: ZonedDateTime, u: Float, maxW: Float): PointF {
        val pad = u * 1.1f; val bar = u * 0.32f; val site = d.site.trim(); val rows = rows(d)
        val hm = hm(t); val ss = ss(t); val dl = dl(t)
        val pHm = p(T800, 2.7f * u, Color.WHITE); val pSs = p(T600, 1.15f * u, 0xBFFFFFFF.toInt())
        val pD = p(T500, .95f * u, 0xD9FFFFFF.toInt()); val pSite = p(T700, .95f * u, AC)
        val pL = p(T500, .85f * u, 0x8CFFFFFF.toInt()); val pV = p(T600, .95f * u, Color.WHITE)
        val pI = p(T700, .75f * u, AC).apply { textAlign = Paint.Align.CENTER }
        val hmW = pHm.measureText(hm); val ssW = pSs.measureText(ss)
        val lW = rows.maxOfOrNull { pL.measureText(it.label) } ?: 0f
        val ic = u * 1.35f; val vx = ic + u * .55f + lW + u * .8f
        val rW = rows.maxOfOrNull { pV.measureText(it.value) } ?: 0f
        val inner = minOf(maxW - pad * 2 - bar, maxOf(hmW + ssW + u * .3f, pD.measureText(dl), if (site.isEmpty()) 0f else pSite.measureText(site), if (rows.isEmpty()) 0f else vx + rW))
        val lines = rows.map { wrap(pV, it.value, maxOf(u * 4, inner - vx)) }
        val lh = u * 1.5f
        val H = pad * 2 + u * 2.9f + u * 1.35f + (if (site.isNotEmpty()) u * 1.5f else 0f) + (if (rows.isEmpty()) 0f else u + lines.sumOf { it.size } * lh)
        val W = inner + pad * 2 + bar
        if (c == null) return PointF(W, H)
        val rect = RectF(0f, 0f, W, H); val r = u * .9f
        c.drawRoundRect(rect, r, r, fill(0xA80E1016.toInt()))
        c.drawPath(clipped(rect, r, RectF(0f, 0f, bar, H)), fill(AC))
        val cx = bar + pad; var cy = pad
        if (site.isNotEmpty()) { c.drawText(site, cx, cy + u * .95f, pSite); cy += u * 1.5f }
        c.drawText(hm, cx, cy + u * 2.4f, pHm); c.drawText(ss, cx + hmW + u * .15f, cy + u * 2.4f, pSs); cy += u * 2.9f
        c.drawText(dl, cx, cy + u * .95f, pD); cy += u * 1.35f
        if (rows.isNotEmpty()) {
            c.drawRect(cx, cy + u * .45f, cx + inner, cy + u * .45f + maxOf(1f, u * .06f), fill(0x29FFFFFF)); cy += u
            rows.forEachIndexed { i, row ->
                val yc = cy + lh / 2
                c.drawRoundRect(RectF(cx, yc - ic / 2, cx + ic, yc + ic / 2), u * .35f, u * .35f, fill(0x33FFB020))
                c.drawText(row.icon, cx + ic / 2, mid(pI, yc), pI); c.drawText(row.label, cx + ic + u * .55f, mid(pL, yc), pL)
                lines[i].forEachIndexed { j, l -> c.drawText(l, cx + vx, mid(pV, yc + j * lh), pV) }
                cy += lines[i].size * lh
            }
        }
        return PointF(W, H)
    }

    // 1 工程紀錄（表格式）
    private fun board(c: Canvas?, d: WmData, t: ZonedDateTime, u: Float, maxW: Float): PointF {
        val green = 0xFF1E6B3A.toInt()
        val pH = p(T700, 1.05f * u, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        val pL = p(T700, .85f * u, 0xFF2F5D3F.toInt()); val pV = p(T600, .9f * u, 0xFF1A1A1A.toInt())
        val all = listOf(Row("", "日期", dl(t)), Row("", "時間", hms(t))) + rows(d).map { Row("", it.label, it.value) }
        val padX = u * .8f; val hh = u * 2.0f; val lh = u * 1.35f
        val lw = (all.maxOf { pL.measureText(it.label) }) + padX * 2
        val title = d.site.trim().ifEmpty { "工程紀錄" }
        val vwNeed = maxOf(all.maxOf { pV.measureText(it.value) } + padX * 2, pH.measureText(title) + padX * 2 - lw)
        val vw = minOf(maxW - lw, vwNeed)
        val lines = all.map { wrap(pV, it.value, maxOf(u * 4, vw - padX * 2)) }
        val heights = lines.map { it.size * lh + u * .55f }
        val W = lw + vw; val H = hh + heights.sum()
        if (c == null) return PointF(W, H)
        val line = fill(0xFF7A8A7F.toInt()).apply { strokeWidth = maxOf(1f, u * .08f) }
        c.drawRect(0f, 0f, W, hh, fill(green)); c.drawText(title, W / 2, mid(pH, hh / 2), pH)
        var y = hh
        all.forEachIndexed { i, r ->
            val h = heights[i]
            c.drawRect(0f, y, lw, y + h, fill(0xF2EAF2EC.toInt())); c.drawRect(lw, y, W, y + h, fill(0xF2FFFFFF.toInt()))
            c.drawText(r.label, padX, mid(pL, y + h / 2), pL)
            lines[i].forEachIndexed { j, l -> c.drawText(l, lw + padX, mid(pV, y + u * .275f + lh / 2 + j * lh), pV) }
            c.drawLine(0f, y, W, y, line); y += h
        }
        c.drawLine(lw, hh, lw, H, line)
        c.drawRect(0f, 0f, W, H, Paint(line).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1.5f, u * .14f); color = green })
        return PointF(W, H)
    }

    private fun clipped(rr: RectF, r: Float, part: RectF): Path {
        val a = Path().apply { addRoundRect(rr, r, r, Path.Direction.CW) }; val b = Path().apply { addRect(part, Path.Direction.CW) }
        a.op(b, Path.Op.INTERSECT); return a
    }

    private fun shadow(pt: Paint, u: Float) = pt.apply { setShadowLayer(u * .3f, 0f, u * .08f, 0xD9000000.toInt()) }

    // 2 簡約（無底框）
    private fun minimal(c: Canvas?, d: WmData, t: ZonedDateTime, u: Float, maxW: Float): PointF {
        val pT = shadow(p(T800, 1.7f * u, Color.WHITE), u); val pD = shadow(p(T600, 1.0f * u, Color.WHITE), u)
        val pS = shadow(p(T700, .95f * u, AC), u); val pR = shadow(p(T500, .9f * u, Color.WHITE), u)
        val tW = pT.measureText(hms(t)); val first = tW + u * .7f + pD.measureText(dl(t))
        val texts = rows(d).map { "${it.label}　${it.value}" }
        val wrapped = texts.flatMap { wrap(pR, it, maxW) }
        val site = d.site.trim()
        val W = minOf(maxW, maxOf(first, if (site.isEmpty()) 0f else pS.measureText(site), wrapped.maxOfOrNull { pR.measureText(it) } ?: 0f))
        val H = u * 2.1f + (if (site.isEmpty()) 0f else u * 1.4f) + wrapped.size * u * 1.35f
        if (c == null) return PointF(W, H)
        var y = u * 1.6f
        c.drawText(hms(t), 0f, y, pT); c.drawText(dl(t), tW + u * .7f, y, pD); y += u * .5f
        if (site.isNotEmpty()) { y += u * 1.2f; c.drawText(site, 0f, y, pS); y += u * .2f }
        wrapped.forEach { y += u * 1.35f; c.drawText(it, 0f, y - u * .25f, pR) }
        return PointF(W, H)
    }

    // 3 大時間
    private fun bigTime(c: Canvas?, d: WmData, t: ZonedDateTime, u: Float, maxW: Float): PointF {
        val yellow = 0xFFFFD02A.toInt()
        val pT = shadow(p(T800, 4.6f * u, Color.WHITE), u); val pD = shadow(p(T600, 1.05f * u, Color.WHITE), u)
        val pS = shadow(p(T700, 1.0f * u, yellow), u); val pR = p(T500, .88f * u, Color.WHITE)
        val hm = hm(t); val tW = pT.measureText(hm); val gap = u * .7f; val bar = u * .28f
        val date = t.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")); val wk = "星期" + WD[t.dayOfWeek.value - 1] + "  " + ss(t).drop(1) + "秒"
        val site = d.site.trim()
        val rightW = maxOf(pD.measureText(date), pD.measureText(wk), if (site.isEmpty()) 0f else pS.measureText(site))
        val topW = tW + gap + bar + gap + rightW; val topH = u * 4.4f
        val padR = u * .6f
        val rws = rows(d).map { "${it.label}：${it.value}" }
        val innerMax = maxW - padR * 2
        val lines = rws.flatMap { wrap(pR, it, innerMax) }
        val rowsW = (lines.maxOfOrNull { pR.measureText(it) } ?: 0f) + padR * 2
        val W = minOf(maxW, maxOf(topW, rowsW)); val boxH = if (lines.isEmpty()) 0f else lines.size * u * 1.3f + padR * 1.2f
        val H = topH + (if (lines.isEmpty()) 0f else u * .5f + boxH)
        if (c == null) return PointF(W, H)
        c.drawText(hm, 0f, u * 3.9f, pT)
        val bx = tW + gap; c.drawRect(bx, u * .5f, bx + bar, u * 4.1f, fill(yellow))
        val rx = bx + bar + gap
        c.drawText(date, rx, u * 1.5f, pD); c.drawText(wk, rx, u * 2.75f, pD); if (site.isNotEmpty()) c.drawText(site, rx, u * 4.0f, pS)
        if (lines.isNotEmpty()) {
            val top = topH + u * .5f
            c.drawRoundRect(RectF(0f, top, W, top + boxH), u * .5f, u * .5f, fill(0x73000000))
            lines.forEachIndexed { i, l -> c.drawText(l, padR, mid(pR, top + padR * .6f + u * .65f + i * u * 1.3f), pR) }
        }
        return PointF(W, H)
    }

    // 4 藍色商務
    private fun blue(c: Canvas?, d: WmData, t: ZonedDateTime, u: Float, maxW: Float): PointF {
        val blue = 0xFF1F5FBF.toInt(); val dark = 0xFF12305E.toInt()
        val pH = p(T700, 1.0f * u, Color.WHITE); val pHt = p(T800, 1.35f * u, Color.WHITE)
        val pDt = p(T700, .95f * u, dark); val pL = p(T500, .8f * u, 0xFF6B7A90.toInt()); val pV = p(T600, .9f * u, 0xFF1B2433.toInt())
        val pad = u * 1.0f; val hh = u * 2.3f; val site = d.site.trim().ifEmpty { "時間相機" }
        val hmS = hm(t); val dt = dl(t) + "   " + hms(t)
        val rows = rows(d); val lw = rows.maxOfOrNull { pL.measureText(it.label) } ?: 0f; val vx = u * .9f + lw + u * .8f
        val need = maxOf(pH.measureText(site) + u * 1.5f + pHt.measureText(hmS), pDt.measureText(dt), if (rows.isEmpty()) 0f else vx + rows.maxOf { pV.measureText(it.value) })
        val inner = minOf(maxW - pad * 2, need)
        val lines = rows.map { wrap(pV, it.value, maxOf(u * 4, inner - vx)) }
        val lh = u * 1.4f
        val W = inner + pad * 2; val H = hh + pad + u * 1.3f + (if (rows.isEmpty()) 0f else u * .6f + lines.sumOf { it.size } * lh) + pad * .8f
        if (c == null) return PointF(W, H)
        val r = u * .7f; val rect = RectF(0f, 0f, W, H)
        c.drawRoundRect(rect, r, r, fill(0x40000000).apply { maskFilter = BlurMaskFilter(u, BlurMaskFilter.Blur.OUTER) })
        c.drawRoundRect(rect, r, r, fill(0xF5FFFFFF.toInt()))
        c.drawPath(clipped(rect, r, RectF(0f, 0f, W, hh)), fill(blue))
        c.drawText(site, pad, mid(pH, hh / 2), pH)
        pHt.textAlign = Paint.Align.RIGHT; c.drawText(hmS, W - pad, mid(pHt, hh / 2), pHt)
        var y = hh + pad; c.drawText(dt, pad, mid(pDt, y + u * .55f), pDt); y += u * 1.3f
        if (rows.isNotEmpty()) {
            c.drawRect(pad, y + u * .25f, W - pad, y + u * .25f + maxOf(1f, u * .05f), fill(0x221F5FBF)); y += u * .6f
            rows.forEachIndexed { i, row ->
                val yc = y + lh / 2
                c.drawCircle(pad + u * .25f, yc, u * .22f, fill(blue))
                c.drawText(row.label, pad + u * .9f, mid(pL, yc), pL)
                lines[i].forEachIndexed { j, l -> c.drawText(l, pad + vx, mid(pV, yc + j * lh), pV) }
                y += lines[i].size * lh
            }
        }
        return PointF(W, H)
    }

    /** 樣板縮圖 */
    fun thumbnail(d: WmData, tpl: Int, w: Int, h: Int, t: ZonedDateTime = Names.now()): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF7A8FA3.toInt(), 0xFF3C4650.toInt(), Shader.TileMode.CLAMP) })
        c.drawRect(0f, h * .62f, w.toFloat(), h.toFloat(), fill(0x33000000))
        stamp(c, w.toFloat(), h.toFloat(), d.copy(tpl = tpl, size = 1), t); return b
    }
}
