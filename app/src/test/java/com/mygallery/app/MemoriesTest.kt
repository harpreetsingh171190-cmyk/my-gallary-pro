package com.mygallery.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoriesTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 31)

    private fun at(date: LocalDate, hour: Int = 12): Long = date.atTime(hour, 0).toEpochSecond(ZoneOffset.UTC)

    private fun photo(id: Long, epochSeconds: Long) = GalleryMedia(
        id, Uri.parse("content://media/external/files/$id"), "p$id.jpg", "image/jpeg", 1_000L, epochSeconds, "Camera",
    )

    @Test
    fun onThisDayGroupsEarlierYearsOnSameDay() {
        val items = listOf(
            photo(1, at(LocalDate.of(2024, 10, 31))),
            photo(2, at(LocalDate.of(2025, 10, 31))),
            photo(3, at(LocalDate.of(2025, 10, 30))), // different day, not included
        )
        val memories = buildMemories(items, today, ZoneOffset.UTC).filter { it.kind == MemoryKind.ON_THIS_DAY }
        assertEquals(listOf(1, 2), memories.map { it.yearsAgo })
        assertEquals(listOf(2L), memories[0].items.map { it.id }) // 2025, one year ago
        assertEquals(listOf(1L), memories[1].items.map { it.id }) // 2024, two years ago
    }

    @Test
    fun burstOfPhotosBecomesTripWhenLongEnough() {
        val start = LocalDate.of(2026, 6, 1)
        val burst = (0 until 30).map { i -> photo(i.toLong(), at(start) + i * 3_600L) } // 30 photos over ~30 hours
        val lonely = listOf(photo(100, at(LocalDate.of(2026, 9, 1)))) // gap of months, too small for a trip
        val trips = buildMemories(burst + lonely, today, ZoneOffset.UTC).filter { it.kind == MemoryKind.TRIP }
        assertEquals(1, trips.size)
        assertEquals(30, trips[0].items.size)
        assertEquals(start, trips[0].start)
    }

    @Test
    fun monthsNeedEnoughPhotosAndAreNewestFirst() {
        val march = (0 until 5).map { photo(it.toLong(), at(LocalDate.of(2026, 3, 10)) + it * 60L) }
        val april = (10 until 13).map { photo(it.toLong(), at(LocalDate.of(2026, 4, 10)) + it * 60L) } // too few
        val months = buildMemories(march + april, today, ZoneOffset.UTC).filter { it.kind == MemoryKind.MONTH }
        assertEquals(listOf(YearMonth.of(2026, 3)), months.map { it.month })
    }
}
