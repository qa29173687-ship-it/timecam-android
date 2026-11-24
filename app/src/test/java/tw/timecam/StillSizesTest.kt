package tw.timecam
import org.junit.Assert.*
import org.junit.Test

class StillSizesTest {
    private val flagship = listOf(
        8192 to 6144, 8000 to 6000, 4096 to 3072, 4000 to 3000,
        3840 to 2160, 3264 to 2448, 1920 to 1440, 1280 to 960, 640 to 480,
    )

    @Test fun shortListHasMaxAndMegapixelTiers() {
        val list = StillSizes.shortList(flagship)
        assertEquals(StillSizes.ID_MAX, list.first().id)
        assertTrue(list.first().isMax)
        assertEquals(8192, list.first().width)
        assertEquals(6144, list.first().height)
        assertEquals("最高（約 50MP）", StillSizes.summary(list.first()))
        val ids = list.filter { !it.isMax }.map { it.id }
        assertTrue("50MP size kept", "8192x6144" in ids)
        assertTrue("near-12MP kept", ids.any { it == "4096x3072" || it == "4000x3000" })
        assertFalse("tiny sizes omitted", ids.any { it.startsWith("1920") || it.startsWith("640") })
        // 48MP merges into 50MP; 16:9 8MP yields to 4:3
        assertFalse("8000x6000" in ids)
        assertTrue(list.size in 3..8)
    }

    @Test fun prefersFourByThreeWhenMegapixelsSimilar() {
        val list = StillSizes.shortList(listOf(3840 to 2160, 3264 to 2448, 4000 to 3000))
        val concrete = list.filter { !it.isMax }
        assertTrue(concrete.any { it.id == "4000x3000" })
        val eight = concrete.filter { it.pixels in 7_000_000L..9_000_000L }
        assertEquals(1, eight.size)
        assertEquals("3264x2448", eight.single().id)
    }

    @Test fun labelsUseAboutMpAndTimes() {
        assertEquals("約 12MP（4000×3000）", StillSizes.labelFor(4000, 3000))
        assertEquals("約 50MP（8192×6144）", StillSizes.labelFor(8192, 6144))
        assertEquals(12, StillSizes.megapixels(4032, 3024))
        assertEquals(8, StillSizes.megapixels(3840, 2160))
    }

    @Test fun defaultIsAboutTwelveMegapixels() {
        val list = StillSizes.shortList(flagship)
        val d = StillSizes.defaultChoice(list)
        assertFalse(d.isMax)
        assertTrue(d.pixels in 8_000_000L..16_000_000L)
        assertEquals(d.id, StillSizes.resolve(null, list).id)
        assertEquals(StillSizes.ID_MAX, StillSizes.resolve("max", list).id)
        assertTrue(StillSizes.resolve("max", list).isMax)
        assertEquals("4096x3072", StillSizes.resolve("4096x3072", list).id)
        // unknown size picks closest
        val near = StillSizes.resolve("4100x3070", list)
        assertEquals("4096x3072", near.id)
    }

    @Test fun emptyFallsBackToTwelve() {
        val list = StillSizes.shortList(emptyList())
        assertEquals(2, list.size)
        assertTrue(list[0].isMax)
        assertEquals("4000x3000", list[1].id)
        assertEquals("4000x3000", StillSizes.resolve(null, list).id)
    }

    @Test fun capUnlimitedWhenMax() {
        val max = StillChoice("max", "最高", 8192, 6144, true)
        val twelve = StillChoice("4000x3000", "約 12MP（4000×3000）", 4000, 3000, false)
        assertTrue(StillSizes.capPixels(max) > 100_000_000)
        assertEquals(12_000_000L, StillSizes.capPixels(twelve))
        assertEquals(4000, StillSizes.capLongEdge(twelve))
        assertTrue(StillSizes.capLongEdge(max) > 10_000)
        assertFalse(ImageMath.needsCap(8192, 6144, StillSizes.capLongEdge(max), StillSizes.capPixels(max)))
        assertTrue(ImageMath.needsCap(8192, 6144, StillSizes.capLongEdge(twelve), StillSizes.capPixels(twelve)))
        assertEquals(4000 to 3000, ImageMath.capSize(8192, 6144, StillSizes.capLongEdge(twelve), StillSizes.capPixels(twelve)))
    }

    @Test fun dedupesIdenticalSizes() {
        val list = StillSizes.shortList(listOf(4000 to 3000, 4000 to 3000, 3000 to 4000))
        val concrete = list.filter { !it.isMax }
        assertEquals(1, concrete.size)
        assertEquals(12_000_000L, concrete.single().pixels)
    }
}
