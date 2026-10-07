package dev.clickety.wear

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Verlet bead simulation inside a round (or rectangular) watch face.
 *
 * Velocities are implicit (`x - px`), measured in pixels per substep. Collisions use a uniform
 * grid with cells the size of one bead, so each bead only checks its 3x3 neighbourhood.
 */
class BeadSim(
    private val width: Float,
    private val height: Float,
    private val round: Boolean,
    val radius: Float,
    maxBeads: Int,
) {
    var count = 0
        private set

    val x = FloatArray(maxBeads)
    val y = FloatArray(maxBeads)
    private val px = FloatArray(maxBeads)
    private val py = FloatArray(maxBeads)

    /** 0..1 brightness boost that flares when a bead moves fast and fades as it settles. */
    val glow = FloatArray(maxBeads)

    /** Per-bead base tint so a resting pile still has some texture. */
    val tint = FloatArray(maxBeads)

    private val cx = width / 2f
    private val cy = height / 2f
    private val bowl = min(width, height) / 2f - radius * 0.6f

    private val cell = radius * 2f
    private val cols = (width / cell).toInt() + 1
    private val rows = (height / cell).toInt() + 1
    private val cellHead = IntArray(cols * rows)
    private val next = IntArray(maxBeads)

    /** Hardest single hit in the last step, in px/substep. Drives click volume. */
    var peakImpact = 0f
        private set

    /** Number of audible hits in the last step. */
    var hits = 0
        private set

    /** Sum of impact speed in the last step. Drives haptics. */
    var impactEnergy = 0f
        private set

    var touchX = Float.NaN
    var touchY = Float.NaN

    fun capacity() = x.size

    fun setCount(target: Int) {
        val n = target.coerceIn(0, capacity())
        while (count < n) spawn()
        count = n
    }

    private fun spawn() {
        val i = count
        // Drop new beads near the centre; the solver separates any overlap within a few frames.
        val a = Random.nextFloat() * 6.2831855f
        val d = sqrt(Random.nextFloat()) * bowl * 0.5f
        x[i] = cx + kotlin.math.cos(a) * d
        y[i] = cy + kotlin.math.sin(a) * d
        px[i] = x[i]
        py[i] = y[i]
        glow[i] = 1f
        tint[i] = Random.nextFloat()
        count++
    }

    /**
     * Advances one frame.
     *
     * @param gx gravity along screen x in px/s²
     * @param gy gravity along screen y in px/s²
     */
    fun step(gx: Float, gy: Float, frameSeconds: Float) {
        peakImpact = 0f
        hits = 0
        impactEnergy = 0f
        val dt = frameSeconds / SUBSTEPS
        val ax = gx * dt * dt
        val ay = gy * dt * dt
        repeat(SUBSTEPS) {
            integrate(ax, ay)
            repeat(SOLVER_ITERATIONS) {
                buildGrid()
                collide()
                constrain()
            }
        }
        for (i in 0 until count) {
            val vx = x[i] - px[i]
            val vy = y[i] - py[i]
            val speed = sqrt(vx * vx + vy * vy)
            glow[i] = max(glow[i] * 0.94f, min(1f, speed / 3f))
        }
    }

    private val maxStep = radius * 0.9f
    private val maxStep2 = maxStep * maxStep

    private fun integrate(ax: Float, ay: Float) {
        val pushing = !touchX.isNaN()
        val reach = radius * 7f
        val reach2 = reach * reach
        for (i in 0 until count) {
            var vx = (x[i] - px[i]) * DAMPING
            var vy = (y[i] - py[i]) * DAMPING
            // Cap speed below one radius per substep so beads can't tunnel through each other.
            val v2 = vx * vx + vy * vy
            if (v2 > maxStep2) {
                val k = maxStep / sqrt(v2)
                vx *= k
                vy *= k
            }
            if (pushing) {
                val dx = x[i] - touchX
                val dy = y[i] - touchY
                val d2 = dx * dx + dy * dy
                if (d2 < reach2 && d2 > 1e-3f) {
                    val d = sqrt(d2)
                    val f = (1f - d / reach) * radius * 0.35f
                    vx += dx / d * f
                    vy += dy / d * f
                }
            }
            px[i] = x[i]
            py[i] = y[i]
            x[i] += vx + ax
            y[i] += vy + ay
        }
    }

    private fun buildGrid() {
        cellHead.fill(-1)
        for (i in 0 until count) {
            val c = cellOf(x[i], y[i])
            next[i] = cellHead[c]
            cellHead[c] = i
        }
    }

    private fun cellOf(px: Float, py: Float): Int {
        val col = (px / cell).toInt().coerceIn(0, cols - 1)
        val row = (py / cell).toInt().coerceIn(0, rows - 1)
        return row * cols + col
    }

    private fun collide() {
        val minDist = radius * 2f
        val minDist2 = minDist * minDist
        for (i in 0 until count) {
            val col = (x[i] / cell).toInt().coerceIn(0, cols - 1)
            val row = (y[i] / cell).toInt().coerceIn(0, rows - 1)
            for (r in max(0, row - 1)..min(rows - 1, row + 1)) {
                for (c in max(0, col - 1)..min(cols - 1, col + 1)) {
                    var j = cellHead[r * cols + c]
                    while (j != -1) {
                        if (j > i) {
                            val dx = x[j] - x[i]
                            val dy = y[j] - y[i]
                            val d2 = dx * dx + dy * dy
                            if (d2 < minDist2 && d2 > 1e-6f) {
                                val d = sqrt(d2)
                                val nx = dx / d
                                val ny = dy / d
                                // Closing speed along the contact normal, before separating.
                                val closing = ((x[i] - px[i]) - (x[j] - px[j])) * nx +
                                    ((y[i] - py[i]) - (y[j] - py[j])) * ny
                                if (closing > HIT_THRESHOLD) recordHit(closing)
                                val push = (minDist - d) * 0.5f
                                x[i] -= nx * push
                                y[i] -= ny * push
                                x[j] += nx * push
                                y[j] += ny * push
                            }
                        }
                        j = next[j]
                    }
                }
            }
        }
    }

    private fun constrain() {
        if (round) {
            val limit = bowl - radius
            for (i in 0 until count) {
                val dx = x[i] - cx
                val dy = y[i] - cy
                val d2 = dx * dx + dy * dy
                if (d2 > limit * limit) {
                    val d = sqrt(d2)
                    val nx = dx / d
                    val ny = dy / d
                    val vx = x[i] - px[i]
                    val vy = y[i] - py[i]
                    val outward = vx * nx + vy * ny
                    if (outward > HIT_THRESHOLD) recordHit(outward)
                    x[i] = cx + nx * limit
                    y[i] = cy + ny * limit
                    // Drop the outward speed and bleed a little tangential speed so beads roll
                    // along the rim instead of skating.
                    val keep = max(outward, 0f)
                    px[i] = x[i] - (vx - nx * keep) * WALL_FRICTION
                    py[i] = y[i] - (vy - ny * keep) * WALL_FRICTION
                }
            }
        } else {
            val lo = radius
            val hiX = width - radius
            val hiY = height - radius
            for (i in 0 until count) {
                if (x[i] < lo) { hitWall(x[i] - px[i]); x[i] = lo; px[i] = lo }
                if (x[i] > hiX) { hitWall(x[i] - px[i]); x[i] = hiX; px[i] = hiX }
                if (y[i] < lo) { hitWall(y[i] - py[i]); y[i] = lo; py[i] = lo }
                if (y[i] > hiY) { hitWall(y[i] - py[i]); y[i] = hiY; py[i] = hiY }
            }
        }
    }

    private fun hitWall(v: Float) {
        val s = kotlin.math.abs(v)
        if (s > HIT_THRESHOLD) recordHit(s)
    }

    private fun recordHit(speed: Float) {
        hits++
        impactEnergy += speed
        if (speed > peakImpact) peakImpact = speed
    }

    private companion object {
        const val SUBSTEPS = 4
        const val SOLVER_ITERATIONS = 2
        const val DAMPING = 0.996f
        const val WALL_FRICTION = 0.97f
        const val HIT_THRESHOLD = 0.9f
    }
}
