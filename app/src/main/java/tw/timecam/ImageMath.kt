package tw.timecam

/** 純計算（可單元測試）：EXIF 方向、取樣、取景框 */
object ImageMath {
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
}
