package com.example.karaoke


import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max

class MainActivity : ComponentActivity(), AudioPipe.MelSink {
    // 横=タイムステップ数、縦=メル帯域
    private val melBands = 64
    private val timeSteps = 256

    // スペクトログラムバッファ（dB）
    private val spec = Array(melBands) { FloatArray(timeSteps) { -120f } }
    private var xWrite = 0
    private val ACTION_USB_PERMISSION = "com.example.karaoke.USB_PERMISSION"

    private var uiUpdate by mutableStateOf(0) // 再描画トリガ

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioPipe.setSink(this)

        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { connectFirstCdc() }) { Text("USB接続") }
                        Button(onClick = { clearSpec() }) { Text("クリア") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Spectrogram(spec, timeSteps, melBands, uiUpdate)
                }
            }
        }
    }

    private fun clearSpec() {
        for (y in 0 until melBands) {
            java.util.Arrays.fill(spec[y], -120f)
        }
        xWrite = 0
        uiUpdate++
    }

    /** USB CDC デバイスに接続 */
    private fun connectFirstCdc() {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        val device = mgr.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).map { dev.getInterface(it) }.any { it.interfaceClass == 2 }
        } ?: return

        if (!mgr.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(
                this,
                1001,
                Intent(this, UsbPermissionReceiver::class.java)
                    .setAction(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE // ★ 31+ は MUTABLE 必須
            )
            mgr.requestPermission(device, pi)
        } else {
            UsbReaderService.start(this, device)
        }
    }

    /** Mel列が届くたびに右方向へスクロール追加 */
    override fun onMelColumn(columnDb: FloatArray) {
        // AudioPipe からはバックグラウンドで呼ばれるので、
        // UI部分の更新だけメインスレッドに投げる
        runOnUiThread {
            val colIdx = xWrite
            for (y in 0 until melBands) {
                spec[melBands - 1 - y][colIdx] = columnDb[y].coerceIn(-100f, 0f)
            }
            xWrite = (xWrite + 1) % timeSteps
            uiUpdate++  // ← これで確実に再composeされる
        }
    }
}

@Composable
fun Spectrogram(spec: Array<FloatArray>, timeSteps: Int, melBands: Int, trigger: Int) {
    // dB(-100..0) → 0..1 → 色
    fun colorMap(vDb: Float): Color {
        val v = ((vDb + 100f) / 100f).coerceIn(0f, 1f)
        // 簡易カラーマップ（黒→青→シアン→黄→白）
        val r = when {
            v < 0.5f -> 0f
            else -> (v - 0.5f) * 2f
        }
        val g = when {
            v < 0.5f -> v * 2f
            else -> 1f
        }
        val b = 1f - max(0f, v - 0.5f) * 2f
        return Color(r, g, b)
    }

    // 再描画トリガ "trigger" は使わないが、値変化で再compose
    LaunchedEffect(trigger) {}

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val cellW = size.width / timeSteps
            val cellH = size.height / melBands
            for (x in 0 until timeSteps) {
                for (y in 0 until melBands) {
                    val c = colorMap(spec[y][x])
                    drawRect(
                        color = c,
                        topLeft = Offset(x * cellW, y * cellH),
                        size = androidx.compose.ui.geometry.Size(cellW + 1f, cellH + 1f)
                    )
                }
            }
        }
    }
}
