package com.tracear.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import java.util.Random

@RunWith(AndroidJUnit4::class)
class VisualTrackerInstrumentedTest {
    private val quad=doubleArrayOf(120.0,100.0,520.0,100.0,520.0,380.0,120.0,380.0)
    @Test fun opticalFlowTracksPlanarTranslationAndRecoversAfterLoss(){
        assertTrue(OpenCVLoader.initLocal())
        val tracker=VisualTracker();val first=Mat(480,640,CvType.CV_8UC1,Scalar(230.0));val next=Mat();val blank=Mat.zeros(480,640,CvType.CV_8UC1);val transform=Mat(2,3,CvType.CV_64FC1)
        try{
            val random=Random(42)
            for(i in 0..450)Imgproc.circle(first,Point(40.0+random.nextInt(560),40.0+random.nextInt(400)),2+random.nextInt(5),Scalar(random.nextInt(180).toDouble()),-1)
            assertTrue("Textured plane must initialize",tracker.select(first,quad))
            transform.put(0,0,1.0,0.0,12.0,0.0,1.0,-8.0)
            Imgproc.warpAffine(first,next,transform,Size(640.0,480.0))
            val result=tracker.update(next,1000)
            assertFalse("LK/RANSAC should retain surface",result.lost)
            assertTrue(result.confidence>=55)
            for(i in 0..3)assertTrue("Tracked corner must follow perspective",hypot(result.corners!![2*i]-quad[2*i]-12,result.corners[2*i+1]-quad[2*i+1]+8)<4.0)
            assertTrue("Blank camera must lose tracking",tracker.update(blank,2000).lost)
            val recovered=tracker.update(next,3000)
            assertFalse("ORB should relocalize original surface",recovered.lost)
            tracker.pause()
            assertFalse("Pause resumes via reference relocalization",tracker.update(first,4000).lost)
            tracker.reset()
            assertNull(tracker.update(first,5000).corners)
        }finally{tracker.close();first.release();next.release();blank.release();transform.release()}
    }
    @Test fun blankSurfaceRequiresVisibleDetails(){
        assertTrue(OpenCVLoader.initLocal());val tracker=VisualTracker();val blank=Mat(480,640,CvType.CV_8UC1,Scalar(255.0))
        try{assertFalse(tracker.select(blank,quad));assertTrue(tracker.update(blank,1000).lost)}finally{tracker.close();blank.release()}
    }
}
