package com.example.pcaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import org.concentus.OpusDecoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class AudioStreamService : Service() {

    companion object {
        const val PORT = 5005
        const val CONTROL_PORT = 5006
        const val ACTION_STOP = "stop"
        private const val CHANNEL_ID = "stream"
        private const val RATE = 48000
        private const val CH = 2
        private const val FRAME = 240                 // samples/channel per packet (5 ms)
        private const val FRAME_MS = 5f
        private const val PREBUFFER_PACKETS = 10      // ~50 ms
        private const val MAX_BUFFER_MS = 150         // drop instead of drifting into lag
        private const val UI_INTERVAL_MS = 100L
    }

    @Volatile private var running = false
    @Volatile private var peer: InetAddress? = null
    @Volatile private var rttMs = -1
    private var rx: Thread? = null
    private var ping: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        if (!running) {
            running = true
            StreamState.stats.value = Stats(running = true)
            rx = Thread(::receiveLoop, "audio-rx").also { it.start() }
            ping = Thread(::pingLoop, "audio-ping").also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PC audio stream", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, AudioStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Receiving PC audio")
            .setContentText("UDP port $PORT")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
    }

    /** RTT probe: sends a timestamp to the PC's echo port once a second. */
    private fun pingLoop() {
        val s = DatagramSocket().apply { soTimeout = 400 }
        val out = ByteArray(8)
        val inBuf = ByteArray(8)
        try {
            while (running) {
                val p = peer
                if (p != null) {
                    val t0 = System.nanoTime()
                    ByteBuffer.wrap(out).putLong(t0)
                    try {
                        s.send(DatagramPacket(out, 8, p, CONTROL_PORT))
                        val r = DatagramPacket(inBuf, 8)
                        s.receive(r)
                        if (ByteBuffer.wrap(inBuf).long == t0)
                            rttMs = ((System.nanoTime() - t0) / 1_000_000).toInt()
                    } catch (_: SocketTimeoutException) { rttMs = -1 }
                }
                Thread.sleep(1000)
            }
        } catch (_: InterruptedException) {
        } finally { s.close() }
    }

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(max(minBuf * 4, RATE * CH * 2 * MAX_BUFFER_MS / 1000 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val decoder = OpusDecoder(RATE, CH)
        val socket = DatagramSocket(PORT).apply { soTimeout = 200; receiveBufferSize = 1 shl 16 }
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        val pcm = ShortArray(FRAME * CH)

        var lastSeq = -1
        var received = 0
        var playing = false
        var framesWritten = 0L

        // stats window
        var lastArrival = 0L
        var jitter = 0f
        var winRecv = 0; var winLost = 0; var winBytes = 0
        var peakL = 0; var peakR = 0
        var lastUi = SystemClock.elapsedRealtime()
        var lastPacketAt = 0L
        var codecName = "-"

        fun publish(now: Long) {
            val secs = (now - lastUi) / 1000f
            val queued = if (playing)
                ((framesWritten - (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)) * 1000 / RATE).toInt().coerceAtLeast(0)
            else (received * FRAME_MS).toInt()
            val total = winRecv + winLost
            StreamState.stats.value = Stats(
                running = true,
                streaming = now - lastPacketAt < 1000 && lastPacketAt != 0L,
                codec = codecName,
                peakL = peakL / 32768f, peakR = peakR / 32768f,
                bufferMs = queued, jitterMs = jitter, rttMs = rttMs,
                lossPct = if (total > 0) winLost * 100f / total else 0f,
                kbps = if (secs > 0) (winBytes * 8 / 1000f / secs).toInt() else 0,
            )
            track.setVolume(StreamState.volume.value)
            peakL = 0; peakR = 0; winRecv = 0; winLost = 0; winBytes = 0
            lastUi = now
        }

        try {
            while (running) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastUi >= UI_INTERVAL_MS) publish(now)

                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                if (packet.length <= 5) continue
                peer = packet.address

                val codec = buf[0].toInt()
                val seq = ((buf[1].toInt() and 0xFF) shl 24) or ((buf[2].toInt() and 0xFF) shl 16) or
                    ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                val gap = if (lastSeq == -1) 1 else seq - lastSeq
                if (gap <= 0) continue                       // late or duplicate
                lastPacketAt = now
                codecName = if (codec == 1) "Opus" else "PCM"

                // jitter (RFC 3550 style, in ms)
                val arrival = System.nanoTime()
                if (lastArrival != 0L) {
                    val d = abs((arrival - lastArrival) / 1e6f - gap * FRAME_MS)
                    jitter += (d - jitter) / 16f
                }
                lastArrival = arrival

                // packet loss concealment for short gaps (Opus only)
                if (gap > 1) {
                    winLost += gap - 1
                    if (codec == 1 && gap <= 4) {
                        repeat(gap - 1) {
                            decoder.decode(null, 0, 0, pcm, 0, FRAME, false)
                            framesWritten += write(track, pcm, framesWritten, playing)
                        }
                    }
                }
                lastSeq = seq
                winRecv++
                winBytes += packet.length

                val ok = if (codec == 1) {
                    try { decoder.decode(buf, 5, packet.length - 5, pcm, 0, FRAME, false) == FRAME }
                    catch (_: Exception) { false }
                } else {
                    if (packet.length - 5 < FRAME * CH * 2) false else {
                        ByteBuffer.wrap(buf, 5, FRAME * CH * 2).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(pcm); true
                    }
                }
                if (!ok) continue

                for (i in 0 until FRAME) {
                    peakL = max(peakL, abs(pcm[i * 2].toInt()))
                    peakR = max(peakR, abs(pcm[i * 2 + 1].toInt()))
                }

                framesWritten += write(track, pcm, framesWritten, playing)
                received++
                if (!playing && received >= PREBUFFER_PACKETS) { track.play(); playing = true }
            }
        } finally {
            socket.close(); track.stop(); track.release()
            StreamState.stats.value = Stats()
        }
    }

    /** Non-blocking write; skips the packet if we're already too far behind (keeps latency bounded). */
    private fun write(track: AudioTrack, pcm: ShortArray, written: Long, playing: Boolean): Int {
        if (playing) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if ((written - head) * 1000 / RATE > MAX_BUFFER_MS) return 0
        }
        val n = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        return if (n > 0) n / CH else 0
    }

    override fun onDestroy() {
        running = false
        ping?.interrupt()
        rx?.join(1000)
        StreamState.stats.value = Stats()
        super.onDestroy()
    }
}
