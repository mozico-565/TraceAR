package com.tracear.app

import android.graphics.Bitmap
import android.opengl.*
import com.google.ar.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** All GL resources and AR frame access belong to the GLSurfaceView thread. */
class GlScene {
    var cameraTexture = 0; private set
    private var cameraProgram = 0
    private var artProgram = 0
    private var artTexture = 0
    private var bitmap: Bitmap? = null
    private val quad = floats(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
    private val cameraUv = floats(FloatArray(8))
    // The quad lies in anchor-local XZ; local +Y is the detected surface normal.
    private val vertices = floats(floatArrayOf(-.5f,.001f,.5f, .5f,.001f,.5f, -.5f,.001f,-.5f, .5f,.001f,-.5f))
    private val artUv = floats(floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f))
    private val projection=FloatArray(16); private val view=FloatArray(16)
    private val model=FloatArray(16); private val mv=FloatArray(16); private val mvp=FloatArray(16)
    fun init() {
        cameraTexture=texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        artTexture=texture(GLES20.GL_TEXTURE_2D)
        cameraProgram=program("attribute vec2 p; attribute vec2 uv; varying vec2 t; void main(){gl_Position=vec4(p,0.,1.);t=uv;}",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 t; void main(){gl_FragColor=texture2D(image,t);}")
        artProgram=program("attribute vec3 p; attribute vec2 uv; uniform mat4 mvp; varying vec2 t; void main(){gl_Position=mvp*vec4(p,1.);t=uv;}",
            "precision mediump float; uniform sampler2D image; uniform float opacity; varying vec2 t; void main(){vec4 c=texture2D(image,t);gl_FragColor=vec4(c.a > 0.001 ? c.rgb / c.a : vec3(0.),c.a*opacity);}")
        bitmap?.let { upload(it) }
    }
    fun upload(b:Bitmap) {
        bitmap=b
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,artTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,b,0)
    }
    fun background(frame:Frame) {
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,quad,Coordinates2d.TEXTURE_NORMALIZED,cameraUv)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(cameraProgram)
        bind(cameraProgram,"p",quad,2); bind(cameraProgram,"uv",cameraUv,2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,cameraTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram,"image"),0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        checkGl()
    }
    fun artwork(camera:Camera, pose:Pose, width:Float, aspect:Float, rotation:Float, opacity:Float) {
        if(bitmap==null) return
        camera.getProjectionMatrix(projection,0,.01f,100f); camera.getViewMatrix(view,0)
        pose.toMatrix(model,0)
        Matrix.rotateM(model,0,rotation,0f,1f,0f)
        Matrix.scaleM(model,0,width,1f,width/aspect)
        Matrix.multiplyMM(mv,0,view,0,model,0); Matrix.multiplyMM(mvp,0,projection,0,mv,0)
        GLES20.glUseProgram(artProgram)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA)
        bind(artProgram,"p",vertices,3); bind(artProgram,"uv",artUv,2)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(artProgram,"mvp"),1,false,mvp,0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(artProgram,"opacity"),opacity)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,artTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(artProgram,"image"),0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisable(GLES20.GL_BLEND)
        checkGl()
    }
    private fun texture(target:Int):Int {
        val id=IntArray(1); GLES20.glGenTextures(1,id,0); GLES20.glBindTexture(target,id[0])
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }
    private fun program(vertex:String,fragment:String):Int {
        fun shader(type:Int,src:String):Int {
            val s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,src); GLES20.glCompileShader(s)
            val ok=IntArray(1); GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0)
            check(ok[0]!=0){GLES20.glGetShaderInfoLog(s)}; return s
        }
        val v=shader(GLES20.GL_VERTEX_SHADER,vertex); val f=shader(GLES20.GL_FRAGMENT_SHADER,fragment)
        val p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p)
        val ok=IntArray(1); GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0)
        check(ok[0]!=0){GLES20.glGetProgramInfoLog(p)}
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f); return p
    }
    private fun bind(program:Int,name:String,buffer:FloatBuffer,size:Int) {
        buffer.position(0); val location=GLES20.glGetAttribLocation(program,name)
        GLES20.glEnableVertexAttribArray(location); GLES20.glVertexAttribPointer(location,size,GLES20.GL_FLOAT,false,0,buffer)
    }
    private fun checkGl(){val e=GLES20.glGetError(); check(e==GLES20.GL_NO_ERROR){"OpenGL error $e"}}
    companion object {fun floats(a:FloatArray):FloatBuffer=ByteBuffer.allocateDirect(a.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(a);position(0)}}
}
