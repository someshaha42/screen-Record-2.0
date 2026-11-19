package com.example.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.example.compat.PlatformCompat
import com.example.region.PixelRegion
import com.example.settings.InternalRecordingConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages MediaProjection, VirtualDisplay, dynamic display resize handling, and the OpenGL ES 2.0
 * GPU surface-to-surface pipeline for both Full Screen capture and pixel-exact Region cropping
 * within the user's selected Portrait or Landscape orientation without CPU frame copies.
 */
class ScreenCaptureManager(
  private val context: Context,
  private val mediaProjection: MediaProjection,
  private val outputSurface: Surface,
  private val outputWidth: Int,
  private val outputHeight: Int,
  private val cropRegion: PixelRegion?,
  private val onProjectionStopped: () -> Unit,
) {
  private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
  private val isReleased = AtomicBoolean(false)

  private var glThread: HandlerThread? = null
  private var glHandler: Handler? = null
  private var glPipeline: GlVideoPipeline? = null
  private var virtualDisplay: VirtualDisplay? = null

  private var currentCaptureWidth = 0
  private var currentCaptureHeight = 0
  private var currentDensityDpi = DisplayMetrics.DENSITY_DEFAULT

  private val projectionCallback =
    object : MediaProjection.Callback() {
      override fun onStop() {
        Log.i(TAG, "MediaProjection stopped by system or user")
        if (!isReleased.get()) {
          onProjectionStopped()
        }
      }

      override fun onCapturedContentResize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || isReleased.get()) return
        Log.d(TAG, "onCapturedContentResize: ${width}x$height")
        resizeCapture(width, height)
      }

      override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        Log.d(TAG, "onCapturedContentVisibilityChanged: isVisible=$isVisible")
      }
    }

  private val displayListener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) = Unit

      override fun onDisplayRemoved(displayId: Int) = Unit

      override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY || isReleased.get()) return
        val (w, h, dpi) = queryScreenMetrics(context)
        if (w != currentCaptureWidth || h != currentCaptureHeight) {
          Log.d(TAG, "Display rotated/changed: ${w}x$h")
          resizeCapture(w, h, dpi)
        }
      }
    }

  fun start() {
    val (w, h, dpi) = queryScreenMetrics(context)
    currentCaptureWidth = w
    currentCaptureHeight = h
    currentDensityDpi = dpi

    val thread = HandlerThread("FrameCut-GLPipeline").apply { start() }
    glThread = thread
    val handler = Handler(thread.looper)
    glHandler = handler

    val readyLatch = CountDownLatch(1)
    var initError: Throwable? = null
    var inputSurface: Surface? = null

    handler.post {
      try {
        val pipeline =
          GlVideoPipeline(
            encoderSurface = outputSurface,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            initialCaptureWidth = w,
            initialCaptureHeight = h,
            cropRegion = cropRegion,
          )
        pipeline.initGl()
        glPipeline = pipeline
        inputSurface = pipeline.captureSurface
      } catch (t: Throwable) {
        initError = t
      } finally {
        readyLatch.countDown()
      }
    }

    readyLatch.await(3, TimeUnit.SECONDS)
    initError?.let { throw it }

    val surface =
      inputSurface ?: throw IllegalStateException("Failed to initialize GL capture surface")

    mediaProjection.registerCallback(projectionCallback, handler)
    displayManager.registerDisplayListener(displayListener, handler)

    virtualDisplay =
      mediaProjection.createVirtualDisplay(
        "FrameCut-VirtualDisplay",
        w,
        h,
        dpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        surface,
        null,
        handler,
      )
  }

  fun setPaused(paused: Boolean) {
    glHandler?.post { glPipeline?.setPaused(paused) }
  }

  private fun resizeCapture(newW: Int, newH: Int, newDpi: Int = currentDensityDpi) {
    val evenW = if (newW % 2 == 0) newW else (newW - 1).coerceAtLeast(2)
    val evenH = if (newH % 2 == 0) newH else (newH - 1).coerceAtLeast(2)
    if (evenW == currentCaptureWidth && evenH == currentCaptureHeight) return

    currentCaptureWidth = evenW
    currentCaptureHeight = evenH
    currentDensityDpi = newDpi

    glHandler?.post {
      glPipeline?.updateCaptureDimensions(evenW, evenH)
      runCatching { virtualDisplay?.resize(evenW, evenH, newDpi) }
    }
  }

  fun stopAndRelease() {
    if (isReleased.getAndSet(true)) return

    runCatching { displayManager.unregisterDisplayListener(displayListener) }
    runCatching { mediaProjection.unregisterCallback(projectionCallback) }
    runCatching { virtualDisplay?.release() }
    virtualDisplay = null

    val releaseLatch = CountDownLatch(1)
    glHandler?.post {
      runCatching { glPipeline?.release() }
      glPipeline = null
      releaseLatch.countDown()
    }
    releaseLatch.await(1500, TimeUnit.MILLISECONDS)

    glThread?.quitSafely()
    runCatching { glThread?.join(1000) }
    glThread = null
    glHandler = null

    runCatching { mediaProjection.stop() }
  }

  companion object {
    private const val TAG = "ScreenCaptureManager"

    fun queryScreenMetrics(context: Context): Triple<Int, Int, Int> {
      return PlatformCompat.queryMaximumDisplayMetrics(context)
    }
  }
}

/**
 * Lightweight OpenGL ES 2.0 OES-to-RGBA surface pipeline. Renders VirtualDisplay frames directly on
 * GPU onto MediaCodec's input EGLSurface with exact region UV cropping, seamless encoder surface
 * swapping on rotation, and monotonic presentation timestamps across pause/resume.
 */
private class GlVideoPipeline(
  private val encoderSurface: Surface,
  private val outputWidth: Int,
  private val outputHeight: Int,
  initialCaptureWidth: Int,
  initialCaptureHeight: Int,
  private val cropRegion: PixelRegion?,
) : SurfaceTexture.OnFrameAvailableListener {

  private val minFrameIntervalNs =
    ((1_000_000_000L / InternalRecordingConfig.TARGET_FPS.coerceIn(1, 240)) * 85L) / 100L
  private var lastSubmittedPtsNs = -1L

  private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

  private var programId = 0
  private var textureId = 0
  private var positionHandle = 0
  private var texCoordHandle = 0
  private var texMatrixHandle = 0

  private var surfaceTexture: SurfaceTexture? = null
  var captureSurface: Surface? = null
    private set

  private var captureWidth = initialCaptureWidth
  private var captureHeight = initialCaptureHeight

  private val stMatrix = FloatArray(16)
  private val vertexBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
  private val texCoordBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

  private var isPaused = false
  private var firstFrameNs = 0L
  private var pauseStartNs = 0L
  private var pausedAccumulatedNs = 0L
  private var released = false

  fun initGl() {
    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }
    val version = IntArray(2)
    check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "Unable to initialize EGL14" }

    val attribList =
      intArrayOf(
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL_RECORDABLE_ANDROID, 1,
        EGL14.EGL_NONE,
      )
    val configs = arrayOfNulls<EGLConfig>(1)
    val numConfigs = IntArray(1)
    check(EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
      "Unable to find RGB888+recordable EGLConfig"
    }
    val config = configs[0] ?: throw IllegalStateException("Null EGLConfig")

    val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
    eglContext =
      EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
    check(eglContext != EGL14.EGL_NO_CONTEXT) { "Failed to create EGLContext" }

    val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
    eglSurface =
      EGL14.eglCreateWindowSurface(eglDisplay, config, encoderSurface, surfaceAttribs, 0)
    check(eglSurface != EGL14.EGL_NO_SURFACE) { "Failed to create EGLWindowSurface" }

    check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      "eglMakeCurrent failed"
    }

    programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
    texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
    texMatrixHandle = GLES20.glGetUniformLocation(programId, "uTexMatrix")

    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    textureId = textures[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MIN_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MAG_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_S,
      GLES20.GL_CLAMP_TO_EDGE,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_T,
      GLES20.GL_CLAMP_TO_EDGE,
    )

    surfaceTexture =
      SurfaceTexture(textureId).apply {
        setDefaultBufferSize(captureWidth, captureHeight)
        setOnFrameAvailableListener(this@GlVideoPipeline)
      }
    captureSurface = Surface(surfaceTexture)
    recomputeGeometry()
  }

  fun setPaused(paused: Boolean) {
    if (paused == isPaused) return
    val now = SystemClock.elapsedRealtimeNanos()
    if (paused) {
      pauseStartNs = now
      isPaused = true
    } else {
      if (pauseStartNs > 0L && firstFrameNs > 0L) {
        pausedAccumulatedNs += (now - pauseStartNs).coerceAtLeast(0L)
      }
      pauseStartNs = 0L
      isPaused = false
    }
  }

  fun updateCaptureDimensions(newW: Int, newH: Int) {
    captureWidth = newW
    captureHeight = newH
    surfaceTexture?.setDefaultBufferSize(newW, newH)
    recomputeGeometry()
  }

  private fun recomputeGeometry() {
    var xScale = 1f
    var yScale = 1f

    val sLeft: Float
    val sRight: Float
    val tBottom: Float
    val tTop: Float

    if (cropRegion == null) {
      sLeft = 0f
      sRight = 1f
      tBottom = 0f
      tTop = 1f

      // Fit rotated capture cleanly inside fixed orientation canvas without distortion
      val captureAspect = captureWidth.toFloat() / captureHeight.toFloat()
      val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()
      if (kotlin.math.abs(captureAspect - outputAspect) > 0.01f) {
        if (captureAspect > outputAspect) {
          yScale = outputAspect / captureAspect
        } else {
          xScale = captureAspect / outputAspect
        }
      }
    } else {
      if (captureWidth == cropRegion.screenWidth && captureHeight == cropRegion.screenHeight) {
        sLeft = cropRegion.left.toFloat() / captureWidth.toFloat()
        sRight = cropRegion.right.toFloat() / captureWidth.toFloat()
        tTop = 1f - (cropRegion.top.toFloat() / captureHeight.toFloat())
        tBottom = 1f - (cropRegion.bottom.toFloat() / captureHeight.toFloat())
      } else {
        // Orientation changed during region capture: keep proportional crop inside current capture
        val normLeft = cropRegion.left.toFloat() / cropRegion.screenWidth.toFloat()
        val normRight = cropRegion.right.toFloat() / cropRegion.screenWidth.toFloat()
        val normTop = cropRegion.top.toFloat() / cropRegion.screenHeight.toFloat()
        val normBottom = cropRegion.bottom.toFloat() / cropRegion.screenHeight.toFloat()
        sLeft = normLeft.coerceIn(0f, 0.9f)
        sRight = normRight.coerceIn(sLeft + 0.05f, 1f)
        tTop = (1f - normTop).coerceIn(0.1f, 1f)
        tBottom = (1f - normBottom).coerceIn(0f, tTop - 0.05f)

        val croppedW = (sRight - sLeft) * captureWidth
        val croppedH = (tTop - tBottom) * captureHeight
        val cropAspect = croppedW / croppedH.coerceAtLeast(1f)
        val outAspect = outputWidth.toFloat() / outputHeight.toFloat()
        if (kotlin.math.abs(cropAspect - outAspect) > 0.01f) {
          if (cropAspect > outAspect) yScale = outAspect / cropAspect
          else xScale = cropAspect / outAspect
        }
      }
    }

    // Triangle strip: bottom-left, bottom-right, top-left, top-right
    vertexBuffer.clear()
    vertexBuffer.put(
      floatArrayOf(
        -xScale, -yScale,
        xScale, -yScale,
        -xScale, yScale,
        xScale, yScale,
      ),
    )
    vertexBuffer.position(0)

    texCoordBuffer.clear()
    texCoordBuffer.put(
      floatArrayOf(
        sLeft, tBottom,
        sRight, tBottom,
        sLeft, tTop,
        sRight, tTop,
      ),
    )
    texCoordBuffer.position(0)
  }

  override fun onFrameAvailable(st: SurfaceTexture) {
    if (released) return
    try {
      st.updateTexImage()
      if (isPaused) return

      st.getTransformMatrix(stMatrix)
      val nowNs = SystemClock.elapsedRealtimeNanos()
      if (firstFrameNs == 0L) {
        firstFrameNs = nowNs
      }
      val ptsNs = (nowNs - firstFrameNs - pausedAccumulatedNs).coerceAtLeast(0L)
      if (lastSubmittedPtsNs >= 0L && (ptsNs - lastSubmittedPtsNs) < minFrameIntervalNs) {
        return
      }
      lastSubmittedPtsNs = ptsNs

      GLES20.glViewport(0, 0, outputWidth, outputHeight)
      GLES20.glClearColor(0f, 0f, 0f, 1f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

      GLES20.glUseProgram(programId)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

      GLES20.glEnableVertexAttribArray(positionHandle)
      GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

      GLES20.glEnableVertexAttribArray(texCoordHandle)
      GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

      GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, stMatrix, 0)
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

      GLES20.glDisableVertexAttribArray(positionHandle)
      GLES20.glDisableVertexAttribArray(texCoordHandle)

      EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
      EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    } catch (e: Exception) {
      Log.e(TAG, "Error rendering GL frame", e)
    }
  }

  fun release() {
    if (released) return
    released = true
    runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
    runCatching { captureSurface?.release() }
    captureSurface = null
    runCatching { surfaceTexture?.release() }
    surfaceTexture = null

    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
      if (eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        if (textureId != 0) {
          GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
          textureId = 0
        }
        if (programId != 0) {
          GLES20.glDeleteProgram(programId)
          programId = 0
        }
      }
      EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
      if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
      if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
      EGL14.eglReleaseThread()
      EGL14.eglTerminate(eglDisplay)
    }
    eglSurface = EGL14.EGL_NO_SURFACE
    eglContext = EGL14.EGL_NO_CONTEXT
    eglDisplay = EGL14.EGL_NO_DISPLAY
  }

  private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val prog = GLES20.glCreateProgram()
    GLES20.glAttachShader(prog, vs)
    GLES20.glAttachShader(prog, fs)
    GLES20.glLinkProgram(prog)
    val status = IntArray(1)
    GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) {
      "GL program link failed: ${GLES20.glGetProgramInfoLog(prog)}"
    }
    GLES20.glDeleteShader(vs)
    GLES20.glDeleteShader(fs)
    return prog
  }

  private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) {
      "GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
    }
    return shader
  }

  companion object {
    private const val TAG = "GlVideoPipeline"
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    private const val VERTEX_SHADER =
      """
      attribute vec4 aPosition;
      attribute vec4 aTexCoord;
      uniform mat4 uTexMatrix;
      varying vec2 vTexCoord;
      void main() {
        gl_Position = aPosition;
        vTexCoord = (uTexMatrix * aTexCoord).xy;
      }
      """

    private const val FRAGMENT_SHADER =
      """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      varying vec2 vTexCoord;
      uniform samplerExternalOES sTexture;
      void main() {
        gl_FragColor = texture2D(sTexture, vTexCoord);
      }
      """
  }
}
