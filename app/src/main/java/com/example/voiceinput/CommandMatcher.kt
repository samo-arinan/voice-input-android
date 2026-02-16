package com.example.voiceinput

class CommandMatcher(
    private val commands: List<VoiceCommand>,
    private val sampleMfccs: Map<String, List<Array<FloatArray>>>
) {

    data class MatchResult(
        val command: VoiceCommand,
        val distance: Float
    )

    fun match(inputMfcc: Array<FloatArray>): MatchResult? {
        var bestMatch: MatchResult? = null

        for (cmd in commands) {
            val samples = sampleMfccs[cmd.id] ?: continue
            if (samples.isEmpty()) continue

            val minDist = samples.minOf { DtwMatcher.dtwDistance(inputMfcc, it) }

            if (minDist < cmd.threshold) {
                if (bestMatch == null || minDist < bestMatch.distance) {
                    bestMatch = MatchResult(cmd, minDist)
                }
            }
        }

        return bestMatch
    }
}
