package com.tracear.app

import kotlin.math.*

/** Camera-independent safety gates; coordinates are clockwise image coordinates. */
object TrackingMath {
    fun area(q:DoubleArray):Double = (0..3).sumOf { i -> val j=(i+1)%4; q[2*i]*q[2*j+1]-q[2*j]*q[2*i+1] }/2
    fun validQuad(q:DoubleArray):Boolean {
        if(q.size!=8 || q.any{!it.isFinite()} || area(q)<64) return false
        return (0..3).all { i -> val j=(i+1)%4;val k=(i+2)%4
            (q[2*j]-q[2*i])*(q[2*k+1]-q[2*j+1])-(q[2*j+1]-q[2*i+1])*(q[2*k]-q[2*j])>1
        }
    }
    fun order(points:DoubleArray):DoubleArray {
        require(points.size==8)
        val cx=(0..3).sumOf{points[it*2]}/4;val cy=(0..3).sumOf{points[it*2+1]}/4
        val indices=(0..3).sortedBy{atan2(points[it*2+1]-cy,points[it*2]-cx)}
        val start=indices.indices.minBy{points[indices[it]*2]+points[indices[it]*2+1]}
        return DoubleArray(8){i->points[indices[(start+i/2)%4]*2+i%2]}
    }
    fun project(h:DoubleArray,q:DoubleArray):DoubleArray? {
        if(h.size!=9 || h.any{!it.isFinite()}) return null
        val out=DoubleArray(q.size);var sign=0
        for(i in q.indices step 2){val x=q[i];val y=q[i+1];val d=h[6]*x+h[7]*y+h[8]
            if(abs(d)<1e-7 || !d.isFinite())return null
            val s=if(d>0)1 else -1;if(sign!=0&&sign!=s)return null;sign=s
            out[i]=(h[0]*x+h[1]*y+h[2])/d;out[i+1]=(h[3]*x+h[4]*y+h[5])/d
        }
        return out.takeIf{validQuad(it)}
    }
    fun reliable(inliers:Int,total:Int,error:Double):Boolean = inliers>=12 && total>=inliers && inliers.toDouble()/total>=.55 && error.isFinite() && error<=3.5
    fun sane(reference:DoubleArray,current:DoubleArray,w:Int,h:Int):Boolean = validQuad(current) && area(current)/area(reference) in .08..12.0 && current.indices.all{ i->current[i] in (if(i%2==0)-w.toDouble()..w*2.0 else -h.toDouble()..h*2.0)}
}
