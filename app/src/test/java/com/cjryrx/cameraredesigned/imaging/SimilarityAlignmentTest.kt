package com.cjryrx.cameraredesigned.imaging

import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SimilarityAlignmentTest {
    private val width = 221
    private val height = 163
    private fun scene(x: Double, y: Double): Double =
        .46 + .10*sin(x*.14 + y*.09) + .12*cos(x*.043 - y*.115) +
            .08*sin(x*.14 + cos(y*.036)*5) + .07*cos(x*.056 + sin(y*.048)*7 + y*.08)

    private fun transformed(scale: Double, angle: Double): Similarity {
        val a = scale * cos(angle); val b = scale * sin(angle)
        val x = (width-1)*.5; val y = (height-1)*.5
        return Similarity(a,b,4.8+x*(1-a)+b*y, -3.4+y*(1-a)-b*x)
    }

    private fun frame(transform: Similarity, gain: Double, defocusRight: Boolean?): FloatArray {
        val inv = 1.0/(transform.a*transform.a + transform.b*transform.b)
        return FloatArray(width*height) { i ->
            val u = i%width - transform.tx; val v = i/width - transform.ty
            val x = (transform.a*u + transform.b*v)*inv
            val y = (-transform.b*u + transform.a*v)*inv
            val fine = if (defocusRight == (x > width*.5)) 0.0 else .085*sin(x*1.34 + y*.72)
            ((scene(x,y)+fine)*gain).coerceIn(0.0,1.0).toFloat()
        }
    }
    private fun maxError(expected: Similarity, observed: Similarity): Double {
        var worst = 0.0
        for (x in listOf(0.0, width*.5, width-1.0)) for (y in listOf(0.0, height*.5, height-1.0)) {
            worst = max(worst,hypot(expected.x(x,y)-observed.x(x,y), expected.y(x,y)-observed.y(x,y)))
        }
        return worst
    }
    @Test fun oppositeDepthPlanesWithBreathingAndHandRollStayRegistered() {
        for ((scale,angle) in listOf(1.056 to .041, .954 to -.052)) {
            val expected = transformed(scale,angle)
            val result = SimilarityAlignment.estimate(frame(Similarity(),1.0,true),frame(expected,1.0,false),width,height,true)
            assertTrue("Corner misalignment ${maxError(expected,result)} with $result", maxError(expected,result) < .55)
        }
    }
    @Test fun brightFrameStaysRegisteredAfterClipping() {
        val expected = transformed(1.023,-.045)
        val result = SimilarityAlignment.estimate(frame(Similarity(),1.0,null),frame(expected,1.62,null),width,height,false)
        assertTrue("Corner misalignment ${maxError(expected,result)}", maxError(expected,result) < .7)
    }
    @Test fun differentScenesAreRejected() {
        val random = Random(9384)
        val unrelated = FloatArray(width*height) { random.nextFloat() }
        for (focus in listOf(true,false)) {
            assertThrows(IllegalArgumentException::class.java) {
                SimilarityAlignment.estimate(frame(Similarity(),1.0,null),unrelated,width,height,focus)
            }
        }
    }
    @Test fun largeScaleChangeIsRejectedRatherThanApproximatedAtSearchBoundary() {
        val outside = transformed(1.24, .105)
        for (focus in listOf(true,false)) {
            assertThrows(IllegalArgumentException::class.java) {
                SimilarityAlignment.estimate(frame(Similarity(),1.0,null), frame(outside,1.0,null),width,height,focus)
            }
        }
    }
    @Test fun refinementResistsTwoIndependentForegroundMotions() {
        val model = transformed(1.024,.026)
        val seed = model.copy(tx=model.tx+1.3,ty=model.ty-1.1)
        val matches = ArrayList<SimilarityAlignment.Match>()
        for (x in listOf(35.0,112.0,184.0)) for (y in listOf(25.0,80.0,135.0)) {
            val outlier = x<40 && y<100
            matches += SimilarityAlignment.Match(x,y,model.x(x,y)+(if (outlier) 7 else 0),model.y(x,y))
        }
        assertTrue(maxError(model,SimilarityAlignment.refine(seed,matches,width,height,8)) < .0001)
        assertEquals(seed,SimilarityAlignment.refine(seed,matches.take(3),width,height,8))
    }
}
