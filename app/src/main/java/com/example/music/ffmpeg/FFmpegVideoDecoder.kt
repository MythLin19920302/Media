package com.example.music.ffmpeg

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * FFmpeg 视频解码器：C 这一层只做解封装和解码，每次 [decodeNextFrame] 输出一帧 8 位 yuv420p。
 * 什么时候显示、要不要丢帧、怎么画到屏幕上，都由调用方负责（见 player/ 目录）。
 * 实现在 app/src/main/cpp/video_decoder.c。不是线程安全的：打开、解码、关闭要在同一个线程上依次调用。
 */
class FFmpegVideoDecoder private constructor() : Closeable {

  /** 视频流参数，打开时由 native 回调填好。 */
  data class StreamInfo(
    val codec: String,
    val width: Int,
    val height: Int,
    /** 显示前要顺时针旋转的角度：0 / 90 / 180 / 270。 */
    val rotation: Int,
    val fps: Double,
    /** 时长（微秒），未知为 -1。 */
    val durationUs: Long,
    /** FFmpeg 的 AVColorSpace 取值：1 = BT.709，5 / 6 = BT.601，9 / 10 = BT.2020，2 = 未标注。 */
    val colorSpace: Int,
    val colorSpaceName: String,
    /** FFmpeg 的 AVColorRange 取值：1 = 有限范围（tv），2 = 全范围（pc），0 = 未标注。 */
    val colorRange: Int,
  ) {
    private val rotated: Boolean get() = rotation % 180 != 0
    val displayWidth: Int get() = if (rotated) height else width
    val displayHeight: Int get() = if (rotated) width else height
  }

  /** 解码器实际输出的格式（交给 Kotlin 之前会统一成 8 位 yuv420p 并去掉每行的对齐填充）。 */
  data class DecodedFormat(val pixelFormat: String, val linesizeY: Int, val linesizeU: Int, val linesizeV: Int)

  private var handle = 0L

  lateinit var streamInfo: StreamInfo
    private set

  /** 第一帧解出来后才有值。 */
  var decodedFormat: DecodedFormat? = null
    private set

  /**
   * 解出下一帧，写进 Y、U、V 三个 direct ByteBuffer（每个平面紧密排列，U、V 的宽高是 Y 的一半，向上取整）。
   * 返回这一帧的显示时间（微秒）；没有更多帧时返回 [END_OF_STREAM]；出错抛 [IOException]。
   */
  fun decodeNextFrame(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer): Long {
    check(handle != 0L) { "FFmpegVideoDecoder is closed" }
    return nativeDecodeNextFrame(handle, y, u, v)
  }

  override fun close() {
    if (handle != 0L) {
      nativeClose(handle)
      handle = 0L
    }
  }

  // 下面两个方法由 native 回调，名字和参数要和 video_decoder.c 里的 GetMethodID 一致
  @Suppress("unused")
  private fun onStreamInfo(
    codec: String,
    width: Int,
    height: Int,
    rotation: Int,
    fps: Double,
    durationUs: Long,
    colorSpace: Int,
    colorSpaceName: String,
    colorRange: Int,
  ) {
    streamInfo = StreamInfo(codec, width, height, rotation, fps, durationUs, colorSpace, colorSpaceName, colorRange)
  }

  @Suppress("unused")
  private fun onFirstFrame(pixelFormat: String, linesizeY: Int, linesizeU: Int, linesizeV: Int) {
    decodedFormat = DecodedFormat(pixelFormat, linesizeY, linesizeU, linesizeV)
  }

  private external fun nativeOpen(fd: Int): Long
  private external fun nativeDecodeNextFrame(handle: Long, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer): Long
  private external fun nativeClose(handle: Long)

  companion object {
    /** [decodeNextFrame] 的返回值：没有更多帧了。 */
    const val END_OF_STREAM = Long.MIN_VALUE

    init {
      System.loadLibrary("ffmpeg_bridge")
    }

    /** 打开 fd 指向的视频。FFmpeg 会复制一份 fd，调用方之后可以关掉自己的。失败抛 [IOException]。 */
    @Throws(IOException::class)
    fun open(fd: Int): FFmpegVideoDecoder {
      val decoder = FFmpegVideoDecoder()
      decoder.handle = decoder.nativeOpen(fd)
      return decoder
    }
  }
}
