package com.openminis.app.speech

internal object VadCaptureWindow {

    fun tailFrames(sampleRate: Int, frameSamples: Int, tailSeconds: Float): Int =
        (sampleRate * tailSeconds / frameSamples).toInt()

    fun prerollCapacityBytes(sampleRate: Int, prerollSeconds: Float): Int =
        (sampleRate * prerollSeconds).toInt() * 2

    fun expectedFlushSeconds(prerollHeldSeconds: Float, confirmedSeconds: Float): Float =
        prerollHeldSeconds + confirmedSeconds
}
