package tw.timecam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** 使用者選的靜態照片輸出（「最高」或指定寬高） */
data class StillChoice(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val isMax: Boolean,
) {
    val pixels: Long get() = width.toLong() * height
}

/** 從相機支援的 JPEG 尺寸整理成短清單，並讀寫設定 */
object StillSizes {
    const val PREF_KEY = "photoRes"
    const val ID_MAX = "max"
    const val DEFAULT_PIXELS = ImageMath.MAX_STILL_PIXELS
    const val MIN_LIST_PIXELS = 3_000_000L

    fun pref(c: Context): String? = WmData.prefs(c).getString(PREF_KEY, null)
    fun save(c: Context, id: String) { WmData.prefs(c).edit().putString(PREF_KEY, id).apply() }

    fun megapixels(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 0
        return round(w.toLong() * h / 1_000_000.0).toInt().coerceAtLeast(1)
    }

    fun labelFor(w: Int, h: Int): String = "約 ${megapixels(w, h)}MP（${w}×${h}）"

    /** 清單或摘要列用：最高會附上約略畫素 */
    fun summary(choice: StillChoice): String {
        if (!choice.isMax) return choice.label
        val mp = megapixels(choice.width, choice.height)
        return if (mp > 0) "最高（約 ${mp}MP）" else "最高"
    }

    fun shortList(raw: List<Pair<Int, Int>>): List<StillChoice> {
        val unique = raw.filter { it.first > 0 && it.second > 0 }
            .distinct()
            .sortedWith(
                compareByDescending<Pair<Int, Int>> { it.first.toLong() * it.second }
                    .thenBy { aspectPenalty(it.first, it.second) }
            )
        if (unique.isEmpty()) {
            return listOf(
                StillChoice(ID_MAX, "最高", 0, 0, true),
                StillChoice("4000x3000", labelFor(4000, 3000), 4000, 3000, false),
            )
        }
        val max = unique.first()
        val candidates = unique.filter { it.first.toLong() * it.second >= MIN_LIST_PIXELS }.ifEmpty { listOf(max) }
        val kept = mutableListOf<Pair<Int, Int>>()
        for (s in candidates) {
            val prev = kept.lastOrNull()
            if (prev == null) { kept.add(s); continue }
            if (similar(prev, s)) {
                if (aspectPenalty(s.first, s.second) + 1e-6 < aspectPenalty(prev.first, prev.second)) kept[kept.lastIndex] = s
                continue
            }
            kept.add(s)
        }
        val hasNear12 = kept.any { val p = it.first.toLong() * it.second; p in 8_000_000L..16_000_000L }
        if (!hasNear12) {
            val closest = unique.minByOrNull { abs(it.first.toLong() * it.second - DEFAULT_PIXELS) }
            if (closest != null && closest !in kept && closest.first.toLong() * closest.second >= MIN_LIST_PIXELS) {
                kept.add(closest)
                kept.sortByDescending { it.first.toLong() * it.second }
            }
        }
        val list = mutableListOf(StillChoice(ID_MAX, "最高", max.first, max.second, true))
        for (s in kept) list.add(StillChoice("${s.first}x${s.second}", labelFor(s.first, s.second), s.first, s.second, false))
        return list
    }

    fun defaultChoice(choices: List<StillChoice>): StillChoice {
        val concrete = choices.filter { !it.isMax }
        if (concrete.isEmpty()) return choices.first()
        return concrete.minBy { abs(it.pixels - DEFAULT_PIXELS) }
    }

    fun resolve(pref: String?, choices: List<StillChoice>): StillChoice {
        if (choices.isEmpty()) return StillChoice(ID_MAX, "最高", 0, 0, true)
        if (pref == ID_MAX) return choices.first { it.isMax }
        if (!pref.isNullOrBlank()) {
            choices.find { !it.isMax && it.id == pref }?.let { return it }
            val m = Regex("""^(\d+)x(\d+)$""").matchEntire(pref.trim())
            if (m != null) {
                val target = m.groupValues[1].toLong() * m.groupValues[2].toLong()
                val concrete = choices.filter { !it.isMax }
                if (concrete.isNotEmpty()) return concrete.minBy { abs(it.pixels - target) }
            }
        }
        return defaultChoice(choices)
    }

    fun capPixels(choice: StillChoice): Long = if (choice.isMax || choice.pixels <= 0) Long.MAX_VALUE / 8 else choice.pixels
    fun capLongEdge(choice: StillChoice): Int = if (choice.isMax || choice.width <= 0) Int.MAX_VALUE / 4 else max(choice.width, choice.height)

    private fun similar(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean {
        val pa = a.first.toLong() * a.second
        val pb = b.first.toLong() * b.second
        val ratio = min(pa, pb).toDouble() / max(pa, pb)
        return ratio >= 0.80
    }

    /** 0 表示正好 4:3 或 3:4（照片預設比例） */
    fun aspectPenalty(w: Int, h: Int): Double {
        if (h == 0) return 999.0
        val r = w.toDouble() / h
        return min(abs(r - 4.0 / 3), abs(r - 3.0 / 4))
    }

    /** 後鏡頭（或指定朝向）的 JPEG 輸出尺寸，含高解析模式 */
    fun querySizes(context: Context, lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK): List<Pair<Int, Int>> {
        return try {
            val chars = characteristics(context, lensFacing) ?: return emptyList()
            jpegSizes(chars)
        } catch (_: Exception) { emptyList() }
    }

    fun choices(context: Context, lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK, info: CameraInfo? = null): List<StillChoice> {
        val raw = (querySizes(context, lensFacing) + jpegSizesFromInfo(info)).distinct()
        return shortList(raw)
    }

    fun jpegSizes(chars: CameraCharacteristics): List<Pair<Int, Int>> {
        val out = LinkedHashSet<Pair<Int, Int>>()
        fun add(map: StreamConfigurationMap?) {
            if (map == null) return
            map.getOutputSizes(ImageFormat.JPEG)?.forEach { out.add(it.width to it.height) }
            runCatching { map.getHighResolutionOutputSizes(ImageFormat.JPEG) }.getOrNull()?.forEach { out.add(it.width to it.height) }
        }
        add(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        if (Build.VERSION.SDK_INT >= 31) {
            add(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION))
        }
        return out.toList()
    }

    private fun characteristics(context: Context, lensFacing: Int): CameraCharacteristics? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var fallback: CameraCharacteristics? = null
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            if (fallback == null) fallback = chars
            if (chars.get(CameraCharacteristics.LENS_FACING) == lensFacing) return chars
        }
        return fallback
    }

    /** 已綁定的 CameraX 鏡頭，經 Camera2CameraInfo 讀同一組 JPEG 尺寸（含高解析） */
    @OptIn(ExperimentalCamera2Interop::class)
    fun jpegSizesFromInfo(info: CameraInfo?): List<Pair<Int, Int>> {
        if (info == null) return emptyList()
        return try {
            val c2 = Camera2CameraInfo.from(info)
            val out = LinkedHashSet<Pair<Int, Int>>()
            fun add(map: StreamConfigurationMap?) {
                if (map == null) return
                map.getOutputSizes(ImageFormat.JPEG)?.forEach { out.add(it.width to it.height) }
                runCatching { map.getHighResolutionOutputSizes(ImageFormat.JPEG) }.getOrNull()?.forEach { out.add(it.width to it.height) }
            }
            add(c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            if (Build.VERSION.SDK_INT >= 31) {
                add(c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION))
            }
            out.toList()
        } catch (_: Exception) { emptyList() }
    }
}
