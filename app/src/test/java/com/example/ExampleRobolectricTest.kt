package com.example

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FrameCut", appName)
  }

  @Test
  fun `tv banner drawable and leanback launcher activity resolve`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val banner = ContextCompat.getDrawable(context, R.drawable.tv_banner)
    assertNotNull(banner)

    val leanbackIntent =
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val resolved = context.packageManager.queryIntentActivities(leanbackIntent, 0)
    assertTrue(resolved.any { it.activityInfo.name == MainActivity::class.java.name })
  }
}
