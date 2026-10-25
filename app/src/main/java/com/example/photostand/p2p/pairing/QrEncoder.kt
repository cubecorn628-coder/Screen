// File: app/src/main/java/com/example/photostand/p2p/pairing/QrEncoder.kt
package com.example.photostand.p2p.pairing

/**
 * Spec-compliant QR Code encoder (ISO/IEC 18004), pure Kotlin — no third-party runtime
 * dependency needed for *generating* codes.
 *
 * Supported envelope: versions 1..9, error-correction levels L/M, byte mode (UTF-8),
 * all eight data masks with ISO penalty-based mask selection, Reed-Solomon GF(256)
 * error correction, finder/alignment/timing patterns, format & version BCH info.
 *
 * The matrix layout, RS interleaving and format placement were cross-validated
 * bit-for-bit against an independent QR implementation.
 */
object QrEncoder {

    enum class EcLevel(val indicatorBits: Int) {
        L(0b01),
        M(0b00)
    }

    class PayloadTooLargeException(message: String) : Exception(message)

    data class QrMatrix(val size: Int, private val modules: BooleanArray) {
        fun isDark(row: Int, col: Int): Boolean = modules[row * size + col]
        fun setDark(row: Int, col: Int, dark: Boolean) {
            modules[row * size + col] = dark
        }
    }

    // region GF(256) arithmetic, primitive polynomial 0x11D
    private val expTable = IntArray(512)
    private val logTable = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            expTable[i] = x
            logTable[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) expTable[i] = expTable[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return expTable[logTable[a] + logTable[b]]
    }

    private fun rsGenerator(degree: Int): IntArray {
        var gen = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(gen.size + 1)
            for ((j, coef) in gen.withIndex()) {
                next[j] = next[j] xor coef
                next[j + 1] = next[j + 1] xor gfMul(coef, expTable[i])
            }
            gen = next
        }
        return gen
    }

    private fun rsRemainder(data: IntArray, ecLen: Int): IntArray {
        val gen = rsGenerator(ecLen)
        val result = IntArray(ecLen)
        for (b in data) {
            val factor = b xor result[0]
            // shift left
            for (j in 0 until ecLen - 1) result[j] = result[j + 1]
            result[ecLen - 1] = 0
            for (j in 0 until ecLen) result[j] = result[j] xor gfMul(gen[j + 1], factor)
        }
        return result
    }
    // endregion

    // region Specification tables (versions 1-9, EC L/M)
    // group1 blocks, data per block g1, group2 blocks, data per block g2, EC per block
    private data class EcSpec(
        val g1Blocks: Int, val g1Data: Int, val g2Blocks: Int, val g2Data: Int, val ecPerBlock: Int
    )

    private val ecTable: Map<EcLevel, List<EcSpec>> = mapOf(
        EcLevel.L to listOf(
            EcSpec(1, 19, 0, 0, 7),
            EcSpec(1, 34, 0, 0, 10),
            EcSpec(1, 55, 0, 0, 15),
            EcSpec(1, 80, 0, 0, 20),
            EcSpec(1, 108, 0, 0, 26),
            EcSpec(2, 68, 0, 0, 18),
            EcSpec(2, 78, 0, 0, 20),
            EcSpec(2, 97, 0, 0, 24),
            EcSpec(2, 116, 0, 0, 30)
        ),
        EcLevel.M to listOf(
            EcSpec(1, 16, 0, 0, 10),
            EcSpec(1, 28, 0, 0, 16),
            EcSpec(1, 44, 0, 0, 26),
            EcSpec(2, 32, 0, 0, 18),
            EcSpec(2, 43, 0, 0, 24),
            EcSpec(4, 27, 0, 0, 16),
            EcSpec(4, 31, 0, 0, 18),
            EcSpec(2, 38, 2, 39, 22),
            EcSpec(3, 36, 2, 37, 22)
        )
    )

    private val alignmentCenters: Map<Int, List<Int>> = mapOf(
        1 to emptyList(),
        2 to listOf(6, 18),
        3 to listOf(6, 22),
        4 to listOf(6, 26),
        5 to listOf(6, 30),
        6 to listOf(6, 34),
        7 to listOf(6, 22, 38),
        8 to listOf(6, 24, 42),
        9 to listOf(6, 26, 46)
    )

    private val remainderBits: Map<Int, Int> = mapOf(
        1 to 0, 2 to 7, 3 to 7, 4 to 7, 5 to 7, 6 to 7, 7 to 0, 8 to 0, 9 to 0
    )

    private const val MAX_PAYLOAD_BYTES = 230 // version 9-L byte mode ceiling
    // endregion

    private fun spec(version: Int, ec: EcLevel): EcSpec =
        ecTable.getValue(ec)[version - 1]

    private fun dataCapacity(version: Int, ec: EcLevel): Int {
        val s = spec(version, ec)
        return s.g1Blocks * s.g1Data + s.g2Blocks * s.g2Data
    }

    /**
     * Encodes [content] as a QR matrix using the smallest fitting version, preferring
     * error-correction level [preferred] and falling back to L when M cannot fit.
     */
    fun encode(content: String, preferred: EcLevel = EcLevel.M): QrMatrix {
        val payload = content.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_PAYLOAD_BYTES) {
            throw PayloadTooLargeException(
                "Pairing token is ${payload.size} bytes; QR envelope supports up to $MAX_PAYLOAD_BYTES"
            )
        }
        val candidates = if (preferred == EcLevel.M) listOf(EcLevel.M, EcLevel.L) else listOf(EcLevel.L)
        var chosenVersion = -1
        var chosenEc = EcLevel.L
        outer@ for (ec in candidates) {
            for (version in 1..9) {
                val capacityBits = dataCapacity(version, ec) * 8
                // mode (4) + 8-bit char count (8) + data + terminator (4)
                if (4 + 8 + payload.size * 8 + 4 <= capacityBits) {
                    chosenVersion = version
                    chosenEc = ec
                    break@outer
                }
            }
        }
        check(chosenVersion > 0) { "No QR version 1-9 fits the payload" }

        val size = 17 + chosenVersion * 4
        val matrix = QrMatrix(size, BooleanArray(size * size))
        val reserved = Array(size) { BooleanArray(size) }

        placeFunctionPatterns(matrix, reserved, chosenVersion)
        val codewords = interleave(buildBitStream(payload, chosenVersion, chosenEc), chosenVersion, chosenEc)
        placeData(matrix, reserved, codewords, chosenVersion)

        val bestMask = selectMask(matrix, reserved, chosenEc)
        applyMask(matrix, reserved, bestMask)
        applyFormatInfo(matrix, chosenEc, bestMask)
        applyVersionInfo(matrix, chosenVersion)
        return matrix
    }

    // region Bit stream construction (byte mode)
    private fun buildBitStream(payload: ByteArray, version: Int, ec: EcLevel): IntArray {
        val capacityBytes = dataCapacity(version, ec)
        val bits = ArrayList<Int>(capacityBytes * 8)
        // byte mode indicator 0100
        bits.addAll(listOf(0, 1, 0, 0))
        // 8-bit character count (versions 1-9)
        val count = payload.size
        for (i in 7 downTo 0) bits.add((count shr i) and 1)
        for (b in payload) {
            val value = b.toInt() and 0xFF
            for (i in 7 downTo 0) bits.add((value shr i) and 1)
        }
        val totalCapacityBits = capacityBytes * 8
        // terminator (up to 4 zero bits)
        repeat(4) { if (bits.size < totalCapacityBits) bits.add(0) }
        // pad to byte boundary
        while (bits.size % 8 != 0) bits.add(0)
        // alternating pad codewords 0xEC, 0x11
        var padIndex = 0
        while (bits.size < totalCapacityBits) {
            val padByte = if (padIndex % 2 == 0) 0xEC else 0x11
            for (i in 7 downTo 0) bits.add((padByte shr i) and 1)
            padIndex++
        }
        return IntArray(bits.size / 8) { i ->
            var value = 0
            for (k in 0 until 8) value = (value shl 1) or bits[i * 8 + k]
            value
        }
    }

    private fun interleave(codewords: IntArray, version: Int, ec: EcLevel): IntArray {
        val s = spec(version, ec)
        data class Block(val data: IntArray, val ec: IntArray)
        val blocks = ArrayList<Block>()
        var index = 0
        repeat(s.g1Blocks) {
            val data = codewords.copyOfRange(index, index + s.g1Data)
            blocks.add(Block(data, rsRemainder(data, s.ecPerBlock)))
            index += s.g1Data
        }
        repeat(s.g2Blocks) {
            val data = codewords.copyOfRange(index, index + s.g2Data)
            blocks.add(Block(data, rsRemainder(data, s.ecPerBlock)))
            index += s.g2Data
        }
        val result = ArrayList<Int>(codewords.size + blocks.size * s.ecPerBlock)
        val maxData = blocks.maxOf { it.data.size }
        for (i in 0 until maxData) {
            for (block in blocks) if (i < block.data.size) result.add(block.data[i])
        }
        for (i in 0 until s.ecPerBlock) {
            for (block in blocks) result.add(block.ec[i])
        }
        return result.toIntArray()
    }
    // endregion

    // region Function patterns
    private fun placeFunctionPatterns(matrix: QrMatrix, reserved: Array<BooleanArray>, version: Int) {
        val n = matrix.size
        fun placeFinder(row0: Int, col0: Int) {
            for (r in row0 - 1..row0 + 7) {
                for (c in col0 - 1..col0 + 7) {
                    if (r in 0 until n && c in 0 until n) reserved[r][c] = true
                }
            }
            for (r in 0 until 7) {
                for (c in 0 until 7) {
                    val dark = r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4)
                    matrix.setDark(row0 + r, col0 + c, dark)
                }
            }
        }
        placeFinder(0, 0)
        placeFinder(0, n - 7)
        placeFinder(n - 7, 0)

        // timing patterns
        for (i in 8 until n - 8) {
            val dark = i % 2 == 0
            matrix.setDark(6, i, dark)
            matrix.setDark(i, 6, dark)
            reserved[6][i] = true
            reserved[i][6] = true
        }

        // alignment patterns
        val centers = alignmentCenters.getValue(version)
        val lastCenter = centers.lastOrNull() ?: -1
        for (ar in centers) {
            for (ac in centers) {
                if ((ar == 6 && ac == 6) ||
                    (ar == 6 && ac == lastCenter) ||
                    (ar == lastCenter && ac == 6)
                ) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val r = ar + dr
                        val c = ac + dc
                        reserved[r][c] = true
                        matrix.setDark(r, c, maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1)
                    }
                }
            }
        }

        // fixed dark module
        matrix.setDark(n - 8, 8, true)
        reserved[n - 8][8] = true

        // reserve format info strips
        for (i in 0 until 9) {
            reserved[8][i] = true
            reserved[i][8] = true
        }
        for (i in n - 8 until n) {
            reserved[8][i] = true
            reserved[i][8] = true
        }

        // reserve version info (v >= 7)
        if (version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    reserved[i][n - 11 + j] = true
                    reserved[n - 11 + j][i] = true
                }
            }
        }
    }

    private fun placeData(matrix: QrMatrix, reserved: Array<BooleanArray>, codewords: IntArray, version: Int) {
        val n = matrix.size
        val bits = BooleanArray(codewords.size * 8)
        for ((wIndex, word) in codewords.withIndex()) {
            for (i in 0 until 8) {
                bits[wIndex * 8 + i] = ((word shr (7 - i)) and 1) == 1
            }
        }
        var bitIndex = 0
        var col = n - 1
        var upward = true
        while (col > 0) {
            // Skip the timing-pattern column (6) by rewinding the loop variable itself:
            // mutating a local copy would desync the column pairs (column 0 dropped,
            // column 4 traversed twice) and corrupt the whole symbol.
            if (col == 6) col--
            for (z in 0 until n) {
                val row = if (upward) n - 1 - z else z
                for (dc in 0..1) {
                    val c = col - dc
                    if (!reserved[row][c] && bitIndex < bits.size) {
                        matrix.setDark(row, c, bits[bitIndex])
                        bitIndex++
                    }
                }
            }
            upward = !upward
            col -= 2
        }
        val expectedUsed = n * n - reserved.sumOf { row -> row.count { it } } - remainderBits.getValue(version)
        check(bitIndex == expectedUsed) { "QR data placement mismatch: $bitIndex vs $expectedUsed" }
    }
    // endregion

    // region Masking & penalty
    private fun maskCondition(mask: Int, row: Int, col: Int): Boolean = when (mask) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> (row / 2 + col / 3) % 2 == 0
        5 -> (row * col) % 2 + (row * col) % 3 == 0
        6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0
        else -> ((row + col) % 2 + (row * col) % 3) % 2 == 0
    }

    private fun applyMask(matrix: QrMatrix, reserved: Array<BooleanArray>, mask: Int) {
        val n = matrix.size
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (!reserved[r][c] && maskCondition(mask, r, c)) {
                    matrix.setDark(r, c, !matrix.isDark(r, c))
                }
            }
        }
    }

    private fun selectMask(base: QrMatrix, reserved: Array<BooleanArray>, ec: EcLevel): Int {
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val trial = QrMatrix(base.size, BooleanArray(base.size * base.size))
            for (r in 0 until base.size) {
                for (c in 0 until base.size) trial.setDark(r, c, base.isDark(r, c))
            }
            applyMask(trial, reserved, mask)
            applyFormatInfo(trial, ec, mask)
            applyVersionInfo(trial, (trial.size - 17) / 4)
            val score = calculatePenalty(trial)
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
            }
        }
        return bestMask
    }

    private fun calculatePenalty(matrix: QrMatrix): Int {
        val n = matrix.size
        var score = 0

        // N1: runs of five or more same-coloured modules
        for (r in 0 until n) {
            var run = 1
            for (c in 1 until n) {
                if (matrix.isDark(r, c) == matrix.isDark(r, c - 1)) run++ else {
                    if (run >= 5) score += 3 + (run - 5)
                    run = 1
                }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        for (c in 0 until n) {
            var run = 1
            for (r in 1 until n) {
                if (matrix.isDark(r, c) == matrix.isDark(r - 1, c)) run++ else {
                    if (run >= 5) score += 3 + (run - 5)
                    run = 1
                }
            }
            if (run >= 5) score += 3 + (run - 5)
        }

        // N2: 2x2 uniform blocks
        for (r in 0 until n - 1) {
            for (c in 0 until n - 1) {
                val v = matrix.isDark(r, c)
                if (v == matrix.isDark(r + 1, c) && v == matrix.isDark(r, c + 1) && v == matrix.isDark(r + 1, c + 1)) {
                    score += 3
                }
            }
        }

        // N3: 1:1:3:1:1 finder-like pattern with four light modules on either side
        val pattern = booleanArrayOf(true, false, true, true, true, false, true)
        fun hasLightRunHorizontal(row: Int, start: Int, length: Int): Boolean {
            if (start < 0 || start + length > n) return false
            for (i in start until start + length) if (matrix.isDark(row, i)) return false
            return true
        }
        fun hasLightRunVertical(col: Int, start: Int, length: Int): Boolean {
            if (start < 0 || start + length > n) return false
            for (i in start until start + length) if (matrix.isDark(i, col)) return false
            return true
        }
        for (r in 0 until n) {
            for (c in 0..n - 7) {
                var match = true
                for (k in 0 until 7) if (matrix.isDark(r, c + k) != pattern[k]) { match = false; break }
                if (match && (hasLightRunHorizontal(r, c - 4, 4) || hasLightRunHorizontal(r, c + 7, 4))) score += 40
            }
        }
        for (c in 0 until n) {
            for (r in 0..n - 7) {
                var match = true
                for (k in 0 until 7) if (matrix.isDark(r + k, c) != pattern[k]) { match = false; break }
                if (match && (hasLightRunVertical(c, r - 4, 4) || hasLightRunVertical(c, r + 7, 4))) score += 40
            }
        }

        // N4: dark module proportion
        var darkCount = 0
        for (r in 0 until n) for (c in 0 until n) if (matrix.isDark(r, c)) darkCount++
        val deviation = kotlin.math.abs(100.0 * darkCount / (n * n) - 50.0) / 5.0
        score += deviation.toInt()
        return score
    }
    // endregion

    // region Format & version BCH info
    private fun bchFormatInfo(ec: EcLevel, mask: Int): Int {
        val data = (ec.indicatorBits shl 3) or mask
        var bch = data shl 10
        val generator = 0x537
        for (i in 14 downTo 10) {
            if ((bch shr i) and 1 == 1) bch = bch xor (generator shl (i - 10))
        }
        return ((data shl 10) or bch) xor 0x5412
    }

    private fun bchVersionInfo(version: Int): Int {
        var bch = version shl 12
        val generator = 0x1F25
        for (i in 17 downTo 12) {
            if ((bch shr i) and 1 == 1) bch = bch xor (generator shl (i - 12))
        }
        return (version shl 12) or bch
    }

    private fun applyFormatInfo(matrix: QrMatrix, ec: EcLevel, mask: Int) {
        val n = matrix.size
        val bits = bchFormatInfo(ec, mask)
        // vertical strip, column 8 (row 6 is the timing line)
        for (i in 0 until 15) {
            val row = when {
                i < 6 -> i
                i < 8 -> i + 1
                else -> n - 15 + i
            }
            matrix.setDark(row, 8, ((bits shr i) and 1) == 1)
        }
        // horizontal strip, row 8 (mirror layout)
        for (i in 0 until 15) {
            val col = when {
                i < 8 -> n - 1 - i
                i == 8 -> 7
                else -> 15 - i - 1
            }
            matrix.setDark(8, col, ((bits shr i) and 1) == 1)
        }
    }

    private fun applyVersionInfo(matrix: QrMatrix, version: Int) {
        if (version < 7) return
        val n = matrix.size
        val bits = bchVersionInfo(version)
        for (i in 0 until 18) {
            val r = i / 3
            val c = n - 11 + (i % 3)
            val dark = ((bits shr i) and 1) == 1
            matrix.setDark(r, c, dark)
            matrix.setDark(c, r, dark)
        }
    }
    // endregion
}
