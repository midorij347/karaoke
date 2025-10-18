package com.example.karaoke


import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.os.IBinder
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbReaderService : Service() {
    companion object {
        private const val ACTION_START = "com.example.melscope.ACTION_START"
        private const val EXTRA_DEVICE = "device"

        fun start(ctx: Context, device: UsbDevice) {
            val i = Intent(ctx, UsbReaderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE, device)
            }
            ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var conn: UsbDeviceConnection? = null
    private var inEp: UsbEndpoint? = null
    private var dataIntf: UsbInterface? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val device = intent.getParcelableExtra<UsbDevice>(EXTRA_DEVICE) ?: return START_NOT_STICKY
            openAndRead(device)
        }
        return START_STICKY
    }

    private fun openAndRead(device: UsbDevice) {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        // CDCは通常、Comm(IF class=2) + Data(IF class=10)の2インターフェース構成
        val commIf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
        val dataIf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            ?: run {
                // データIF1個構成でない場合はインタフェース0を試す
                device.getInterface(0)
            }

        val connection = mgr.openDevice(device) ?: return
        conn = connection

        // Claim interfaces
        commIf?.let { connection.claimInterface(it, true) }
        dataIf?.let { connection.claimInterface(it, true); dataIntf = it }

        // Bulk IN endpoint 検出
        val epIn = (0 until (dataIf?.endpointCount ?: 0))
            .map { dataIf!!.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        inEp = epIn

        // CDCライン設定(ボーレート等)は必要ない（ESP側はUSB-CDCでRaw垂れ流し）
        // 読み取りループ
        epIn ?: return
        scope.launch {
            val buf = ByteArray(4096)
            val bb = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
            while (isActive) {
                val read = connection.bulkTransfer(epIn, buf, buf.size, 50) // 50ms timeout
                if (read != null && read > 0) {
                    // 受信バイト列→Short PCM へ詰め替え
                    // ESPは16-bit little-endian モノラル
                    bb.clear()
                    bb.put(buf, 0, read)
                    bb.flip()
                    val shorts = ShortArray(bb.remaining() / 2)
                    bb.asShortBuffer().get(shorts)
                    AudioPipe.enqueuePcm(shorts) // DSP側へ投入
                }
                // else: タイムアウトはループ継続
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { inEp = null; dataIntf?.let { conn?.releaseInterface(it) } } catch (_: Throwable) {}
        try { conn?.close() } catch (_: Throwable) {}
    }
}
