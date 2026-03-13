package com.example.sleepsongs

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SleepSongsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SleepSongsScreen()
                }
            }
        }
    }
}

@Composable
private fun SleepSongsScreen() {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val player = remember { LoopingAudioPlayer(context) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var repeatCountText by remember { mutableStateOf("3") }
    var fadeOnFinalLoop by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Pick a file and press Play") }
    var currentLoop by remember { mutableIntStateOf(0) }
    var totalLoops by remember { mutableIntStateOf(0) }
    var selectedDurationMs by remember { mutableStateOf<Long?>(null) }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val readFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, readFlags)
            } catch (_: SecurityException) {
                // Not all providers support persistable permissions.
            }
            selectedUri = uri
            selectedName = contentResolver.getDisplayName(uri)
            selectedDurationMs = contentResolver.getDurationMs(context, uri)
            status = "Selected: ${selectedName ?: uri}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sleep Songs",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { pickAudioLauncher.launch(arrayOf("audio/*", "video/*")) }) {
            Text("Choose audio or video file")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = selectedName ?: "No file selected",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = repeatCountText,
            onValueChange = { input ->
                if (input.all { it.isDigit() }) {
                    repeatCountText = input
                }
            },
            label = { Text("Number of plays") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        val repeatCount = repeatCountText.toIntOrNull()
        val totalPlayDurationMs = if (selectedDurationMs != null && repeatCount != null && repeatCount > 0) {
            selectedDurationMs!! * repeatCount
        } else {
            null
        }

        Text(
            text = when {
                selectedDurationMs == null -> "Song length: unavailable"
                totalPlayDurationMs == null -> "Song length: ${formatDuration(selectedDurationMs!!)}"
                else -> "Total play time: ${formatDuration(totalPlayDurationMs)}"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = fadeOnFinalLoop,
                onCheckedChange = { fadeOnFinalLoop = it }
            )
            Text("Fade volume during final loop")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val selectedRepeatCount = repeatCountText.toIntOrNull()
                    val uri = selectedUri
                    when {
                        uri == null -> status = "Select an audio or video file first"
                        selectedRepeatCount == null || selectedRepeatCount <= 0 -> status = "Enter a repeat count greater than 0"
                        else -> {
                            player.play(
                                uri = uri,
                                repeatCount = selectedRepeatCount,
                                fadeOnFinalLoop = fadeOnFinalLoop,
                                onLoopStart = { loop, total ->
                                    currentLoop = loop
                                    totalLoops = total
                                    status = "Playing loop $loop of $total"
                                },
                                onFinished = {
                                    status = "Done"
                                    currentLoop = 0
                                    totalLoops = 0
                                },
                                onError = { error ->
                                    status = error
                                    currentLoop = 0
                                    totalLoops = 0
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Play")
            }

            Button(
                onClick = {
                    player.stop()
                    status = "Stopped"
                    currentLoop = 0
                    totalLoops = 0
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(text = status, style = MaterialTheme.typography.bodyMedium)

        if (currentLoop > 0 && totalLoops > 0) {
            Text(
                text = "Progress: $currentLoop / $totalLoops",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private class LoopingAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: MediaPlayer? = null
    private var fadeJob: Job? = null

    fun play(
        uri: Uri,
        repeatCount: Int,
        fadeOnFinalLoop: Boolean,
        onLoopStart: (loop: Int, total: Int) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()

        val mediaPlayer = MediaPlayer()
        player = mediaPlayer

        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )

        var currentLoop = 1

        mediaPlayer.setOnPreparedListener { mp ->
            mp.setVolume(1f, 1f)
            onLoopStart(currentLoop, repeatCount)
            mp.start()

            if (fadeOnFinalLoop && repeatCount == 1) {
                startFadeDuringThisLoop(mp)
            }
        }

        mediaPlayer.setOnCompletionListener { mp ->
            if (currentLoop >= repeatCount) {
                onFinished()
                stop()
                return@setOnCompletionListener
            }

            currentLoop += 1
            mp.seekTo(0)
            mp.setVolume(1f, 1f)
            onLoopStart(currentLoop, repeatCount)
            mp.start()

            if (fadeOnFinalLoop && currentLoop == repeatCount) {
                startFadeDuringThisLoop(mp)
            }
        }

        mediaPlayer.setOnErrorListener { _, what, extra ->
            onError("Playback error ($what, $extra)")
            stop()
            true
        }

        try {
            mediaPlayer.setDataSource(appContext, uri)
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            onError("Could not play selected file: ${e.localizedMessage ?: "unknown error"}")
            stop()
        }
    }

    fun stop() {
        fadeJob?.cancel()
        fadeJob = null

        player?.apply {
            setOnPreparedListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            if (isPlaying) {
                stop()
            }
            reset()
            release()
        }
        player = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun startFadeDuringThisLoop(mp: MediaPlayer) {
        fadeJob?.cancel()
        val durationMs = mp.duration.coerceAtLeast(1)
        val steps = 40
        val stepDelayMs = (durationMs / steps).coerceAtLeast(25)

        fadeJob = scope.launch {
            for (step in 0 until steps) {
                val volume = 1f - (step.toFloat() / steps.toFloat())
                mp.setVolume(volume, volume)
                delay(stepDelayMs.toLong())
            }
            mp.setVolume(0f, 0f)
        }
    }
}

private fun ContentResolver.getDisplayName(uri: Uri): String? {
    return query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }
}

private fun ContentResolver.getDurationMs(context: Context, uri: Uri): Long? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
        retriever.release()
        duration
    } catch (_: Exception) {
        null
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
