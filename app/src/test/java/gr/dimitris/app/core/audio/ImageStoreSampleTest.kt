package gr.dimitris.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageStoreSampleTest {
    @Test fun `small image is not sampled`() = assertEquals(1, ImageStore.sampleSize(800, 600))
    @Test fun `phone photo is sampled down but never below the target`() = assertEquals(2, ImageStore.sampleSize(4000, 3000))
    @Test fun `huge image samples by powers of two`() = assertEquals(8, ImageStore.sampleSize(12000, 9000))
}
