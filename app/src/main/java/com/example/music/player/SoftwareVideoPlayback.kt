package com.example.music.player

import com.example.music.ffmpeg.FFmpegVideoDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 播放统计。 */
data class PlaybackProgress(
  val positionUs: Long,
  val framesDecoded: Int,
  val framesDrawn: Int,
  val framesDropped: Int,
  /** 每帧在 C 层解码并拷出 YUV 的平均耗时。 */
  val avgDecodeUs: Int,
  /** 每帧在渲染线程上传纹理并绘制的平均耗时（CPU 侧时间）。 */
  val avgDrawUs: Int,
)

/** 播放过程中的回调，都在后台线程上调用。 */
interface PlaybackListener {
  fun onStreamInfo(info: FFmpegVideoDecoder.StreamInfo, colorMatrix: YuvColorMatrix)
  fun onFirstFrame(format: FFmpegVideoDecoder.DecodedFormat)
  fun onProgress(progress: PlaybackProgress)
}

private const val POOL_SIZE = 3                     // 一帧在解、一帧等着画、一帧备用
private const val LATE_THRESHOLD_MIN_US = 40_000L   // 晚到多少要丢帧：一帧时长，限制在 40~100 毫秒（同 ffplay）
private const val LATE_THRESHOLD_MAX_US = 100_000L
private const val PROGRESS_INTERVAL_NS = 200_000_000L

/**
 * 软解播放循环：向 FFmpeg 要一帧 YUV → 按时间戳等到点，太晚就丢 → 交给 [YuvGlRenderer] 显示。
 * 只播画面不播声音，节奏按系统时钟控制。协程被取消（换视频、离开页面）时停止并关闭解码器。
 */
suspend fun playVideo(fd: Int, renderer: YuvGlRenderer, listener: PlaybackListener) {
  withContext(Dispatchers.Default) {
    FFmpegVideoDecoder.open(fd).use { decoder ->
      val info = decoder.streamInfo
      val colorMatrix = YuvColorMatrix(
        YuvStandard.fromFFmpeg(info.colorSpace, info.height),
        fullRange = info.colorRange == 2,
      )
      renderer.configure(colorMatrix, info.rotation)
      listener.onStreamInfo(info, colorMatrix)

      // 帧池：几块 YUV 缓冲区轮流用，渲染线程画完一帧就还回来
      val pool = Channel<YuvFrame>(POOL_SIZE)
      repeat(POOL_SIZE) { pool.trySend(YuvFrame(info.width, info.height, pool)) }
      val frameUs = if (info.fps > 0) (1_000_000 / info.fps).toLong() else 33_333L
      val lateThresholdUs = frameUs.coerceIn(LATE_THRESHOLD_MIN_US, LATE_THRESHOLD_MAX_US)

      var decoded = 0
      var dropped = 0
      var decodeNsTotal = 0L
      var firstPtsUs = Long.MIN_VALUE
      var clockStartNs = 0L
      var positionUs = 0L
      var lastReportNs = 0L
      fun report() {
        val stats = renderer.stats()
        listener.onProgress(
          PlaybackProgress(
            positionUs = positionUs,
            framesDecoded = decoded,
            framesDrawn = stats.framesDrawn,
            framesDropped = dropped + stats.framesReplaced,
            avgDecodeUs = if (decoded > 0) (decodeNsTotal / decoded / 1000).toInt() else 0,
            avgDrawUs = stats.avgDrawUs,
          ),
        )
      }

      try {
        while (true) {
          val frame = pool.receive()
          val decodeStart = System.nanoTime()
          val ptsUs = decoder.decodeNextFrame(frame.y, frame.u, frame.v)
          if (ptsUs == FFmpegVideoDecoder.END_OF_STREAM) {
            frame.release()
            break
          }
          decodeNsTotal += System.nanoTime() - decodeStart
          decoded++
          if (decoded == 1) decoder.decodedFormat?.let(listener::onFirstFrame)

          frame.ptsUs = ptsUs
          if (firstPtsUs == Long.MIN_VALUE) {
            firstPtsUs = ptsUs
            clockStartNs = System.nanoTime()
          }
          // 这一帧应该在开播后 positionUs 显示；和实际过去的时间比：> 0 是早了，< 0 是晚了
          positionUs = ptsUs - firstPtsUs
          val earlyUs = positionUs - (System.nanoTime() - clockStartNs) / 1000
          if (earlyUs < -lateThresholdUs && decoded > 1) {
            dropped++  // 晚太多：不显示，接着解下一帧追进度
            frame.release()
          } else {
            if (earlyUs > 0) delay(earlyUs / 1000)
            renderer.submit(frame)
          }

          val now = System.nanoTime()
          if (now - lastReportNs >= PROGRESS_INTERVAL_NS) {
            lastReportNs = now
            report()
          }
        }
        report()
      } finally {
        pool.close()
      }
    }
  }
}
