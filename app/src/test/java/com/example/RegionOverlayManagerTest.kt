package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.region.PixelRegion
import com.example.region.RegionBorderOverlayView
import com.example.region.RegionOverlayManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegionOverlayManagerTest {

  @Test
  fun `show and hide from background thread do not throw`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = RegionOverlayManager(context)
    val region = PixelRegion(100, 200, 700, 1200, 1080, 2400)
    val errorRef = AtomicReference<Throwable?>(null)
    val latch = CountDownLatch(1)

    Thread {
        try {
          manager.show(region, enabled = true)
          manager.setPaused(true)
          manager.hide()
        } catch (t: Throwable) {
          errorRef.set(t)
        } finally {
          latch.countDown()
        }
      }
      .start()

    latch.await(3, TimeUnit.SECONDS)
    assertNull(errorRef.get())
  }

  @Test
  fun `overlay draws zero pixels inside recorded crop region even near screen edges`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val region = PixelRegion(left = 0, top = 0, right = 400, bottom = 600, screenWidth = 800, screenHeight = 1200)
    val view = RegionBorderOverlayView(context, region)
    view.measure(
      android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
      android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
    )
    view.layout(0, 0, 800, 1200)

    val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    view.draw(canvas)

    // Verify every sampled point inside the recorded crop region remains 100% transparent
    for (y in region.top until region.bottom step 10) {
      for (x in region.left until region.right step 10) {
        assertEquals(
          "Expected transparent pixel inside crop region at ($x, $y)",
          Color.TRANSPARENT,
          bitmap.getPixel(x, y),
        )
      }
    }
    bitmap.recycle()
  }
}
