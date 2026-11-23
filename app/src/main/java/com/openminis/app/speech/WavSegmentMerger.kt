package com.openminis.app.speech

object WavSegmentMerger {

    private const val HEADER = VoiceActivityDetector.WAV_HEADER_BYTES

    private const val OFFSET_RIFF_SIZE = 4
    private const val OFFSET_DATA_SIZE = 40

    fun merge(segments: List<ByteArray>): ByteArray? {
        val valid = segments.filter { it.size > HEADER }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid[0]

        val payloadBytes = valid.sumOf { it.size - HEADER }
        val out = ByteArray(HEADER + payloadBytes)

        System.arraycopy(valid[0], 0, out, 0, HEADER)
        var pos = HEADER
        for (seg in valid) {
            val n = seg.size - HEADER
            System.arraycopy(seg, HEADER, out, pos, n)
            pos += n
        }

        putLeUInt32(out, OFFSET_RIFF_SIZE, (out.size - 8).toLong())
        putLeUInt32(out, OFFSET_DATA_SIZE, payloadBytes.toLong())
        return out
    }

    fun totalSeconds(segments: List<ByteArray>, sampleRate: Int = 16_000): Float {
        if (sampleRate <= 0) return 0f
        val payload = segments.sumOf { maxOf(0, it.size - HEADER) }
        return payload / 2f / sampleRate
    }

    private fun putLeUInt32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
