package com.mygallery.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceGroupingTest {
    private fun face(uri: String, vararg v: Float) = FaceRecord(uri, floatArrayOf(*v))

    @Test
    fun similarFacesJoinOneGroupAndDifferentFacesSplit() {
        val groups = groupFaces(
            listOf(
                face("a", 1f, 0f, 0f),
                face("b", 0.98f, 0.1f, 0f), // almost the same direction
                face("c", 0f, 1f, 0f),      // a different person
            ),
        )
        assertEquals(listOf(2, 1), groups.map { it.size })
    }

    @Test
    fun unrelatedFacesStaySeparate() {
        val groups = groupFaces(listOf(face("a", 1f, 0f), face("b", 0f, 1f), face("c", -1f, 0f)))
        assertEquals(3, groups.size)
    }

    @Test
    fun zeroVectorIsLeftAlone() {
        val zero = floatArrayOf(0f, 0f, 0f)
        assertEquals(zero.toList(), l2Normalize(zero).toList())
    }

    @Test
    fun peopleNeedEnoughDistinctPhotos() {
        // Three faces in three photos form a person; two faces in one photo do not.
        val index = FaceIndex(
            scanned = mutableSetOf("p1", "p2", "p3", "p4"),
            faces = mutableListOf(
                face("p1", 1f, 0f), face("p2", 1f, 0.01f), face("p3", 1f, 0.02f),
                face("p4", 0f, 1f), face("p4", 0.01f, 1f),
            ),
        )
        val people = peopleClusters(index)
        assertEquals(1, people.size)
        assertEquals(3, people[0].size)
    }
}
