package tw.timecam

/** 純計算（可單元測試）：EXIF 方向、取樣、取景框、輸出上限 */
object ImageMath {
    /** 靜態照片輸出上限：約 1,200 萬畫素、長邊約 4,000 */
    const val MAX_STILL_PIXELS = 12_000_000L
    const val MAX_STILL_LONG_EDGE = 4000
    const val THUMB_PIXELS = 200_000L

    data class Orient(val degrees: Int, val flipH: Boolean)
    /** EXIF orientation 1..8 → 旋轉角度與是否水平翻轉（翻轉先於旋轉） */
    fun orient(o: Int): Orient = when (o) {
        2 -> Orient(0, true); 3 -> Orient(180, false); 4 -> Orient(180, true)
        5 -> Orient(90, true); 6 -> Orient(90, false); 7 -> Orient(270, true); 8 -> Orient(270, false)
        else -> Orient(0, false)
    }
    fun rotatedSize(w: Int, h: Int, deg: Int): Pair<Int, Int> = if (((deg % 360) + 360) % 180 == 90) h to w else w to h
    /** 最小的 2 的次方取樣率，使像素數 ≤ maxPixels */
    fun sampleFor(w: Int, h: Int, maxPixels: Long): Int { var s = 1; while ((w.toLong() / s) * (h.toLong() / s) > maxPixels) s *= 2; return s }
    /**
     * 解碼用取樣：若嚴格 [sampleFor] 會一下跳到遠低於目標（例如 12.5MP → 3MP），
     * 改用前一檔，讓後續 [capSize] 精準縮到上限。memoryPixels 防止前一檔超出可用記憶體。
     */
    fun sampleForDecode(w: Int, h: Int, targetPixels: Long, memoryPixels: Long): Int {
        val s = sampleFor(w, h, targetPixels)
        if (s <= 1) return 1
        val prev = s / 2
        val prevPx = (w.toLong() / prev) * (h.toLong() / prev)
        return if (prevPx <= memoryPixels) prev else s
    }
    /** BitmapFactory 以 inSampleSize 解碼後的預期尺寸（允許 ±1 誤差） */
    fun decodedOk(srcW: Int, srcH: Int, sample: Int, gotW: Int, gotH: Int): Boolean {
        val ew = srcW / sample.toDouble(); val eh = srcH / sample.toDouble()
        return kotlin.math.abs(gotW - ew) <= 1.01 && kotlin.math.abs(gotH - eh) <= 1.01
    }
    /** 以 FIT 方式把 aspect(寬/高) 的影像放進 (vw,vh)，回傳 left, top, width, height */
    fun fit(vw: Float, vh: Float, aspect: Float): FloatArray {
        return if (vw / vh > aspect) { val w = vh * aspect; floatArrayOf((vw - w) / 2, 0f, w, vh) }
        else { val h = vw / aspect; floatArrayOf(0f, (vh - h) / 2, vw, h) }
    }
    /** JPEG 是否完整（結尾附近有 FFD9） */
    fun jpegComplete(tail: ByteArray): Boolean {
        for (i in tail.size - 2 downTo 0) if (tail[i] == 0xFF.toByte() && tail[i + 1] == 0xD9.toByte()) return true
        return false
    }
    fun needsCap(w: Int, h: Int, maxLong: Int = MAX_STILL_LONG_EDGE, maxPixels: Long = MAX_STILL_PIXELS): Boolean {
        if (w <= 0 || h <= 0) return false
        return maxOf(w, h) > maxLong || w.toLong() * h > maxPixels
    }
    /** 縮到長邊與像素上限之內；已符合則原寸回傳 */
    fun capSize(w: Int, h: Int, maxLong: Int = MAX_STILL_LONG_EDGE, maxPixels: Long = MAX_STILL_PIXELS): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        val longEdge = maxOf(w, h)
        val pixels = w.toLong() * h
        var scale = 1.0
        if (longEdge > maxLong) scale = kotlin.math.min(scale, maxLong.toDouble() / longEdge)
        if (pixels > maxPixels) scale = kotlin.math.min(scale, kotlin.math.sqrt(maxPixels.toDouble() / pixels))
        if (scale >= 1.0) return w to h
        val nw = kotlin.math.round(w * scale).toInt().coerceAtLeast(1)
        val nh = kotlin.math.round(h * scale).toInt().coerceAtLeast(1)
        return nw to nh
    }
    /** 縮圖目標尺寸（約 maxPixels 點） */
    fun thumbSize(w: Int, h: Int, maxPixels: Long = THUMB_PIXELS): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1 to 1
        val pixels = w.toLong() * h
        if (pixels <= maxPixels) return w to h
        val s = kotlin.math.sqrt(maxPixels.toDouble() / pixels)
        return kotlin.math.round(w * s).toInt().coerceAtLeast(1) to kotlin.math.round(h * s).toInt().coerceAtLeast(1)
    }
}
