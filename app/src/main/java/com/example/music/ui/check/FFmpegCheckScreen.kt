package com.example.music.ui.check

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.music.ffmpeg.CapabilityCheck
import com.example.music.ffmpeg.ComponentList
import com.example.music.ffmpeg.FFmpegReport
import com.example.music.ffmpeg.LibraryVersion
import com.example.music.ffmpeg.runFFmpegSelfCheck
import com.example.music.ui.common.KeyValueRow
import com.example.music.ui.common.MonoText
import com.example.music.ui.common.SectionCard
import com.example.music.ui.theme.MusicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** FFmpeg 自检页：进入时通过 JNI 调用 FFmpeg，展示库是否加载成功、API 是否可用。 */
@Composable
fun FFmpegCheckScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
  val report by produceState<FFmpegReport?>(initialValue = null) {
    value = withContext(Dispatchers.Default) { runFFmpegSelfCheck() }
  }
  when (val current = report) {
    null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    else -> ReportList(current, contentPadding, modifier)
  }
}

@Composable
private fun ReportList(
  report: FFmpegReport,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = 16.dp,
      end = 16.dp,
      top = contentPadding.calculateTopPadding() + 16.dp,
      bottom = contentPadding.calculateBottomPadding() + 16.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { StatusCard(report) }
    when (report) {
      is FFmpegReport.Failed -> item {
        SectionCard("错误信息") { MonoText(report.error) }
      }
      is FFmpegReport.Loaded -> {
        item {
          SectionCard("版本") {
            KeyValueRow("FFmpeg", report.version)
            report.libraries.forEach { KeyValueRow(it.name, it.version) }
          }
        }
        item {
          val passed = report.checks.count { it.passed }
          SectionCard("关键能力（$passed/${report.checks.size}）") {
            report.checks.forEach { CheckRow(it) }
          }
        }
        items(report.components) { component ->
          SectionCard("${component.label}（${component.names.size}）") {
            MonoText(component.names.joinToString("  ·  "))
          }
        }
      }
    }
  }
}

@Composable
private fun StatusCard(report: FFmpegReport) {
  val healthy = report is FFmpegReport.Loaded && report.allPassed
  val message = when (report) {
    is FFmpegReport.Failed -> "动态库加载或调用失败"
    is FFmpegReport.Loaded ->
      if (report.allPassed) "动态库已加载，API 调用正常"
      else "有 ${report.checks.count { !it.passed }} 项关键能力缺失"
  }
  val colors = MaterialTheme.colorScheme
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (healthy) colors.primaryContainer else colors.errorContainer,
      contentColor = if (healthy) colors.onPrimaryContainer else colors.onErrorContainer,
    ),
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("FFmpeg 自检", style = MaterialTheme.typography.titleMedium)
      Text(
        text = if (healthy) "✓ $message" else "✗ $message",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun CheckRow(check: CapabilityCheck) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(check.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Text(
      text = if (check.passed) "✓ 可用" else "✗ 缺失",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      color = if (check.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun ReportListPreview() {
  MusicTheme {
    ReportList(
      report = FFmpegReport.Loaded(
        version = "n8.1.3",
        libraries = listOf(LibraryVersion("libavutil", "60.8.100"), LibraryVersion("libavcodec", "62.11.100")),
        checks = listOf(CapabilityCheck("H.264 硬件编码器", true), CapabilityCheck("overlay 滤镜", false)),
        components = listOf(ComponentList("编码器", listOf("aac", "h264_mediacodec"))),
      ),
      contentPadding = PaddingValues(),
    )
  }
}
