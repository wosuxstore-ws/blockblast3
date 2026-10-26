package com.blockblast.game

import kotlin.random.Random

/** All block shapes. "X" = filled cell. The first number is the spawn weight. */
object Shapes {
    private fun d(weight: Int, vararg rows: String): Pair<Int, List<String>> =
        Pair(weight, rows.toList())

    private val DEFS: List<Pair<Int, List<String>>> = listOf(
        d(1, "X"),
        // lines
        d(3, "XX"), d(3, "X", "X"),
        d(3, "XXX"), d(3, "X", "X", "X"),
        d(2, "XXXX"), d(2, "X", "X", "X", "X"),
        d(1, "XXXXX"), d(1, "X", "X", "X", "X", "X"),
        // squares / rectangles
        d(3, "XX", "XX"),
        d(1, "XXX", "XXX", "XXX"),
        d(2, "XXX", "XXX"), d(2, "XX", "XX", "XX"),
        // small corner L
        d(2, "X.", "XX"), d(2, ".X", "XX"), d(2, "XX", "X."), d(2, "XX", ".X"),
        // big corner L
        d(1, "X..", "X..", "XXX"), d(1, "..X", "..X", "XXX"),
        d(1, "XXX", "X..", "X.."), d(1, "XXX", "..X", "..X"),
        // T
        d(2, "XXX", ".X."), d(2, ".X.", "XXX"), d(2, "X.", "XX", "X."), d(2, ".X", "XX", ".X"),
        // S / Z
        d(2, ".XX", "XX."), d(2, "XX.", ".XX"), d(2, "X.", "XX", ".X"), d(2, ".X", "XX", "X."),
        // L / J tetrominoes
        d(1, "X.", "X.", "XX"), d(1, ".X", ".X", "XX"), d(1, "XX", "X.", "X."), d(1, "XX", ".X", ".X"),
        d(1, "XXX", "X.."), d(1, "XXX", "..X"), d(1, "X..", "XXX"), d(1, "..X", "XXX")
    )

    private val WEIGHTS = IntArray(DEFS.size) { DEFS[it].first }
    private val TOTAL = WEIGHTS.sum()

    val ALL: List<Array<IntArray>> = DEFS.map { def ->
        val rows = def.second
        val cells = ArrayList<IntArray>()
        for (r in rows.indices) {
            for (c in rows[r].indices) {
                if (rows[r][c] == 'X') cells.add(intArrayOf(r, c))
            }
        }
        cells.toTypedArray()
    }

    fun randomShape(rng: Random): Int {
        var x = rng.nextInt(TOTAL)
        for (i in WEIGHTS.indices) {
            x -= WEIGHTS[i]
            if (x < 0) return i
        }
        return 0
    }
}

/** A piece in the tray. color is 1..7 (same value stored in the grid). */
class Piece(val shape: Int, val color: Int) {
    val cells: Array<IntArray> = Shapes.ALL[shape]
    val rows: Int = cells.maxOf { it[0] } + 1
    val cols: Int = cells.maxOf { it[1] } + 1
    val size: Int get() = cells.size
}
