package com.example.sleepsongs

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Bundle.EMPTY
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : AppCompatActivity() {
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

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var repeatCountText by remember { mutableStateOf("3") }
    var fadeOnFinalLoop by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Pick a file and press Play") }
    var selectedDurationMs by remember { mutableStateOf<Long?>(null) }
    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            status = "Notification permission denied. Playback still works while app is open."
        }
    }

    val pickFileLauncher = rememberLauncherForActivityResult(
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
        val token = SessionToken(context, ComponentName(context, SleepSongsPlaybackService::class.java))
        val controllerFuture: ListenableFuture<MediaController> =
            MediaController.Builder(context, token).buildAsync()

        controllerFuture.addListener(
            {
                try {
                    mediaController = controllerFuture.get()
                    status = "Ready"
                } catch (e: Exception) {
                    status = "Could not connect to playback service"
                }
            },
            ContextCompat.getMainExecutor(context)
        )

        onDispose {
            MediaController.releaseFuture(controllerFuture)
            mediaController = null
        }
    }

    DisposableEffect(mediaController) {
        val controller = mediaController ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                status = if (isPlaying) "Playing" else "Paused / stopped"
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    val repeatCount = repeatCountText.toIntOrNull()
    val totalPlayDurationMs = if (selectedDurationMs != null && repeatCount != null && repeatCount > 0) {
        selectedDurationMs!! * repeatCount
    } else {
        null
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { pickFileLauncher.launch(arrayOf("audio/*", "video/*")) }) {
                Text("Choose audio or video")
            }

            RoutePickerButton()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Icon(Icons.Filled.Notifications, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enable media notifications")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val controller = mediaController
                    val uri = selectedUri
                    val selectedRepeatCount = repeatCountText.toIntOrNull()

                    when {
                        controller == null -> status = "Playback service is not ready"
                        uri == null -> status = "Select an audio or video file first"
                        selectedRepeatCount == null || selectedRepeatCount <= 0 -> status = "Enter a repeat count greater than 0"
                        else -> {
                            val command = SessionCommand(PlaybackCommands.START_PLAYBACK, EMPTY)
                            val args = bundleOf(
                                PlaybackCommands.EXTRA_URI to uri.toString(),
                                PlaybackCommands.EXTRA_REPEAT_COUNT to selectedRepeatCount,
                                PlaybackCommands.EXTRA_FADE_ON_FINAL_LOOP to fadeOnFinalLoop,
                                PlaybackCommands.EXTRA_TITLE to (selectedName ?: "Sleep Song")
                            )

                            controller.sendCustomCommand(command, args)
                            status = "Starting playback"
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Play")
            }

            Button(
                onClick = {
                    val controller = mediaController
                    if (controller == null) {
                        status = "Playback service is not ready"
                    } else {
                        controller.sendCustomCommand(
                            SessionCommand(PlaybackCommands.STOP_PLAYBACK, EMPTY),
                            Bundle()
                        )
                        status = "Stopped"
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RoutePickerButton() {
    AndroidView(
        factory = { context ->
            MediaRouteButton(context).apply {
                routeSelector = MediaRouteSelector.Builder()
                    .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                    .build()
                contentDescription = "Audio output device"
            }
        },
        modifier = Modifier
            .height(48.dp)
            .width(48.dp)
    )
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
