package dev.rawheic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Gainmap
import android.os.Build
import android.util.Log

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Converts a DNG (RAW) + JPEG capture pair into a HEIC file that keeps the
 * HDR gain map (ISO 21496-1) attached, so the result stays "Ultra HDR"
 * while being far smaller than the original DNG.
 *
 * Pipeline:
 *  1. Decode the camera's processed JPEG (the ISP tone map — the same base
 *     Samsung's own HEIC shots are built from).
 *  2. Parse the DNG (uncompressed Bayer, the common Samsung layout) into a
 *     linear-light proxy and use its unclipped highlights to build an HDR
 *     rendition blended over the SDR base.
 *  3. Compute the per-pixel log2 gain map between the HDR rendition and the
 *     SDR base.
 *  4. Attach the [Gainmap] to the SDR bitmap and let the platform HEIC
 *     encoder write base + gain map metadata into one HEIC file.
 *
 * If any step is unsupported (JPEG-compressed DNG, no HEIC encoder, older
 * API), the pipeline degrades gracefully: HDR-with-gain-map → SDR HEIC →
 * JPEG. The shot is never lost.
 */
object HeicTranscoder {

    private const val TAG = "HeicTranscoder"

    /** Gain map is computed at this divisor of the full resolution. */
    private const val GAIN_MAP_SCALE = 4

    /** Max log2 headroom stored in the gain map (stops above SDR white). */
    private const val MAX_GAIN_HEADROOM = 5.0f

    /**
     * Byte encoding for the RAW proxy: sRGB tone curve up to SDR white
     * (byte 235), then linear headroom 1.0..[PROXY_MAX_LINEAR] mapped across
     * bytes 236..255. [byteToLinear] is its exact inverse.
     */
    private const val PROXY_WHITE_BYTE = 235
    private const val PROXY_MAX_LINEAR = 4.0f

    data class Result(
        val file: File,
        val bytes: Long,
        val gainMapAttached: Boolean
    )

    fun transcode(
        context: Context,
        dngFile: File?,
        jpegFile: File
    ): Result? {
        val base = decodeBitmap(jpegFile) ?: run {
            Log.e(TAG, "Failed to decode JPEG")
            return null
        }


        val hdrBitmap: Bitmap? = dngFile
            ?.takeIf { it.exists() && it.length() > 0 }
            ?.let { buildHdrRendition(base, it) }

        val gainMap = hdrBitmap?.let { computeGainMap(base, it) }

        val encoded = encodeHeic(context, base, gainMap) ?: run {
            Log.w(TAG, "HEIC encode failed; falling back to JPEG")
            val fallback = File(context.cacheDir, "fallback_${System.currentTimeMillis()}.jpg")
            fallback.outputStream().use { base.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            return Result(fallback, fallback.length(), false)
        }

        hdrBitmap?.recycle()
        return Result(encoded, encoded.length(), gainMap != null)
    }

    // ---------------------------------------------------------------- //
    // Bitmap decoding + HDR rendition
    // ---------------------------------------------------------------- //

    private fun decodeBitmap(file: File): Bitmap? = runCatching {
        FileInputStream(file).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /**
     * Blend unclipped DNG highlights into the SDR base to form an HDR
     * rendition. Blending happens in linear light; the result is re-encoded
     * with headroom above SDR white so the gain map can see it. Only pixels
     * near SDR clipping are modified — everything else keeps the ISP look.
     */
    private fun buildHdrRendition(base: Bitmap, dng: File): Bitmap {
        val raw = decodeDngProxy(dng, base.width, base.height) ?: return base
        val w = base.width
        val h = base.height
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val basePx = IntArray(w * h)
        val rawPx = IntArray(w * h)
        base.getPixels(basePx, 0, w, 0, 0, w, h)
        raw.getPixels(rawPx, 0, w, 0, 0, w, h)
        val outPx = basePx.copyOf()

        for (i in basePx.indices) {
            val br = Color.red(basePx[i])
            val bg = Color.green(basePx[i])
            val bb = Color.blue(basePx[i])
            val clip = max(br, max(bg, bb)) / 255f
            if (clip < 0.97f) continue // not near clipping — keep graded base

            // RAW proxy channels decoded back to linear (0..PROXY_MAX_LINEAR).
            val rr = byteToLinear(Color.red(rawPx[i]))
            val rg = byteToLinear(Color.green(rawPx[i]))
            val rb = byteToLinear(Color.blue(rawPx[i]))
            val rawLuma = 0.2126f * rr + 0.7152f * rg + 0.0722f * rb
            if (rawLuma <= clip) continue // RAW also clipped — nothing to recover

            val headroom = min(1.5f, (rawLuma - clip) / max(clip, 0.05f))
            val baseLinR = srgbDecode(br / 255f)
            val baseLinG = srgbDecode(bg / 255f)
            val baseLinB = srgbDecode(bb / 255f)
            val outLinR = min(PROXY_MAX_LINEAR, baseLinR + (rr - baseLinR) * headroom)
            val outLinG = min(PROXY_MAX_LINEAR, baseLinG + (rg - baseLinG) * headroom)
            val outLinB = min(PROXY_MAX_LINEAR, baseLinB + (rb - baseLinB) * headroom)
            outPx[i] = Color.rgb(
                linearToByte(outLinR),
                linearToByte(outLinG),
                linearToByte(outLinB)
            )
        }
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        raw.recycle()
        return out
    }

    // ---------------------------------------------------------------- //
    // Linear <-> proxy byte encoding
    // ---------------------------------------------------------------- //

    /** sRGB-encoded value (0..1) plus linear headroom 1..4 in bytes 236..255. */
    private fun linearToByte(linear: Float): Int {
        val c = linear.coerceIn(0f, PROXY_MAX_LINEAR)
        return if (c <= 1f) {
            (srgbEncode(c) * PROXY_WHITE_BYTE).toInt().coerceIn(0, PROXY_WHITE_BYTE)
        } else {
            (PROXY_WHITE_BYTE + (c - 1f) / (PROXY_MAX_LINEAR - 1f) * (255 - PROXY_WHITE_BYTE))
                .toInt().coerceIn(PROXY_WHITE_BYTE + 1, 255)
        }
    }

    /** Exact inverse of [linearToByte]. */
    private fun byteToLinear(byte: Int): Float = when {
        byte <= PROXY_WHITE_BYTE -> srgbDecode(byte / PROXY_WHITE_BYTE.toFloat())
        else -> 1f + (byte - PROXY_WHITE_BYTE) / (255f - PROXY_WHITE_BYTE) *
            (PROXY_MAX_LINEAR - 1f)
    }

    private fun srgbEncode(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    private fun srgbDecode(s: Float): Float =
        if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)

    // ---------------------------------------------------------------- //
    // Minimal DNG (TIFF) parser for uncompressed Bayer data
    // ---------------------------------------------------------------- //

    private data class DngInfo(
        val width: Int,
        val height: Int,
        val bitsPerSample: Int,
        val stripOffsets: LongArray,
        val rowsPerStrip: Int,
        // 2x2 CFA pattern: 0=R, 1=G, 2=B. e.g. [0,1,1,2] = RGGB.
        val cfaPattern: IntArray,
        val blackLevel: Int,
        val whiteLevel: Int
    )

    // TIFF tags we need
    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_CFA_PATTERN = 33422
    private const val TAG_SUB_IFDS = 330
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717

    private const val COMPRESSION_UNCOMPRESSED = 1
    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4

    /**
     * Parses an uncompressed Bayer DNG into a small linear RGB proxy.
     * Checks the raw SubIFD first (the DNG spec stores raw data there), then
     * falls back to IFD0. Returns null for unsupported layouts (e.g.
     * JPEG-compressed DNG) so the caller degrades to SDR-only encoding.
     */
    private fun decodeDngProxy(dng: File, targetW: Int, targetH: Int): Bitmap? =
        runCatching {
            val bytes = dng.readBytes()
            val info = parseDng(bytes) ?: return@runCatching null
            val sampleBytes = info.bitsPerSample / 8
            val black = info.blackLevel.coerceAtLeast(0)
            val white = info.whiteLevel
                .coerceIn(1, (1 shl info.bitsPerSample) - 1)
                .toFloat()

            val outW = targetW / GAIN_MAP_SCALE
            val outH = targetH / GAIN_MAP_SCALE
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val px = IntArray(outW * outH)

            fun sampleLinear(x: Int, y: Int): FloatArray {
                val strip = min(y / info.rowsPerStrip, info.stripOffsets.size - 1)
                val rowInStrip = y % info.rowsPerStrip
                val offset = info.stripOffsets[strip].toInt() +
                    rowInStrip * info.width * sampleBytes
                val cfa = info.cfaPattern[(y % 2) * 2 + (x % 2)]
                val pos = offset + x * sampleBytes
                if (pos < 0 || pos + sampleBytes > bytes.size) return FloatArray(3)
                val v = when (sampleBytes) {
                    2 -> ((bytes[pos].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 1].toInt() and 0xFF)
                    else -> bytes[pos].toInt() and 0xFF
                }
                val lin = ((v - black) / (white - black)).coerceIn(0f, PROXY_MAX_LINEAR)
                return when (cfa) {
                    0 -> floatArrayOf(lin, 0f, 0f)
                    1 -> floatArrayOf(0f, lin, 0f)
                    else -> floatArrayOf(0f, 0f, lin)
                }
            }

            // 2x2 box: each output pixel averages one full CFA unit.
            val scaleY = max(2, (info.height / outH / 2) * 2)
            val scaleX = max(2, (info.width / outW / 2) * 2)
            for (oy in 0 until outH) {
                for (ox in 0 until outW) {
                    var r = 0f; var g = 0f; var b = 0f
                    for (dy in 0 until 2) {
                        for (dx in 0 until 2) {
                            val sx = min(ox * scaleX + dx, info.width - 1)
                            val sy = min(oy * scaleY + dy, info.height - 1)
                            val c = sampleLinear(sx, sy)
                            r += c[0]; g += c[1]; b += c[2]
                        }
                    }
                    // Linear values — headroom above 1.0 must survive.
                    px[oy * outW + ox] = Color.rgb(
                        linearToByte(r / 4f),
                        linearToByte(g / 4f),
                        linearToByte(b / 4f)
                    )
                }
            }
            bmp.setPixels(px, 0, outW, 0, 0, outW, outH)
            bmp
        }.onFailure { Log.w(TAG, "DNG parse failed", it) }.getOrNull()

    private data class Ifd(val tags: MutableMap<Int, IntArray> = mutableMapOf())

    /** Parse the TIFF structure, preferring the raw SubIFD over IFD0. */
    private fun parseDng(bytes: ByteArray): DngInfo? {
        if (bytes.size < 8) return null
        val byteOrder = when {
            bytes[0] == 'I'.toByte() && bytes[1] == 'I'.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 'M'.toByte() && bytes[1] == 'M'.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val magic = readShort(bytes, 2, byteOrder)
        if (magic != 42) return null
        val ifd0Offset = readInt(bytes, 4, byteOrder)
        val ifd0 = parseIfd(bytes, ifd0Offset, byteOrder) ?: return null

        // DNG spec: raw data lives in SubIFDs pointed to from IFD0.
        val subOffsets = ifd0.tags[TAG_SUB_IFDS] ?: intArrayOf()
        var rawIfd: Ifd? = null
        for (off in subOffsets) {
            rawIfd = parseIfd(bytes, off, byteOrder)
            if (rawIfd != null) break
        }
        val raw = rawIfd ?: ifd0

        val width = raw.tags[TAG_IMAGE_WIDTH]?.firstOrNull() ?: return null
        val height = raw.tags[TAG_IMAGE_LENGTH]?.firstOrNull() ?: return null
        val bits = raw.tags[TAG_BITS_PER_SAMPLE]?.firstOrNull() ?: return null
        val compression = raw.tags[TAG_COMPRESSION]?.firstOrNull() ?: -1
        val samples = raw.tags[TAG_SAMPLES_PER_PIXEL]?.firstOrNull() ?: 1
        val rowsPerStrip = raw.tags[TAG_ROWS_PER_STRIP]?.firstOrNull() ?: height
        val stripOffsets = raw.tags[TAG_STRIP_OFFSETS] ?: return null
        val cfaPattern = ifd0.tags[TAG_CFA_PATTERN] ?: intArrayOf(0, 1, 1, 2)
        val black = raw.tags[TAG_BLACK_LEVEL]?.firstOrNull() ?: 0
        val white = raw.tags[TAG_WHITE_LEVEL]?.firstOrNull() ?: (1 shl bits) - 1

        if (compression != COMPRESSION_UNCOMPRESSED) {
            Log.w(TAG, "DNG compression=$compression unsupported")
            return null
        }
        if (width <= 0 || height <= 0 || bits !in intArrayOf(8, 16) ||
            samples !in 1..3 || stripOffsets.isEmpty()
        ) {
            Log.w(TAG, "DNG layout unsupported: ${width}x${height} bits=$bits")
            return null
        }
        return DngInfo(
            width, height, bits,
            stripOffsets.map { it.toLong() }.toLongArray(),
            if (rowsPerStrip > 0) rowsPerStrip else height,
            if (cfaPattern.size >= 4) cfaPattern.copyOf(4) else intArrayOf(0, 1, 1, 2),
            black, white
        )
    }

    private fun parseIfd(bytes: ByteArray, offset: Int, order: ByteOrder): Ifd? {
        if (offset < 0 || offset + 2 > bytes.size) return null
        val entryCount = readShort(bytes, offset, order)
        val ifd = Ifd()
        var pos = offset + 2
        repeat(entryCount) {
            if (pos + 12 > bytes.size) return@repeat
            val tag = readShort(bytes, pos, order)
            val type = readShort(bytes, pos + 2, order)
            val count = readInt(bytes, pos + 4, order)
            if (count <= 0) { pos += 12; return@repeat }
            val valueSize = when (type) {
                TYPE_BYTE, TYPE_ASCII -> 1
                TYPE_SHORT -> 2
                TYPE_LONG -> 4
                else -> 0
            }
            if (valueSize == 0) { pos += 12; return@repeat }
            val totalBytes = valueSize * count

            fun readValues(base: Int): IntArray {
                val ints = IntArray(count)
                for (i in 0 until count) {
                    val p = base + i * valueSize
                    if (p + valueSize > bytes.size) break
                    ints[i] = when (type) {
                        TYPE_BYTE, TYPE_ASCII -> bytes[p].toInt() and 0xFF
                        TYPE_SHORT -> readShort(bytes, p, order)
                        TYPE_LONG -> readInt(bytes, p, order)
                        else -> 0
                    }
                }
                return ints
            }

            // TIFF: values up to 4 bytes are stored inline (left-justified at
            // pos+8); larger values are stored at a file offset.
            ifd.tags[tag] =
                if (totalBytes <= 4) readValues(pos + 8)
                else {
                    val dataOffset = readInt(bytes, pos + 8, order)
                    if (dataOffset in 1..(bytes.size - totalBytes)) {
                        readValues(dataOffset)
                    } else {
                        intArrayOf()
                    }
                }
            pos += 12
        }
        return if (ifd.tags.isNotEmpty()) ifd else null
    }

    private fun readShort(b: ByteArray, off: Int, order: ByteOrder): Int =
        ByteBuffer.wrap(b, off, 2).order(order).short.toInt() and 0xFFFF

    private fun readInt(b: ByteArray, off: Int, order: ByteOrder): Int =
        ByteBuffer.wrap(b, off, 4).order(order).int

    // ---------------------------------------------------------------- //
    // Gain map
    // ---------------------------------------------------------------- //

    /**
     * Single-channel log2 gain map between SDR base and HDR rendition per
     * UltraHDR / ISO 21496-1: gain = log2(hdr / sdr) in linear light,
     * clamped to [1, MAX_GAIN_HEADROOM] — SDR is the floor, never dimmed.
     */
    private fun computeGainMap(sdr: Bitmap, hdr: Bitmap): Gainmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching<Gainmap?> {
            val w = sdr.width / GAIN_MAP_SCALE
            val h = sdr.height / GAIN_MAP_SCALE
            val smallSdr = Bitmap.createScaledBitmap(sdr, w, h, true)
            val smallHdr = Bitmap.createScaledBitmap(hdr, w, h, true)
            val gain = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val sdrPx = IntArray(w * h)
            val hdrPx = IntArray(w * h)
            smallSdr.getPixels(sdrPx, 0, w, 0, 0, w, h)
            smallHdr.getPixels(hdrPx, 0, w, 0, 0, w, h)
            val outPx = IntArray(w * h)

            var minGain = MAX_GAIN_HEADROOM
            var maxGain = 1f
            for (i in sdrPx.indices) {
                // Both planes go through the same proxy decode so the ratio
                // is computed in linear light.
                val sLuma = proxyLuma(sdrPx[i])
                val hLuma = proxyLuma(hdrPx[i])
                val ratio = if (sLuma > 0.001f) hLuma / sLuma else 1f
                val g = (ln(ratio) / ln(2f)).coerceIn(1f, MAX_GAIN_HEADROOM)
                val normalized = ((g - 1f) / (MAX_GAIN_HEADROOM - 1f)).coerceIn(0f, 1f)
                val v = (normalized * 255f).toInt().coerceIn(0, 255)
                outPx[i] = Color.rgb(v, v, v)
                minGain = min(minGain, g)
                maxGain = max(maxGain, g)
            }
            gain.setPixels(outPx, 0, w, 0, 0, w, h)
            smallSdr.recycle()
            smallHdr.recycle()

            Gainmap(gain).apply {
                setRatioMax(maxGain, maxGain, maxGain)
                setMinDisplayRatioForHdrTransition(1f)
                setDisplayRatioForFullHdr(maxGain)
            }
        }.onFailure { Log.w(TAG, "gain map computation failed", it) }.getOrNull()
    }

    /** Luma (0..~4 linear) of a pixel encoded with [linearToByte]. */
    private fun proxyLuma(px: Int): Float =
        0.2126f * byteToLinear(Color.red(px)) +
            0.7152f * byteToLinear(Color.green(px)) +
            0.0722f * byteToLinear(Color.blue(px))

    // ---------------------------------------------------------------- //
    // HEIC encoding
    // ---------------------------------------------------------------- //

    /** Encode SDR base + optional gain map to HEIC using the platform encoder. */
    private fun encodeHeic(
        context: Context,
        base: Bitmap,
        gainMap: Gainmap?
    ): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        // Probe that the platform encoder actually produces HEIF output.
        val probeOk = runCatching {
            val probe = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val baos = ByteArrayOutputStream()
            val ok = probe.compress(Bitmap.CompressFormat.HEIF, 90, baos)
            probe.recycle()
            ok && baos.size() > 0
        }.getOrDefault(false)
        if (!probeOk) return null

        val outFile = File(context.cacheDir, "out_${System.currentTimeMillis()}.heic")
        if (gainMap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            base.gainmap = gainMap
        }
        val ok = runCatching {
            outFile.outputStream().use { base.compress(Bitmap.CompressFormat.HEIF, 90, it) }
        }.getOrDefault(false)
        if (!ok || outFile.length() == 0L) {
            outFile.delete()
            return null
        }
        return outFile
    }
}
