package tw.timecam

import org.junit.Assert.*
import org.junit.Test
import tw.timecam.upload.UploadTimes
import java.time.ZoneId
import java.time.ZonedDateTime

class AddrAndUploadTest {
    @Test fun cleanAddrKeepsPostalCode() {
        assertEquals("235新北市中和區安邦巷5弄6號", Geo.cleanAddr("235新北市中和區安邦巷5弄6號"))
        assertEquals("235新北市中和區安邦巷5弄6號", Geo.cleanAddr("台灣235新北市中和區安邦巷5弄6號"))
        assertEquals("235新北市中和區安邦巷5弄6號", Geo.cleanAddr("臺灣 235新北市中和區安邦巷5弄6號"))
        assertEquals("新北市中和區連城路1號", Geo.cleanAddr("  新北市中和區連城路1號  "))
    }

    @Test fun parseAndFormatTime() {
        assertEquals(23 to 0, UploadTimes.parseHm("23:00"))
        assertEquals(7 to 5, UploadTimes.parseHm("7:05"))
        assertEquals("23:00", UploadTimes.formatHm(23, 0))
        assertEquals("07:05", UploadTimes.formatHm(7, 5))
        assertEquals(23 to 0, UploadTimes.parseHm("bad"))
    }

    @Test fun delayToNextIsTomorrowIfAlreadyPassed() {
        val tz = ZoneId.of("Asia/Taipei")
        // 2026-11-22 23:30 → next 23:00 is 2026-11-23 23:00
        val now = ZonedDateTime.of(2026, 11, 22, 23, 30, 0, 0, tz).toInstant().toEpochMilli()
        val delay = UploadTimes.delayToNext(now, "23:00", tz)
        val next = UploadTimes.nextAt(now, "23:00", tz)
        assertEquals(2026, next.year); assertEquals(11, next.monthValue); assertEquals(23, next.dayOfMonth)
        assertEquals(23, next.hour); assertEquals(0, next.minute)
        assertEquals(delay, next.toInstant().toEpochMilli() - now)
        assertTrue(delay > 0)
    }

    @Test fun delayToNextSameDayIfBefore() {
        val tz = ZoneId.of("Asia/Taipei")
        val now = ZonedDateTime.of(2026, 11, 22, 10, 0, 0, 0, tz).toInstant().toEpochMilli()
        val next = UploadTimes.nextAt(now, "23:00", tz)
        assertEquals(22, next.dayOfMonth)
        assertEquals(23, next.hour)
        val hours = UploadTimes.delayToNext(now, "23:00", tz) / 3_600_000.0
        assertEquals(13.0, hours, 0.01)
    }
}
