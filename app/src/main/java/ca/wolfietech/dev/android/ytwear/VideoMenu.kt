package ca.wolfietech.dev.android.ytwear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** What the video menu shows: a heading and a list, or a load in progress, or an error. */
class VideoMenu {
    var heading by mutableStateOf("")
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
}

/** The Videos menu: Search at the top, then the home feed or search results. */
@Composable
fun VideoMenuScreen(
    menu: VideoMenu,
    onSearch: () -> Unit,
    onVoiceSearch: () -> Unit,
    onPlay: (index: Int) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    ScalingLazyColumn(Modifier.fillMaxWidth()) {
        // One search box at the top of every feed; the microphone sits inside it.
        item {
            val voiceLabel = stringResource(R.string.menu_voice_search)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSearch, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.menu_search)) }
                FilledTonalIconButton(onClick = onVoiceSearch, modifier = Modifier.semantics { contentDescription = voiceLabel }) {
                    Glyph(Glyphs.Mic)
                }
            }
        }
        item { Text(menu.heading, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center) }
        when {
            menu.loading -> item { Text(stringResource(R.string.menu_loading), style = MaterialTheme.typography.bodySmall) }
            menu.error != null -> item {
                Text(menu.error!!, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            menu.videos.isEmpty() -> item { Text(stringResource(R.string.menu_no_videos), style = MaterialTheme.typography.bodySmall) }
            else -> itemsIndexed(menu.videos) { index, video ->
                FilledTonalButton(onClick = { onPlay(index) }, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            video.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(video.channel, video.durationSeconds?.let(::duration)).joinToString(" · "),
                            style = MaterialTheme.typography.bodyExtraSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 3723 -> 1:02:03, 65 -> 1:05. */
private fun duration(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
