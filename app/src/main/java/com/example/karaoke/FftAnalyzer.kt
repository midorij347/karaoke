package com.example.karaoke

import android.util.Log
import kotlin.math.*

class FftAnalyzer(
    private val sampleRate: Int,
    private val fftSize: Int = 1024,
    private val logTag: String = "FFT"
) {
    private val window = DoubleArray(fftSize) { i -> 0.5 * (1 - cos(2.0 * Math.PI * i / (fftSize - 1))) }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val ring = ShortArray(fftSize)
    private var wp = 0
    private var filled = false
    private var clipCount = 0

    fun feedPcm16Le(samples: ShortArray) {
        for (s in samples) {
            ring[wp] = s
            if (s == Short.MIN_VALUE || s == Short.MAX_VALUE) clipCount++
            wp++
            if (wp >= fftSize) { wp = 0; filled = true }
        }
        if (filled) analyzeAndLog()
    }

    private fun analyzeAndLog() {
        var idx = wp
        var peakAbs = 0.0
        var sumSq = 0.0

        for (i in 0 until fftSize) {
            val s = ring[idx].toInt() / 32768.0 // -1.0..+1.0
            idx++; if (idx >= fftSize) idx = 0
            val w = window[i]
            val v = s * w
            re[i] = v
            im[i] = 0.0
            val a = abs(v)
            if (a > peakAbs) peakAbs = a
            sumSq += v * v
        }

        fftInPlace(re, im)

        // パワースペクトル（片側、DC/ナイキスト除外気味）
        val n2 = fftSize / 2
        val mags = DoubleArray(n2)
        for (k in 1 until n2) { // 0(DC)は除外
            val rr = re[k]; val ii = im[k]
            mags[k] = (rr * rr + ii * ii)
        }

        data class Peak(val k: Int, val p: Double)
        var p1 = Peak(1, 0.0); var p2 = Peak(1, 0.0); var p3 = Peak(1, 0.0)
        for (k in 1 until n2) {
            val p = mags[k]
            if (p > p1.p) { p3 = p2; p2 = p1; p1 = Peak(k, p) }
            else if (p > p2.p) { p3 = p2; p2 = Peak(k, p) }
            else if (p > p3.p) { p3 = Peak(k, p) }
        }

        fun binToHz(k: Int) = k.toDouble() * sampleRate / fftSize
        val rms = sqrt(sumSq / fftSize)
        val dbfs = if (rms > 0) 20 * ln(rms) / ln(10.0) else -160.0

        Log.i(logTag, buildString {
            append("peakHz="); append("%.1f".format(binToHz(p1.k)))
            append(" (k="); append(p1.k); append(")")
            append(", top3=[")
            append("%.0f".format(binToHz(p1.k))).append(", ")
            append("%.0f".format(binToHz(p2.k))).append(", ")
            append("%.0f".format(binToHz(p3.k))).append("] Hz")
            append(", rms="); append("%.5f".format(rms))
            append(" ("); append("%.1f".format(dbfs)); append(" dBFS)")
            append(", clip="); append(clipCount)
        })

        // 接触不良の簡易判定（全部同値に近い/ゼロ多すぎ など）
        val zeroLike = mags.sum() < 1e-6
        if (zeroLike || rms < 1e-4) {
            Log.w(logTag, "Signal too small / flat → 配線・接触を確認してください")
        }
        clipCount = 0
    }

    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // ビット反転
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlenRe = cos(ang); val wlenIm = sin(ang)
            for (i in 0 until n step len) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len/2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len/2] * wr - im[i + k + len/2] * wi
                    val vIm = re[i + k + len/2] * wi + im[i + k + len/2] * wr
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len/2] = uRe - vRe
                    im[i + k + len/2] = uIm - vIm
                    val nxtWr = wr * wlenRe - wi * wlenIm
                    wi = wr * wlenIm + wi * wlenRe
                    wr = nxtWr
                }
            }
            len = len shl 1
        }
    }
}
