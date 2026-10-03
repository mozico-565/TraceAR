package com.tracear.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class MainActivity:AppCompatActivity(),GLSurfaceView.Renderer {
    private lateinit var cameraHost:FrameLayout
    private var engine:TrackingEngine?=null
    private var trackingMode=0
    private var rendered:Bitmap?=null
    private lateinit var surface:GLSurfaceView
    private lateinit var status:TextView
    private lateinit var lockButton:MaterialButton
    private lateinit var hideButton:MaterialButton
    private lateinit var retryButton:MaterialButton
    @Volatile private var session:Session?=null
    @Volatile private var resumed=false
    @Volatile private var sessionRunning=false
    @Volatile private var fatal=false
    @Volatile private var locked=false
    @Volatile private var hidden=false
    @Volatile private var opacity=.65f
    @Volatile private var displayRotation=0
    private var installRequested=false
    private var cameraRequested=false
    private val scene=GlScene()
    @Volatile private var anchor:Anchor?=null
    private var plane:Plane?=null
    private var localPose=Pose.IDENTITY
    private var width=.25f
    private var angle=0f
    private var aspect=1f
    private var selected=false
    private var lastStatus=""
    private var viewportWidth=1;private var viewportHeight=1
    private val commands=ConcurrentLinkedQueue<()->Unit>()
    private data class Touch(val x:Float,val y:Float,val move:Boolean)
    private val touches=ConcurrentLinkedQueue<Touch>()
    private val worker=Executors.newSingleThreadExecutor()
    private val generation=AtomicInteger()
    @Volatile private var original:Bitmap?=null
    private var mode=0
    private var contrast=1f
    private var lastX=0f;private var lastY=0f;private var moved=false
    private var multi=false;private var previousDistance=0f;private var previousAngle=0f
    private val picker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){uri -> uri?.let{loadImage(it)}}
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted) startAr() else { message(getString(R.string.permission));retryButton.visibility=View.VISIBLE }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root=FrameLayout(this)
        surface=GLSurfaceView(this).apply {
            setEGLContextClientVersion(2);preserveEGLContextOnPause=true
            setRenderer(this@MainActivity)
            setOnTouchListener{_,event -> handleTouch(event);true}
        }
        cameraHost=FrameLayout(this)
        cameraHost.addView(surface,FrameLayout.LayoutParams(-1,-1))
        root.addView(cameraHost,FrameLayout.LayoutParams(-1,-1))
        trackingMode=getPreferences(MODE_PRIVATE).getInt("tracking_mode",0)
        val top=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(18),dp(20),dp(12));background=rounded(0xD9101C22.toInt())}
        top.addView(TextView(this).apply{text="TraceAR";textSize=25f;setTextColor(0xFF55E3BF.toInt())})
        status=TextView(this).apply{textSize=15f;setTextColor(Color.WHITE);text=getString(R.string.scan_surface)}
        top.addView(status)
        top.addView(button(R.string.tracking_mode){trackingDialog()})
        retryButton=button(R.string.retry){
            if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
                if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) permission.launch(Manifest.permission.CAMERA)
                else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
            } else { engine?.pause();fatal=false;startAr() }
        }.apply{visibility=View.GONE}
        top.addView(retryButton)
        root.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val bottom=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(12));background=rounded(0xDF101C22.toInt())}
        val row=LinearLayout(this)
        row.addView(button(R.string.choose_image){picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},LinearLayout.LayoutParams(0,-2,1f))
        lockButton=button(R.string.lock_artwork){
            if(engine?.hasSurface==true){locked=!locked;lockButton.setText(if(locked)R.string.unlock_artwork else R.string.lock_artwork);syncAppearance()}
        }
        row.addView(lockButton,LinearLayout.LayoutParams(0,-2,1f));bottom.addView(row)
        val second=LinearLayout(this)
        hideButton=button(R.string.hide){hidden=!hidden;hideButton.setText(if(hidden)R.string.show else R.string.hide);syncAppearance()}
        second.addView(hideButton,LinearLayout.LayoutParams(0,-2,1f))
        second.addView(button(R.string.reset_artwork){engine?.reset();locked=false;hidden=false;lockButton.setText(R.string.lock_artwork);hideButton.setText(R.string.hide);syncAppearance()},LinearLayout.LayoutParams(0,-2,1f))
        second.addView(button(R.string.precision){precisionDialog()},LinearLayout.LayoutParams(0,-2,1f));bottom.addView(second)
        bottom.addView(TextView(this).apply{text=getString(R.string.opacity);setTextColor(Color.WHITE);textSize=13f})
        bottom.addView(Slider(this).apply{valueFrom=.05f;valueTo=1f;value=.65f;addOnChangeListener{_,v,_->opacity=v;syncAppearance()}},LinearLayout.LayoutParams(-1,dp(38)))
        bottom.addView(TextView(this).apply{text=getString(R.string.hint);setTextColor(0xFFB8C6CD.toInt());textSize=11f})
        root.addView(bottom,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root){v,insets -> val i=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout());v.setPadding(i.left,i.top,i.right,i.bottom);insets}
        setContentView(root)
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun rounded(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(22).toFloat()}
    private fun button(label:Int,action:()->Unit)=MaterialButton(this).apply{setText(label);textSize=12f;minimumWidth=0;minWidth=0;setPadding(dp(6),0,dp(6),0);setOnClickListener{action()}}
    private fun message(text:String){runOnUiThread{if(!isDestroyed){status.text=text}}}
    private fun fail(e:Exception){Log.e("TraceAR","AR failed",e);fatal=true;message("${getString(R.string.error)}: ${e.javaClass.simpleName} — ${e.message ?: ""}");runOnUiThread{retryButton.visibility=View.VISIBLE}}
    override fun onResume(){super.onResume();resumed=true;displayRotation=windowManager.defaultDisplay.rotation;startAr()}
    private fun syncAppearance(){engine?.appearance(locked,hidden,opacity)}
    private fun trackingDialog(){
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.tracking_mode)
            .setSingleChoiceItems(arrayOf(getString(R.string.auto_mode),"ARCore",getString(R.string.visual_mode)),trackingMode){dialog,which->
                trackingMode=which;getPreferences(MODE_PRIVATE).edit().putInt("tracking_mode",which).apply()
                engine?.close();engine=null;locked=false;lockButton.setText(R.string.lock_artwork)
                dialog.dismiss();fatal=false;startAr()
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun activateVisual(){
        if(engine !is VisualTrackingEngine){
            engine?.close();surface.onPause();sessionRunning=false
            surface.visibility=View.GONE
            try{engine=VisualTrackingEngine(this,cameraHost){text->message(text);retryButton.visibility=if(text.startsWith(getString(R.string.visual_error)))View.VISIBLE else View.GONE};rendered?.let{engine?.artwork(it)};syncAppearance()}
            catch(e:Exception){Log.e("TraceAR","Visual initialization failed",e);message(getString(R.string.visual_error)+": "+e.message);return}
        }
        engine?.start()
    }
    private fun startAr() {
        if(!resumed)return
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            if(!cameraRequested){cameraRequested=true;permission.launch(Manifest.permission.CAMERA)}
            else {message(getString(R.string.permission));retryButton.visibility=View.VISIBLE};return
        }
        if(trackingMode==2){activateVisual();return}
        val availability=ArCoreApk.getInstance().checkAvailability(this)
        if(availability.isTransient){surface.postDelayed({if(resumed)startAr()},400);return}
        if(!availability.isSupported){activateVisual();if(trackingMode==1)message(getString(R.string.unsupported));return}
        if(engine !is ArCoreTrackingEngine){
            engine?.close();surface.visibility=View.VISIBLE
            engine=ArCoreTrackingEngine({anchor!=null},{startArCore()},{pauseArCore()},
                {commands.add{touches.clear();anchor?.detach();anchor=null;plane=null;localPose=Pose.IDENTITY;width=.25f;angle=0f}},
                {bitmap,reset->commands.add{scene.upload(bitmap);aspect=bitmap.width.toFloat()/bitmap.height;if(reset)width=min(.25f,.4f*aspect);selected=true}})
            rendered?.let{engine?.artwork(it)}
        }
        engine?.start()
    }
    private fun pauseArCore(){sessionRunning=false;surface.onPause();try{session?.pause()}catch(e:Exception){Log.e("TraceAR","Pause failed",e)}}
    private fun startArCore() {
        if(!resumed || sessionRunning) return
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            if(!cameraRequested){cameraRequested=true;permission.launch(Manifest.permission.CAMERA)}
            else {message(getString(R.string.permission));retryButton.visibility=View.VISIBLE}
            return
        }
        try {
            val availability=ArCoreApk.getInstance().checkAvailability(this)
            if(availability.isTransient){surface.postDelayed({if(resumed)startAr()},400);return}
            if(!availability.isSupported){message(getString(R.string.unsupported));return}
            if(session==null) {
                if(ArCoreApk.getInstance().requestInstall(this,!installRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED){installRequested=true;message(getString(R.string.install));return}
                val s=Session(this)
                val config=Config(s).apply {
                    planeFindingMode=Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    focusMode=Config.FocusMode.AUTO
                    if(s.isDepthModeSupported(Config.DepthMode.AUTOMATIC))depthMode=Config.DepthMode.AUTOMATIC
                }
                s.configure(config);session=s
            }
            if(fatal) return
            session!!.resume();sessionRunning=true;surface.onResume();retryButton.visibility=View.GONE
        } catch(e:Exception){fail(e)}
    }
    override fun onPause(){resumed=false;engine?.pause();super.onPause()}
    override fun onDestroy(){engine?.close();worker.shutdownNow();session?.close();session=null;super.onDestroy()}
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?){try{scene.init()}catch(e:Exception){fail(e)}}
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int){viewportWidth=w;viewportHeight=h;GLES20.glViewport(0,0,w,h)}
    override fun onDrawFrame(gl:GL10?) {
        GLES20.glClearColor(.04f,.07f,.09f,1f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s=session ?: return
        if(!resumed || fatal || !sessionRunning)return
        try {
            while(true){val command=commands.poll()?:break;command()}
            s.setDisplayGeometry(displayRotation,viewportWidth,viewportHeight)
            s.setCameraTextureName(scene.cameraTexture)
            val frame=s.update()
            if(frame.timestamp!=0L)scene.background(frame)
            val camera=frame.camera
            if(camera.trackingState!=TrackingState.TRACKING){touches.clear();state(getString(R.string.lost)+" · "+camera.trackingFailureReason.name);return}
            while(true){val t=touches.poll()?:break;if(!locked&&selected)place(frame,t)}
            val a=anchor
            if(a!=null && a.trackingState==TrackingState.TRACKING) {
                if(!hidden)scene.artwork(camera,a.pose.compose(localPose),width,aspect,angle,opacity)
                state(getString(if(locked)R.string.locked else R.string.placed))
            } else if(a!=null)state(getString(R.string.lost))
            else {
                val hit=frame.hitTest(viewportWidth/2f,viewportHeight/2f).any{val p=it.trackable;p is Plane&&p.trackingState==TrackingState.TRACKING&&p.isPoseInPolygon(it.hitPose)}
                val text=if(hit) getString(if(selected)R.string.selected else R.string.ready) else getString(R.string.scan_surface)
                state(text+" · "+getString(if(s.config.depthMode==Config.DepthMode.AUTOMATIC)R.string.depth else R.string.planes))
            }
        } catch(e:CameraNotAvailableException){fail(e)}catch(e:Exception){fail(e)}
    }
    private fun state(text:String){if(text!=lastStatus){lastStatus=text;message(text)}}
    private fun place(frame:Frame,t:Touch) {
        if(t.move && anchor!=null) {
            // Ray-plane intersection in the ORIGINAL anchor's coordinate system. No per-drag re-anchoring.
            val projection=FloatArray(16);val view=FloatArray(16);val pv=FloatArray(16);val inv=FloatArray(16)
            frame.camera.getProjectionMatrix(projection,0,.01f,100f);frame.camera.getViewMatrix(view,0)
            android.opengl.Matrix.multiplyMM(pv,0,projection,0,view,0)
            if(!android.opengl.Matrix.invertM(inv,0,pv,0))return
            fun unproject(z:Float):FloatArray {val out=FloatArray(4);android.opengl.Matrix.multiplyMV(out,0,inv,0,floatArrayOf(t.x/viewportWidth*2-1,1-t.y/viewportHeight*2,z,1f),0);return floatArrayOf(out[0]/out[3],out[1]/out[3],out[2]/out[3])}
            val inverse=anchor!!.pose.inverse();val a=inverse.transformPoint(unproject(-1f));val b=inverse.transformPoint(unproject(1f))
            val point=SurfaceMath.intersect(a,b)?:return
            val x=point[0];val z=point[2]
            val candidate=anchor!!.pose.compose(Pose.makeTranslation(x,0f,z))
            val p=plane
            if(p!=null && p.trackingState==TrackingState.TRACKING && p.isPoseInPolygon(candidate))localPose=Pose.makeTranslation(x,0f,z)
            return
        }
        val hit=frame.hitTest(t.x,t.y).firstOrNull { val p=it.trackable;p is Plane&&p.trackingState==TrackingState.TRACKING&&p.isPoseInPolygon(it.hitPose)&&p.subsumedBy==null }
        if(hit==null){state(getString(R.string.no_hit));return}
        val next=hit.createAnchor();anchor?.detach();anchor=next;plane=hit.trackable as Plane;localPose=Pose.IDENTITY
    }
    private fun handleTouch(e:MotionEvent) {
        if(locked)return
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{lastX=e.x;lastY=e.y;moved=false;multi=false}
            MotionEvent.ACTION_POINTER_DOWN->{multi=true;if(e.pointerCount>=2){previousDistance=distance(e);previousAngle=rotation(e)}}
            MotionEvent.ACTION_MOVE->{
                if(e.pointerCount>=2){val d=distance(e);val a=rotation(e);val ratio=if(previousDistance>0)d/previousDistance else 1f;val delta=SurfaceMath.rotationDelta(a,previousAngle);previousDistance=d;previousAngle=a;commands.add{if(!locked){width=(width*ratio).coerceIn(min(.02f,.02f*aspect),min(3f,3f*aspect));angle+=delta}}}
                else if(!multi && hypot(e.x-lastX,e.y-lastY)>dp(8)){moved=true;touches.clear();touches.add(Touch(e.x,e.y,true))}
            }
            MotionEvent.ACTION_UP->{if(!moved&&!multi)touches.add(Touch(e.x,e.y,false))}
            MotionEvent.ACTION_CANCEL->{touches.clear();multi=false}
        }
    }
    private fun distance(e:MotionEvent)=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0))
    private fun rotation(e:MotionEvent)=Math.toDegrees(atan2((e.getY(1)-e.getY(0)).toDouble(),(e.getX(1)-e.getX(0)).toDouble())).toFloat()
    private fun loadImage(uri:Uri) {
        val token=generation.incrementAndGet();message(getString(R.string.loading))
        worker.execute {try{val bitmap=ImageProcessing.decode(contentResolver,uri);if(token==generation.get()&&!isDestroyed){original=bitmap;runOnUiThread{mode=0;contrast=1f;rendered=bitmap;engine?.artwork(bitmap,true)}}}catch(e:Exception){Log.e("TraceAR","Image decode failed",e);message(getString(R.string.image_error))}}
    }
    private fun precisionDialog() {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(8))}
        val options=arrayOf(getString(R.string.original),getString(R.string.gray),getString(R.string.line))
        val group=RadioGroup(this)
        options.forEachIndexed{i,label->group.addView(RadioButton(this).apply{id=100+i;text=label;isChecked=i==mode})}
        group.setOnCheckedChangeListener{_,id->mode=id-100;process()};box.addView(group)
        box.addView(TextView(this).apply{text=getString(R.string.contrast)})
        box.addView(Slider(this).apply{valueFrom=.5f;valueTo=3f;value=contrast;addOnChangeListener{_,v,_->contrast=v};addOnSliderTouchListener(object:Slider.OnSliderTouchListener{override fun onStartTrackingTouch(slider:Slider){};override fun onStopTrackingTouch(slider:Slider){process()}})})
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.precision).setView(box).setPositiveButton(android.R.string.ok,null).show()
    }
    private fun process(){val source=original?:return;val m=mode;val c=contrast;val token=generation.incrementAndGet();worker.execute{try{val result=ImageProcessing.filter(source,m,c);if(token==generation.get()&&!isDestroyed)runOnUiThread{rendered=result;engine?.artwork(result)}}catch(e:Exception){Log.e("TraceAR","Filter failed",e);message(getString(R.string.image_error))}}}
}
