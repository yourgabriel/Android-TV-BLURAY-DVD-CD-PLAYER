// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 DiscMount contributors

package dev.discmount.core

/**
 * Reads a title-set IFO (VTS_xx_0.IFO) and works out which parts of the concatenated title VOBs
 * (VTS_xx_1.VOB, VTS_xx_2.VOB, ...) belong to the main program chain.
 *
 * Many discs interleave several picture versions (angles or per-language variants) inside the same VOBs.
 * Playing the raw files then plays every ~1 s unit once per version: timestamps jump back and forth and
 * the picture and sound stutter. Following one program chain's cell list avoids that.
 */
object DvdIfo {
    class Cell(val vobId: Int, val cellId: Int, val blockMode: Int)
    class Plan(
        /** Byte ranges [start, length] within the concatenated title VOBs, in playback order. */
        val ranges: List<LongArray>,
        val pgcIndex: Int, val pgcCount: Int, val seconds: Int, val cells: Int, val skippedCells: Int,
    ) {
        val size: Long get() = ranges.sumOf { it[1] }
    }

    private const val SECTOR = 2048L
    private const val MAX_IFO = 16 * 1024 * 1024

    fun plan(ifo: DiscFile, vobSize: Long): Plan? {
        if (ifo.size < 0x100 || ifo.size > MAX_IFO) return null
        val b = ByteArray(ifo.size.toInt())
        var done = 0
        while (done < b.size) {
            val n = ifo.read(done.toLong(), b, done, b.size - done)
            if (n <= 0) return null
            done += n
        }
        fun u8(o: Int) = b[o].toInt() and 0xFF
        fun be16(o: Int) = (u8(o) shl 8) or u8(o + 1)
        fun be32(o: Int) = (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()
        fun bcd(v: Int) = (v shr 4) * 10 + (v and 15)
        if (String(b, 0, 12, Charsets.US_ASCII) != "DVDVIDEO-VTS") return null

        val pgcit = (be32(0xCC) * SECTOR).toInt()
        val cadt = (be32(0xE0) * SECTOR).toInt()
        if (pgcit <= 0 || cadt <= 0 || pgcit + 8 > b.size || cadt + 8 > b.size) return null

        // Program chains: pick the longest one (first wins on ties).
        val pgcCount = be16(pgcit)
        var best: List<Cell>? = null
        var bestSeconds = -1
        var bestIndex = -1
        for (i in 0 until pgcCount) {
            val srp = pgcit + 8 + i * 8
            if (srp + 8 > b.size) break
            val pgc = pgcit + be32(srp + 4).toInt()
            if (pgc + 0xEC > b.size) continue
            val nCells = u8(pgc + 3)
            val playOff = be16(pgc + 0xE8)
            val posOff = be16(pgc + 0xEA)
            if (nCells == 0 || playOff == 0 || posOff == 0) continue
            if (pgc + playOff + nCells * 24 > b.size || pgc + posOff + nCells * 4 > b.size) continue
            val seconds = bcd(u8(pgc + 4)) * 3600 + bcd(u8(pgc + 5)) * 60 + bcd(u8(pgc + 6))
            if (seconds <= bestSeconds) continue
            best = (0 until nCells).map { c ->
                val pos = pgc + posOff + c * 4
                Cell(be16(pos), u8(pos + 3), u8(pgc + playOff + c * 24) shr 6)
            }
            bestSeconds = seconds
            bestIndex = i
        }
        val cells = best ?: return null

        // Inside an angle block only the first cell (angle 1) is played; block mode 2/3 = further angles.
        val chosen = cells.filter { it.blockMode == 0 || it.blockMode == 1 }

        // Cell address table: one entry per contiguous piece of a cell (interleaved cells have many pieces).
        val count = ((be32(cadt + 4) + 1 - 8) / 12).toInt()
        if (count <= 0 || cadt + 8 + count * 12 > b.size) return null
        val pieces = HashMap<Int, ArrayList<LongArray>>()
        for (i in 0 until count) {
            val e = cadt + 8 + i * 12
            val first = be32(e + 4)
            val last = be32(e + 8)
            if (last < first) continue
            pieces.getOrPut((be16(e) shl 8) or u8(e + 2)) { ArrayList() }.add(longArrayOf(first, last))
        }
        val ranges = ArrayList<LongArray>()
        for (c in chosen) {
            val list = pieces[(c.vobId shl 8) or c.cellId] ?: continue
            for (p in list.sortedBy { it[0] }) {
                val start = p[0] * SECTOR
                val end = minOf((p[1] + 1) * SECTOR, vobSize)
                if (end <= start) continue
                val prev = ranges.lastOrNull()
                if (prev != null && prev[0] + prev[1] == start) prev[1] += end - start
                else ranges.add(longArrayOf(start, end - start))
            }
        }
        if (ranges.isEmpty()) return null
        return Plan(ranges, bestIndex, pgcCount, bestSeconds, chosen.size, cells.size - chosen.size)
    }
}

/** A view of [base] that only contains the given byte ranges, back to back. */
class DvdTitleFile(private val base: DiscFile, private val ranges: List<LongArray>) : DiscFile {
    private val starts = LongArray(ranges.size)
    override val size: Long

    init {
        var acc = 0L
        for ((i, r) in ranges.withIndex()) { starts[i] = acc; acc += r[1] }
        size = acc
    }

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val want = minOf(len.toLong(), size - pos).toInt()
        var done = 0
        while (done < want) {
            val p = pos + done
            var lo = 0
            var hi = ranges.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (starts[mid] <= p) lo = mid else hi = mid - 1 }
            val inRange = p - starts[lo]
            val n = base.read(ranges[lo][0] + inRange, dst, off + done, minOf(ranges[lo][1] - inRange, (want - done).toLong()).toInt())
            if (n <= 0) break
            done += n
        }
        return if (done == 0) -1 else done
    }

    override fun close() { base.close() }
}
