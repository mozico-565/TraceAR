package com.tracear.app

import android.content.ContentResolver
import android.graphics.*
import android.net.Uri
import kotlin.math.*

object ImageProcessing {
    fun decode(resolver:ContentResolver,uri:Uri):Bitmap {
        val source=ImageDecoder.createSource(resolver,uri)
        return ImageDecoder.decodeBitmap(source) { decoder,info,_ ->
            val scale=min(1.0,1536.0/max(info.size.width,info.size.height))
            decoder.setTargetSize(max(1,(info.size.width*scale).toInt()),max(1,(info.size.height*scale).toInt()))
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    }
    fun filter(source:Bitmap,mode:Int,contrast:Float):Bitmap {
        val w=source.width;val h=source.height;val pixels=IntArray(w*h)
        source.getPixels(pixels,0,w,0,0,w,h)
        val luma=FloatArray(pixels.size){i -> val c=pixels[i]; .299f*Color.red(c)+.587f*Color.green(c)+.114f*Color.blue(c)}
        for(y in 0 until h) for(x in 0 until w) {
            val i=y*w+x;val c=pixels[i]
            fun adjust(v:Float)=((v-128)*contrast+128).roundToInt().coerceIn(0,255)
            pixels[i]=when(mode) {
                1 -> adjust(luma[i]).let{Color.argb(Color.alpha(c),it,it,it)}
                2 -> {
                    fun g(dx:Int,dy:Int)=luma[(y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1)]
                    val gx=-g(-1,-1)+g(1,-1)-2*g(-1,0)+2*g(1,0)-g(-1,1)+g(1,1)
                    val gy=-g(-1,-1)-2*g(0,-1)-g(1,-1)+g(-1,1)+2*g(0,1)+g(1,1)
                    val alpha=(hypot(gx,gy)*contrast).roundToInt().coerceIn(0,255)*Color.alpha(c)/255
                    // Transparent background leaves the real paper visible; cyan lines work on light/dark surfaces.
                    Color.argb(alpha,65,245,204)
                }
                else -> Color.argb(Color.alpha(c),adjust(Color.red(c).toFloat()),adjust(Color.green(c).toFloat()),adjust(Color.blue(c).toFloat()))
            }
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
    }
}
