package tw.timecam
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime
class NamesTest {
    private val t = ZonedDateTime.of(2026, 10, 21, 10, 14, 2, 0, Names.TZ)
    @Test fun withCase() = assertEquals("20261021_101402_A-1023", Names.base(t, "A-1023"))
    @Test fun noCase() = assertEquals("20261021_101402", Names.base(t, " "))
    @Test fun sanitize() = assertEquals("A-1-2-3", Names.safe("A/1 2:3"))
}
