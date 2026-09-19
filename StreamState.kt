package com.example.pcaudio

import kotlinx.coroutines.flow.MutableStateFlow

/** Live stats published by the service, observed by the Compose UI (same process). */
data class Stats(
    val running: Boolean = false,
    val streaming: Boolean = false,
    val codec: String = "-",
    val peakL: Float = 0f,       // 0..1, pre-volume
    val peakR: Float = 0f,
    val bufferMs: Int = 0,       // audio queued in AudioTrack
    val jitterMs: Float = 0f,
    val rttMs: Int = -1,         // -1 = unknown
    val lossPct: Float = 0f,
    val kbps: Int = 0,
) {
    /** Rough one-way estimate: packetization + network (RTT/2) + jitter + queued audio. */
    val estLatencyMs: Int
        get() = 5 + (if (rttMs >= 0) rttMs / 2 else 0) + jitterMs.toInt() + bufferMs
}

object StreamState {
    val stats = MutableStateFlow(Stats())
    val volume = MutableStateFlow(1f)   // 0..1 software gain
}
