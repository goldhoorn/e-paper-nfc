package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.nfc.TagLostException
import android.nfc.tech.NfcA
import android.os.SystemClock
import android.util.Log

/**
 * Native writer for WaveShare NFC-powered e-paper.
 *
 * Vendor commands are raw ISO14443-A frames starting with 0xCD, matching the
 * published hardware protocol (WaveShare Android SDK docs / C demo). This is
 * an original implementation — it does not link WaveShare's NFC.jar.
 *
 * Image bytes go to panel SRAM as a sequential stream after CD 07.
 * Reconnecting mid-stream resets that pointer, so a lost field cannot resume.
 */
class WaveshareEpaperWriter {

    @Volatile var progress: Int = 0
        private set
    @Volatile var status: String = ""
        private set

    fun sendBitmap(nfc: NfcA, sizeEnum: Int, bitmap: Bitmap): FlashResult {
        val model = EpaperModel.fromSdkEnum(sizeEnum)
            ?: return FlashResult(false, "Unknown display size enum $sizeEnum")

        val packed = try {
            packBitmap(bitmap, model)
        } catch (e: IllegalArgumentException) {
            return FlashResult(false, e.message ?: "Incorrect image resolution")
        }

        val blackSet = packed.black.count { it != 0.toByte() && it != 0xFF.toByte() }
        Log.i(TAG, "model=$sizeEnum ${model.width}x${model.height} type=${model.epdType} " +
            "blackBytes=${packed.black.size} varied=$blackSet")

        progress = 0
        var lastError = "Failed to write over NFC"

        for (attempt in 1..MAX_FULL_ATTEMPTS) {
            try {
                if (!ensureConnected(nfc, COMMAND_TIMEOUT_MS)) {
                    lastError = "Failed to connect to tag"
                    setStatus(lastError)
                    break
                }
                if (attempt > 1) {
                    setStatus("Field dropped — finding lock again ($attempt/$MAX_FULL_ATTEMPTS)")
                    SystemClock.sleep(200L)
                }
                waitForStableField(nfc)
                handshake(nfc, model)
                sendImageData(nfc, model, packed.black, packed.red)
                refreshAndWait(nfc, model)
                progress = 100
                setStatus("Done")
                return FlashResult(true, "")
            } catch (e: SessionResetException) {
                lastError = e.message ?: lastError
                Log.w(TAG, "Attempt $attempt aborted: $lastError")
                setStatus("Field dropped — restarting")
                quietlyClose(nfc)
                SystemClock.sleep(300L)
            } catch (e: TransferException) {
                lastError = e.message ?: lastError
                Log.w(TAG, "Transfer failed at ${e.phase}: $lastError")
                setStatus(lastError)
                quietlyClose(nfc)
                break
            } catch (e: Exception) {
                lastError = humanize(e)
                Log.w(TAG, "Unexpected transfer error", e)
                setStatus(lastError)
                quietlyClose(nfc)
                break
            }
        }

        return FlashResult(false, lastError)
    }

    /**
     * Cheap CD 0D probes until the field is stable. SRAM is empty until
     * handshake, so drops here are free. Starting the 48 KB stream without
     * this lock is why 7.5" transfers die on Samsung.
     */
    private fun waitForStableField(nfc: NfcA) {
        setStatus("Find the sweet spot — hold still")
        nfc.timeout = LOCK_PROBE_TIMEOUT_MS
        var streak = 0
        val deadline = SystemClock.elapsedRealtime() + LOCK_GIVE_UP_MS
        while (streak < LOCK_STREAK) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw SessionResetException("No stable NFC lock — move the camera-area of the phone over the display coil")
            }
            try {
                if (!nfc.isConnected && !ensureConnected(nfc, LOCK_PROBE_TIMEOUT_MS)) {
                    streak = 0
                    setStatus("No field — slide the upper back of the phone slowly")
                    SystemClock.sleep(80L)
                    continue
                }
                val rx = nfc.transceive(byteArrayOf(CMD, 0x0D))
                if (rx.size >= 2 && rx[0] == 0.toByte() && rx[1] == 0.toByte()) {
                    streak++
                    progress = (streak * 4) / LOCK_STREAK
                    setStatus("Locked $streak/$LOCK_STREAK — do not move")
                } else {
                    streak = 0
                    setStatus("Weak reply — adjust a few millimetres")
                }
            } catch (e: Exception) {
                streak = 0
                setStatus("Signal dropped — adjust position")
                SystemClock.sleep(80L)
            }
        }
        nfc.timeout = COMMAND_TIMEOUT_MS
        Log.i(TAG, "NFC field locked")
    }

    private fun handshake(nfc: NfcA, model: EpaperModel) {
        setStatus("Waking display")
        nfc.timeout = COMMAND_TIMEOUT_MS
        expectAck(nfc, byteArrayOf(CMD, 0x0D), "probe")
        expectAck(nfc, byteArrayOf(CMD, 0x00, model.epdType.toByte()), "select panel", afterMs = 80)
        expectAck(nfc, byteArrayOf(CMD, 0x01), "normal mode", afterMs = 40)
        expectAck(nfc, byteArrayOf(CMD, 0x02), "config 1", afterMs = 40)
        expectAck(nfc, byteArrayOf(CMD, 0x03), "power on", afterMs = 40)
        expectAck(nfc, byteArrayOf(CMD, 0x05), "config 2", afterMs = 40)
        expectAck(nfc, byteArrayOf(CMD, 0x06), "load", afterMs = 20)
    }

    private fun sendImageData(nfc: NfcA, model: EpaperModel, black: ByteArray, red: ByteArray?) {
        nfc.timeout = COMMAND_TIMEOUT_MS
        expectAck(nfc, byteArrayOf(CMD, 0x07, 0x00), "data prep")

        val payload = model.chunkPayload
        val chunks = model.chunkCount
        setStatus("Sending image 0/$chunks")

        if (model.blankFirstPass) {
            val ff = ByteArray(payload) { 0xFF.toByte() }
            for (i in 0 until chunks) {
                sendChunk(nfc, 0x08, ff, i, chunks, progressMax = 45)
            }
        } else {
            for (i in 0 until chunks) {
                val slice = black.sliceChunk(i, payload)
                sendChunk(nfc, 0x08, slice, i, chunks, progressMax = if (red != null) 45 else 88)
            }
        }

        if (model.padTailBytes > 0) {
            val tail = ByteArray(model.padTailBytes) { 0xFF.toByte() }
            sendChunk(nfc, 0x08, tail, chunks, chunks + 1, progressMax = 88)
        }

        expectAck(nfc, byteArrayOf(CMD, 0x18), "data power")

        if (model.blankFirstPass) {
            for (i in 0 until chunks) {
                sendChunk(nfc, 0x19, black.sliceChunk(i, payload), i, chunks, progressBase = 46, progressMax = 88)
            }
        } else if (red != null) {
            for (i in 0 until chunks) {
                sendChunk(nfc, 0x08, red.sliceChunk(i, payload), i, chunks, progressBase = 46, progressMax = 88)
            }
        }
    }

    private fun sendChunk(
        nfc: NfcA,
        subCmd: Int,
        payload: ByteArray,
        index: Int,
        total: Int,
        progressBase: Int = 5,
        progressMax: Int = 88
    ) {
        val frame = ByteArray(3 + payload.size)
        frame[0] = CMD
        frame[1] = subCmd.toByte()
        frame[2] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 3, payload.size)
        expectAck(nfc, frame, "chunk ${index + 1}/$total", retries = CHUNK_RETRIES)
        val span = (progressMax - progressBase).coerceAtLeast(1)
        progress = progressBase + ((index + 1) * span / total)
        if (index == 0 || (index + 1) % 8 == 0 || index + 1 == total) {
            setStatus("Sending image ${index + 1}/$total")
        }
        SystemClock.sleep(if (payload.size >= 100) 8L else 2L)
    }

    private fun refreshAndWait(nfc: NfcA, model: EpaperModel) {
        setStatus("Refreshing display — keep holding")
        progress = 90

        // CD 09 starts the panel waveform. The MCU often stops ACKing while it
        // refreshes, which Android reports as TagLost. Do not close/reconnect —
        // that aborts the refresh and is what produced the fake "Success".
        nfc.timeout = REFRESH_CMD_TIMEOUT_MS
        try {
            val rx = nfc.transceive(byteArrayOf(CMD, 0x09))
            Log.i(TAG, "refresh reply ${rx.toHex()}")
        } catch (e: Exception) {
            Log.w(TAG, "refresh did not ACK (${humanize(e)}); polling busy anyway")
        }

        nfc.timeout = READY_POLL_TIMEOUT_MS
        val started = SystemClock.elapsedRealtime()
        val deadline = started + READY_WAIT_MS
        var polls = 0
        var sawReady = false
        while (SystemClock.elapsedRealtime() < deadline) {
            polls++
            try {
                if (!nfc.isConnected) {
                    throw SessionResetException("Tag lost during refresh")
                }
                val rx = nfc.transceive(byteArrayOf(CMD, 0x0A))
                if (rx.size >= 2 && rx[0] == 0xFF.toByte() && rx[1] == 0x00.toByte()) {
                    sawReady = true
                    break
                }
                Log.v(TAG, "busy poll $polls reply ${rx.toHex()}")
            } catch (e: SessionResetException) {
                throw e
            } catch (e: TagLostException) {
                Log.d(TAG, "busy (tag lost) poll $polls")
            } catch (e: Exception) {
                Log.d(TAG, "busy poll $polls: ${humanize(e)}")
            }
            progress = (90 + (polls.coerceAtMost(20) / 2)).coerceAtMost(98)
            SystemClock.sleep(if (model.slowRefresh) 400L else 200L)
        }

        if (!sawReady) {
            throw TransferException(
                "wait ready",
                "Display did not finish refreshing — keep the phone still and try again"
            )
        }

        val waited = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "refresh ready after ${waited}ms ($polls polls)")
        // A reset panel replies FF 00 immediately. A real 7.5" refresh takes seconds.
        if (model.slowRefresh && waited < MIN_REFRESH_MS) {
            throw SessionResetException("Refresh finished too quickly — panel likely reset")
        }

        nfc.timeout = COMMAND_TIMEOUT_MS
        try {
            expectAck(nfc, byteArrayOf(CMD, 0x04), "power off", retries = 2)
        } catch (e: Exception) {
            Log.w(TAG, "power off after successful refresh: ${humanize(e)}")
        }
        progress = 99
    }

    private fun expectAck(
        nfc: NfcA,
        tx: ByteArray,
        phase: String,
        retries: Int = CMD_RETRIES,
        afterMs: Long = 0L
    ) {
        var lastError: Exception? = null
        repeat(retries) { attempt ->
            try {
                if (!nfc.isConnected) {
                    throw SessionResetException("Tag lost during $phase")
                }
                val rx = nfc.transceive(tx)
                if (rx.size >= 2 && rx[0] == 0.toByte() && rx[1] == 0.toByte()) {
                    if (afterMs > 0) SystemClock.sleep(afterMs)
                    return
                }
                lastError = TransferException(phase, "Bad reply during $phase (${rx.toHex()})")
                Log.w(TAG, "$phase attempt ${attempt + 1}: ${lastError?.message}")
            } catch (e: SessionResetException) {
                throw e
            } catch (e: TransferException) {
                lastError = e
            } catch (e: TagLostException) {
                throw SessionResetException("Tag lost during $phase")
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "$phase attempt ${attempt + 1} failed: ${humanize(e)}")
            }
            SystemClock.sleep(20L * (attempt + 1))
        }
        val msg = humanize(lastError)
        if (msg.contains("Tag lost", ignoreCase = true) || msg.contains("out of date", ignoreCase = true)) {
            throw SessionResetException("Tag lost during $phase")
        }
        throw TransferException(phase, "Failed during $phase ($msg)")
    }

    private fun ensureConnected(nfc: NfcA, timeoutMs: Int): Boolean {
        return try {
            if (!nfc.isConnected) {
                nfc.connect()
            }
            nfc.timeout = timeoutMs
            nfc.isConnected
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${humanize(e)}")
            false
        }
    }

    private fun quietlyClose(nfc: NfcA) {
        try {
            if (nfc.isConnected) nfc.close()
        } catch (_: Exception) {
        }
    }

    private fun setStatus(msg: String) {
        status = msg
        Log.i(TAG, msg)
    }

    private fun humanize(e: Throwable?): String {
        val raw = e?.message ?: e?.javaClass?.simpleName ?: "unknown error"
        return when {
            raw.contains("Tag was lost", ignoreCase = true) ->
                "Tag lost — hold still and try again"
            raw.contains("out of date", ignoreCase = true) ->
                "NFC handle expired — lift and tap again"
            else -> raw
        }
    }

    private fun ByteArray.sliceChunk(index: Int, payload: Int): ByteArray {
        val start = index * payload
        val end = (start + payload).coerceAtMost(size)
        if (start >= size) return ByteArray(payload) { 0xFF.toByte() }
        if (end - start == payload) return copyOfRange(start, end)
        val out = ByteArray(payload) { 0xFF.toByte() }
        System.arraycopy(this, start, out, 0, end - start)
        return out
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    data class FlashResult(val success: Boolean, val errMessage: String)

    private class TransferException(val phase: String, message: String) : Exception(message)
    private class SessionResetException(message: String) : Exception(message)

    data class PackedPlanes(val black: ByteArray, val red: ByteArray?)

    data class EpaperModel(
        val sdkEnum: Int,
        val width: Int,
        val height: Int,
        val packedRows: Int,
        val epdType: Int,
        val chunkPayload: Int,
        val chunkCount: Int,
        val rotate270: Boolean,
        val hasRed: Boolean,
        val blankFirstPass: Boolean,
        val padTailBytes: Int,
        val slowRefresh: Boolean
    ) {
        companion object {
            fun fromSdkEnum(n: Int): EpaperModel? = when (n) {
                1 -> EpaperModel(1, 250, 128, 122, 4, 16, 250, true, false, false, 0, false)
                2 -> EpaperModel(2, 296, 128, 128, 7, 16, 296, true, false, false, 0, false)
                3 -> EpaperModel(3, 400, 300, 300, 10, 100, 150, false, false, false, 0, false)
                4 -> EpaperModel(4, 800, 480, 480, 14, 120, 400, false, false, false, 0, true)
                5 -> EpaperModel(5, 880, 528, 528, 17, 120, 484, false, false, false, 110, true)
                6 -> EpaperModel(6, 264, 176, 176, 16, 121, 48, true, false, true, 0, false)
                7 -> EpaperModel(7, 296, 128, 128, 8, 74, 64, true, true, false, 0, false)
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "WsEpaper"
        private const val CMD: Byte = 0xCD.toByte()
        private const val COMMAND_TIMEOUT_MS = 8000
        private const val REFRESH_CMD_TIMEOUT_MS = 2500
        private const val READY_POLL_TIMEOUT_MS = 1500
        private const val READY_WAIT_MS = 90_000L
        private const val MIN_REFRESH_MS = 2500L
        private const val CMD_RETRIES = 3
        private const val CHUNK_RETRIES = 3
        private const val MAX_FULL_ATTEMPTS = 3
        private const val LOCK_STREAK = 12
        private const val LOCK_PROBE_TIMEOUT_MS = 600
        private const val LOCK_GIVE_UP_MS = 25_000L

        fun packBitmap(src: Bitmap, model: EpaperModel): PackedPlanes {
            var bmp = src
            val w = bmp.width
            val h = bmp.height
            val matches =
                (w == model.width && h == model.height) ||
                    (w == model.height && h == model.width)
            if (!matches) {
                throw IllegalArgumentException(
                    "Incorrect image resolution ${w}x${h}, expected ${model.width}x${model.height}"
                )
            }

            if (model.rotate270) {
                val m = Matrix()
                m.setRotate(270f)
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
            }

            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

            val black = if (model.sdkEnum == 1) {
                packMono213(pixels)
            } else {
                packMono(pixels, bmp.width, model.packedRows)
            }
            val red = if (model.hasRed) packRed(pixels, bmp.width, model.packedRows) else null
            return PackedPlanes(black, red)
        }

        private fun packMono(pixels: IntArray, width: Int, rows: Int): ByteArray {
            val bytesPerRow = width / 8
            val out = ByteArray(rows * bytesPerRow)
            for (y in 0 until rows) {
                for (xb in 0 until bytesPerRow) {
                    var packed = 0
                    for (k in 0 until 8) {
                        packed = packed shl 1
                        val px = pixels[k + xb * 8 + y * width]
                        if ((px and 0xFF) > 128) packed = packed or 1
                    }
                    out[y * bytesPerRow + xb] = packed.toByte()
                }
            }
            return out
        }

        /** Official SDK special-cases 2.13" as 250 rows of 16 bytes over a 128-wide buffer. */
        private fun packMono213(pixels: IntArray): ByteArray {
            val out = ByteArray(250 * 16)
            for (y in 0 until 250) {
                for (xb in 0 until 16) {
                    var packed = 0
                    for (k in 0 until 8) {
                        packed = packed shl 1
                        val px = pixels[k + xb * 8 + y * 128]
                        if ((px and 0xFF) > 128) packed = packed or 1
                    }
                    out[y * 16 + xb] = packed.toByte()
                }
            }
            return out
        }

        private fun packRed(pixels: IntArray, width: Int, rows: Int): ByteArray {
            val bytesPerRow = width / 8
            val out = ByteArray(rows * bytesPerRow)
            for (y in 0 until rows) {
                for (xb in 0 until bytesPerRow) {
                    var packed = 0
                    for (k in 0 until 8) {
                        packed = packed shl 1
                        val px = pixels[k + xb * 8 + y * width]
                        val r = Color.red(px)
                        val g = Color.green(px)
                        val b = Color.blue(px)
                        if (r > 150 && g < 150 && b < 200) packed = packed or 1
                    }
                    out[y * bytesPerRow + xb] = packed.toByte()
                }
            }
            return out
        }
    }
}
