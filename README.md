# TraceAR 1.0.0

Native Kotlin Android tracing with ARCore and OpenGL ES 2.0. Android 9+ and an ARCore-supported device with Google Play Services for AR are required. Unsupported devices show an explanation rather than a fake screen overlay. No network permission, analytics, cloud processing or API keys.

## Use
1. Grant camera access; install/update Google Play Services for AR if requested.
2. Scan a well-lit textured table, floor or wall. The status indicates detected planes.
3. Choose a local image, then tap the surface. Pinch to resize, use two fingers to rotate, and drag to reposition on the same surface.
4. Adjust opacity or Precision (grayscale, contrast, local Sobel line art). Lock to disable gestures while world tracking continues.
5. Hide/show or reset. Anchors are session-local; closing or rotating the activity starts a new session. No cross-session persistence is claimed.

## Implementation
Only the render thread calls `Session.update`, hit tests, creates/detaches anchors and changes anchor-local transforms. UI commands use a concurrent queue. Drag uses ray/plane intersection in the existing anchor's coordinate system and validates the plane polygon, avoiding repeated anchor replacement. Both horizontal and vertical plane hit poses orient the artwork in local XZ. Image dimensions and alpha are retained; decoding is bounded to 1536px. Depth is enabled only when supported, assisting ARCore surface finding; placement still requires a detected plane and falls back to plane detection on devices without depth. No arbitrary-object reconstruction or depth occlusion is claimed.

Pause stops GLSurfaceView before pausing the AR session. Resume starts ARCore before the GL loop. Tracking loss hides the drawing to avoid misleading placement. World stability depends on lighting, surface texture and device tracking; this is not a millimeter-accuracy measurement tool.

## Build
Java 17: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
CI uploads `TraceAR-debug` and attaches the debug-signed APK to v1.0.0. Debug signing is for installation/testing, not a production Play Store release.

## Verification
CI verifies compilation, lint and ray/plane geometry unit tests. No physical ARCore phone is available in the development environment. Runtime camera rendering, alignment, wall/table placement, transparency, tracking recovery, lifecycle and gesture comfort require device validation. Test a textured table and wall; lock a drawing and move sideways/closer; verify perspective changes and world stability. Also deny camera permission, pause/resume, pick a transparent image and reset.

Text-to-image generation is intentionally not shipped; the local bitmap-to-texture pipeline can accept future generated artwork without changing the AR renderer.
