package tw.timecam.upload

import android.content.Context
import tw.timecam.Names
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 自動上傳設定（SharedPreferences） */
class UploadPrefs(c: Context) {
    private val p = c.applicationContext.getSharedPreferences("upload", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) { p.edit().putBoolean("enabled", v).apply() }
    /** immediate | daily */
    var whenMode: String
        get() = p.getString("when", "immediate")!!
        set(v) { p.edit().putString("when", v).apply() }
    var time: String
        get() = p.getString("time", "23:00")!!
        set(v) { p.edit().putString("time", v).apply() }
    var wifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", true)
        set(v) { p.edit().putBoolean("wifiOnly", v).apply() }
    var chargingOnly: Boolean
        get() = p.getBoolean("chargingOnly", false)
        set(v) { p.edit().putBoolean("chargingOnly", v).apply() }
    var folder: String
        get() = p.getString("folder", "時間相機")!!.ifBlank { "時間相機" }
        set(v) { p.edit().putString("folder", v.ifBlank { "時間相機" }).apply() }
    var folderId: String
        get() = p.getString("folderId", "")!!
        set(v) { p.edit().putString("folderId", v).apply() }
    var accountEmail: String
        get() = p.getString("accountEmail", "")!!
        set(v) { p.edit().putString("accountEmail", v).apply() }
    var granted: Boolean
        get() = p.getBoolean("granted", false)
        set(v) { p.edit().putBoolean("granted", v).apply() }
    var lastUploadAt: Long
        get() = p.getLong("lastUploadAt", 0L)
        set(v) { p.edit().putLong("lastUploadAt", v).apply() }
    var lastError: String
        get() = p.getString("lastError", "")!!
        set(v) { p.edit().putString("lastError", v).apply() }

    fun clearAccount() {
        p.edit().remove("accountEmail").remove("granted").remove("folderId").apply()
    }

    fun lastUploadLabel(): String {
        if (lastUploadAt <= 0) return "尚未上傳"
        val t = Instant.ofEpochMilli(lastUploadAt).atZone(Names.TZ)
        return "上次上傳 " + t.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
    }
}

object UploadTimes {
    fun parseHm(hhmm: String): Pair<Int, Int> {
        val p = hhmm.trim().split(":")
        val h = p.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 23
        val m = p.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return h to m
    }
    fun formatHm(h: Int, m: Int) = "%02d:%02d".format(h, m)
    /** 距離下一次「每天指定時間」的毫秒數（已過則推到明天） */
    fun delayToNext(nowMs: Long, hhmm: String, tz: ZoneId = Names.TZ): Long {
        val (h, m) = parseHm(hhmm)
        val now = Instant.ofEpochMilli(nowMs).atZone(tz)
        var t = now.withHour(h).withMinute(m).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli() - nowMs
    }
    fun nextAt(nowMs: Long, hhmm: String, tz: ZoneId = Names.TZ): ZonedDateTime {
        val (h, m) = parseHm(hhmm)
        val now = Instant.ofEpochMilli(nowMs).atZone(tz)
        var t = now.withHour(h).withMinute(m).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t
    }
}
