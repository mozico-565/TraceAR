package com.tracear.app

import android.graphics.*
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
class VisualTrackingEngine(private val activity:AppCompatActivity,private val host:FrameLayout,private val report:(String)->Unit):TrackingEngine {
    private val nativeReady=try{OpenCVLoader.initLocal().also{check(it){"OpenCV initialization failed"}}}catch(e:LinkageError){throw IllegalStateException("OpenCV native library unavailable",e)}
    private val executor=Executors.newSingleThreadExecutor()
    private val pending=ConcurrentLinkedQueue<()->Unit>()
    private val tracker=VisualTracker()
    private val gray=Mat()
    private var bytes=ByteArray(0)
    private val preview=PreviewView(activity).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FILL_CENTER}
    private val overlay=ArtworkView()
    private val controls=LinearLayout(activity)
    private val confirm=MaterialButton(activity).apply{setText(R.string.track_surface);setOnClickListener{selectSurface()}}
    private val auto=MaterialButton(activity).apply{setText(R.string.auto_surface);setOnClickListener{autoRequested=true}}
    private val reselect=MaterialButton(activity).apply{setText(R.string.reselect_surface);setOnClickListener{reset()}}
    private var provider:ProcessCameraProvider?=null
    private var analysis:ImageAnalysis?=null
    private var active=false;private var closed=false
    @Volatile private var target:OutputTransform?=null
    @Volatile private var mapping:Matrix?=null
    @Volatile private var autoRequested=false
    @Volatile private var selecting=true
    @Volatile private var boundSurface=false
    private var lastGood=0L
    private var lastStatus=""
    override val hasSurface get()=boundSurface
    init {
        host.addView(preview,FrameLayout.LayoutParams(-1,-1));host.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        controls.addView(auto,LinearLayout.LayoutParams(0,-2,1f));controls.addView(confirm,LinearLayout.LayoutParams(0,-2,1f));controls.addView(reselect,LinearLayout.LayoutParams(0,-2,1f))
        host.addView(controls,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{bottomMargin=activity.resources.displayMetrics.density.times(240).toInt()})
        reselect.visibility=View.GONE
        preview.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->target=preview.outputTransform}
    }
    override fun start(){
        if(closed||active)return
        if(!OpenCVLoader.initLocal()){report(activity.getString(R.string.visual_error));return}
        active=true
        preview.post {
            if(!active)return@post
            val future=ProcessCameraProvider.getInstance(activity)
            future.addListener({
                if(!active||closed)return@addListener
                try{
                    provider=future.get()
                    val rotation=preview.display?.rotation?:Surface.ROTATION_0
                    val p=Preview.Builder().setTargetRotation(rotation).build().also{it.setSurfaceProvider(preview.surfaceProvider)}
                    val a=ImageAnalysis.Builder().setTargetResolution(Size(640,480)).setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis=a;a.setAnalyzer(executor){frame->analyze(frame)}
                    val viewport=preview.viewPort?:throw IllegalStateException("Camera viewport not ready")
                    provider!!.bindToLifecycle(activity,CameraSelector.DEFAULT_BACK_CAMERA,UseCaseGroup.Builder().setViewPort(viewport).addUseCase(p).addUseCase(a).build())
                    report(activity.getString(R.string.select_surface))
                    target=preview.outputTransform
                }catch(e:Exception){Log.e("TraceAR","Visual camera start failed",e);active=false;report(activity.getString(R.string.visual_error)+": "+e.message)}
            },ContextCompat.getMainExecutor(activity))
        }
    }
    override fun pause(){active=false;analysis?.clearAnalyzer();provider?.unbindAll();pending.add{tracker.pause()}}
    override fun close(){if(closed)return;pause();closed=true;executor.execute{tracker.close();gray.release()};executor.shutdown();host.removeView(preview);host.removeView(overlay);host.removeView(controls)}
    override fun artwork(bitmap:Bitmap){overlay.bitmap=bitmap;overlay.invalidate()}
    override fun appearance(locked:Boolean,hidden:Boolean,opacity:Float){overlay.locked=locked;overlay.hidden=hidden;overlay.opacity=opacity;overlay.invalidate()}
    override fun reset(){selecting=true;boundSurface=false;pending.add{tracker.reset()};overlay.quad=null;overlay.tracked=null;overlay.available=false;overlay.resetArtwork();overlay.invalidate();confirm.visibility=View.VISIBLE;auto.visibility=View.VISIBLE;reselect.visibility=View.GONE;report(activity.getString(R.string.select_surface))}
    private fun selectSurface(){
        val matrix=mapping?:return
        val inverse=Matrix();if(!matrix.invert(inverse))return
        overlay.captureAspect()
        val corners=overlay.selection().copyOf();inverse.mapPoints(corners)
        val q=DoubleArray(8){corners[it].toDouble()}
        if(!TrackingMath.validQuad(q))return
        selecting=false;confirm.visibility=View.GONE;auto.visibility=View.GONE;reselect.visibility=View.VISIBLE
        pending.add{tracker.select(gray,q);boundSurface=true}
    }
    private fun analyze(frame:ImageProxy){
        try{
            if(!active||closed)return
            val w=frame.width;val h=frame.height
            if(bytes.size!=w*h)bytes=ByteArray(w*h)
            val plane=frame.planes[0];val buffer=plane.buffer;val origin=buffer.position()
            if(plane.pixelStride==1){for(y in 0 until h){buffer.position(origin+y*plane.rowStride);buffer.get(bytes,y*w,w)}}
            else for(y in 0 until h)for(x in 0 until w)bytes[y*w+x]=buffer.get(origin+y*plane.rowStride+x*plane.pixelStride)
            gray.create(h,w,CvType.CV_8UC1);gray.put(0,0,bytes)
            val output=target
            if(output==null){activity.runOnUiThread{target=preview.outputTransform};return}
            val factory=ImageProxyTransformFactory().apply{isUsingCropRect=true;isUsingRotationDegrees=false}
            val matrix=Matrix();CoordinateTransform(factory.getOutputTransform(frame),output).transform(matrix);mapping=matrix
            while(true){val task=pending.poll()?:break;task()}
            if(autoRequested){autoRequested=false;val rectangle=tracker.rectangle(gray)
                activity.runOnUiThread{if(selecting&&rectangle!=null){val q=FloatArray(8){rectangle[it].toFloat()};matrix.mapPoints(q);overlay.quad=q;overlay.invalidate()}else if(selecting)report(activity.getString(R.string.no_rectangle))}}
            if(!selecting)publish(tracker.update(gray,SystemClock.elapsedRealtime()),matrix)
        }catch(e:Exception){Log.e("TraceAR","Visual analysis failed",e);pending.add{tracker.pause()};activity.runOnUiThread{overlay.available=false;overlay.invalidate();report(activity.getString(R.string.visual_error)+": "+e.javaClass.simpleName)}}
        finally{frame.close()}
    }
    private fun publish(result:VisualTracker.Result,matrix:Matrix){
        val now=SystemClock.elapsedRealtime();if(!result.lost)lastGood=now
        val points=result.corners?.let{FloatArray(8){i->it[i].toFloat()}.also{matrix.mapPoints(it)}}
        activity.runOnUiThread{
            if(closed||!active||selecting)return@runOnUiThread
            if(points!=null)overlay.tracked=points
            overlay.available=!result.lost||now-lastGood<350;overlay.invalidate()
            val text=activity.getString(if(result.lost)R.string.visual_lost else if(overlay.locked)R.string.locked else R.string.visual_tracking)+(if(result.lost)"" else " · ${result.confidence}%")
            if(text!=lastStatus){lastStatus=text;report(text)}
        }
    }
    private inner class ArtworkView:View(activity){
        var bitmap:Bitmap?=null;var quad:FloatArray?=null;var tracked:FloatArray?=null
        var locked=false;var hidden=false;var opacity=.65f;var available=false
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val planeMatrix=Matrix();private var planeHeight=1f
        private var tx=.5f;private var ty=.5f;private var scale=.8f;private var angle=0f
        private var corner=-1;private var last=FloatArray(2);private var dist=0f;private var rotation=0f;private var multiple=false
        fun captureAspect(){val q=selection();planeHeight=((hypot(q[6]-q[0],q[7]-q[1])+hypot(q[4]-q[2],q[5]-q[3]))/(hypot(q[2]-q[0],q[3]-q[1])+hypot(q[4]-q[6],q[5]-q[7]))).coerceIn(.1f,10f)}
        fun resetArtwork(){tx=.5f;ty=.5f;scale=.8f;angle=0f}
        fun selection():FloatArray{
            if(quad==null)quad=floatArrayOf(width*.18f,height*.32f,width*.82f,height*.32f,width*.82f,height*.62f,width*.18f,height*.62f)
            return quad!!
        }
        override fun onDraw(canvas:Canvas){super.onDraw(canvas)
            if(selecting){val q=selection();paint.color=0xFF55E3BF.toInt();paint.alpha=255;paint.style=Paint.Style.STROKE;paint.strokeWidth=3*resources.displayMetrics.density
                val path=Path();path.moveTo(q[0],q[1]);for(i in 1..3)path.lineTo(q[2*i],q[2*i+1]);path.close();canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
                for(i in 0..3)canvas.drawCircle(q[2*i],q[2*i+1],9*resources.displayMetrics.density,paint)
                return
            }
            val q=tracked?:return
            if(!available||hidden)return
            val image=bitmap?:return
            // Surface coordinates keep a stable aspect derived at acquisition, independent of subsequent perspective.
            if(planeHeight<=0)planeHeight=1f
            val canonical=floatArrayOf(0f,0f,1f,0f,1f,planeHeight,0f,planeHeight)
            if(!planeMatrix.setPolyToPoly(canonical,0,q,0,4))return
            val iw=scale*min(1f,planeHeight*image.width/image.height);val ih=iw*image.height/image.width
            val local=Matrix();local.setTranslate(-image.width/2f,-image.height/2f);local.postScale(iw/image.width,ih/image.height);local.postRotate(angle);local.postTranslate(tx,ty*planeHeight)
            val transform=Matrix();transform.setConcat(planeMatrix,local)
            paint.alpha=(opacity*255).toInt();canvas.drawBitmap(image,transform,paint);paint.alpha=255
        }
        private fun planePoint(x:Float,y:Float):FloatArray?{val inverse=Matrix();if(!planeMatrix.invert(inverse))return null;return floatArrayOf(x,y).also{inverse.mapPoints(it)}}
        override fun onTouchEvent(e:MotionEvent):Boolean{
            if(locked)return true
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{multiple=false;last=floatArrayOf(e.x,e.y)
                    if(selecting){val q=selection();corner=(0..3).minBy{hypot(e.x-q[it*2],e.y-q[it*2+1])};if(hypot(e.x-q[corner*2],e.y-q[corner*2+1])>48*resources.displayMetrics.density)corner=-1}}
                MotionEvent.ACTION_POINTER_DOWN->{multiple=true;if(e.pointerCount>1){dist=distance(e);rotation=rotation(e)}}
                MotionEvent.ACTION_MOVE->{
                    if(selecting&&corner>=0){val next=selection().copyOf();next[corner*2]=e.x.coerceIn(0f,width.toFloat());next[corner*2+1]=e.y.coerceIn(0f,height.toFloat());if(TrackingMath.validQuad(DoubleArray(8){next[it].toDouble()}))quad=next}
                    else if(!selecting&&available){if(e.pointerCount>=2){val d=distance(e);val a=rotation(e);if(dist>0)scale=(scale*d/dist).coerceIn(.05f,3f);angle+=SurfaceMath.rotationDelta(a,rotation);dist=d;rotation=a}
                        else if(!multiple){val previous=planePoint(last[0],last[1]);val next=planePoint(e.x,e.y);if(previous!=null&&next!=null){tx+=(next[0]-previous[0]);ty+=(next[1]-previous[1])/planeHeight};last=floatArrayOf(e.x,e.y)}}
                    invalidate()}
                MotionEvent.ACTION_UP->{if(selecting){val q=selection();planeHeight=((hypot(q[6]-q[0],q[7]-q[1])+hypot(q[4]-q[2],q[5]-q[3]))/(hypot(q[2]-q[0],q[3]-q[1])+hypot(q[4]-q[6],q[5]-q[7]))).coerceIn(.1f,10f)};corner=-1;performClick()}
                MotionEvent.ACTION_CANCEL->{corner=-1;multiple=false}
            };return true
        }
        override fun performClick():Boolean{super.performClick();return true}
        private fun distance(e:MotionEvent)=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0))
        private fun rotation(e:MotionEvent)=Math.toDegrees(atan2((e.getY(1)-e.getY(0)).toDouble(),(e.getX(1)-e.getX(0)).toDouble())).toFloat()
    }
}
