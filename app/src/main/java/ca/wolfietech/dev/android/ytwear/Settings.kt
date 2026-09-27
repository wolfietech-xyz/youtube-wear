package ca.wolfietech.dev.android.ytwear

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** User settings, saved on the watch. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** How long a like or unlike may take before the watch gives up. */
    var likeTimeoutSeconds by savedInt("like_timeout_seconds", default = 8)
        private set

    /** How long finding and buffering a video may take before the watch gives up. */
    var videoTimeoutSeconds by savedInt("video_timeout_seconds", default = 50)
        private set

    fun changeLikeTimeout(by: Int) {
        likeTimeoutSeconds = (likeTimeoutSeconds + by).coerceIn(1, 120)
    }

    fun changeVideoTimeout(by: Int) {
        videoTimeoutSeconds = (videoTimeoutSeconds + by).coerceIn(5, 600)
    }

    private fun savedInt(key: String, default: Int) = object {
        private val state = mutableIntStateOf(prefs.getInt(key, default))

        operator fun getValue(owner: Any?, property: Any?): Int = state.intValue

        operator fun setValue(owner: Any?, property: Any?, value: Int) {
            state.intValue = value
            prefs.edit().putInt(key, value).apply()
        }
    }
}

@Composable
fun SettingsScreen(settings: Settings) {
    ScalingLazyColumn(Modifier.fillMaxWidth()) {
        item { Text("Settings", style = MaterialTheme.typography.titleMedium) }
        item {
            SecondsSetting(
                label = "Like timeout",
                seconds = settings.likeTimeoutSeconds,
                onDown = { settings.changeLikeTimeout(-1) },
                onUp = { settings.changeLikeTimeout(1) },
            )
        }
        item {
            SecondsSetting(
                label = "Video timeout",
                seconds = settings.videoTimeoutSeconds,
                onDown = { settings.changeVideoTimeout(-5) },
                onUp = { settings.changeVideoTimeout(5) },
            )
        }
    }
}

@Composable
private fun SecondsSetting(label: String, seconds: Int, onDown: () -> Unit, onUp: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onDown) { Text("−") }
            Text("$seconds s", modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
            FilledTonalButton(onClick = onUp) { Text("+") }
        }
    }
}
