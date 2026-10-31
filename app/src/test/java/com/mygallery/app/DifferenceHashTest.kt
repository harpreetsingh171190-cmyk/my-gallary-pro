package com.mygallery.app

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DifferenceHashTest {
    private fun gradient(reversed: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (x in 0 until 16) {
            val v = if (reversed) 255 - x * 16 else x * 16
            for (y in 0 until 16) bmp.setPixel(x, y, Color.rgb(v, v, v))
        }
        return bmp
    }

    @Test
    fun sameImageGivesSameHash() {
        assertEquals(SmartOrganization.differenceHash(gradient(false)), SmartOrganization.differenceHash(gradient(false)))
    }

    @Test
    fun mirroredImageGivesDifferentHash() {
        assertNotEquals(SmartOrganization.differenceHash(gradient(false)), SmartOrganization.differenceHash(gradient(true)))
    }
}
