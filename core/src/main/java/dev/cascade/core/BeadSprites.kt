package dev.cascade.core

import android.graphics.*
import kotlin.math.*

/** One texture atlas of baked lighting, so every bead, swirl, glint and trail is drawn in a
 * single drawVertices call. Cells are padded by a pixel so filtering never bleeds. */
class BeadSprites(val radius: Float, theme: Int) {
    val size = ceil(radius * 2.7f).toInt().coerceAtLeast(12)
    val center = size / 2f
    private val stride = size + 2
    val bitmap: Bitmap = Bitmap.createBitmap(stride * COLUMNS, stride * COLUMNS, Bitmap.Config.ARGB_8888)
    val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    init {
        val c = Canvas(bitmap)
        for (k in 0 until 32) { c.save(); c.translate(cellX(k), cellY(k)); sprite(c, k % 4, k / 4, theme); c.restore() }
        c.save(); c.translate(cellX(SWIRL), cellY(SWIRL)); swirl(c); c.restore()
        c.save(); c.translate(cellX(GLINT), cellY(GLINT)); glint(c); c.restore()
        c.save(); c.translate(cellX(TRAIL), cellY(TRAIL)); trail(c, theme); c.restore()
    }

    /** Left/top texel of a cell's drawable area. */
    fun cellX(k: Int) = (k % COLUMNS) * stride + 1f
    fun cellY(k: Int) = (k / COLUMNS) * stride + 1f

    private fun sprite(c: Canvas, tint: Int, light: Int, theme: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rr = radius * .96f; val cc = center
        val base = blend(base(theme, tint), Color.WHITE, (if (theme == MIXED) 0f else tint * .035f) + light * .055f)
        // A soft footprint extends beyond the sphere; dark rims read as contact occlusion.
        p.shader = RadialGradient(cc, cc + rr*.18f, rr*1.3f, intArrayOf(0xbb000000.toInt(),0x55000000,0), floatArrayOf(0f,.72f,1f), Shader.TileMode.CLAMP)
        c.drawCircle(cc,cc+rr*.18f,rr*1.3f,p)
        p.shader = RadialGradient(cc-rr*.3f,cc-rr*.4f,rr*1.6f,
            intArrayOf(blend(base,Color.WHITE,.52f),base,blend(base,Color.BLACK,.78f)), floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cc,cc,rr,p)
        // Light refracted through the glass gathers on the side opposite the highlight.
        p.shader = RadialGradient(cc+rr*.28f,cc+rr*.48f,rr*.68f,
            intArrayOf(blend(base,Color.WHITE,.6f) and 0xffffff or 0xa0000000.toInt(),0),null,Shader.TileMode.CLAMP)
        c.drawCircle(cc,cc,rr,p)
        // Fresnel: glass reflects more at grazing angles, so the rim brightens.
        p.shader = RadialGradient(cc,cc,rr,intArrayOf(0,0,blend(base,Color.WHITE,.7f) and 0xffffff or 0x70000000),
            floatArrayOf(0f,.78f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cc,cc,rr,p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = rr*.045f
        p.color = blend(base,Color.WHITE,.6f); p.alpha = 125
        c.drawCircle(cc,cc,rr*.97f,p)
        p.style = Paint.Style.FILL; p.alpha = 255
        p.color = 0x65ffffff
        c.drawOval(cc+rr*.40f,cc-rr*.19f,cc+rr*.60f,cc+rr*.17f,p)
    }

    private fun swirl(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE; p.strokeWidth = radius * .11f; p.color = 0x308fe7ff
        c.drawArc(center-radius*.55f, center-radius*.65f, center+radius*.5f, center+radius*.6f, 35f, 160f, false, p)
        p.strokeWidth = radius * .045f; p.color = 0x22ffffff
        c.drawArc(center-radius*.3f, center-radius*.45f, center+radius*.65f, center+radius*.5f, 180f, 130f, false, p)
    }

    /** The specular highlight, drawn separately so it can follow the device's tilt. */
    private fun glint(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val h = center
        p.shader = RadialGradient(h,h,h*.95f,intArrayOf(0xe6ffffff.toInt(),0x40ffffff,0),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(h,h,h*.95f,p)
        p.shader = null; p.color = 0xf4ffffff.toInt()
        c.drawOval(h-h*.47f,h-h*.43f,h+h*.47f,h+h*.09f,p)
    }

    private fun trail(c: Canvas, theme: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val h = center
        val tone = blend(base(theme, 0), Color.WHITE, .35f) and 0xffffff
        p.shader = RadialGradient(h,h,h,intArrayOf(tone or 0x30000000,tone or 0x14000000,0),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(h,h,h,p)
    }

    private fun base(theme: Int, tint: Int): Int =
        if (theme == MIXED) BASES[intArrayOf(0, 2, 3, 4)[tint]] else BASES[theme.coerceIn(0, BASES.size - 1)]

    private fun blend(a: Int,b: Int,t: Float) = Color.rgb(
        (Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),
        (Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),
        (Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt())

    companion object {
        const val COLUMNS = 6
        const val SWIRL = 32; const val GLINT = 33; const val TRAIL = 34
        val THEMES = arrayOf("Azure","Pearl","Amber","Emerald","Ruby","Amethyst","Smoke","Mixed")
        private const val MIXED = 7
        private val BASES = intArrayOf(0xff1261d7.toInt(), 0xffb7cbd8.toInt(), 0xffb97513.toInt(), 0xff128b62.toInt(),
            0xffc0182f.toInt(), 0xff7a3cc8.toInt(), 0xff4a5260.toInt())
    }
}
