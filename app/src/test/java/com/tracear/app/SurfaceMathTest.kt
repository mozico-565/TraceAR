package com.tracear.app
import org.junit.Assert.*
import org.junit.Test
class SurfaceMathTest {
    @Test fun rayIntersectsSurfaceInAnchorSpace(){assertArrayEquals(floatArrayOf(1f,0f,2f),SurfaceMath.intersect(floatArrayOf(0f,1f,0f),floatArrayOf(2f,-1f,4f))!!,.0001f)}
    @Test fun parallelRayCannotJumpArtwork(){assertNull(SurfaceMath.intersect(floatArrayOf(0f,1f,0f),floatArrayOf(2f,1f,4f)))}
    @Test fun surfaceBehindCameraIsRejected(){assertNull(SurfaceMath.intersect(floatArrayOf(0f,1f,0f),floatArrayOf(0f,2f,0f)))}
    @Test fun rotationAcrossBoundaryDoesNotJump(){assertEquals(2f,SurfaceMath.rotationDelta(-179f,179f),.001f)}
}
