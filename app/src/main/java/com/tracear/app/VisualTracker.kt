package com.tracear.app

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import org.opencv.features2d.ORB
import org.opencv.features2d.BFMatcher
import org.opencv.video.Video
import kotlin.math.*

/** All methods, including close, run on the analysis executor. No camera frames leave the device. */
class VisualTracker {
    data class Result(val corners:DoubleArray?,val confidence:Int,val lost:Boolean)
    private val flowP=MatOfPoint2f();private val flowN=MatOfPoint2f();private val flowBack=MatOfPoint2f()
    private val flowStatus=MatOfByte();private val flowError=MatOfFloat();private val flowReverseStatus=MatOfByte();private val flowReverseError=MatOfFloat()
    private val reference=Mat();private val previous=Mat();private val descriptors=Mat()
    private val orb=ORB.create(500);private val matcher=BFMatcher.create(Core.NORM_HAMMING,false)
    private var refKeys=arrayOf<KeyPoint>()
    private var original:DoubleArray?=null
    private var refs=arrayOf<Point>();private var tracked=arrayOf<Point>()
    private var lost=true;private var lastSearch=0L
    private var smooth:DoubleArray?=null
    fun reset(){original=null;refs=emptyArray();tracked=emptyArray();smooth=null;lost=true;reference.release();previous.release();descriptors.release()}
    fun pause(){lost=true}
    fun select(gray:Mat,q:DoubleArray):Boolean {
        reset();if(!TrackingMath.validQuad(q))return false
        original=q.copyOf();gray.copyTo(reference);gray.copyTo(previous)
        val mask=Mat.zeros(gray.rows(),gray.cols(),CvType.CV_8UC1)
        val cx=(0..3).sumOf{q[it*2]}/4;val cy=(0..3).sumOf{q[it*2+1]}/4
        val region=MatOfPoint(*(0..3).map{Point((cx+(q[it*2]-cx)*1.5).coerceIn(0.0,gray.cols()-1.0),(cy+(q[it*2+1]-cy)*1.5).coerceIn(0.0,gray.rows()-1.0))}.toTypedArray())
        val keys=MatOfKeyPoint();val features=MatOfPoint()
        try {
            Imgproc.fillConvexPoly(mask,region,Scalar(255.0))
            orb.detectAndCompute(reference,mask,keys,descriptors);refKeys=keys.toArray()
            Imgproc.goodFeaturesToTrack(gray,features,180,.01,7.0,mask,3,false,.04)
            refs=features.toArray();tracked=refs.copyOf();lost=refs.size<12;smooth=q.copyOf()
            return !lost
        }finally{mask.release();region.release();keys.release();features.release()}
    }
    fun rectangle(gray:Mat):DoubleArray? {
        val edges=Mat();val hierarchy=Mat();val contours=ArrayList<MatOfPoint>();var best:DoubleArray?=null
        try {
            Imgproc.Canny(gray,edges,60.0,160.0);Imgproc.findContours(edges,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
            for(c in contours){val f=MatOfPoint2f(*c.toArray());val a=MatOfPoint2f()
                try{Imgproc.approxPolyDP(f,a,Imgproc.arcLength(f,true)*.02,true)
                    if(a.total()==4L){val q=TrackingMath.order(a.toArray().flatMap{listOf(it.x,it.y)}.toDoubleArray())
                        if(TrackingMath.validQuad(q)&&TrackingMath.area(q)>gray.total()*.08 && (best==null||TrackingMath.area(q)>TrackingMath.area(best!!)))best=q}
                }finally{f.release();a.release()}
            };return best
        }finally{edges.release();hierarchy.release();contours.forEach{it.release()}}
    }
    fun update(gray:Mat,now:Long):Result {
        val q=original?:return Result(null,0,false)
        if(!lost && tracked.size>=12){
            val p=flowP.apply{fromArray(*tracked)};val n=flowN;val back=flowBack
            val status=flowStatus;val error=flowError;val reverseStatus=flowReverseStatus;val reverseError=flowReverseError
            run {
                Video.calcOpticalFlowPyrLK(previous,gray,p,n,status,error,Size(21.0,21.0),3)
                Video.calcOpticalFlowPyrLK(gray,previous,n,back,reverseStatus,reverseError,Size(21.0,21.0),3)
                val next=n.toArray();val backwards=back.toArray();val flags=status.toArray();val rev=reverseStatus.toArray();val errors=error.toArray()
                val good=tracked.indices.filter{flags[it].toInt()!=0&&rev[it].toInt()!=0&&errors[it]<25&&hypot(backwards[it].x-tracked[it].x,backwards[it].y-tracked[it].y)<1.5}
                val result=estimate(good.map{refs[it]}.toTypedArray(),good.map{next[it]}.toTypedArray(),gray,false)
                if(result!=null){gray.copyTo(previous);return result}
            }
        }
        lost=true
        if(now-lastSearch>=450){lastSearch=now
            val keys=MatOfKeyPoint();val desc=Mat();val empty=Mat();val matches=ArrayList<MatOfDMatch>()
            try {
                if(!descriptors.empty()){
                    orb.detectAndCompute(gray,empty,keys,desc)
                    if(!desc.empty()){
                        matcher.knnMatch(descriptors,desc,matches,2);val current=keys.toArray()
                        val good=matches.mapNotNull{val a=it.toArray();if(a.size>=2&&a[0].distance<.75f*a[1].distance)a[0] else null}
                        val result=estimate(good.map{refKeys[it.queryIdx].pt}.toTypedArray(),good.map{current[it.trainIdx].pt}.toTypedArray(),gray,true)
                        if(result!=null){gray.copyTo(previous);lost=false;return result}
                    }
                }
            }finally{keys.release();desc.release();empty.release();matches.forEach{it.release()}}
        }
        return Result(smooth?.copyOf(),0,true)
    }
    private fun estimate(a:Array<Point>,b:Array<Point>,gray:Mat,relocalizing:Boolean):Result? {
        if(a.size<12)return null
        val src=MatOfPoint2f(*a);val dst=MatOfPoint2f(*b);val mask=Mat()
        val h=Calib3d.findHomography(src,dst,Calib3d.RANSAC,3.0,mask,1000,.995)
        try {
            if(h.empty())return null
            val values=DoubleArray(9);h.get(0,0,values)
            val flags=ByteArray(a.size);mask.get(0,0,flags);val keep=a.indices.filter{flags[it].toInt()!=0}
            val residual=keep.map{i->val d=values[6]*a[i].x+values[7]*a[i].y+values[8];hypot((values[0]*a[i].x+values[1]*a[i].y+values[2])/d-b[i].x,(values[3]*a[i].x+values[4]*a[i].y+values[5])/d-b[i].y)}.average()
            if(!TrackingMath.reliable(keep.size,a.size,residual))return null
            // Reject tiny clusters: a homography extrapolated from one corner is unstable.
            val xs=keep.map{a[it].x};val ys=keep.map{a[it].y};val q=original!!
            if((xs.max()-xs.min())*(ys.max()-ys.min())<TrackingMath.area(q)*.12)return null
            val corners=TrackingMath.project(values,q)?:return null
            if(!TrackingMath.sane(q,corners,gray.cols(),gray.rows()))return null
            val prior=smooth
            if(!relocalizing&&prior!=null&&(0..3).any{hypot(corners[it*2]-prior[it*2],corners[it*2+1]-prior[it*2+1])>gray.cols()*.25})return null
            smooth=if(prior==null||relocalizing)corners else DoubleArray(8){prior[it]+(corners[it]-prior[it])*.85}
            refs=keep.map{a[it]}.toTypedArray();tracked=keep.map{b[it]}.toTypedArray();lost=false
            return Result(smooth!!.copyOf(),(100.0*keep.size/a.size).toInt(),false)
        }finally{src.release();dst.release();mask.release();h.release()}
    }
    fun close(){reset();listOf(flowP,flowN,flowBack,flowStatus,flowError,flowReverseStatus,flowReverseError).forEach{it.release()};orb.clear();matcher.clear()}
}
