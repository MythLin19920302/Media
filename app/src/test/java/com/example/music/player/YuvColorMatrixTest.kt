package com.example.music.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YuvColorMatrixTest {

  /**
   * 参考值取自 assets/samples/landscape_720p.mp4（BT.709、有限范围）第 2 秒那一帧：
   * 同一个像素的 YUV 原始值，以及桌面 ffmpeg 按 BT.709 有限范围换算出的 RGB（色条内部，色度均匀的位置）。
   */
  @Test
  fun bt709LimitedMatchesFfmpeg() {
    val matrix = YuvColorMatrix(YuvStandard.BT709, fullRange = false)
    val samples = listOf(
      intArrayOf(16, 128, 128) to intArrayOf(0, 0, 0),
      intArrayOf(63, 102, 240) to intArrayOf(255, 1, 0),
      intArrayOf(78, 214, 230) to intArrayOf(255, 0, 254),
      intArrayOf(32, 240, 118) to intArrayOf(1, 0, 255),
      intArrayOf(219, 16, 138) to intArrayOf(254, 255, 0),
      intArrayOf(173, 42, 26) to intArrayOf(0, 255, 1),
      intArrayOf(235, 128, 128) to intArrayOf(255, 255, 255),
    )
    for ((yuv, expected) in samples) {
      assertClose(expected, matrix.toRgb(yuv[0], yuv[1], yuv[2]), tolerance = 2, label = yuv.contentToString())
    }
  }

  /** 之前在电脑上实测过的 BT.601 像素：画面中心的纯蓝色。 */
  @Test
  fun bt601LimitedPureBlue() {
    val matrix = YuvColorMatrix(YuvStandard.BT601, fullRange = false)
    assertArrayEquals(intArrayOf(0, 0, 255), matrix.toRgb(41, 240, 110))
  }

  @Test
  fun fullRangeKeepsBlackAndWhite() {
    val matrix = YuvColorMatrix(YuvStandard.BT709, fullRange = true)
    assertArrayEquals(intArrayOf(0, 0, 0), matrix.toRgb(0, 128, 128))
    assertArrayEquals(intArrayOf(255, 255, 255), matrix.toRgb(255, 128, 128))
  }

  @Test
  fun unspecifiedColorSpaceGuessesByHeight() {
    assertEquals(YuvStandard.BT709, YuvStandard.fromFFmpeg(colorSpace = 2, height = 1080))
    assertEquals(YuvStandard.BT601, YuvStandard.fromFFmpeg(colorSpace = 2, height = 480))
    assertEquals(YuvStandard.BT601, YuvStandard.fromFFmpeg(colorSpace = 6, height = 1080))
  }

  /** 旋转后，屏幕的每个角应该取到原始画面的哪个角。 */
  @Test
  fun textureCoordinatesRotateClockwise() {
    // 原始画面四个角的纹理坐标
    val topLeft = 0f to 0f
    val topRight = 1f to 0f
    val bottomLeft = 0f to 1f
    val bottomRight = 1f to 1f
    // 顺时针转 90°：屏幕左上角显示原始的左下角，右上角显示原始的左上角
    val rotated90 = corners(textureCoordinates(90))
    assertEquals(bottomLeft, rotated90.getValue("screenTopLeft"))
    assertEquals(topLeft, rotated90.getValue("screenTopRight"))
    assertEquals(bottomRight, rotated90.getValue("screenBottomLeft"))
    assertEquals(topRight, rotated90.getValue("screenBottomRight"))
    // 不旋转：一一对应
    val identity = corners(textureCoordinates(0))
    assertEquals(topLeft, identity.getValue("screenTopLeft"))
    assertEquals(bottomRight, identity.getValue("screenBottomRight"))
    // 转 180°：左上对右下
    assertEquals(bottomRight, corners(textureCoordinates(180)).getValue("screenTopLeft"))
    // 转 270°：屏幕左上角显示原始的右上角
    assertEquals(topRight, corners(textureCoordinates(270)).getValue("screenTopLeft"))
  }

  /** 顶点顺序是 左下、右下、左上、右上。 */
  private fun corners(coords: FloatArray): Map<String, Pair<Float, Float>> = mapOf(
    "screenBottomLeft" to (coords[0] to coords[1]),
    "screenBottomRight" to (coords[2] to coords[3]),
    "screenTopLeft" to (coords[4] to coords[5]),
    "screenTopRight" to (coords[6] to coords[7]),
  )

  private fun assertClose(expected: IntArray, actual: IntArray, tolerance: Int, label: String) {
    val close = expected.indices.all { abs(expected[it] - actual[it]) <= tolerance }
    assertTrue("$label: expected ${expected.contentToString()} but was ${actual.contentToString()}", close)
  }
}
