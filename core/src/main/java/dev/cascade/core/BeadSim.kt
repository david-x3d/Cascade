package dev.cascade.core

import kotlin.math.*
import kotlin.random.Random

/** Metres are normalized to screen width. Solid spheres, projected into a shallow 2D tray.
 * Fixed 240 Hz integration, sequential accumulated impulses and split position correction.
 * All broad-phase and contact storage is allocated once, never in step(). */
class BeadSim(val height: Float, val round: Boolean, val radius: Float, val capacity: Int) {
    val x = FloatArray(capacity); val y = FloatArray(capacity)
    val vx = FloatArray(capacity); val vy = FloatArray(capacity)
    val r = FloatArray(capacity); val invMass = FloatArray(capacity)
    val omega = FloatArray(capacity); val angle = FloatArray(capacity)
    val glow = FloatArray(capacity); val tint = IntArray(capacity)
    val sleeping = BooleanArray(capacity)
    private val quiet = FloatArray(capacity)
    private val supported = BooleanArray(capacity)
    var count = 0; private set
    var corner = .075f
    var peakImpulse = 0f; private set
    var wallImpulse = 0f; private set
    var impactRadius = radius; private set
    var scatterEnergy = 0f; private set
    var awakeCount = 0; private set
    private var oldAx = 0f; private var oldAy = 0f
    private val cell = radius * 2.25f
    private val cols = ceil(1f / cell).toInt() + 2
    private val rows = ceil(height / cell).toInt() + 2
    private val heads = IntArray(cols * rows)
    private val next = IntArray(capacity)
    private val maxContacts = capacity * 24
    private val ca = IntArray(maxContacts); private val cb = IntArray(maxContacts)
    private val nx = FloatArray(maxContacts); private val ny = FloatArray(maxContacts)
    private val depth = FloatArray(maxContacts); private val normal = FloatArray(maxContacts)
    private val tangent = FloatArray(maxContacts); private val bounce = FloatArray(maxContacts)
    private val bvx = FloatArray(maxContacts); private val bvy = FloatArray(maxContacts)
    private var contacts = 0
    val fingerX = FloatArray(10); val fingerY = FloatArray(10)
    val fingerVx = FloatArray(10); val fingerVy = FloatArray(10)
    val fingerOn = BooleanArray(10)
    var fingerRadius = .04f
    private val random = Random(731)

    init {
        for (i in 0 until capacity) {
            r[i] = radius * (.9f + random.nextFloat() * .2f)
            invMass[i] = 1f / (r[i] / radius).pow(3)
            tint[i] = random.nextInt(4)
            angle[i] = random.nextFloat() * 360f
        }
    }

    fun setCount(value: Int) {
        val n = value.coerceIn(1, capacity)
        // Deterministic non-overlapping hex packing avoids explosive startup overlaps.
        if (n > count) {
            val spacing = radius * 2.24f
            var index = 0
            var row = 0
            var py = radius * 1.2f
            while (py < height - radius && index < n) {
                var px = radius * 1.2f + (row % 2) * spacing * .5f
                while (px < 1f - radius && index < n) {
                    val dx = px - .5f; val dy = py - height * .5f
                    if (!round || dx * dx + dy * dy < (.5f - radius * 1.3f).pow(2)) {
                        x[index] = px; y[index] = py; vx[index] = 0f; vy[index] = 0f
                        omega[index] = 0f; index++
                    }
                    px += spacing
                }
                py += spacing * .87f; row++
            }
            count = index
        } else count = n
        wakeAll()
    }

    fun wakeAll() { for (i in 0 until count) { sleeping[i] = false; quiet[i] = 0f } }
    fun resetEvents() { peakImpulse = 0f; wallImpulse = 0f; scatterEnergy = 0f }

    fun step(ax: Float, ay: Float) {
        if (abs(ax - oldAx) + abs(ay - oldAy) > .065f) {
            wakeAll(); oldAx = ax; oldAy = ay
        }
        contacts = 0; heads.fill(-1); supported.fill(false)
        for (i in 0 until count) {
            if (!sleeping[i]) {
                vx[i] += ax * DT; vy[i] += ay * DT
                // Emergency CCD guard: less than half the smallest diameter per substep.
                val speed = hypot(vx[i], vy[i]); val limit = radius * .8f / DT
                if (speed > limit) { vx[i] *= limit / speed; vy[i] *= limit / speed }
                x[i] += vx[i] * DT; y[i] += vy[i] * DT
                angle[i] = (angle[i] + omega[i] * DT * 180f / PI.toFloat()) % 360f
                omega[i] *= .998f
            }
            val gx = (x[i] / cell).toInt().coerceIn(0, cols - 1)
            val gy = (y[i] / cell).toInt().coerceIn(0, rows - 1)
            val key = gy * cols + gx; next[i] = heads[key]; heads[key] = i
        }
        for (i in 0 until count) {
            val gx = (x[i] / cell).toInt().coerceIn(0, cols - 1)
            val gy = (y[i] / cell).toInt().coerceIn(0, rows - 1)
            for (yy in max(0, gy - 1)..min(rows - 1, gy + 1)) {
                for (xx in max(0, gx - 1)..min(cols - 1, gx + 1)) {
                    var j = heads[yy * cols + xx]
                    while (j >= 0) {
                        if (j > i) {
                            val dx = x[j] - x[i]; val dy = y[j] - y[i]
                            val rr = r[i] + r[j]; val d2 = dx * dx + dy * dy
                            if (d2 < (rr + .0002f).pow(2)) {
                                val d = sqrt(d2).coerceAtLeast(.000001f)
                                add(i, j, dx / d, dy / d, rr - d)
                            }
                        }
                        j = next[j]
                    }
                }
            }
            boundary(i)
            for (f in 0..9) if (fingerOn[f]) {
                val dx = fingerX[f] - x[i]; val dy = fingerY[f] - y[i]
                val d = hypot(dx, dy).coerceAtLeast(.000001f)
                if (d < r[i] + fingerRadius) {
                    sleeping[i] = false; quiet[i] = 0f
                    add(i, -2, dx / d, dy / d, r[i] + fingerRadius - d, fingerVx[f], fingerVy[f])
                }
            }
        }
        repeat(16) { for (c in 0 until contacts) solve(c) }
        for (c in 0 until contacts) {
            val a = ca[c]; val b = cb[c]
            val ia = if (sleeping[a]) 0f else invMass[a]
            val ib = if (b >= 0 && !sleeping[b]) invMass[b] else 0f
            val correction = (depth[c] - .00012f).coerceAtLeast(0f) * .65f / max(.000001f, ia + ib)
            if (!sleeping[a]) { x[a] -= nx[c] * correction * ia; y[a] -= ny[c] * correction * ia }
            if (b >= 0 && !sleeping[b]) { x[b] += nx[c] * correction * ib; y[b] += ny[c] * correction * ib }
            // Resting support impulses must never generate sound or haptics.
            if (bounce[c] > .025f) {
                val impulse = normal[c]
                if (impulse > peakImpulse) { peakImpulse = impulse; impactRadius = r[a] }
                if (b == -1) wallImpulse = max(wallImpulse, impulse)
                scatterEnergy += impulse * bounce[c]
            }
        }
        awakeCount = 0
        for (i in 0 until count) {
            val speed = hypot(vx[i], vy[i])
            glow[i] += ((speed / 1.5f).coerceIn(0f, 1f) - glow[i]) * .035f
            if (supported[i] && speed < .07f && abs(omega[i]) * r[i] < .07f) {
                quiet[i] += DT
                if (quiet[i] > .65f) { sleeping[i] = true; vx[i] = 0f; vy[i] = 0f; omega[i] = 0f }
            } else { quiet[i] = 0f; sleeping[i] = false }
            if (!sleeping[i]) awakeCount++
        }
    }

    private fun boundary(i: Int) {
        if (round) {
            val dx = x[i] - .5f; val dy = y[i] - height * .5f
            val d = hypot(dx, dy).coerceAtLeast(.000001f)
            val pen = d + r[i] - .5f
            if (pen > -.0002f) add(i, -1, dx / d, dy / d, pen)
        } else {
            val cr = corner.coerceIn(r[i], min(.5f, height * .5f))
            val cx = x[i].coerceIn(cr, 1f - cr)
            val cy = y[i].coerceIn(cr, height - cr)
            val dx = x[i] - cx; val dy = y[i] - cy
            val d = hypot(dx, dy)
            val pen = d + r[i] - cr
            if (pen > -.0002f && d > .000001f) add(i, -1, dx / d, dy / d, pen)
        }
    }

    private fun add(a: Int, b: Int, ux: Float, uy: Float, pen: Float, uxv: Float = 0f, uyv: Float = 0f) {
        if (contacts == maxContacts) return
        supported[a] = true; if (b >= 0) supported[b] = true
        if (b >= 0 && sleeping[a] != sleeping[b]) {
            val moving = if (sleeping[a]) b else a
            if (hypot(vx[moving], vy[moving]) > .15f) {
                sleeping[a] = false; sleeping[b] = false; quiet[a] = 0f; quiet[b] = 0f
            }
        }
        val c = contacts++
        ca[c] = a; cb[c] = b; nx[c] = ux; ny[c] = uy; depth[c] = pen
        bvx[c] = if (b >= 0) vx[b] else uxv; bvy[c] = if (b >= 0) vy[b] else uyv
        val vn = (bvx[c] - vx[a]) * ux + (bvy[c] - vy[a]) * uy
        bounce[c] = if (vn < -.10f) -vn * .38f else 0f
        normal[c] = 0f; tangent[c] = 0f
    }

    private fun solve(c: Int) {
        val a = ca[c]; val b = cb[c]
        if (sleeping[a] && (b < 0 || sleeping[b])) return
        val ia = if (sleeping[a]) 0f else invMass[a]
        val ib = if (b >= 0 && !sleeping[b]) invMass[b] else 0f
        if (ia + ib == 0f) return
        val ux = nx[c]; val uy = ny[c]
        val dx = (if (b >= 0) vx[b] else bvx[c]) - vx[a]
        val dy = (if (b >= 0) vy[b] else bvy[c]) - vy[a]
        val old = normal[c]
        normal[c] = max(0f, old + (bounce[c] - dx * ux - dy * uy) / (ia + ib))
        val j = normal[c] - old
        val slip = -dx * uy + dy * ux - omega[a] * r[a] - (if (b >= 0) omega[b] * r[b] else 0f)
        val oldT = tangent[c]
        // I = 2/5 mr² for a solid sphere; tangential effective mass includes rotation.
        tangent[c] = (oldT - slip / (3.5f * (ia + ib))).coerceIn(-.32f * normal[c], .32f * normal[c])
        val jt = tangent[c] - oldT
        vx[a] -= (ux * j - uy * jt) * ia; vy[a] -= (uy * j + ux * jt) * ia
        omega[a] -= jt * 2.5f * ia / r[a]
        if (b >= 0) {
            vx[b] += (ux * j - uy * jt) * ib; vy[b] += (uy * j + ux * jt) * ib
            omega[b] -= jt * 2.5f * ib / r[b]
        }
        // Contact-dependent rolling resistance, independent of the sign of spin.
        val roll = normal[c] * .0007f
        omega[a] -= omega[a].coerceIn(-roll / r[a], roll / r[a])
        if (b >= 0) omega[b] -= omega[b].coerceIn(-roll / r[b], roll / r[b])
    }

    companion object { const val DT = 1f / 240f }
}
