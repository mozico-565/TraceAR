package com.tracear.app

import android.graphics.Bitmap

interface TrackingEngine {
    val hasSurface:Boolean
    fun start()
    fun pause()
    fun close()
    fun reset()
    fun artwork(bitmap:Bitmap)
    fun appearance(locked:Boolean,hidden:Boolean,opacity:Float)
}

/** The existing render-thread AR implementation remains the owner of Session.update(). */
class ArCoreTrackingEngine(
    private val ready:()->Boolean,private val resume:()->Unit,private val stop:()->Unit,
    private val clear:()->Unit,private val image:(Bitmap)->Unit
):TrackingEngine {
    override val hasSurface get()=ready()
    override fun start()=resume()
    override fun pause()=stop()
    override fun close()=stop()
    override fun reset()=clear()
    override fun artwork(bitmap:Bitmap)=image(bitmap)
    override fun appearance(locked:Boolean,hidden:Boolean,opacity:Float){}
}
