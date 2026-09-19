package com.example.pcaudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.log10
import kotlin.math.roundToInt

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFF9A825)
private val Red = Color(0xFFC62828)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    PlayerScreen(
                        ip = localIp(),
                        onStart = { startForegroundService(Intent(this, AudioStreamService::class.java)) },
                        onStop = { stopService(Intent(this, AudioStreamService::class.java)) },
                    )
                }
            }
        }
    }

    private fun localIp(): String =
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "not on Wi-Fi"
}

@Composable
fun PlayerScreen(ip: String, onStart: () -> Unit, onStop: () -> Unit) {
    val stats by StreamState.stats.collectAsState()
    val volume by StreamState.volume.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(20.dp).statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("PC Audio", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // Connection
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (stats.streaming) Green else if (stats.running) Amber else Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            stats.streaming -> "Streaming (${stats.codec})"
                            stats.running -> "Waiting for PC…"
                            else -> "Stopped"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text("python sender.py $ip", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }

        // Start / stop
        if (stats.running) {
            OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text("Stop") }
        } else {
            Button(onClick = onStart, Modifier.fillMaxWidth()) { Text("Start receiving") }
        }

        // Level meters + volume
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Level", fontWeight = FontWeight.SemiBold)
                LevelBar("L", stats.peakL)
                LevelBar("R", stats.peakR)
                Text("Volume  ${(volume * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
                Slider(value = volume, onValueChange = { StreamState.volume.value = it })
            }
        }

        // Latency & health
        val latColor = when {
            stats.estLatencyMs < 60 -> Green
            stats.estLatencyMs < 120 -> Amber
            else -> Red
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (stats.streaming) "${stats.estLatencyMs}" else "–",
                        fontSize = 44.sp, fontWeight = FontWeight.Bold, color = latColor
                    )
                    Text(" ms est. latency", Modifier.padding(bottom = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Buffer", "${stats.bufferMs} ms")
                    Metric("Jitter", "%.1f ms".format(stats.jitterMs))
                    Metric("RTT", if (stats.rttMs >= 0) "${stats.rttMs} ms" else "–")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Loss", "%.1f%%".format(stats.lossPct), if (stats.lossPct > 2f) Red else null)
                    Metric("Bitrate", "${stats.kbps} kbps")
                    Metric("Codec", stats.codec)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) =
    Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(color))

@Composable
private fun Metric(label: String, value: String, valueColor: Color? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 80.dp)) {
        Text(value, fontWeight = FontWeight.SemiBold, color = valueColor ?: Color.Unspecified)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Peak meter: dB-scaled (-60..0 dBFS) with fast attack / slower release. */
@Composable
private fun LevelBar(label: String, peak: Float) {
    val db = if (peak > 0.0001f) 20f * log10(peak) else -60f
    val target = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val level by animateFloatAsState(target, tween(if (target > 0f) 60 else 250), label = "level")
    val color = when {
        level > 0.95f -> Red
        level > 0.8f -> Amber
        else -> Green
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(20.dp), fontFamily = FontFamily.Monospace)
        Box(
            Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level).background(color))
        }
    }
}
