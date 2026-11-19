package com.example.music.ui.player

import android.content.Context
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.music.ffmpeg.FFmpegVideoDecoder
import com.example.music.player.PlaybackListener
import com.example.music.player.PlaybackProgress
import com.example.music.player.YuvColorMatrix
import com.example.music.player.YuvGlRenderer
import com.example.music.player.playVideo
import com.example.music.ui.common.KeyValueRow
import com.example.music.ui.common.SectionCard
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 竖屏视频的画面最高这么高，免得占满整屏。 */
private val MAX_PORTRAIT_HEIGHT = 420.dp

private sealed interface VideoSource {
  val label: String

  /** 打包在 assets 里的示例视频。 */
  data class Sample(val assetPath: String, override val label: String) : VideoSource

  /** 用户从相册选的视频。 */
  data class Picked(val uri: Uri) : VideoSource {
    override val label: String get() = "相册视频"
  }
}

private val SAMPLES = listOf(
  VideoSource.Sample("samples/landscape_720p.mp4", "横屏示例"),
  VideoSource.Sample("samples/portrait_rotate90.mp4", "竖屏示例（带 90° 旋转）"),
)

/** 页面上显示的播放状态；播放回调在后台线程直接写这些 State，Compose 会在主线程刷新。 */
private class PlaybackState : PlaybackListener {
  var status by mutableStateOf("")
  var stream by mutableStateOf<FFmpegVideoDecoder.StreamInfo?>(null)
  var colorMatrix by mutableStateOf<YuvColorMatrix?>(null)
  var decodedFormat by mutableStateOf<FFmpegVideoDecoder.DecodedFormat?>(null)
  var progress by mutableStateOf<PlaybackProgress?>(null)

  fun reset(status: String) {
    this.status = status
    stream = null
    colorMatrix = null
    decodedFormat = null
    progress = null
  }

  override fun onStreamInfo(info: FFmpegVideoDecoder.StreamInfo, colorMatrix: YuvColorMatrix) {
    stream = info
    this.colorMatrix = colorMatrix
    status = "播放中"
  }

  override fun onFirstFrame(format: FFmpegVideoDecoder.DecodedFormat) {
    decodedFormat = format
  }

  override fun onProgress(progress: PlaybackProgress) {
    this.progress = progress
  }
}

/**
 * 软解播放页：C 层（FFmpeg）只负责解封装和解码出 YUV；
 * Kotlin 层负责按时间戳控制节奏、丢帧，并用 OpenGL ES 着色器完成 YUV→RGB 和旋转。
 */
@Composable
fun SoftwarePlayerScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val state = remember { PlaybackState() }
  val renderer = remember { YuvGlRenderer() }
  // 换视频时，上一次播放要完全退出、关掉解码器后，下一次才开始
  val playbackMutex = remember { Mutex() }
  var source by remember { mutableStateOf<VideoSource>(SAMPLES.first()) }
  var playRequest by remember { mutableIntStateOf(0) }  // 每点一次按钮加一，用来触发重新播放
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) {
      source = VideoSource.Picked(uri)
      playRequest++
    }
  }

  // 换了视频或点了重播时开始播放；离开页面或再次切换时，上一次播放会被取消
  LaunchedEffect(source, playRequest) {
    playbackMutex.withLock {
      state.reset("正在打开：${source.label}")
      runPlayback(context, source, renderer, state)
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(
        start = 16.dp,
        end = 16.dp,
        top = contentPadding.calculateTopPadding() + 16.dp,
        bottom = contentPadding.calculateBottomPadding() + 16.dp,
      ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SAMPLES.forEach { sample ->
        FilledTonalButton(onClick = { source = sample; playRequest++ }) { Text(sample.label) }
      }
      FilledTonalButton(onClick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
      }) { Text("从相册选择") }
      OutlinedButton(onClick = { playRequest++ }) { Text("重播") }
    }

    val stream = state.stream
    VideoView(
      renderer = renderer,
      aspectRatio = stream?.let { it.displayWidth.toFloat() / it.displayHeight } ?: (16f / 9f),
    )

    SectionCard("当前") {
      KeyValueRow("视频", source.label)
      KeyValueRow("状态", state.status)
    }
    stream?.let { StreamCard(it) }
    state.decodedFormat?.let { DecodeCard(it, stream) }
    state.colorMatrix?.let { RenderCard(it, stream) }
    state.progress?.let { ProgressCard(it, stream) }
    Text(
      "只解码和显示画面，不播放声音；播放节奏按系统时钟控制。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 黑底容器里放一个 GLSurfaceView，由 [YuvGlRenderer] 负责画；跟随页面的 onPause / onResume。 */
@Composable
private fun VideoView(renderer: YuvGlRenderer, aspectRatio: Float) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val size = if (aspectRatio >= 1f) {
    Modifier.fillMaxWidth().aspectRatio(aspectRatio)
  } else {
    Modifier.height(MAX_PORTRAIT_HEIGHT).aspectRatio(aspectRatio)
  }
  Box(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
    AndroidView(
      factory = { viewContext ->
        GLSurfaceView(viewContext).apply {
          setEGLContextClientVersion(2)
          preserveEGLContextOnPause = true
          setRenderer(renderer)
          renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY  // 有新帧时才画
          renderer.attach(this)
          val observer = LifecycleEventObserver { _, event ->
            when (event) {
              Lifecycle.Event.ON_RESUME -> onResume()
              Lifecycle.Event.ON_PAUSE -> onPause()
              else -> Unit
            }
          }
          lifecycle.addObserver(observer)
          tag = observer
        }
      },
      onRelease = { view ->
        (view.tag as? LifecycleEventObserver)?.let(lifecycle::removeObserver)
        renderer.detach(view)
      },
      modifier = size,
    )
  }
}

@Composable
private fun StreamCard(stream: FFmpegVideoDecoder.StreamInfo) {
  SectionCard("① 解封装（C / FFmpeg）") {
    KeyValueRow("解码器", "${stream.codec}（软件解码）")
    KeyValueRow("编码尺寸", "${stream.width}×${stream.height}")
    KeyValueRow(
      "旋转",
      if (stream.rotation == 0) "无"
      else "顺时针 ${stream.rotation}°，显示为 ${stream.displayWidth}×${stream.displayHeight}",
    )
    KeyValueRow("帧率", if (stream.fps > 0) "%.2f fps".format(Locale.US, stream.fps) else "未知")
    KeyValueRow("时长", if (stream.durationUs >= 0) seconds(stream.durationUs) else "未知")
    KeyValueRow(
      "色彩标注",
      "${stream.colorSpaceName} / " + when (stream.colorRange) {
        1 -> "tv"
        2 -> "pc"
        else -> "未标注"
      },
    )
  }
}

@Composable
private fun DecodeCard(format: FFmpegVideoDecoder.DecodedFormat, stream: FFmpegVideoDecoder.StreamInfo?) {
  SectionCard("② 解码（C / FFmpeg）") {
    KeyValueRow("解码器输出", format.pixelFormat)
    KeyValueRow("每行字节数 Y / U / V", "${format.linesizeY} / ${format.linesizeU} / ${format.linesizeV}")
    if (stream != null) {
      val chromaWidth = (stream.width + 1) / 2
      val chromaHeight = (stream.height + 1) / 2
      KeyValueRow("交给 Kotlin", "yuv420p，紧密排列")
      KeyValueRow("平面尺寸 Y / U / V", "${stream.width}×${stream.height} / ${chromaWidth}×${chromaHeight} ×2")
    }
  }
}

@Composable
private fun RenderCard(colorMatrix: YuvColorMatrix, stream: FFmpegVideoDecoder.StreamInfo?) {
  SectionCard("③ 渲染（Kotlin / OpenGL ES）") {
    KeyValueRow("YUV→RGB", "GPU 片元着色器")
    KeyValueRow("换算系数", colorMatrix.description)
    KeyValueRow(
      "旋转",
      if ((stream?.rotation ?: 0) == 0) "无" else "纹理坐标顺时针 ${stream?.rotation}°",
    )
  }
}

@Composable
private fun ProgressCard(progress: PlaybackProgress, stream: FFmpegVideoDecoder.StreamInfo?) {
  SectionCard("④ 播放统计") {
    val total = stream?.durationUs?.takeIf { it >= 0 }?.let { " / ${seconds(it)}" } ?: ""
    KeyValueRow("进度", seconds(progress.positionUs) + total)
    KeyValueRow(
      "解码 / 显示 / 丢弃",
      "${progress.framesDecoded} / ${progress.framesDrawn} / ${progress.framesDropped}",
    )
    KeyValueRow("平均解码（含拷出 YUV）", milliseconds(progress.avgDecodeUs))
    KeyValueRow("平均上传纹理并绘制", milliseconds(progress.avgDrawUs))
  }
}

private fun seconds(us: Long): String = "%.1f 秒".format(Locale.US, us / 1_000_000.0)

private fun milliseconds(us: Int): String = "%.2f 毫秒/帧".format(Locale.US, us / 1000.0)

/** 打开视频并播放，直到播完、出错，或协程被取消。 */
private suspend fun runPlayback(
  context: Context,
  source: VideoSource,
  renderer: YuvGlRenderer,
  state: PlaybackState,
) {
  try {
    withContext(Dispatchers.IO) { openVideo(context, source) }.use { pfd ->
      playVideo(pfd.fd, renderer, state)
    }
    state.status = "播放结束"
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    state.status = "播放失败：${e.message}"
  }
}

/** 拿到视频的文件描述符：示例视频先从 assets 拷到缓存目录再打开；相册视频直接向系统要。 */
private fun openVideo(context: Context, source: VideoSource): ParcelFileDescriptor = when (source) {
  is VideoSource.Sample -> {
    val file = File(context.cacheDir, source.assetPath)
    file.parentFile?.mkdirs()
    context.assets.open(source.assetPath).use { input -> file.outputStream().use { input.copyTo(it) } }
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }
  is VideoSource.Picked ->
    context.contentResolver.openFileDescriptor(source.uri, "r")
      ?: throw FileNotFoundException(source.uri.toString())
}
