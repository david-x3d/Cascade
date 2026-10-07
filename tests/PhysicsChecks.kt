import dev.cascade.core.BeadSim
import kotlin.math.*

fun checkFinite(s: BeadSim) {
    for (i in 0 until s.count) {
        check(s.x[i].isFinite() && s.y[i].isFinite() && s.omega[i].isFinite()) { "Non-finite body $i" }
        if (s.round) check(hypot(s.x[i]-.5f,s.y[i]-s.height*.5f)+s.r[i] < .515f) { "Escaped circle" }
        else check(s.x[i] in -.015f..1.015f && s.y[i] in -.015f..(s.height+.015f)) { "Escaped tray" }
    }
}
fun main() {
    // Two unequal masses: momentum conservation, separation and restitution.
    val pair = BeadSim(1f,false,.02f,2); pair.setCount(2)
    pair.x[0]=.47f; pair.x[1]=.51f; pair.y[0]=.5f; pair.y[1]=.5f
    pair.vx[0]=.4f; pair.vx[1]=-.2f
    val momentum = pair.vx[0]/pair.invMass[0]+pair.vx[1]/pair.invMass[1]
    repeat(12) { pair.step(0f,0f) }
    check(abs(momentum-pair.vx[0]/pair.invMass[0]-pair.vx[1]/pair.invMass[1])<.0001f)
    check(pair.vx[1]-pair.vx[0] > .15f) { "No restitution" }
    println("PASS unequal-mass momentum and restitution")
    for (round in listOf(true,false)) {
        val s=BeadSim(if(round) 1f else 2.15f,round,if(round) .016f else .0135f,if(round) 450 else 1200)
        s.setCount(s.capacity)
        check(s.count==s.capacity)
        val start=System.nanoTime()
        repeat(2400) { s.step(0f,2.1f); if(it%240==0) checkFinite(s) }
        var rms=0f; var asleep=0
        for(i in 0 until s.count) { rms+=s.vx[i]*s.vx[i]+s.vy[i]*s.vy[i]; if(s.sleeping[i]) asleep++ }
        rms=sqrt(rms/s.count)
        println("settled round=$round rms=$rms asleep=$asleep/${s.count}")
        check(rms<.04f) { "Pile does not settle" }
        check(asleep>s.count*.75f) { "Sleeping ineffective" }
        s.resetEvents(); repeat(240) { s.step(0f,2.1f) }
        check(s.wallImpulse<.1f) { "Resting pile emits impact events" }
        repeat(960) { tick ->
            val ax=sin(tick*.04f)*10f; val ay=cos(tick*.031f)*10f
            s.step(ax,ay); if(tick%60==0) checkFinite(s)
        }
        check(s.awakeCount>s.count*.5f) { "Shake failed to wake beads" }
        checkFinite(s)
        println("PASS pile, sleep, wake and shake containment; ${((System.nanoTime()-start)/1e6).toInt()} ms")
    }
    val finger=BeadSim(1f,false,.02f,1); finger.setCount(1)
    finger.x[0]=.5f; finger.y[0]=.5f
    finger.fingerOn[0]=true; finger.fingerX[0]=.46f; finger.fingerY[0]=.5f; finger.fingerVx[0]=2f
    finger.step(0f,0f)
    check(finger.vx[0]>1f) { "Kinematic finger did not transfer momentum" }
    println("PASS kinematic throw")
}
