package dev.cascade.core

import android.graphics.*
import kotlin.math.*

/** A small atlas of baked lighting. Only the translucent internal swirl rotates at runtime. */
class BeadSprites(val radius: Float, theme: Int) {
    val size = ceil(radius * 2.7f).toInt().coerceAtLeast(12)
    val center = size / 2f
    val sprites = Array(32) { make(it % 4, it / 4, theme) }
    val swirl: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE; p.strokeWidth = radius * .11f; p.color = 0x308fe7ff
        c.drawArc(center-radius*.55f, center-radius*.65f, center+radius*.5f, center+radius*.6f, 35f, 160f, false, p)
        p.strokeWidth = radius * .045f; p.color = 0x22ffffff
        c.drawArc(center-radius*.3f, center-radius*.45f, center+radius*.65f, center+radius*.5f, 180f, 130f, false, p)
    }
    private fun make(tint: Int, light: Int, theme: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rr = radius * .96f; val cc = center
        val bases = intArrayOf(0xff1261d7.toInt(), 0xffb7cbd8.toInt(), 0xffb97513.toInt(), 0xff128b62.toInt())
        val base = blend(bases[theme.coerceIn(0,3)], Color.WHITE, tint * .035f + light * .055f)
        // A soft footprint extends beyond the sphere; dark rims read as contact occlusion.
        p.shader = RadialGradient(cc, cc + rr*.18f, rr*1.3f, intArrayOf(0xbb000000.toInt(),0x55000000,0), floatArrayOf(0f,.72f,1f), Shader.TileMode.CLAMP)
        c.drawCircle(cc,cc+rr*.18f,rr*1.3f,p)
        p.shader = RadialGradient(cc-rr*.3f,cc-rr*.4f,rr*1.6f,
            intArrayOf(blend(base,Color.WHITE,.52f),base,blend(base,Color.BLACK,.78f)), floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cc,cc,rr,p)
        p.shader = RadialGradient(cc+rr*.28f,cc+rr*.48f,rr*.68f,
            intArrayOf(blend(base,Color.WHITE,.6f) and 0xffffff or 0xa0000000.toInt(),0),null,Shader.TileMode.CLAMP)
        c.drawCircle(cc,cc,rr,p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = rr*.045f
        p.color = blend(base,Color.WHITE,.6f); p.alpha = 125
        c.drawCircle(cc,cc,rr*.97f,p)
        p.style = Paint.Style.FILL; p.alpha = 255
        p.shader = RadialGradient(cc-rr*.38f,cc-rr*.43f,rr*.38f,intArrayOf(0xe6ffffff.toInt(),0),null,Shader.TileMode.CLAMP)
        c.drawCircle(cc-rr*.38f,cc-rr*.43f,rr*.38f,p)
        p.shader = null; p.color = 0xf4ffffff.toInt()
        c.drawOval(cc-rr*.53f,cc-rr*.60f,cc-rr*.17f,cc-rr*.40f,p)
        p.color = 0x65ffffff
        c.drawOval(cc+rr*.40f,cc-rr*.19f,cc+rr*.60f,cc+rr*.17f,p)
        return bmp
    }
    private fun blend(a: Int,b: Int,t: Float) = Color.rgb(
        (Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),
        (Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),
        (Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt())
}
