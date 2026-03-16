package com.vortex.emulator.patcher

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Supported patch formats with magic bytes for auto-detection.
 */
enum class PatchType(val displayName: String, val extensions: List<String>) {
    IPS("IPS (International Patching System)", listOf("ips")),
    UPS("UPS (Universal Patching System)", listOf("ups")),
    BPS("BPS (Binary Patching System)", listOf("bps")),
    XDELTA("xdelta3", listOf("xdelta", "xdelta3", "vcdiff")),
    PPF("PPF (PlayStation Patch Format)", listOf("ppf")),
    APS("APS (Advanced Patching System)", listOf("aps")),
    UNKNOWN("Unknown", emptyList());

    companion object {
        fun fromExtension(ext: String): PatchType {
            val lower = ext.lowercase()
            return entries.firstOrNull { type ->
                type.extensions.any { it == lower }
            } ?: UNKNOWN
        }

        fun detect(header: ByteArray): PatchType {
            if (header.size < 5) return UNKNOWN
            val head = String(header, 0, minOf(header.size, 5), Charsets.US_ASCII)
            return when {
                head.startsWith("PATCH") -> IPS
                head.startsWith("UPS1") -> UPS
                head.startsWith("BPS1") -> BPS
                head.startsWith("PPF") -> PPF
                header.size >= 4 && header[0] == 0xD6.toByte() &&
                    header[1] == 0xC3.toByte() && header[2] == 0xC4.toByte() -> XDELTA
                head.startsWith("APS1") || head.startsWith("APS2") -> APS
                else -> UNKNOWN
            }
        }
    }
}

data class PatchResult(
    val success: Boolean,
    val outputSize: Long = 0,
    val message: String = "",
    val checksumBefore: Long = 0,
    val checksumAfter: Long = 0,
    val patchType: PatchType = PatchType.UNKNOWN
)

// --- IPS Engine ---

object IpsEngine {
    private const val EOF_MARKER = 0x454F46 // "EOF"

    fun apply(patchData: ByteArray, romData: ByteArray): ByteArray {
        require(patchData.size >= 5 && String(patchData, 0, 5) == "PATCH") {
            "Invalid IPS patch: missing PATCH header"
        }

        val output = romData.copyOf(romData.size.coerceAtLeast(romData.size))
        var result = output
        var pos = 5

        while (pos + 3 <= patchData.size) {
            val offset = ((patchData[pos].toInt() and 0xFF) shl 16) or
                         ((patchData[pos + 1].toInt() and 0xFF) shl 8) or
                          (patchData[pos + 2].toInt() and 0xFF)
            pos += 3

            if (offset == EOF_MARKER) break

            require(pos + 2 <= patchData.size) { "IPS patch truncated at record size" }
            val size = ((patchData[pos].toInt() and 0xFF) shl 8) or
                        (patchData[pos + 1].toInt() and 0xFF)
            pos += 2

            if (size == 0) {
                // RLE record
                require(pos + 3 <= patchData.size) { "IPS RLE record truncated" }
                val rleSize = ((patchData[pos].toInt() and 0xFF) shl 8) or
                               (patchData[pos + 1].toInt() and 0xFF)
                val rleValue = patchData[pos + 2]
                pos += 3

                if (offset + rleSize > result.size) {
                    result = result.copyOf(offset + rleSize)
                }
                for (i in 0 until rleSize) {
                    result[offset + i] = rleValue
                }
            } else {
                // Normal record
                require(pos + size <= patchData.size) { "IPS normal record truncated" }
                if (offset + size > result.size) {
                    result = result.copyOf(offset + size)
                }
                System.arraycopy(patchData, pos, result, offset, size)
                pos += size
            }
        }

        // IPS32 truncation extension
        if (pos + 3 <= patchData.size) {
            val truncSize = ((patchData[pos].toInt() and 0xFF) shl 16) or
                            ((patchData[pos + 1].toInt() and 0xFF) shl 8) or
                             (patchData[pos + 2].toInt() and 0xFF)
            if (truncSize < result.size) {
                result = result.copyOf(truncSize)
            }
        }

        return result
    }
}

// --- UPS Engine ---

object UpsEngine {
    fun apply(patchData: ByteArray, romData: ByteArray): ByteArray {
        require(patchData.size >= 4 && String(patchData, 0, 4) == "UPS1") {
            "Invalid UPS patch: missing UPS1 header"
        }

        val buf = ByteBuffer.wrap(patchData).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 4

        val inputSize = readVarInt(patchData, pos)
        pos = inputSize.second

        val outputSize = readVarInt(patchData, pos)
        pos = outputSize.second

        require(romData.size.toLong() == inputSize.first || romData.size.toLong() == outputSize.first) {
            "ROM size mismatch: expected ${inputSize.first} or ${outputSize.first}, got ${romData.size}"
        }

        val isReverse = romData.size.toLong() == outputSize.first
        val targetSize = if (isReverse) inputSize.first.toInt() else outputSize.first.toInt()
        val output = ByteArray(targetSize)
        System.arraycopy(romData, 0, output, 0, minOf(romData.size, targetSize))

        var romOffset = 0
        val patchEnd = patchData.size - 12 // 3 CRC32s at end

        while (pos < patchEnd) {
            val relativeOffset = readVarInt(patchData, pos)
            pos = relativeOffset.second
            romOffset += relativeOffset.first.toInt()

            while (pos < patchEnd && romOffset < targetSize) {
                val patchByte = patchData[pos]
                pos++
                if (patchByte == 0.toByte()) {
                    romOffset++
                    break
                }
                val original = if (romOffset < romData.size) romData[romOffset] else 0
                output[romOffset] = (original.toInt() xor patchByte.toInt()).toByte()
                romOffset++
            }
        }

        // Verify CRC32
        if (patchData.size >= 12) {
            val expectedOutputCrc = ByteBuffer.wrap(patchData, patchData.size - 8, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val actualCrc = crc32(output)
            if (!isReverse) {
                require(actualCrc == expectedOutputCrc) {
                    "UPS CRC mismatch: expected ${expectedOutputCrc.toString(16)}, got ${actualCrc.toString(16)}"
                }
            }
        }

        return output
    }

    private fun readVarInt(data: ByteArray, startPos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            pos++
            if (b and 0x80 != 0) {
                result += (b and 0x7F).toLong() shl shift
                break
            }
            result += (b or 0x80).toLong() shl shift
            shift += 7
        }
        return Pair(result, pos)
    }
}

// --- BPS Engine ---

object BpsEngine {
    fun apply(patchData: ByteArray, romData: ByteArray): ByteArray {
        require(patchData.size >= 4 && String(patchData, 0, 4) == "BPS1") {
            "Invalid BPS patch: missing BPS1 header"
        }

        var pos = 4
        val sourceSize = readVarInt(patchData, pos); pos = sourceSize.second
        val targetSize = readVarInt(patchData, pos); pos = targetSize.second
        val metadataSize = readVarInt(patchData, pos); pos = metadataSize.second
        pos += metadataSize.first.toInt() // skip metadata

        val output = ByteArray(targetSize.first.toInt())
        var outputOffset = 0
        var sourceRelOffset = 0
        var targetRelOffset = 0
        val patchEnd = patchData.size - 12 // 3 CRC32s

        while (pos < patchEnd) {
            val data = readVarInt(patchData, pos); pos = data.second
            val command = (data.first and 3).toInt()
            val length = (data.first shr 2).toInt() + 1

            when (command) {
                0 -> { // SourceRead
                    for (i in 0 until length) {
                        if (outputOffset < output.size && outputOffset < romData.size) {
                            output[outputOffset] = romData[outputOffset]
                        }
                        outputOffset++
                    }
                }
                1 -> { // TargetRead
                    for (i in 0 until length) {
                        require(pos < patchEnd) { "BPS TargetRead overflow" }
                        if (outputOffset < output.size) {
                            output[outputOffset] = patchData[pos]
                        }
                        pos++
                        outputOffset++
                    }
                }
                2 -> { // SourceCopy
                    val offsetData = readVarInt(patchData, pos); pos = offsetData.second
                    val signed = offsetData.first
                    sourceRelOffset += if (signed and 1L != 0L) -(signed shr 1).toInt() else (signed shr 1).toInt()
                    for (i in 0 until length) {
                        if (outputOffset < output.size && sourceRelOffset < romData.size) {
                            output[outputOffset] = romData[sourceRelOffset]
                        }
                        outputOffset++
                        sourceRelOffset++
                    }
                }
                3 -> { // TargetCopy
                    val offsetData = readVarInt(patchData, pos); pos = offsetData.second
                    val signed = offsetData.first
                    targetRelOffset += if (signed and 1L != 0L) -(signed shr 1).toInt() else (signed shr 1).toInt()
                    for (i in 0 until length) {
                        if (outputOffset < output.size && targetRelOffset < output.size) {
                            output[outputOffset] = output[targetRelOffset]
                        }
                        outputOffset++
                        targetRelOffset++
                    }
                }
            }
        }

        // Verify CRC32
        val expectedTargetCrc = ByteBuffer.wrap(patchData, patchData.size - 8, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val actualCrc = crc32(output)
        require(actualCrc == expectedTargetCrc) {
            "BPS CRC mismatch: expected ${expectedTargetCrc.toString(16)}, got ${actualCrc.toString(16)}"
        }

        return output
    }

    private fun readVarInt(data: ByteArray, startPos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toLong() and 0xFF
            pos++
            result += (b and 0x7F) shl shift
            if (b and 0x80 != 0L) break
            result += 1L shl shift
            shift += 7
        }
        return Pair(result, pos)
    }
}

// --- PPF Engine (v1, v2, v3) ---

object PpfEngine {
    fun apply(patchData: ByteArray, romData: ByteArray): ByteArray {
        require(patchData.size >= 6 && String(patchData, 0, 3) == "PPF") {
            "Invalid PPF patch: missing PPF header"
        }

        val version = patchData[5].toInt()
        return when (version) {
            0 -> applyV1(patchData, romData)
            1 -> applyV2(patchData, romData)
            2 -> applyV3(patchData, romData)
            else -> throw IllegalArgumentException("Unsupported PPF version: $version")
        }
    }

    private fun applyV1(patchData: ByteArray, romData: ByteArray): ByteArray {
        val output = romData.copyOf()
        var pos = 56
        while (pos + 5 < patchData.size) {
            val offset = ByteBuffer.wrap(patchData, pos, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            pos += 4
            val size = patchData[pos].toInt() and 0xFF
            pos++
            val off = offset.toInt()
            if (off + size <= output.size) {
                System.arraycopy(patchData, pos, output, off, size)
            }
            pos += size
        }
        return output
    }

    private fun applyV2(patchData: ByteArray, romData: ByteArray): ByteArray {
        val output = romData.copyOf()
        var pos = 56
        while (pos + 5 < patchData.size) {
            val offset = ByteBuffer.wrap(patchData, pos, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            pos += 4
            val size = patchData[pos].toInt() and 0xFF
            pos++
            val off = offset.toInt()
            if (off + size <= output.size) {
                System.arraycopy(patchData, pos, output, off, size)
            }
            pos += size
        }
        return output
    }

    private fun applyV3(patchData: ByteArray, romData: ByteArray): ByteArray {
        val output = romData.copyOf()
        // PPF3 has an imagetype byte at offset 56, blockcheck at 57
        var pos = 58
        // Check for undo data flag
        val hasUndoData = patchData[57].toInt() and 0x01 != 0
        while (pos + 9 < patchData.size) {
            val offset = ByteBuffer.wrap(patchData, pos, 8)
                .order(ByteOrder.LITTLE_ENDIAN).long
            pos += 8
            val size = patchData[pos].toInt() and 0xFF
            pos++
            val off = offset.toInt()
            if (off + size <= output.size) {
                System.arraycopy(patchData, pos, output, off, size)
            }
            pos += size
            if (hasUndoData) {
                pos += size // skip undo data
            }
        }
        return output
    }
}

// --- APS Engine (N64 and GBA variants) ---

object ApsEngine {
    fun apply(patchData: ByteArray, romData: ByteArray): ByteArray {
        require(patchData.size >= 4) { "Invalid APS patch: too small" }
        val header = String(patchData, 0, 4)

        return when {
            header == "APS1" -> applyAps1(patchData, romData)
            header.startsWith("APS") -> applyApsN64(patchData, romData)
            else -> throw IllegalArgumentException("Unknown APS variant")
        }
    }

    private fun applyAps1(patchData: ByteArray, romData: ByteArray): ByteArray {
        // GBA APS format
        val output = romData.copyOf()
        var pos = 5 // Skip "APS1" + type byte
        while (pos + 5 < patchData.size) {
            val offset = ByteBuffer.wrap(patchData, pos, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            pos += 4
            val size = patchData[pos].toInt() and 0xFF
            pos++
            val off = offset.toInt()
            // Skip original data
            pos += size
            // Apply patched data
            if (pos + size <= patchData.size && off + size <= output.size) {
                System.arraycopy(patchData, pos, output, off, size)
            }
            pos += size
        }
        return output
    }

    private fun applyApsN64(patchData: ByteArray, romData: ByteArray): ByteArray {
        // N64 APS format
        val output = romData.copyOf()
        var pos = 78 // Skip N64 APS header (78 bytes)
        while (pos + 6 < patchData.size) {
            val offset = ByteBuffer.wrap(patchData, pos, 4)
                .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            pos += 4
            val size = ByteBuffer.wrap(patchData, pos, 2)
                .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            pos += 2
            val off = offset.toInt()
            if (pos + size <= patchData.size && off + size <= output.size) {
                System.arraycopy(patchData, pos, output, off, size)
            }
            pos += size
        }
        return output
    }
}

// --- xdelta3 / VCDIFF Engine ---

object XdeltaEngine {
    // VCDIFF magic: 0xD6C3C4 00
    fun apply(patchData: ByteArray, sourceData: ByteArray): ByteArray {
        require(patchData.size >= 4 &&
            patchData[0] == 0xD6.toByte() &&
            patchData[1] == 0xC3.toByte() &&
            patchData[2] == 0xC4.toByte()) {
            "Invalid xdelta/VCDIFF patch: bad magic bytes"
        }

        val version = patchData[3].toInt() and 0xFF
        require(version == 0 || version == 0x53) {
            "Unsupported VCDIFF version: $version"
        }

        var pos = 4
        // Header indicator
        val headerIndicator = patchData[pos].toInt() and 0xFF
        pos++

        // Secondary compressor ID (if bit 0 set)
        if (headerIndicator and 0x01 != 0) pos++
        // Code table data (if bit 1 set)
        if (headerIndicator and 0x02 != 0) {
            val codeTableLen = readVcdiffInt(patchData, pos)
            pos = codeTableLen.second
            pos += codeTableLen.first
        }
        // Application data (if bit 2 set)
        if (headerIndicator and 0x04 != 0) {
            val appDataLen = readVcdiffInt(patchData, pos)
            pos = appDataLen.second
            pos += appDataLen.first
        }

        val outputChunks = mutableListOf<ByteArray>()

        // Process windows
        while (pos < patchData.size) {
            val windowResult = processWindow(patchData, pos, sourceData, outputChunks)
            pos = windowResult
            if (pos >= patchData.size) break
        }

        // Combine output
        val totalSize = outputChunks.sumOf { it.size }
        val output = ByteArray(totalSize)
        var offset = 0
        for (chunk in outputChunks) {
            System.arraycopy(chunk, 0, output, offset, chunk.size)
            offset += chunk.size
        }

        return output
    }

    private fun processWindow(
        patchData: ByteArray,
        startPos: Int,
        sourceData: ByteArray,
        output: MutableList<ByteArray>
    ): Int {
        var pos = startPos

        // Window indicator
        val winIndicator = patchData[pos].toInt() and 0xFF
        pos++

        var sourceSegment: ByteArray? = null
        var sourceSegmentOffset = 0

        // VCD_SOURCE (bit 0) or VCD_TARGET (bit 1)
        if (winIndicator and 0x01 != 0 || winIndicator and 0x02 != 0) {
            val sourceLen = readVcdiffInt(patchData, pos); pos = sourceLen.second
            val sourcePos = readVcdiffInt(patchData, pos); pos = sourcePos.second

            if (winIndicator and 0x01 != 0) {
                // Source from original file
                val segStart = sourcePos.first
                val segLen = sourceLen.first
                sourceSegment = ByteArray(segLen)
                if (segStart + segLen <= sourceData.size) {
                    System.arraycopy(sourceData, segStart, sourceSegment, 0, segLen)
                }
            } else {
                // Target from output built so far
                val totalOutput = output.sumOf { it.size }
                val combined = ByteArray(totalOutput)
                var off = 0
                for (chunk in output) {
                    System.arraycopy(chunk, 0, combined, off, chunk.size)
                    off += chunk.size
                }
                val segStart = sourcePos.first
                val segLen = sourceLen.first
                sourceSegment = ByteArray(segLen)
                if (segStart + segLen <= combined.size) {
                    System.arraycopy(combined, segStart, sourceSegment, 0, segLen)
                }
            }
        }

        // Delta encoding length
        val deltaLen = readVcdiffInt(patchData, pos); pos = deltaLen.second
        val targetWindowLen = readVcdiffInt(patchData, pos); pos = targetWindowLen.second

        // Delta indicator
        val deltaIndicator = patchData[pos].toInt() and 0xFF
        pos++

        val addRunDataLen = readVcdiffInt(patchData, pos); pos = addRunDataLen.second
        val instructionsLen = readVcdiffInt(patchData, pos); pos = instructionsLen.second
        val copyAddressLen = readVcdiffInt(patchData, pos); pos = copyAddressLen.second

        val addRunData = ByteArray(addRunDataLen.first)
        System.arraycopy(patchData, pos, addRunData, 0, addRunDataLen.first)
        pos += addRunDataLen.first

        val instructions = ByteArray(instructionsLen.first)
        System.arraycopy(patchData, pos, instructions, 0, instructionsLen.first)
        pos += instructionsLen.first

        val copyAddresses = ByteArray(copyAddressLen.first)
        System.arraycopy(patchData, pos, copyAddresses, 0, copyAddressLen.first)
        pos += copyAddressLen.first

        // Execute instructions
        val targetWindow = ByteArray(targetWindowLen.first)
        var targetPos = 0
        var addRunPos = 0
        var instrPos = 0
        var copyAddrPos = 0

        while (instrPos < instructions.size && targetPos < targetWindow.size) {
            val inst = instructions[instrPos].toInt() and 0xFF
            instrPos++

            if (inst == 0) continue // NOOP

            // Default VCDIFF code table
            val type1: Int
            val size1: Int
            val mode1: Int
            val type2: Int
            val size2: Int
            val mode2: Int

            decodeInstruction(inst).let { decoded ->
                type1 = decoded[0]; size1 = decoded[1]; mode1 = decoded[2]
                type2 = decoded[3]; size2 = decoded[4]; mode2 = decoded[5]
            }

            fun getSize(s: Int): Int {
                return if (s == 0) {
                    val v = readVcdiffInt(instructions, instrPos)
                    instrPos = v.second
                    v.first
                } else s
            }

            fun executeOp(type: Int, size: Int, mode: Int) {
                val actualSize = if (size == 0 && type != 0) {
                    val v = readVcdiffInt(instructions, instrPos)
                    instrPos = v.second
                    v.first
                } else size

                when (type) {
                    0 -> {} // NOOP
                    1 -> { // ADD
                        val count = minOf(actualSize, targetWindow.size - targetPos, addRunData.size - addRunPos)
                        System.arraycopy(addRunData, addRunPos, targetWindow, targetPos, count)
                        addRunPos += count
                        targetPos += count
                    }
                    2 -> { // RUN
                        if (addRunPos < addRunData.size) {
                            val byte = addRunData[addRunPos]
                            addRunPos++
                            val count = minOf(actualSize, targetWindow.size - targetPos)
                            for (i in 0 until count) {
                                targetWindow[targetPos + i] = byte
                            }
                            targetPos += count
                        }
                    }
                    3 -> { // COPY
                        val addr = readVcdiffAddress(copyAddresses, copyAddrPos, mode, sourceSegment?.size ?: 0, targetPos)
                        copyAddrPos = addr.second

                        val count = minOf(actualSize, targetWindow.size - targetPos)
                        val sourceSize = sourceSegment?.size ?: 0

                        for (i in 0 until count) {
                            val srcIdx = addr.first + i
                            targetWindow[targetPos + i] = if (srcIdx < sourceSize) {
                                sourceSegment!![srcIdx]
                            } else {
                                targetWindow[srcIdx - sourceSize]
                            }
                        }
                        targetPos += count
                    }
                }
            }

            executeOp(type1, size1, mode1)
            if (type2 != 0) executeOp(type2, size2, mode2)
        }

        output.add(targetWindow)
        return pos
    }

    private fun decodeInstruction(code: Int): IntArray {
        // Simplified default VCDIFF code table
        // Returns: [type1, size1, mode1, type2, size2, mode2]
        return when {
            code == 0 -> intArrayOf(0, 0, 0, 0, 0, 0) // NOOP
            code == 1 -> intArrayOf(1, 0, 0, 0, 0, 0) // ADD size=0 (read from stream)
            code == 2 -> intArrayOf(2, 0, 0, 0, 0, 0) // RUN size=0
            code in 3..18 -> intArrayOf(1, code - 2, 0, 0, 0, 0) // ADD size=1..16
            code in 19..34 -> intArrayOf(3, 0, 0, 0, 0, 0) // COPY mode=0 size=0..15
            code in 35..162 -> {
                // COPY with various sizes and modes
                val adjusted = code - 35
                val mode = adjusted / 16
                val size = (adjusted % 16) + 4
                intArrayOf(3, size, mode, 0, 0, 0)
            }
            code in 163..234 -> {
                // ADD+COPY pairs
                val adjusted = code - 163
                val addSize = (adjusted / 12) + 1
                val copyMode = (adjusted % 12) / 3
                val copySize = (adjusted % 3) + 4
                intArrayOf(1, addSize, 0, 3, copySize, copyMode)
            }
            code in 235..246 -> {
                // COPY+ADD pairs
                val adjusted = code - 235
                val copyMode = adjusted / 3
                val addSize = (adjusted % 3) + 1
                intArrayOf(3, 4, copyMode, 1, addSize, 0)
            }
            else -> intArrayOf(0, 0, 0, 0, 0, 0)
        }
    }

    private fun readVcdiffAddress(
        data: ByteArray, startPos: Int,
        mode: Int, sourceSize: Int, targetPos: Int
    ): Pair<Int, Int> {
        val here = sourceSize + targetPos
        val value = readVcdiffInt(data, startPos)
        val addr = when (mode) {
            0 -> value.first // SELF
            1 -> here - value.first // HERE
            else -> value.first // Near/Same cache — simplified
        }
        return Pair(addr, value.second)
    }

    private fun readVcdiffInt(data: ByteArray, startPos: Int): Pair<Int, Int> {
        var result = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            pos++
            result = (result shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) break
        }
        return Pair(result, pos)
    }
}

// --- Utility ---

fun crc32(data: ByteArray): Long {
    val crc = CRC32()
    crc.update(data)
    return crc.value
}
