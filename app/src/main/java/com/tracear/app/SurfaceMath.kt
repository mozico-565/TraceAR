package com.tracear.app

import kotlin.math.abs

object SurfaceMath {
    /** Intersect the camera ray with anchor-local y=0, rejecting parallel/backward rays. */
    fun intersect(a:FloatArray,b:FloatArray):FloatArray? {
        val dy=b[1]-a[1]
        if(abs(dy)<.0001f)return null
        val t=-a[1]/dy
        if(t<=0)return null
        return floatArrayOf(a[0]+t*(b[0]-a[0]),0f,a[2]+t*(b[2]-a[2]))
    }
    fun rotationDelta(current:Float,previous:Float)=((current-previous+540)%360)-180
}
