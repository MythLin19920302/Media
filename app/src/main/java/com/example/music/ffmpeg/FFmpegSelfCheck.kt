package com.example.music.ffmpeg

import android.util.Log

private const val TAG = "FFmpegCheck"

data class LibraryVersion(val name: String, val version: String)

data class CapabilityCheck(val label: String, val passed: Boolean)

data class ComponentList(val label: String, val names: List<String>)

/** 自检结果：要么库加载成功并带回各项数据，要么加载或调用失败并带回错误信息。 */
sealed interface FFmpegReport {
  data class Loaded(
    val version: String,
    val libraries: List<LibraryVersion>,
    val checks: List<CapabilityCheck>,
    val components: List<ComponentList>,
  ) : FFmpegReport {
    val allPassed: Boolean get() = checks.all { it.passed }
  }

  data class Failed(val error: String) : FFmpegReport
}

/** 歌词分享视频这条链路必须具备的组件。 */
private class Requirement(val label: String, val component: FFmpegComponent, val name: String)

private val requirements = listOf(
  Requirement("H.264 硬件编码器", FFmpegComponent.ENCODER, "h264_mediacodec"),
  Requirement("AAC 编码器", FFmpegComponent.ENCODER, "aac"),
  Requirement("H.264 解码器", FFmpegComponent.DECODER, "h264"),
  Requirement("HEVC 解码器", FFmpegComponent.DECODER, "hevc"),
  Requirement("PNG 解码器（歌词图层）", FFmpegComponent.DECODER, "png"),
  Requirement("MP4 封装", FFmpegComponent.MUXER, "mp4"),
  Requirement("MP4/MOV 解封装", FFmpegComponent.DEMUXER, "mov"),
  Requirement("concat 解封装（歌词图层）", FFmpegComponent.DEMUXER, "concat"),
  Requirement("overlay 滤镜", FFmpegComponent.FILTER, "overlay"),
  Requirement("scale 滤镜", FFmpegComponent.FILTER, "scale"),
  Requirement("fd 协议（读取用户选择的文件）", FFmpegComponent.PROTOCOL, "fd"),
)

/** 通过 JNI 调用 FFmpeg 的各个库，汇总成一份报告；任何加载或调用失败都转成 [FFmpegReport.Failed]。 */
fun runFFmpegSelfCheck(): FFmpegReport {
  return try {
    val version = FFmpegBridge.versionInfo()
    val libraries = FFmpegBridge.libraryVersions().mapIndexed { index, value ->
      LibraryVersion(FFmpegBridge.libraryNames[index], formatVersion(value))
    }
    val components = FFmpegComponent.entries.associateWith { component ->
      FFmpegBridge.listComponents(component.id).sorted()
    }
    val checks = requirements.map { requirement ->
      // 有些组件名是逗号分隔的别名列表，例如 "mov,mp4,m4a,3gp,3g2,mj2"
      val available = components.getValue(requirement.component)
        .any { name -> requirement.name in name.split(',') }
      CapabilityCheck(requirement.label, available)
    }
    val report = FFmpegReport.Loaded(
      version = version,
      libraries = libraries,
      checks = checks,
      components = components.map { (component, names) -> ComponentList(component.label, names) },
    )
    Log.i(TAG, "FFmpeg $version loaded; libraries=${libraries.joinToString { "${it.name} ${it.version}" }}")
    Log.i(TAG, "checks passed ${checks.count { it.passed }}/${checks.size}; failed=${checks.filterNot { it.passed }.map { it.label }}")
    report
  } catch (error: LinkageError) {
    // 找不到 .so、依赖库缺失、JNI 函数名对不上，都会落到这里
    Log.e(TAG, "FFmpeg self check failed", error)
    FFmpegReport.Failed(error.toString())
  }
}

/** FFmpeg 的库版本号编码为 major shl 16 or minor shl 8 or micro。 */
private fun formatVersion(value: Int): String =
  "${value ushr 16}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
