package com.example.voicemsg

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (criticalPermissions().all { granted(it) }) pendingAction?.invoke()
            else VoiceHub.status = "Permissions refusées : l'application ne peut pas fonctionner"
            pendingAction = null
        }

    // Sélecteur de photo : aucune permission nécessaire
    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) VoiceHub.sendPhoto(uri)
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun criticalPermissions(): List<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        when {
            Build.VERSION.SDK_INT >= 33 -> perms += listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
            Build.VERSION.SDK_INT >= 31 -> perms += listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            else -> perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return perms
    }

    private fun withPermissions(action: () -> Unit) {
        val wanted = criticalPermissions() +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS)
            else emptyList()
        val missing = wanted.filter { !granted(it) }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceHub.init(this)
        setContent {
            MaterialTheme {
                ChatScreen(
                    onAdvertise = { withPermissions { VoiceHub.advertise() } },
                    onDiscover = { withPermissions { VoiceHub.discover() } },
                    onPickPhoto = { photoPicker.launch("image/*") },
                )
            }
        }
    }
}

@Composable
fun ChatScreen(onAdvertise: () -> Unit, onDiscover: () -> Unit, onPickPhoto: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(VoiceHub.messages.size) {
        if (VoiceHub.messages.isNotEmpty()) listState.animateScrollToItem(VoiceHub.messages.size - 1)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                VoiceHub.status,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            if (!VoiceHub.active && VoiceHub.peerName == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onAdvertise) { Text("Attendre") }
                    OutlinedButton(onClick = onDiscover) { Text("Rechercher") }
                }
                Text(
                    "Sur un téléphone : « Attendre ». Sur l'autre : « Rechercher ».",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                TextButton(onClick = { VoiceHub.stopAll() }) { Text("Arrêter et se déconnecter") }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(VoiceHub.messages, key = { it.id }) { msg ->
                    MessageBubble(msg, playing = VoiceHub.playingId == msg.id)
                }
            }

            if (VoiceHub.peerName != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextButton(onClick = onPickPhoto) { Text("📷") }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message…") },
                        maxLines = 3,
                    )
                    Button(
                        onClick = {
                            VoiceHub.sendText(draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank(),
                    ) { Text("Envoyer") }
                }
                Spacer(Modifier.height(10.dp))
                HoldToTalkButton(
                    recording = VoiceHub.isRecording,
                    onStart = { VoiceHub.startRecording() },
                    onStop = { VoiceHub.stopRecording() },
                )
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage, playing: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (msg.mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            when (msg.kind) {
                Kind.TEXT -> Text(msg.text ?: "")
                Kind.VOICE -> TextButton(onClick = { VoiceHub.play(msg) }) {
                    Text(if (playing) "⏸ Lecture…" else "▶ Écouter le vocal")
                }
                Kind.PHOTO -> PhotoView(msg)
            }
            Text(
                if (msg.mine) "Envoyé" else "Reçu",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun PhotoView(msg: ChatMessage) {
    val file = msg.file ?: return
    val bitmap = remember(file) { ImageUtil.decodeForDisplay(file)?.asImageBitmap() }
    var expanded by remember { mutableStateOf(false) }

    if (bitmap == null) {
        Text("Photo illisible")
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(if (expanded) 260.dp else 160.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded },
        )
    }
}

@Composable
fun HoldToTalkButton(recording: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (recording) 120.dp else 100.dp)
            .clip(CircleShape)
            .background(if (recording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onStart()
                    tryAwaitRelease()
                    onStop()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (recording) "Relâcher\npour envoyer" else "Maintenir\npour parler",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}
