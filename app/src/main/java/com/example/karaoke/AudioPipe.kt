package com.example.karaoke

import kotlinx.coroutines.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.*

object AudioPipe {
    // ★ 実機のサンプリング周波数に合わせる（16k/24k/32k/44.1k など）
    private const val SR = 16000

    // メル生成パラメータ（そのままでOK）
    private const val FFT_SIZE = 512
    private const val HOP = 160       // 10ms @16k
    private const val WIN = 400       // 25ms @16k
    private const val MEL_BANDS = 64
    private const val F_MIN = 20.0
    private const val F_MAX = 8000.0

    // 入力PCMキュー
    private val q = ArrayBlockingQueue<ShortArray>(32)

    // 窓（Hann）
    private val win = FloatArray(WIN) { i ->
        (0.5f * (1f - cos(2.0 * Math.PI * i / (WIN - 1))).toFloat())
    }

    // FFT/Mel
    private val fft = FFT(FFT_SIZE)
    private val mel = MelFilterbank(SR, FFT_SIZE, MEL_BANDS, F_MIN, F_MAX)

    // オーバーラップ用バッファ
    private val frameBuf = FloatArray(WIN)
    private var framePos = 0

    // FFTピークロギング用（Logcat確認）
    private val analyzer = FftAnalyzer(sampleRate = SR, fftSize = 1024, logTag = "FFT")

    // 出力コールバック（メル列）
    interface MelSink { fun onMelColumn(columnDb: FloatArray) }
    @Volatile private var sink: MelSink? = null
    fun setSink(s: MelSink) { sink = s }

    fun enqueuePcm(s: ShortArray) {
        android.util.Log.i("FFT","feed n=${s.size}")
        analyzer.feedPcm16Le(s)
        q.offer(s)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            val fftIn = FloatArray(FFT_SIZE)
            val fftOutRe = FloatArray(FFT_SIZE)
            val fftOutIm = FloatArray(FFT_SIZE)

            while (isActive) {
                val chunk = q.take() // blocking
                var idx = 0
                val n = chunk.size

                while (idx < n) {
                    val canCopy = min(n - idx, WIN - framePos)
                    var i = 0
                    while (i < canCopy) {
                        frameBuf[framePos + i] = chunk[idx + i] / 32768f
                        i++
                    }
                    framePos += canCopy
                    idx += canCopy

                    if (framePos == WIN) {
                        for (i2 in 0 until WIN) fftIn[i2] = frameBuf[i2] * win[i2]
                        for (i2 in WIN until FFT_SIZE) fftIn[i2] = 0f
                        System.arraycopy(fftIn, 0, fftOutRe, 0, FFT_SIZE)
                        java.util.Arrays.fill(fftOutIm, 0f)
                        fft.fft(fftOutRe, fftOutIm)
                        val mag2 = FloatArray(FFT_SIZE / 2 + 1)
                        for (k in 0..FFT_SIZE / 2) {
                            val re = fftOutRe[k]; val im = fftOutIm[k]
                            mag2[k] = re * re + im * im
                        }
                        val melE = mel.apply(mag2)
                        val col = FloatArray(MEL_BANDS)
                        for (m in 0 until MEL_BANDS) {
                            val e = max(1e-12f, melE[m])
                            col[m] = (10f * ln(e) / ln(10f))
                        }
                        sink?.onMelColumn(col)

                        if (HOP < WIN) {
                            System.arraycopy(frameBuf, HOP, frameBuf, 0, WIN - HOP)
                            framePos = WIN - HOP
                        } else framePos = 0
                    }
                }
            }
        }
    }
}

class FFT(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    // 名前衝突を避けるため cos/sin → cosTable/sinTable
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        require(1 shl levels == n) { "FFT size must be power of 2" }
        for (i in 0 until n / 2) {
            val ang = -2.0 * Math.PI * i / n
            // ★ kotlin.math.cos/sin を明示
            cosTable[i] = kotlin.math.cos(ang).toFloat()
            sinTable[i] = kotlin.math.sin(ang).toFloat()
        }
    }

    fun fft(re: FloatArray, im: FloatArray) {
        var j = 0
        for (i in 1 until n - 1) {
            var bit = n shr 1
            while (j >= bit) { j -= bit; bit = bit shr 1 }
            j += bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size shr 1
            val tableStep = n / size
            var i = 0
            while (i < n) {
                var k = 0
                var j2 = i
                while (k < half) {
                    val l = j2 + half
                    // ★ 参照先を cosTable/sinTable に統一
                    val ct = cosTable[k * tableStep]
                    val st = sinTable[k * tableStep]
                    val tpr = re[l] * ct - im[l] * st
                    val tpi = re[l] * st + im[l] * ct
                    re[l] = re[j2] - tpr
                    im[l] = im[j2] - tpi
                    re[j2] += tpr
                    im[j2] += tpi
                    j2++; k++
                }
                i += size
            }
            size = size shl 1
        }
    }
}

class MelFilterbank(
    sr: Int,
    fftSize: Int,
    private val m: Int,
    fMin: Double,
    fMax: Double
) {
    private val nFft = fftSize
    private val nSpec = nFft / 2 + 1
    private val filters: Array<FloatArray>

    init {
        fun hz2mel(f: Double) = 2595.0 * ln(1 + f / 700.0)
        fun mel2hz(mel: Double) = 700.0 * (exp(mel / 2595.0) - 1.0)

        val melMin = hz2mel(fMin)
        val melMax = hz2mel(fMax)
        val melPoints = DoubleArray(m + 2) { melMin + (melMax - melMin) * it / (m + 1) }
        val hzPoints = DoubleArray(m + 2) { mel2hz(melPoints[it]) }
        val bin = IntArray(m + 2) {
            ((nFft) * hzPoints[it] / sr).roundToInt().coerceIn(0, nSpec - 1)
        }

        filters = Array(m) { FloatArray(nSpec) }
        for (i in 0 until m) {
            val start = bin[i]
            val center = bin[i + 1]
            val end = bin[i + 2]
            if (center == start || end == center) continue
            for (k in start until center) {
                filters[i][k] = ((k - start).toFloat() / (center - start))
            }
            for (k in center until end) {
                filters[i][k] = ((end - k).toFloat() / (end - center))
            }
        }
    }

    fun apply(powerSpec: FloatArray): FloatArray {
        val out = FloatArray(m)
        for (i in 0 until m) {
            var s = 0f
            val f = filters[i]
            for (k in f.indices) {
                val w = f[k]
                if (w != 0f) s += w * powerSpec[k]
            }
            out[i] = s
        }
        return out
    }
}
