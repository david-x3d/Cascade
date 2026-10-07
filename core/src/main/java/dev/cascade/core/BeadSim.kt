package dev.cascade.core

import kotlin.math.*
import kotlin.random.Random

/** Metres are normalized to screen width. Solid spheres, projected into a shallow 2D tray.
 * Fixed 240 Hz integration, sequential impulses with precomputed effective masses and split
 * position correction. The broad phase is a counting-sorted grid; bodies are periodically
 * reordered by cell so neighbours stay close in memory. Nothing is allocated in step(). */
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
    /** Solver iterations per substep; the solver exits early once impulses converge. */
    var iterations = 6
    /** Coefficient of restitution for distinct impacts. */
    var restitution = .38f
    /** Coulomb friction coefficient between beads and against the rim. */
    var friction = .32f
    /** In-plane rolling resistance from the tray floor, in widths/s² per unit of normal gravity. */
    var floorDrag = .018f
    /** Gravity component pressing beads into the tray floor (widths/s²). */
    var floorGravity = 2.15f
    var peakImpulse = 0f; private set
    var wallImpulse = 0f; private set
    var impactRadius = radius; private set
    var impactX = .5f; private set
    var scatterEnergy = 0f; private set
    var impacts = 0; private set
    var fingerImpulse = 0f; private set
    var awakeCount = 0; private set
    private var oldAx = 0f; private var oldAy = 0f
    private var stepIndex = 0
    private val cell = radius * 2.25f
    private val cols = ceil(1f / cell).toInt() + 2
    private val rows = ceil(height / cell).toInt() + 2
    private val cellStart = IntArray(cols * rows + 1)
    private val cellKey = IntArray(capacity)
    private val order = IntArray(capacity)
    private val scratchF = FloatArray(capacity)
    private val scratchI = IntArray(capacity)
    private val maxContacts = capacity * 12
    private val ca = IntArray(maxContacts); private val cb = IntArray(maxContacts)
    private val nx = FloatArray(maxContacts); private val ny = FloatArray(maxContacts)
    private val depth = FloatArray(maxContacts); private val normal = FloatArray(maxContacts)
    private val tangent = FloatArray(maxContacts); private val bounce = FloatArray(maxContacts)
    private val bvx = FloatArray(maxContacts); private val bvy = FloatArray(maxContacts)
    private val wa = FloatArray(maxContacts); private val wb = FloatArray(maxContacts)
    private val kn = FloatArray(maxContacts); private val kt = FloatArray(maxContacts)
    private val sa = FloatArray(maxContacts); private val sb = FloatArray(maxContacts)
    private var contacts = 0
    // Warm starting: last step's accumulated impulses, keyed by body pair, with generation stamps.
    private val tableSize = Integer.highestOneBit(maxContacts * 2 - 1) shl 1
    private val tableShift = 32 - Integer.numberOfTrailingZeros(tableSize)
    private var prevKey = IntArray(tableSize); private var prevGen = IntArray(tableSize)
    private var prevN = FloatArray(tableSize); private var prevT = FloatArray(tableSize)
    private var curKey = IntArray(tableSize); private var curGen = IntArray(tableSize)
    private var curN = FloatArray(tableSize); private var curT = FloatArray(tableSize)
    private var generation = 2
    private val remap = IntArray(capacity)
    private var remapped = false
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
    fun resetEvents() {
        peakImpulse = 0f; wallImpulse = 0f; scatterEnergy = 0f; impacts = 0; fingerImpulse = 0f
    }

    /** Vertical jolts make beads hop off the floor and land scattered, like a real tray. */
    fun jolt(strength: Float) {
        if (strength <= 0f) return
        wakeAll()
        for (i in 0 until count) {
            val kick = strength * (.35f + random.nextFloat() * .65f) * invMass[i].coerceAtMost(1.3f)
            val a = random.nextFloat() * 2f * PI.toFloat()
            vx[i] += cos(a) * kick; vy[i] += sin(a) * kick
            omega[i] += (random.nextFloat() - .5f) * kick * 40f
        }
        val hits = (count * strength.coerceAtMost(1f)).toInt()
        impacts += hits; scatterEnergy += strength * strength * count * .02f
        peakImpulse = max(peakImpulse, strength * .9f); impactX = .5f
    }

    /** [spin] and [spinAccel] are the tray's angular velocity and acceleration in screen
     * coordinates (positive turns +x towards +y). Beads feel the matching fictitious forces. */
    fun step(ax: Float, ay: Float, spin: Float = 0f, spinAccel: Float = 0f) {
        if (abs(ax - oldAx) + abs(ay - oldAy) > .065f || abs(spin) > .25f || abs(spinAccel) > 2f) {
            wakeAll(); oldAx = ax; oldAy = ay
        }
        val rotating = spin != 0f || spinAccel != 0f
        val cy0 = height * .5f; val w2 = spin * spin
        contacts = 0; supported.fill(false, 0, count)
        val drag = floorDrag * floorGravity * DT
        val limit = radius * .85f / DT
        for (i in 0 until count) {
            if (!sleeping[i]) {
                var ux = vx[i] + ax * DT; var uy = vy[i] + ay * DT
                if (rotating) {
                    // Euler, centrifugal and Coriolis accelerations about the tray centre.
                    val rx = x[i] - .5f; val ry = y[i] - cy0
                    ux += (spinAccel * ry + w2 * rx + 2f * spin * vy[i]) * DT
                    uy += (-spinAccel * rx + w2 * ry - 2f * spin * vx[i]) * DT
                }
                // Emergency CCD guard: less than half the smallest diameter per substep.
                val speed = sqrt(ux * ux + uy * uy)
                if (speed > limit) { ux *= limit / speed; uy *= limit / speed }
                // Rolling resistance against the floor, never reversing the motion.
                if (speed > 0f) { val k = max(0f, 1f - drag / speed); ux *= k; uy *= k }
                vx[i] = ux; vy[i] = uy
                x[i] += ux * DT; y[i] += uy * DT
                angle[i] = (angle[i] + omega[i] * DT * DEGREES) % 360f
                omega[i] *= .998f
            }
            val gx = (x[i] / cell).toInt().coerceIn(0, cols - 1)
            val gy = (y[i] / cell).toInt().coerceIn(0, rows - 1)
            cellKey[i] = gy * cols + gx
        }
        sortCells()
        if (stepIndex++ % 32 == 0) reorder()
        collide()
        prepare()
        solveAll()
        store()
        finish()
        awakeCount = 0
        for (i in 0 until count) {
            val speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            glow[i] += ((speed / 1.5f).coerceIn(0f, 1f) - glow[i]) * .035f
            if (supported[i] && speed < .07f && abs(omega[i]) * r[i] < .07f) {
                quiet[i] += DT
                if (quiet[i] > .65f) { sleeping[i] = true; vx[i] = 0f; vy[i] = 0f; omega[i] = 0f }
            } else { quiet[i] = 0f; sleeping[i] = false }
            if (!sleeping[i]) awakeCount++
        }
    }

    private fun sortCells() {
        cellStart.fill(0)
        for (i in 0 until count) cellStart[cellKey[i] + 1]++
        for (k in 1 until cellStart.size) cellStart[k] += cellStart[k - 1]
        for (i in 0 until count) { val k = cellKey[i]; order[cellStart[k]] = i; cellStart[k]++ }
        // cellStart now holds cell ends; shift back to starts.
        for (k in cellStart.size - 1 downTo 1) cellStart[k] = cellStart[k - 1]
        cellStart[0] = 0
    }

    /** Spatially sorts body storage so neighbouring beads share cache lines. */
    private fun reorder() {
        permute(x); permute(y); permute(vx); permute(vy); permute(r); permute(invMass)
        permute(omega); permute(angle); permute(glow); permute(quiet)
        for (k in 0 until count) scratchI[k] = tint[order[k]]
        scratchI.copyInto(tint, 0, 0, count)
        for (k in 0 until count) scratchI[k] = if (sleeping[order[k]]) 1 else 0
        for (k in 0 until count) sleeping[k] = scratchI[k] == 1
        for (k in 0 until count) scratchI[k] = cellKey[order[k]]
        scratchI.copyInto(cellKey, 0, 0, count)
        order.copyInto(remap, 0, 0, count); remapped = true
        for (k in 0 until count) order[k] = k
    }

    private fun permute(a: FloatArray) {
        for (k in 0 until count) scratchF[k] = a[order[k]]
        scratchF.copyInto(a, 0, 0, count)
    }

    private fun collide() {
        val anyFinger = fingerOn.any { it }
        for (gy in 0 until rows) for (gx in 0 until cols) {
            val key = gy * cols + gx
            val start = cellStart[key]; val end = cellStart[key + 1]
            for (p in start until end) {
                val i = order[p]
                for (q in p + 1 until end) pair(i, order[q])
                // Forward half-stencil visits each neighbouring cell pair once.
                if (gx + 1 < cols) cellPairs(i, key + 1)
                if (gy + 1 < rows) {
                    if (gx > 0) cellPairs(i, key + cols - 1)
                    cellPairs(i, key + cols)
                    if (gx + 1 < cols) cellPairs(i, key + cols + 1)
                }
                boundary(i)
                if (anyFinger) for (f in 0..9) if (fingerOn[f]) {
                    val dx = fingerX[f] - x[i]; val dy = fingerY[f] - y[i]
                    val d = sqrt(dx * dx + dy * dy).coerceAtLeast(.000001f)
                    if (d < r[i] + fingerRadius) {
                        sleeping[i] = false; quiet[i] = 0f
                        add(i, -2, dx / d, dy / d, r[i] + fingerRadius - d, fingerVx[f], fingerVy[f])
                    }
                }
            }
        }
    }

    private fun cellPairs(i: Int, key: Int) {
        for (q in cellStart[key] until cellStart[key + 1]) pair(i, order[q])
    }

    private fun pair(i: Int, j: Int) {
        val x = x; val y = y; val r = r
        val dx = x[j] - x[i]; val dy = y[j] - y[i]
        val rr = r[i] + r[j] + .0002f
        val d2 = dx * dx + dy * dy
        if (d2 >= rr * rr) return
        if (sleeping[i] && sleeping[j]) { supported[i] = true; supported[j] = true; return }
        val d = sqrt(d2).coerceAtLeast(.000001f)
        add(i, j, dx / d, dy / d, rr - .0002f - d)
    }

    private fun boundary(i: Int) {
        var ux: Float; var uy: Float; var pen: Float
        if (round) {
            val dx = x[i] - .5f; val dy = y[i] - height * .5f
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(.000001f)
            pen = d + r[i] - .5f; ux = dx / d; uy = dy / d
        } else {
            val cr = corner.coerceIn(r[i], min(.5f, height * .5f))
            val cx = x[i].coerceIn(cr, 1f - cr)
            val cy = y[i].coerceIn(cr, height - cr)
            val dx = x[i] - cx; val dy = y[i] - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d <= .000001f) return
            pen = d + r[i] - cr; ux = dx / d; uy = dy / d
        }
        if (pen <= -.0002f) return
        if (sleeping[i]) { supported[i] = true; return }
        add(i, -1, ux, uy, pen)
    }

    private fun add(a: Int, b: Int, ux: Float, uy: Float, pen: Float, uxv: Float = 0f, uyv: Float = 0f) {
        if (contacts == maxContacts) return
        supported[a] = true; if (b >= 0) supported[b] = true
        if (b >= 0 && sleeping[a] != sleeping[b]) {
            val moving = if (sleeping[a]) b else a
            if (vx[moving] * vx[moving] + vy[moving] * vy[moving] > .0225f) {
                sleeping[a] = false; sleeping[b] = false; quiet[a] = 0f; quiet[b] = 0f
            }
        }
        val c = contacts++
        ca[c] = a; cb[c] = b; nx[c] = ux; ny[c] = uy; depth[c] = pen
        bvx[c] = if (b >= 0) vx[b] else uxv; bvy[c] = if (b >= 0) vy[b] else uyv
        val vn = (bvx[c] - vx[a]) * ux + (bvy[c] - vy[a]) * uy
        bounce[c] = if (vn < -.10f) -vn * restitution else 0f
        normal[c] = 0f; tangent[c] = 0f
    }

    /** Sleep state is fixed during solving, so effective masses are computed once per contact. */
    private fun prepare() {
        for (c in 0 until contacts) {
            val a = ca[c]; val b = cb[c]
            val ia = if (sleeping[a]) 0f else invMass[a]
            val ib = if (b >= 0 && !sleeping[b]) invMass[b] else 0f
            wa[c] = ia; wb[c] = ib
            val sum = ia + ib
            kn[c] = if (sum > 0f) 1f / sum else 0f
            // I = 2/5 mr² for a solid sphere; tangential effective mass includes rotation.
            kt[c] = if (sum > 0f) 1f / (3.5f * sum) else 0f
            sa[c] = 2.5f * ia / r[a]
            sb[c] = if (b >= 0) 2.5f * ib / r[b] else 0f
            if (b == -2 || sum == 0f) continue
            val oa = if (remapped) remap[a] else a
            val ob = if (b < 0) -1 else if (remapped) remap[b] else b
            val slot = find(prevKey, prevGen, generation - 1, key(oa, ob))
            if (slot < 0) continue
            val n = prevN[slot] * WARM; val t = (prevT[slot] * WARM).coerceIn(-friction * n, friction * n)
            normal[c] = n; tangent[c] = t
            val ux = nx[c]; val uy = ny[c]
            val px = ux * n - uy * t; val py = uy * n + ux * t
            vx[a] -= px * ia; vy[a] -= py * ia; omega[a] -= t * sa[c]
            if (b >= 0) { vx[b] += px * ib; vy[b] += py * ib; omega[b] -= t * sb[c] }
        }
        remapped = false
    }

    /** Impulses are symmetric under swapping a and b, so the key is order-independent. */
    private fun key(a: Int, b: Int) = if (b < 0) (a shl 16) or 0xffff else (min(a, b) shl 16) or max(a, b)

    private fun find(keys: IntArray, gens: IntArray, gen: Int, key: Int): Int {
        var slot = (key * -0x61c88647) ushr tableShift
        while (gens[slot] == gen) {
            if (keys[slot] == key) return slot
            slot = (slot + 1) and (tableSize - 1)
        }
        return -1
    }

    private fun store() {
        for (c in 0 until contacts) {
            val b = cb[c]
            if (b == -2 || kn[c] == 0f) continue
            val key = key(ca[c], b)
            var slot = (key * -0x61c88647) ushr tableShift
            while (curGen[slot] == generation && curKey[slot] != key) slot = (slot + 1) and (tableSize - 1)
            curGen[slot] = generation; curKey[slot] = key; curN[slot] = normal[c]; curT[slot] = tangent[c]
        }
        var k = prevKey; prevKey = curKey; curKey = k
        k = prevGen; prevGen = curGen; curGen = k
        var f = prevN; prevN = curN; curN = f
        f = prevT; prevT = curT; curT = f
        generation++
    }

    /** Sequential impulses. Arrays are hoisted into locals because ART reloads fields in loops. */
    private fun solveAll() {
        val ca = ca; val cb = cb; val nx = nx; val ny = ny; val kn = kn; val kt = kt
        val wa = wa; val wb = wb; val sa = sa; val sb = sb; val bounce = bounce
        val normal = normal; val tangent = tangent; val bvx = bvx; val bvy = bvy
        val vx = vx; val vy = vy; val omega = omega; val r = r
        val n = contacts; val mu = friction
        for (it in 0 until iterations) {
            var change = 0f
            for (c in 0 until n) {
                val k = kn[c]
                if (k == 0f) continue
                val a = ca[c]; val b = cb[c]
                val ux = nx[c]; val uy = ny[c]
                val dx: Float; val dy: Float; val spinB: Float
                if (b >= 0) { dx = vx[b] - vx[a]; dy = vy[b] - vy[a]; spinB = omega[b] * r[b] }
                else { dx = bvx[c] - vx[a]; dy = bvy[c] - vy[a]; spinB = 0f }
                val old = normal[c]
                val nn = max(0f, old + (bounce[c] - dx * ux - dy * uy) * k)
                normal[c] = nn
                val j = nn - old
                val slip = -dx * uy + dy * ux - omega[a] * r[a] - spinB
                val oldT = tangent[c]
                val limit = mu * nn
                val t = (oldT - slip * kt[c]).coerceIn(-limit, limit)
                tangent[c] = t
                val jt = t - oldT
                val px = ux * j - uy * jt; val py = uy * j + ux * jt
                val ia = wa[c]
                vx[a] -= px * ia; vy[a] -= py * ia
                omega[a] -= jt * sa[c]
                if (b >= 0) {
                    val ib = wb[c]
                    vx[b] += px * ib; vy[b] += py * ib
                    omega[b] -= jt * sb[c]
                }
                val delta = abs(j) + abs(jt)
                if (delta > change) change = delta
            }
            // Early exit once the largest impulse change is negligible.
            if (it >= 3 && change < 1e-5f) break
        }
    }

    private fun finish() {
        for (c in 0 until contacts) {
            val a = ca[c]; val b = cb[c]
            val ia = wa[c]; val ib = wb[c]
            val correction = (depth[c] - .00012f).coerceAtLeast(0f) * .65f * kn[c]
            if (ia > 0f) { x[a] -= nx[c] * correction * ia; y[a] -= ny[c] * correction * ia }
            if (ib > 0f) { x[b] += nx[c] * correction * ib; y[b] += ny[c] * correction * ib }
            // Contact-dependent rolling resistance, independent of the sign of spin.
            val roll = normal[c] * .011f
            if (ia > 0f) omega[a] -= omega[a].coerceIn(-roll / r[a], roll / r[a])
            if (ib > 0f) omega[b] -= omega[b].coerceIn(-roll / r[b], roll / r[b])
            // Resting support impulses must never generate sound or haptics.
            if (bounce[c] > .025f) {
                val impulse = normal[c]
                if (b == -2) { fingerImpulse += impulse; continue }
                if (impulse > peakImpulse) { peakImpulse = impulse; impactRadius = r[a]; impactX = x[a] }
                if (b == -1) wallImpulse = max(wallImpulse, impulse)
                if (impulse > .06f) impacts++
                scatterEnergy += impulse * bounce[c]
            }
        }
    }

    companion object {
        const val DT = 1f / 240f
        private const val DEGREES = 180f / PI.toFloat()
        private const val WARM = .85f
    }
}
