package com.example.sleepsongs

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

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

private data class OutputDeviceOption(
    val id: Int,
    val label: String
)

private data class RecentMediaItem(
    val uriString: String,
    val displayName: String?
)

@Composable
private fun SleepSongsScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val contentResolver = context.contentResolver

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var repeatCountText by remember { mutableStateOf("3") }
    var fadeOnFinalLoop by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Pick a file and press Play") }
    var selectedDurationMs by remember { mutableStateOf<Long?>(null) }
    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    var showOutputPicker by remember { mutableStateOf(false) }
    var outputOptions by remember { mutableStateOf<List<OutputDeviceOption>>(emptyList()) }
    var recentHistory by remember { mutableStateOf(context.loadRecentMediaItems()) }

    val applySelectedMedia: (Uri, String?) -> Unit = { uri, nameHint ->
        val resolvedName = nameHint ?: contentResolver.getDisplayName(uri)
        selectedUri = uri
        selectedName = resolvedName
        selectedDurationMs = contentResolver.getDurationMs(context, uri)
        status = "Selected: ${resolvedName ?: uri}"
        recentHistory = context.addRecentMediaItem(uri.toString(), resolvedName)
    }

    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            status = "Notification permission denied. Notification controls may be hidden."
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
            applySelectedMedia(uri, null)
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
                } catch (_: Exception) {
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

    if (showOutputPicker) {
        AlertDialog(
            onDismissRequest = { showOutputPicker = false },
            title = { Text("Select audio output") },
            text = {
                Column {
                    outputOptions.forEach { option ->
                        Text(
                            text = option.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mediaController?.sendCustomCommand(
                                        SessionCommand(PlaybackCommands.SET_OUTPUT_DEVICE, EMPTY),
                                        bundleOf(PlaybackCommands.EXTRA_OUTPUT_DEVICE_ID to option.id)
                                    )
                                    status = "Audio output: ${option.label}"
                                    showOutputPicker = false
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOutputPicker = false }) {
                    Text("Close")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sleep Songs",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { pickFileLauncher.launch(arrayOf("audio/*", "video/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Choose file")
            }

            Button(
                onClick = {
                    outputOptions = context.getOutputDevicesForPicker()
                    showOutputPicker = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Audio output")
            }
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
                        selectedRepeatCount == null || selectedRepeatCount <= 0 -> status =
                            "Enter a repeat count greater than 0"

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
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Recent history",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )

        if (recentHistory.isEmpty()) {
            Text(
                text = "No recent items yet",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        } else {
            recentHistory.forEach { item ->
                Text(
                    text = item.displayName ?: item.uriString,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            applySelectedMedia(Uri.parse(item.uriString), item.displayName)
                        }
                        .padding(vertical = 10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(
            onClick = { uriHandler.openUri("https://github.com/jaredly/sleepsongs/releases") }
        ) {
            Text("Check for new releases")
        }
    }
}

private fun Context.getOutputDevicesForPicker(): List<OutputDeviceOption> {
    val audioManager = getSystemService(AudioManager::class.java)
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    val options = mutableListOf(OutputDeviceOption(id = OUTPUT_DEFAULT, label = "System default"))

    devices
        .filter { device ->
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        .distinctBy { it.id }
        .forEach { device ->
            val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
            val fallback = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth device"

                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET -> "Headphones"

                else -> "Audio device"
            }
            options += OutputDeviceOption(id = device.id, label = name ?: fallback)
        }

    return options
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

private const val OUTPUT_DEFAULT = -1
private const val PREFS_NAME = "sleep_songs_prefs"
private const val PREF_RECENT_MEDIA = "recent_media_items"
private const val MAX_RECENT_MEDIA_ITEMS = 5

private fun Context.loadRecentMediaItems(): List<RecentMediaItem> {
    val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_RECENT_MEDIA, null)
        ?: return emptyList()

    return try {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val uri = item.optString("uri")
                if (uri.isBlank()) continue
                add(
                    RecentMediaItem(
                        uriString = uri,
                        displayName = item.optString("name").ifBlank { null }
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun Context.addRecentMediaItem(uriString: String, displayName: String?): List<RecentMediaItem> {
    val updated = buildList {
        add(RecentMediaItem(uriString = uriString, displayName = displayName))
        addAll(loadRecentMediaItems().filterNot { it.uriString == uriString })
    }.take(MAX_RECENT_MEDIA_ITEMS)

    val encoded = JSONArray().apply {
        updated.forEach { item ->
            put(
                JSONObject().apply {
                    put("uri", item.uriString)
                    put("name", item.displayName ?: "")
                }
            )
        }
    }.toString()

    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        .edit()
        .putString(PREF_RECENT_MEDIA, encoded)
        .apply()

    return updated
}
