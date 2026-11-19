package com.example.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.example.music.ui.check.FFmpegCheckScreen
import com.example.music.ui.player.SoftwarePlayerScreen
import com.example.music.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MusicTheme {
        MainScreen()
      }
    }
  }
}

private enum class Page(val title: String) {
  PLAYER("软解播放"),
  CHECK("FFmpeg 自检"),
}

@Composable
private fun MainScreen() {
  var page by rememberSaveable { mutableStateOf(Page.PLAYER) }
  Scaffold(
    modifier = Modifier.fillMaxSize(),
    topBar = {
      PrimaryTabRow(selectedTabIndex = page.ordinal, modifier = Modifier.statusBarsPadding()) {
        Page.entries.forEach { item ->
          Tab(selected = item == page, onClick = { page = item }, text = { Text(item.title) })
        }
      }
    },
  ) { innerPadding ->
    when (page) {
      Page.PLAYER -> SoftwarePlayerScreen(contentPadding = innerPadding)
      Page.CHECK -> FFmpegCheckScreen(contentPadding = innerPadding)
    }
  }
}
