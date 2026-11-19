package com.example.music.player

import java.nio.ByteBuffer
import kotlinx.coroutines.channels.SendChannel

/**
 * 一帧 8 位 yuv420p：Y、U、V 三个平面各一块 direct ByteBuffer，每个平面紧密排列。
 * 帧会反复使用：显示完或丢弃后调用 [release] 还回帧池，下一帧接着往里解。
 */
class YuvFrame(val width: Int, val height: Int, private val pool: SendChannel<YuvFrame>) {
  val chromaWidth = (width + 1) / 2
  val chromaHeight = (height + 1) / 2
  val y: ByteBuffer = ByteBuffer.allocateDirect(width * height)
  val u: ByteBuffer = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)
  val v: ByteBuffer = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)

  /** 这一帧的显示时间（微秒）。 */
  var ptsUs = 0L

  /** 还回帧池；帧池已经关闭（这次播放结束了）时什么也不做。 */
  fun release() {
    pool.trySend(this)
  }
}
