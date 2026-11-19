package com.example.music.player

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 用 OpenGL ES 2.0 把 yuv420p 画到 GLSurfaceView 上：Y、U、V 三个平面各传成一张纹理，
 * 在片元着色器里按 [YuvColorMatrix] 换算成 RGB；旋转靠调整纹理坐标完成，不用逐像素搬数据。
 * [configure]、[submit]、[stats] 可以在任意线程调用；onXxx 方法都在 GLSurfaceView 的渲染线程上执行。
 */
class YuvGlRenderer : GLSurfaceView.Renderer {

  data class Stats(val framesDrawn: Int, val framesReplaced: Int, val avgDrawUs: Int)

  private val lock = Any()

  // 以下字段受 lock 保护
  private var view: GLSurfaceView? = null
  private var pending: YuvFrame? = null
  private var colorMatrix = YuvColorMatrix(YuvStandard.BT709, fullRange = false)
  private var rotation = 0
  private var framesDrawn = 0
  private var framesReplaced = 0
  private var drawNsTotal = 0L

  // 以下字段只在渲染线程上使用
  private var program = 0
  private var aPosition = 0
  private var aTexCoord = 0
  private var uYuvToRgb = 0
  private var uOffset = 0
  private val uTextures = IntArray(3)
  private val textures = IntArray(3)
  private var textureWidth = 0
  private var textureHeight = 0
  private var hasImage = false
  private val positions = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
  private var texCoords = floatBufferOf(*textureCoordinates(0))
  private var appliedRotation = 0

  fun attach(view: GLSurfaceView) = synchronized(lock) { this.view = view }

  fun detach(view: GLSurfaceView) = synchronized(lock) { if (this.view === view) this.view = null }

  /** 开始播放一个新视频时调用：设置颜色换算和旋转，并清空统计。 */
  fun configure(colorMatrix: YuvColorMatrix, rotation: Int) {
    synchronized(lock) {
      this.colorMatrix = colorMatrix
      this.rotation = rotation
      pending?.release()
      pending = null
      framesDrawn = 0
      framesReplaced = 0
      drawNsTotal = 0
    }
  }

  /** 交一帧去显示。上一帧如果还没来得及画，就直接还回帧池，算作被替换掉。 */
  fun submit(frame: YuvFrame) {
    val target = synchronized(lock) {
      pending?.let {
        it.release()
        framesReplaced++
      }
      pending = frame
      view
    }
    target?.requestRender()
  }

  fun stats(): Stats = synchronized(lock) {
    Stats(framesDrawn, framesReplaced, if (framesDrawn > 0) (drawNsTotal / framesDrawn / 1000).toInt() else 0)
  }

  override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
    // GL 上下文是新的：着色器和纹理都要重新建
    program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    aPosition = GLES20.glGetAttribLocation(program, "aPosition")
    aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
    uYuvToRgb = GLES20.glGetUniformLocation(program, "uYuvToRgb")
    uOffset = GLES20.glGetUniformLocation(program, "uOffset")
    uTextures[0] = GLES20.glGetUniformLocation(program, "uTexY")
    uTextures[1] = GLES20.glGetUniformLocation(program, "uTexU")
    uTextures[2] = GLES20.glGetUniformLocation(program, "uTexV")
    GLES20.glGenTextures(3, textures, 0)
    for (texture in textures) {
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      // 宽高不是 2 的幂的纹理，在 ES 2.0 里必须用 CLAMP_TO_EDGE
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
    // 平面每行紧密排列，宽度不一定是 4 的倍数，所以按 1 字节对齐读取
    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    textureWidth = 0
    textureHeight = 0
    hasImage = false
  }

  override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
    GLES20.glViewport(0, 0, width, height)
  }

  override fun onDrawFrame(unused: GL10?) {
    val frame: YuvFrame?
    val matrix: YuvColorMatrix
    val currentRotation: Int
    synchronized(lock) {
      frame = pending
      pending = null
      matrix = colorMatrix
      currentRotation = rotation
    }
    val start = System.nanoTime()
    if (frame != null) {
      upload(frame)
      frame.release()  // 数据已经进了纹理，缓冲区可以还回去给下一帧用
      hasImage = true
    }
    if (currentRotation != appliedRotation) {
      texCoords = floatBufferOf(*textureCoordinates(currentRotation))
      appliedRotation = currentRotation
    }
    GLES20.glClearColor(0f, 0f, 0f, 1f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    if (hasImage) draw(matrix)
    if (frame != null) {
      val elapsed = System.nanoTime() - start
      synchronized(lock) {
        framesDrawn++
        drawNsTotal += elapsed
      }
    }
  }

  /** 把三个平面传进三张单通道（LUMINANCE）纹理；尺寸变了才重新分配，否则只更新内容。 */
  private fun upload(frame: YuvFrame) {
    val reallocate = frame.width != textureWidth || frame.height != textureHeight
    val planes = arrayOf(frame.y, frame.u, frame.v)
    for (i in 0..2) {
      val width = if (i == 0) frame.width else frame.chromaWidth
      val height = if (i == 0) frame.height else frame.chromaHeight
      planes[i].position(0)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
      if (reallocate) {
        GLES20.glTexImage2D(
          GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0,
          GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, planes[i],
        )
      } else {
        GLES20.glTexSubImage2D(
          GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
          GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, planes[i],
        )
      }
    }
    textureWidth = frame.width
    textureHeight = frame.height
  }

  private fun draw(matrix: YuvColorMatrix) {
    GLES20.glUseProgram(program)
    GLES20.glUniformMatrix3fv(uYuvToRgb, 1, false, matrix.matrix, 0)
    GLES20.glUniform3fv(uOffset, 1, matrix.offset, 0)
    for (i in 0..2) {
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
      GLES20.glUniform1i(uTextures[i], i)
    }
    positions.position(0)
    GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positions)
    GLES20.glEnableVertexAttribArray(aPosition)
    texCoords.position(0)
    GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
    GLES20.glEnableVertexAttribArray(aTexCoord)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
  }

  private companion object {
    const val VERTEX_SHADER = """
      attribute vec4 aPosition;
      attribute vec2 aTexCoord;
      varying vec2 vTexCoord;
      void main() {
        gl_Position = aPosition;
        vTexCoord = aTexCoord;
      }
    """

    // 每个像素：从三张纹理取出 Y、U、V，减去零点后乘以换算矩阵，就是 RGB
    const val FRAGMENT_SHADER = """
      #ifdef GL_FRAGMENT_PRECISION_HIGH
      precision highp float;
      #else
      precision mediump float;
      #endif
      varying vec2 vTexCoord;
      uniform sampler2D uTexY;
      uniform sampler2D uTexU;
      uniform sampler2D uTexV;
      uniform mat3 uYuvToRgb;
      uniform vec3 uOffset;
      void main() {
        vec3 yuv = vec3(
            texture2D(uTexY, vTexCoord).r,
            texture2D(uTexU, vTexCoord).r,
            texture2D(uTexV, vTexCoord).r);
        gl_FragColor = vec4(clamp(uYuvToRgb * (yuv - uOffset), 0.0, 1.0), 1.0);
      }
    """

    fun floatBufferOf(vararg values: Float): FloatBuffer =
      ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(values)
        position(0)
      }

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
      val program = GLES20.glCreateProgram()
      GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, vertexSource))
      GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
      GLES20.glLinkProgram(program)
      val status = IntArray(1)
      GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
      check(status[0] == GLES20.GL_TRUE) { "link program failed: ${GLES20.glGetProgramInfoLog(program)}" }
      return program
    }

    fun compileShader(type: Int, source: String): Int {
      val shader = GLES20.glCreateShader(type)
      GLES20.glShaderSource(shader, source.trimIndent())
      GLES20.glCompileShader(shader)
      val status = IntArray(1)
      GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
      check(status[0] == GLES20.GL_TRUE) { "compile shader failed: ${GLES20.glGetShaderInfoLog(shader)}" }
      return shader
    }
  }
}
