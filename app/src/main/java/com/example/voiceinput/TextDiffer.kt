package com.example.voiceinput

object TextDiffer {

    fun diff(raw: String, converted: String): List<ConversionChunk> {
        if (raw == converted) {
            return listOf(ConversionChunk(raw, converted))
        }
        if (raw.isEmpty() || converted.isEmpty()) {
            return listOf(ConversionChunk(raw, converted))
        }

        // Find common prefix
        val prefixLen = commonPrefixLength(raw, converted)
        // Find common suffix (not overlapping with prefix)
        val suffixLen = commonSuffixLength(raw, converted, prefixLen)

        val chunks = mutableListOf<ConversionChunk>()

        // Common prefix
        if (prefixLen > 0) {
            val prefix = raw.substring(0, prefixLen)
            chunks.add(ConversionChunk(prefix, prefix))
        }

        // Middle (different) part
        val rawMiddle = raw.substring(prefixLen, raw.length - suffixLen)
        val convertedMiddle = converted.substring(prefixLen, converted.length - suffixLen)

        if (rawMiddle.isNotEmpty() || convertedMiddle.isNotEmpty()) {
            // Try to find common subsequences within the middle
            val middleChunks = diffMiddle(rawMiddle, convertedMiddle)
            chunks.addAll(middleChunks)
        }

        // Common suffix
        if (suffixLen > 0) {
            val suffix = raw.substring(raw.length - suffixLen)
            chunks.add(ConversionChunk(suffix, suffix))
        }

        return chunks
    }

    private fun diffMiddle(raw: String, converted: String): List<ConversionChunk> {
        if (raw.isEmpty() || converted.isEmpty()) {
            return listOf(ConversionChunk(raw, converted))
        }

        // Find longest common substring in the middle
        val (rawStart, convStart, length) = longestCommonSubstring(raw, converted)

        if (length < 2) {
            // No meaningful common part, treat as single change
            return listOf(ConversionChunk(raw, converted))
        }

        val chunks = mutableListOf<ConversionChunk>()

        // Part before the common substring
        val rawBefore = raw.substring(0, rawStart)
        val convBefore = converted.substring(0, convStart)
        if (rawBefore.isNotEmpty() || convBefore.isNotEmpty()) {
            chunks.addAll(diffMiddle(rawBefore, convBefore))
        }

        // The common substring
        val common = raw.substring(rawStart, rawStart + length)
        chunks.add(ConversionChunk(common, common))

        // Part after the common substring
        val rawAfter = raw.substring(rawStart + length)
        val convAfter = converted.substring(convStart + length)
        if (rawAfter.isNotEmpty() || convAfter.isNotEmpty()) {
            chunks.addAll(diffMiddle(rawAfter, convAfter))
        }

        return chunks
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val minLen = minOf(a.length, b.length)
        for (i in 0 until minLen) {
            if (a[i] != b[i]) return i
        }
        return minLen
    }

    private fun commonSuffixLength(a: String, b: String, prefixLen: Int): Int {
        val maxSuffix = minOf(a.length, b.length) - prefixLen
        for (i in 0 until maxSuffix) {
            if (a[a.length - 1 - i] != b[b.length - 1 - i]) return i
        }
        return maxSuffix
    }

    private data class LcsResult(val startA: Int, val startB: Int, val length: Int)

    private fun longestCommonSubstring(a: String, b: String): LcsResult {
        var bestLen = 0
        var bestA = 0
        var bestB = 0

        // Dynamic programming approach
        val prev = IntArray(b.length + 1)
        val curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                if (a[i - 1] == b[j - 1]) {
                    curr[j] = prev[j - 1] + 1
                    if (curr[j] > bestLen) {
                        bestLen = curr[j]
                        bestA = i - bestLen
                        bestB = j - bestLen
                    }
                } else {
                    curr[j] = 0
                }
            }
            prev.indices.forEach { prev[it] = curr[it]; curr[it] = 0 }
        }

        return LcsResult(bestA, bestB, bestLen)
    }
}
