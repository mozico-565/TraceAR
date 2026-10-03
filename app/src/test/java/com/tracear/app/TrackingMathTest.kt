package com.tracear.app
import org.junit.Assert.*
import org.junit.Test
class TrackingMathTest {
    private val q=doubleArrayOf(10.0,10.0,110.0,10.0,110.0,90.0,10.0,90.0)
    @Test fun ordersCorners(){assertArrayEquals(q,TrackingMath.order(doubleArrayOf(110.0,90.0,10.0,10.0,10.0,90.0,110.0,10.0)),.001)}
    @Test fun rejectsBowTie(){assertFalse(TrackingMath.validQuad(doubleArrayOf(0.0,0.0,100.0,100.0,0.0,100.0,100.0,0.0)))}
    @Test fun rejectsReflection(){assertNull(TrackingMath.project(doubleArrayOf(-1.0,0.0,150.0,0.0,1.0,0.0,0.0,0.0,1.0),q))}
    @Test fun translates(){val result=TrackingMath.project(doubleArrayOf(1.0,0.0,5.0,0.0,1.0,7.0,0.0,0.0,1.0),q)!!;assertEquals(15.0,result[0],.001);assertEquals(17.0,result[1],.001)}
    @Test fun rejectsHorizon(){assertNull(TrackingMath.project(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,.01,0.0,-.5),q))}
    @Test fun rejectsNaN(){assertNull(TrackingMath.project(DoubleArray(9){Double.NaN},q))}
    @Test fun confidenceThresholds(){assertTrue(TrackingMath.reliable(12,20,2.0));assertFalse(TrackingMath.reliable(11,11,1.0));assertFalse(TrackingMath.reliable(12,30,1.0));assertFalse(TrackingMath.reliable(18,20,4.0));assertFalse(TrackingMath.reliable(12,12,Double.NaN))}
    @Test fun rejectsExtremeScale(){assertFalse(TrackingMath.sane(q,DoubleArray(8){q[it]*20},640,480));assertTrue(TrackingMath.sane(q,q,640,480))}
    @Test fun rejectsCollinear(){assertFalse(TrackingMath.validQuad(doubleArrayOf(0.0,0.0,20.0,0.0,40.0,0.0,60.0,0.0)))}
}
