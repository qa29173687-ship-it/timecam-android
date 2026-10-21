package tw.timecam
import org.junit.Assert.*
import org.junit.Test
class ImageMathTest {
    @Test fun orientations() {
        assertEquals(ImageMath.Orient(90, false), ImageMath.orient(6)); assertEquals(ImageMath.Orient(270, false), ImageMath.orient(8))
        assertEquals(ImageMath.Orient(180, false), ImageMath.orient(3)); assertEquals(ImageMath.Orient(0, false), ImageMath.orient(1))
        assertEquals(ImageMath.Orient(0, false), ImageMath.orient(0))
    }
    @Test fun rotated() {
        assertEquals(3000 to 4000, ImageMath.rotatedSize(4000, 3000, 90)); assertEquals(4000 to 3000, ImageMath.rotatedSize(4000, 3000, 180))
        assertEquals(3000 to 4000, ImageMath.rotatedSize(4000, 3000, 270)); assertEquals(3000 to 4000, ImageMath.rotatedSize(4000, 3000, -90))
    }
    @Test fun sample() {
        assertEquals(1, ImageMath.sampleFor(4000, 3000, 24_000_000)); assertEquals(2, ImageMath.sampleFor(8160, 6120, 24_000_000))
        assertEquals(1, ImageMath.sampleFor(5000, 4800, 24_000_000))
    }
    @Test fun decoded() {
        assertTrue(ImageMath.decodedOk(8161, 6121, 2, 4081, 3061)); assertTrue(ImageMath.decodedOk(4000, 3000, 1, 4000, 3000))
        assertFalse(ImageMath.decodedOk(4000, 3000, 1, 4000, 1500))
    }
    @Test fun fitRect() {
        val r = ImageMath.fit(1080f, 1500f, 3f / 4f); assertEquals(0f, r[0], .01f); assertEquals(1440f, r[3], .01f); assertEquals(30f, r[1], .01f)
        val v = ImageMath.fit(1080f, 1500f, 9f / 16f); assertEquals(1500f, v[3], .01f); assertEquals(843.75f, v[2], .01f)
    }
    @Test fun jpegEnd() {
        assertTrue(ImageMath.jpegComplete(byteArrayOf(1, 2, 0xFF.toByte(), 0xD9.toByte()))); assertTrue(ImageMath.jpegComplete(byteArrayOf(0xFF.toByte(), 0xD9.toByte(), 0, 0)))
        assertFalse(ImageMath.jpegComplete(byteArrayOf(1, 2, 3, 4)))
    }
}
