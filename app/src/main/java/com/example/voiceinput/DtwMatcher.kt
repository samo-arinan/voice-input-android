package com.example.voiceinput

import kotlin.math.sqrt

object DtwMatcher {

    fun dtwDistance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        val n = a.size
        val m = b.size
        val dtw = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
        dtw[0][0] = 0f

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = euclideanDist(a[i - 1], b[j - 1])
                dtw[i][j] = cost + minOf(
                    dtw[i - 1][j],
                    dtw[i][j - 1],
                    dtw[i - 1][j - 1]
                )
            }
        }

        return dtw[n][m] / (n + m)
    }

    private fun euclideanDist(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }
}
