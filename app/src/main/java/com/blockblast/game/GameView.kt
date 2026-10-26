package com.blockblast.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    companion object {
        const val N = 8
        private const val LIFT = 1.15f // how far above the finger the dragged piece floats (in cells)

        val BG_TOP: Int = Color.parseColor("#1D2347")
        val BG_BOTTOM: Int = Color.parseColor("#0E1126")
        private val BOARD_BG = Color.parseColor("#151A38")
        private val EMPTY = Color.parseColor("#222952")
        private val PANEL = Color.parseColor("#1F2650")
        private val PILL = Color.parseColor("#2A3163")
        private val GOLD = Color.parseColor("#FFC94D")
        private val ORANGE = Color.parseColor("#FF9F1C")
        private val GREEN = Color.parseColor("#4ADE80")
        private val GREY = Color.parseColor("#8E97C4")

        private val COLORS = intArrayOf(
            Color.parseColor("#FF5A5F"), // red
            Color.parseColor("#FF9F1C"), // orange
            Color.parseColor("#FFD93D"), // yellow
            Color.parseColor("#4ADE80"), // green
            Color.parseColor("#38BDF8"), // blue
            Color.parseColor("#818CF8"), // indigo
            Color.parseColor("#E879F9")  // pink
        )
        private val DARK = IntArray(COLORS.size) { shade(COLORS[it], 0.66f) }

        private fun shade(c: Int, f: Float): Int =
            Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }

    private class Burst(val r: Int, val c: Int, val color: Int, var delay: Float) {
        var t = 0f
    }

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float
    )

    private class Pop(val msg: String, val x: Float, var y: Float, val color: Int, val size: Float) {
        var t = 0f
    }

    // ---------------- state ----------------
    private val prefs = context.getSharedPreferences("blockblast", Context.MODE_PRIVATE)
    private val sfx = SoundFx()
    private val rng = Random(System.nanoTime())

    private val grid = Array(N) { IntArray(N) }          // 0 = empty, 1..7 = colour
    private val tray = arrayOfNulls<Piece>(3)
    private val trayPop = FloatArray(3) { 1f }
    private val previewMask = BooleanArray(N * N)

    private var score = 0
    private var displayScore = 0f
    private var best = prefs.getInt("best", 0)
    private var streak = 0
    private var misses = 0
    private var gameOver = false
    private var newBest = false
    private var overT = 0f

    private var dragIdx = -1
    private var dragX = 0f
    private var dragY = 0f
    private var dragT = 0f
    private var snapValid = false
    private var snapRow = 0
    private var snapCol = 0

    private val bursts = ArrayList<Burst>()
    private val particles = ArrayList<Particle>()
    private val pops = ArrayList<Pop>()

    // layout
    private var cell = 0f
    private var boardL = 0f
    private var boardT = 0f
    private var boardS = 0f
    private var headerH = 0f
    private var trayTop = 0f
    private var trayH = 0f
    private var trayCY = 0f
    private var slotW = 0f
    private val sfxRect = RectF()
    private val sfxHit = RectF()
    private val panelRect = RectF()
    private val btnRect = RectF()

    // paints
    private val bgPaint = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()
    private var lastT = 0L

    init {
        sfx.enabled = prefs.getBoolean("sfx", true)
        if (!restoreState()) newGame()
    }

    // ---------------- game logic ----------------
    fun newGame() {
        for (row in grid) row.fill(0)
        score = 0
        displayScore = 0f
        streak = 0
        misses = 0
        gameOver = false
        newBest = false
        overT = 0f
        dragIdx = -1
        bursts.clear()
        particles.clear()
        pops.clear()
        refillTray()
        invalidate()
    }

    private fun refillTray() {
        var attempts = 0
        do {
            for (i in 0 until 3) {
                tray[i] = Piece(Shapes.randomShape(rng), 1 + rng.nextInt(COLORS.size))
                trayPop[i] = 0f
            }
            attempts++
        } while (attempts < 25 && tray.none { it != null && fitsAnywhere(it) })
    }

    private fun canPlace(p: Piece, row: Int, col: Int): Boolean {
        for (cl in p.cells) {
            val r = row + cl[0]
            val c = col + cl[1]
            if (r < 0 || r >= N || c < 0 || c >= N || grid[r][c] != 0) return false
        }
        return true
    }

    private fun fitsAnywhere(p: Piece): Boolean {
        for (r in 0..N - p.rows) for (c in 0..N - p.cols) if (canPlace(p, r, c)) return true
        return false
    }

    private fun updateSnap() {
        snapValid = false
        java.util.Arrays.fill(previewMask, false)
        val p = tray.getOrNull(dragIdx) ?: return
        val left = dragX - p.cols * cell / 2f
        val top = dragY - cell * LIFT - p.rows * cell
        val fr = (top - boardT) / cell
        val fc = (left - boardL) / cell
        val r0 = floor(fr).toInt()
        val c0 = floor(fc).toInt()
        var bestD = 0.85f
        for (dr in 0..1) for (dc in 0..1) {
            val rr = r0 + dr
            val cc = c0 + dc
            val d = hypot(rr - fr, cc - fc)
            if (d < bestD && canPlace(p, rr, cc)) {
                bestD = d
                snapRow = rr
                snapCol = cc
                snapValid = true
            }
        }
        if (!snapValid) return

        // preview which lines would clear
        val tmp = Array(N) { grid[it].copyOf() }
        for (cl in p.cells) tmp[snapRow + cl[0]][snapCol + cl[1]] = p.color
        for (r in 0 until N) {
            var full = true
            for (c in 0 until N) if (tmp[r][c] == 0) { full = false; break }
            if (full) for (c in 0 until N) previewMask[r * N + c] = true
        }
        for (c in 0 until N) {
            var full = true
            for (r in 0 until N) if (tmp[r][c] == 0) { full = false; break }
            if (full) for (r in 0 until N) previewMask[r * N + c] = true
        }
    }

    private fun placePiece(idx: Int, row: Int, col: Int) {
        val p = tray[idx] ?: return
        for (cl in p.cells) grid[row + cl[0]][col + cl[1]] = p.color
        tray[idx] = null

        val fullRows = ArrayList<Int>()
        val fullCols = ArrayList<Int>()
        for (r in 0 until N) {
            var full = true
            for (c in 0 until N) if (grid[r][c] == 0) { full = false; break }
            if (full) fullRows.add(r)
        }
        for (c in 0 until N) {
            var full = true
            for (r in 0 until N) if (grid[r][c] == 0) { full = false; break }
            if (full) fullCols.add(c)
        }
        val lines = fullRows.size + fullCols.size
        var gained = p.size
        val centerR = row + p.rows / 2f
        val centerC = col + p.cols / 2f

        if (lines > 0) {
            streak++
            misses = 0
            val mark = BooleanArray(N * N)
            for (r in fullRows) for (c in 0 until N) mark[r * N + c] = true
            for (c in fullCols) for (r in 0 until N) mark[r * N + c] = true
            for (r in 0 until N) for (c in 0 until N) {
                if (mark[r * N + c]) {
                    val d = hypot(r + 0.5f - centerR, c + 0.5f - centerC)
                    bursts.add(Burst(r, c, grid[r][c], 0.001f + d * 0.035f))
                    grid[r][c] = 0
                }
            }
            val lineScore = 10 * lines * lines * streak
            gained += lineScore

            var empty = true
            for (r in 0 until N) for (c in 0 until N) if (grid[r][c] != 0) empty = false

            val w = width.toFloat()
            val mid = boardT + boardS * 0.45f
            addPop("+$lineScore", (boardL + centerC * cell).coerceIn(w * 0.15f, w * 0.85f),
                boardT + centerR * cell, Color.WHITE, cell * 0.95f)
            var yy = mid
            val praise = when {
                lines >= 4 -> "AMAZING!"
                lines == 3 -> "EXCELLENT!"
                lines == 2 -> "GREAT!"
                else -> null
            }
            if (praise != null) {
                addPop(praise, w / 2f, yy, GOLD, cell * 1.05f)
                yy += cell * 1.1f
            }
            if (streak >= 2) addPop("COMBO x$streak", w / 2f, yy, ORANGE, cell * 0.8f)
            if (empty) {
                gained += 150
                addPop("PERFECT! +150", w / 2f, mid - cell * 1.2f, GREEN, cell * 0.95f)
                sfx.perfect()
            } else {
                sfx.clear(lines + streak - 1)
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } else {
            misses++
            if (misses >= 3) streak = 0
            sfx.place()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        score += gained
        if (score > best) {
            best = score
            newBest = true
            prefs.edit().putInt("best", best).apply()
        }

        if (tray.all { it == null }) refillTray()
        if (tray.none { it != null && fitsAnywhere(it) }) endGame()
    }

    private fun endGame() {
        gameOver = true
        overT = -0.7f
        prefs.edit().remove("grid").apply()
        postDelayed({ sfx.over() }, 500)
    }

    private fun addPop(msg: String, x: Float, y: Float, color: Int, size: Float) {
        pops.add(Pop(msg, x, y, color, size))
    }

    private fun spawnParticles(b: Burst) {
        val cx = boardL + (b.c + 0.5f) * cell
        val cy = boardT + (b.r + 0.5f) * cell
        repeat(4) {
            val ang = rng.nextFloat() * 6.2831855f
            val sp = cell * (1.5f + rng.nextFloat() * 3.5f)
            val life = 0.5f + rng.nextFloat() * 0.4f
            particles.add(
                Particle(
                    cx, cy, cos(ang) * sp, sin(ang) * sp - cell * 1.5f, life, life,
                    COLORS[b.color - 1], cell * (0.07f + rng.nextFloat() * 0.09f)
                )
            )
        }
    }

    // ---------------- save / restore ----------------
    fun saveState() {
        val ed = prefs.edit()
        ed.putInt("best", best)
        ed.putBoolean("sfx", sfx.enabled)
        if (gameOver) {
            ed.remove("grid")
        } else {
            val g = StringBuilder()
            for (r in 0 until N) for (c in 0 until N) g.append(grid[r][c]).append(',')
            val t = tray.joinToString(",") { if (it == null) "-1:0" else "${it.shape}:${it.color}" }
            ed.putString("grid", g.toString())
            ed.putString("tray", t)
            ed.putInt("score", score)
            ed.putInt("streak", streak)
            ed.putInt("misses", misses)
        }
        ed.apply()
    }

    private fun restoreState(): Boolean {
        try {
            val g = prefs.getString("grid", null) ?: return false
            val t = prefs.getString("tray", null) ?: return false
            val cells = g.split(',').filter { it.isNotEmpty() }.map { it.toInt() }
            if (cells.size != N * N || cells.any { it < 0 || it > COLORS.size }) return false
            val parts = t.split(',')
            if (parts.size != 3) return false
            val pcs = arrayOfNulls<Piece>(3)
            for (i in 0 until 3) {
                val ab = parts[i].split(':')
                val s = ab[0].toInt()
                val col = ab[1].toInt()
                if (s >= 0) {
                    if (s >= Shapes.ALL.size || col < 1 || col > COLORS.size) return false
                    pcs[i] = Piece(s, col)
                }
            }
            for (i in 0 until N * N) grid[i / N][i % N] = cells[i]
            for (i in 0 until 3) tray[i] = pcs[i]
            score = prefs.getInt("score", 0)
            displayScore = score.toFloat()
            streak = prefs.getInt("streak", 0)
            misses = prefs.getInt("misses", 0)
            if (tray.all { it == null }) refillTray()
            if (tray.none { it != null && fitsAnywhere(it) }) {
                newGame()
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ---------------- layout ----------------
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        val fh = h.toFloat()
        boardS = min(fw * 0.94f, fh * 0.50f)
        cell = boardS / N
        boardL = (fw - boardS) / 2f
        headerH = fh * 0.18f
        boardT = headerH
        trayTop = boardT + boardS
        trayH = fh - trayTop
        trayCY = trayTop + trayH * 0.46f
        slotW = fw / 3f
        bgPaint.shader = LinearGradient(0f, 0f, 0f, fh, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)

        val pillH = fw * 0.075f
        val pillW = fw * 0.27f
        sfxRect.set(fw - pillW - fw * 0.05f, headerH * 0.12f, fw - fw * 0.05f, headerH * 0.12f + pillH)
        sfxHit.set(sfxRect)
        sfxHit.inset(-fw * 0.02f, -fw * 0.02f)

        val pw = fw * 0.84f
        val ph = max(fh * 0.36f, fw * 0.78f)
        val pt = (fh - ph) / 2f - fh * 0.02f
        panelRect.set((fw - pw) / 2f, pt, (fw + pw) / 2f, pt + ph)
        btnRect.set(
            panelRect.left + pw * 0.14f, panelRect.top + ph * 0.72f,
            panelRect.right - pw * 0.14f, panelRect.top + ph * 0.90f
        )
    }

    // ---------------- update ----------------
    private fun update(dt: Float) {
        val diff = score - displayScore
        displayScore = if (abs(diff) < 0.5f) score.toFloat() else displayScore + diff * min(1f, dt * 8f)
        if (dragIdx >= 0 && dragT < 1f) dragT = min(1f, dragT + dt * 9f)
        for (i in 0 until 3) if (trayPop[i] < 1f) trayPop[i] = min(1f, trayPop[i] + dt * 4f)
        if (gameOver && overT < 1f) overT = min(1f, overT + dt * 2f)

        val bi = bursts.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            if (b.delay > 0f) {
                b.delay -= dt
                if (b.delay <= 0f) spawnParticles(b)
                continue
            }
            b.t += dt / 0.3f
            if (b.t >= 1f) bi.remove()
        }

        val pi = particles.iterator()
        while (pi.hasNext()) {
            val p = pi.next()
            p.life -= dt
            if (p.life <= 0f) {
                pi.remove()
                continue
            }
            p.vy += cell * 10f * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
        }

        val qi = pops.iterator()
        while (qi.hasNext()) {
            val p = qi.next()
            p.t += dt / 1.1f
            p.y -= cell * 0.9f * dt
            if (p.t >= 1f) qi.remove()
        }
    }

    private fun isAnimating(): Boolean =
        bursts.isNotEmpty() || particles.isNotEmpty() || pops.isNotEmpty() || dragIdx >= 0 ||
            abs(score - displayScore) > 0.5f || (gameOver && overT < 1f) || trayPop.any { it < 1f }

    // ---------------- drawing ----------------
    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        var dt = if (lastT == 0L) 0.016f else (now - lastT) / 1_000_000_000f
        lastT = now
        if (dt > 0.05f) dt = 0.05f
        update(dt)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawHeader(canvas)
        drawBoard(canvas)
        drawBursts(canvas)
        drawTray(canvas)
        drawDragged(canvas)
        drawParticles(canvas)
        drawPops(canvas)
        if (gameOver && overT > 0f) drawGameOver(canvas)

        if (isAnimating()) postInvalidateOnAnimation() else lastT = 0L
    }

    private fun fillRR(cv: Canvas, r: RectF, radius: Float, color: Int, alpha: Int) {
        fill.color = color
        fill.alpha = alpha.coerceIn(0, 255)
        cv.drawRoundRect(r, radius, radius, fill)
    }

    private fun drawLabel(
        cv: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, alpha: Int = 255
    ) {
        text.textAlign = align
        text.textSize = size
        text.color = color
        text.alpha = alpha.coerceIn(0, 255)
        cv.drawText(s, x, y, text)
    }

    private fun drawBlock(cv: Canvas, x: Float, y: Float, s: Float, ci: Int, alpha: Int = 255) {
        if (s <= 0.5f || alpha <= 0) return
        val p = s * 0.045f
        val r = s * 0.2f
        rect.set(x + p, y + p, x + s - p, y + s - p)
        fillRR(cv, rect, r, DARK[ci - 1], alpha)
        rect.set(x + p, y + p, x + s - p, y + s - p - s * 0.07f)
        fillRR(cv, rect, r, COLORS[ci - 1], alpha)
        rect.set(x + s * 0.2f, y + s * 0.16f, x + s * 0.8f, y + s * 0.34f)
        fillRR(cv, rect, s * 0.09f, Color.WHITE, alpha * 70 / 255)
    }

    private fun drawHeader(cv: Canvas) {
        val w = width.toFloat()
        val rowY = sfxRect.centerY() + w * 0.014f
        drawLabel(cv, "BEST $best", w * 0.05f, rowY, w * 0.04f, GOLD, Paint.Align.LEFT)
        if (streak >= 2) drawLabel(cv, "COMBO x$streak", w / 2f, rowY, w * 0.042f, ORANGE)

        fillRR(cv, sfxRect, sfxRect.height() / 2f, PILL, 255)
        drawLabel(
            cv, if (sfx.enabled) "SOUND ON" else "SOUND OFF", sfxRect.centerX(),
            sfxRect.centerY() + w * 0.011f, w * 0.03f, if (sfx.enabled) Color.WHITE else GREY
        )

        drawLabel(cv, "SCORE", w / 2f, headerH * 0.50f, w * 0.036f, GREY)
        val size = min(w * 0.14f, headerH * 0.38f)
        drawLabel(cv, displayScore.toInt().toString(), w / 2f, headerH * 0.50f + size * 0.98f, size, Color.WHITE)
    }

    private fun drawBoard(cv: Canvas) {
        val pad = cell * 0.12f
        rect.set(boardL - pad, boardT - pad, boardL + boardS + pad, boardT + boardS + pad)
        fillRR(cv, rect, cell * 0.3f, BOARD_BG, 255)

        val inset = cell * 0.045f
        for (r in 0 until N) {
            for (c in 0 until N) {
                val x = boardL + c * cell
                val y = boardT + r * cell
                val v = grid[r][c]
                if (v == 0) {
                    rect.set(x + inset, y + inset, x + cell - inset, y + cell - inset)
                    fillRR(cv, rect, cell * 0.2f, EMPTY, 255)
                } else {
                    drawBlock(cv, x, y, cell, v)
                }
            }
        }

        if (dragIdx >= 0 && snapValid) {
            val p = tray[dragIdx] ?: return
            val tt = (System.nanoTime() / 1_000_000L % 100_000L) / 1000f
            val pulse = 0.5f + 0.5f * sin(tt * 7f)
            val lineAlpha = 90 + (70 * pulse).toInt()
            for (i in 0 until N * N) {
                if (previewMask[i]) drawBlock(cv, boardL + (i % N) * cell, boardT + (i / N) * cell, cell, p.color, lineAlpha)
            }
            for (cl in p.cells) {
                drawBlock(
                    cv, boardL + (snapCol + cl[1]) * cell, boardT + (snapRow + cl[0]) * cell,
                    cell, p.color, 120
                )
            }
        }
    }

    private fun drawBursts(cv: Canvas) {
        val inset = cell * 0.045f
        for (b in bursts) {
            val x = boardL + b.c * cell
            val y = boardT + b.r * cell
            if (b.delay > 0f) {
                drawBlock(cv, x, y, cell, b.color)
            } else {
                val t = b.t.coerceIn(0f, 1f)
                val s = cell * (1f - t * t)
                val off = (cell - s) / 2f
                drawBlock(cv, x + off, y + off, s, b.color, (255 * (1f - t * 0.6f)).toInt())
                if (t < 0.5f) {
                    rect.set(x + inset, y + inset, x + cell - inset, y + cell - inset)
                    fillRR(cv, rect, cell * 0.2f, Color.WHITE, ((0.5f - t) * 2f * 210f).toInt())
                }
            }
        }
    }

    private fun traySize(p: Piece): Float =
        min(cell * 0.62f, min(slotW * 0.84f / p.cols, trayH * 0.6f / p.rows))

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private fun drawPiece(cv: Canvas, p: Piece, cx: Float, cy: Float, s: Float, alpha: Int) {
        val left = cx - p.cols * s / 2f
        val top = cy - p.rows * s / 2f
        for (cl in p.cells) drawBlock(cv, left + cl[1] * s, top + cl[0] * s, s, p.color, alpha)
    }

    private fun drawTray(cv: Canvas) {
        for (i in 0 until 3) {
            val p = tray[i] ?: continue
            if (i == dragIdx) continue
            val s = traySize(p) * easeOutBack(trayPop[i])
            drawPiece(cv, p, slotW * (i + 0.5f), trayCY, s, if (fitsAnywhere(p)) 255 else 85)
        }
    }

    private fun drawDragged(cv: Canvas) {
        if (dragIdx < 0) return
        val p = tray[dragIdx] ?: return
        val e = 1f - (1f - dragT) * (1f - dragT)
        val s = traySize(p) + (cell - traySize(p)) * e
        val left = dragX - p.cols * s / 2f
        val top = dragY - cell * LIFT - p.rows * s
        for (cl in p.cells) drawBlock(cv, left + cl[1] * s, top + cl[0] * s, s, p.color)
    }

    private fun drawParticles(cv: Canvas) {
        for (p in particles) {
            val k = (p.life / p.maxLife).coerceIn(0f, 1f)
            fill.color = p.color
            fill.alpha = (255 * min(1f, k * 1.6f)).toInt()
            cv.drawCircle(p.x, p.y, p.size * (0.5f + 0.5f * k), fill)
        }
    }

    private fun drawPops(cv: Canvas) {
        for (p in pops) {
            val a = if (p.t < 0.12f) p.t / 0.12f else if (p.t > 0.65f) (1f - p.t) / 0.35f else 1f
            val sc = 0.7f + 0.3f * min(1f, p.t * 8f)
            val ai = (a.coerceIn(0f, 1f) * 255).toInt()
            drawLabel(cv, p.msg, p.x + p.size * 0.04f, p.y + p.size * 0.04f, p.size * sc, Color.BLACK, alpha = ai / 2)
            drawLabel(cv, p.msg, p.x, p.y, p.size * sc, p.color, alpha = ai)
        }
    }

    private fun drawGameOver(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val a = overT.coerceIn(0f, 1f)
        val ai = (a * 255).toInt()
        fill.color = Color.BLACK
        fill.alpha = (a * 185).toInt()
        cv.drawRect(0f, 0f, w, h, fill)

        cv.save()
        cv.translate(0f, (1f - a) * cell * 2f)
        fillRR(cv, panelRect, w * 0.06f, PANEL, ai)
        val ph = panelRect.height()
        val top = panelRect.top
        val cx = panelRect.centerX()
        drawLabel(cv, "GAME OVER", cx, top + ph * 0.19f, w * 0.095f, Color.WHITE, alpha = ai)
        drawLabel(cv, "SCORE", cx, top + ph * 0.35f, w * 0.04f, GREY, alpha = ai)
        drawLabel(cv, score.toString(), cx, top + ph * 0.35f + w * 0.15f, w * 0.15f, Color.WHITE, alpha = ai)
        val bestY = top + ph * 0.35f + w * 0.25f
        if (newBest) drawLabel(cv, "NEW BEST!", cx, bestY, w * 0.06f, GOLD, alpha = ai)
        else drawLabel(cv, "BEST $best", cx, bestY, w * 0.05f, GREY, alpha = ai)
        fillRR(cv, btnRect, btnRect.height() / 2f, GREEN, ai)
        drawLabel(cv, "PLAY AGAIN", btnRect.centerX(), btnRect.centerY() + w * 0.018f, w * 0.05f, BG_BOTTOM, alpha = ai)
        cv.restore()
    }

    // ---------------- input ----------------
    private fun trayHit(x: Float, y: Float): Int {
        if (y < trayTop - cell * 0.3f) return -1
        val i = (x / slotW).toInt().coerceIn(0, 2)
        return if (tray[i] != null) i else -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (gameOver) {
                    if (overT >= 1f && btnRect.contains(e.x, e.y)) newGame()
                    return true
                }
                if (sfxHit.contains(e.x, e.y)) {
                    sfx.enabled = !sfx.enabled
                    invalidate()
                    return true
                }
                val i = trayHit(e.x, e.y)
                if (i >= 0) {
                    dragIdx = i
                    dragX = e.x
                    dragY = e.y
                    dragT = 0f
                    updateSnap()
                    sfx.pick()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIdx >= 0) {
                    dragX = e.x
                    dragY = e.y
                    updateSnap()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragIdx >= 0) {
                    dragX = e.x
                    dragY = e.y
                    updateSnap()
                    val idx = dragIdx
                    if (snapValid) placePiece(idx, snapRow, snapCol) else trayPop[idx] = 0.2f
                    dragIdx = -1
                    snapValid = false
                    java.util.Arrays.fill(previewMask, false)
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragIdx >= 0) {
                    trayPop[dragIdx] = 0.2f
                    dragIdx = -1
                    snapValid = false
                    java.util.Arrays.fill(previewMask, false)
                    invalidate()
                }
            }
        }
        return true
    }
}
