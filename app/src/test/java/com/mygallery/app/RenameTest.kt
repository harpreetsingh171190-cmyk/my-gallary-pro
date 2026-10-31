package com.mygallery.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenameTest {
    private fun item(id: Long, name: String, date: Long) = GalleryMedia(
        id, Uri.parse("content://media/external/files/$id"), name, "image/jpeg", 1_000L, date, "Camera",
    )

    @Test
    fun namesFollowDateOrderAndKeepExtensions() {
        val plan = planRenames(listOf(item(1, "IMG_9.jpg", 200), item(2, "IMG_1.png", 100)), "Trip {n}", 5)
        assertEquals(listOf("Trip 5.png", "Trip 6.jpg"), plan.map { it.second })
        assertEquals(listOf(2L, 1L), plan.map { it.first.id })
    }

    @Test
    fun patternNeedsPlaceholderAndNoIllegalCharacters() {
        assertTrue(isValidPattern("Photo {n}"))
        assertFalse(isValidPattern("Photo"))
        assertFalse(isValidPattern("a/b {n}"))
        assertFalse(isValidPattern("   "))
    }
}
