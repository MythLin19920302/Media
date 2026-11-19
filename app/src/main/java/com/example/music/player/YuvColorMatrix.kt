package com.example.music.player

import kotlin.math.roundToInt

/** YUV 转 RGB 的系数标准；kr、kb 是红、蓝分量在亮度里所占的权重。 */
enum class YuvStandard(val label: String, val kr: Double, val kb: Double) {
  BT601("BT.601", 0.299, 0.114),
  BT709("BT.709", 0.2126, 0.0722),
  BT2020("BT.2020", 0.2627, 0.0593);

  companion object {
    /** 按 FFmpeg 的 AVColorSpace 取值选标准；视频没标注时按惯例猜：高清用 BT.709，标清用 BT.601。 */
    fun fromFFmpeg(colorSpace: Int, height: Int): YuvStandard = when (colorSpace) {
      1 -> BT709
      5, 6 -> BT601
      9, 10 -> BT2020
      else -> if (height >= 720) BT709 else BT601
    }
  }
}

/**
 * YUV → RGB 的换算：rgb = matrix × (yuv − offset)，各分量都归一化到 0~1。
 * GPU 着色器和单元测试用的是同一套数，保证屏幕上的颜色就是测试验证过的颜色。
 */
class YuvColorMatrix(val standard: YuvStandard, val fullRange: Boolean) {

  /** 列主序的 3×3 矩阵，可以直接交给 GLSL 的 mat3。 */
  val matrix: FloatArray

  /** 有限范围时 Y 的黑电平是 16，U、V 的零点都在 128。 */
  val offset: FloatArray = floatArrayOf(if (fullRange) 0f else 16f / 255f, 128f / 255f, 128f / 255f)

  init {
    val kr = standard.kr
    val kb = standard.kb
    val kg = 1 - kr - kb
    // 有限范围的 Y 只用 16~235、U/V 只用 16~240，先拉伸到满幅
    val ys = if (fullRange) 1.0 else 255.0 / 219.0
    val cs = if (fullRange) 1.0 else 255.0 / 224.0
    matrix = floatArrayOf(
      // 第 1 列：Y 对 R、G、B 的贡献
      ys.toFloat(), ys.toFloat(), ys.toFloat(),
      // 第 2 列：U（Cb）
      0f, (-2 * kb * (1 - kb) / kg * cs).toFloat(), (2 * (1 - kb) * cs).toFloat(),
      // 第 3 列：V（Cr）
      (2 * (1 - kr) * cs).toFloat(), (-2 * kr * (1 - kr) / kg * cs).toFloat(), 0f,
    )
  }

  val description: String get() = "${standard.label}，${if (fullRange) "全范围" else "有限范围"}"

  /** 在 CPU 上按同样的公式换算一个像素（输入输出都是 0~255），供单元测试对照。 */
  fun toRgb(y: Int, u: Int, v: Int): IntArray {
    val yuv = floatArrayOf(y / 255f - offset[0], u / 255f - offset[1], v / 255f - offset[2])
    return IntArray(3) { row ->
      val value = matrix[row] * yuv[0] + matrix[3 + row] * yuv[1] + matrix[6 + row] * yuv[2]
      (value * 255f).roundToInt().coerceIn(0, 255)
    }
  }
}

/**
 * 全屏四边形四个顶点对应的纹理坐标，顶点顺序是 左下、右下、左上、右上（用 TRIANGLE_STRIP 画）。
 * 纹理坐标 (0, 0) 是图像第一行的最左边，也就是原始画面的左上角。
 * rotation 是显示前要顺时针转的角度；旋转就是让屏幕的每个角去取原始画面的另一个角。
 */
fun textureCoordinates(rotation: Int): FloatArray = when (rotation) {
  90 -> floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f)
  180 -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f)
  270 -> floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)
  else -> floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
}
