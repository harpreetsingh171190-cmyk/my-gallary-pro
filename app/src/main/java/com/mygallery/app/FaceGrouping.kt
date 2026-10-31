package com.mygallery.app

import kotlin.math.sqrt

/** Minimum cosine similarity for two faces to be treated as the same person. Tune this for your model. */
const val FACE_MATCH_THRESHOLD = 0.55f

/** Minimum number of distinct photos a group needs before it is shown as a person. */
const val MIN_PHOTOS_PER_PERSON = 3

/** One detected face: the photo it came from and its face embedding. Stays on this device. */
class FaceRecord(val uri: String, val embedding: FloatArray)

/** Scanned photo URIs and every face found so far. */
class FaceIndex(
    val scanned: MutableSet<String> = mutableSetOf(),
    val faces: MutableList<FaceRecord> = mutableListOf(),
)

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

fun l2Normalize(v: FloatArray): FloatArray {
    var n = 0f
    for (x in v) n += x * x
    val norm = sqrt(n)
    if (norm < 1e-6f) return v
    return FloatArray(v.size) { v[it] / norm }
}

/**
 * Greedy grouping. A face joins the first group whose centroid is similar enough; otherwise it starts a
 * new group. Each centroid is the running mean of its members, re-normalized.
 */
fun groupFaces(faces: List<FaceRecord>, threshold: Float = FACE_MATCH_THRESHOLD): List<List<FaceRecord>> {
    val groups = mutableListOf<MutableList<FaceRecord>>()
    val centroids = mutableListOf<FloatArray>()
    for (face in faces) {
        val e = l2Normalize(face.embedding)
        var best = -1
        var bestSim = threshold
        for (i in centroids.indices) {
            val sim = dot(e, centroids[i])
            if (sim >= bestSim) {
                bestSim = sim
                best = i
            }
        }
        if (best == -1) {
            groups += mutableListOf(face)
            centroids += e
        } else {
            val n = groups[best].size
            val old = centroids[best]
            groups[best] += face
            centroids[best] = l2Normalize(FloatArray(e.size) { old[it] * n + e[it] })
        }
    }
    return groups
}

/** Groups worth showing as people: enough distinct photos, largest first. */
fun peopleClusters(index: FaceIndex): List<List<FaceRecord>> =
    groupFaces(index.faces)
        .filter { g -> g.map { it.uri }.distinct().size >= MIN_PHOTOS_PER_PERSON }
        .sortedByDescending { g -> g.map { it.uri }.distinct().size }
