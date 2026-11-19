package com.example.music.ffmpeg

/** FFmpeg 的 Kotlin 入口；每个 external fun 的实现在 app/src/main/cpp/ffmpeg_jni.c 里。 */
object FFmpegBridge {
  init {
    // 加载 libffmpeg_bridge.so，它依赖的 FFmpeg 各个 .so 会被系统一并加载。
    System.loadLibrary("ffmpeg_bridge")
  }

  /** [libraryVersions] 返回值对应的库名，顺序与 C 侧一致。 */
  val libraryNames = listOf(
    "libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample",
  )

  /** 编进 App 的 FFmpeg 版本号，例如 "n8.1.3"。 */
  external fun versionInfo(): String

  /** 6 个库各自的版本号，顺序见 [libraryNames]；每个值是 major shl 16 or minor shl 8 or micro。 */
  external fun libraryVersions(): IntArray

  /** 列出某一类已启用的组件名；kind 取 [FFmpegComponent.id]。 */
  external fun listComponents(kind: Int): Array<String>

  /** 把 FFmpeg 的负数错误码转成可读的文字。 */
  external fun errorString(code: Int): String
}

/** FFmpeg 的组件类别；id 要和 ffmpeg_jni.c 里 listComponents 的 switch 分支一致。 */
enum class FFmpegComponent(val id: Int, val label: String) {
  DECODER(0, "解码器"),
  ENCODER(1, "编码器"),
  DEMUXER(2, "解封装"),
  MUXER(3, "封装"),
  FILTER(4, "滤镜"),
  PROTOCOL(5, "协议"),
}
