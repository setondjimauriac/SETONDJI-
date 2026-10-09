package com.example.voicemsg

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {

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
        // Nearby Connections exige la localisation sur toutes les versions (erreur 8034)
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
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
            )
            else -> {}
        }
        return perms
    }

    private var promptShowing = false
    private var skipRelock = false

    /** Demande l'empreinte ou le code du téléphone. */
    private fun authenticate(title: String, onSuccess: () -> Unit) {
        if (promptShowing) return
        promptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Empreinte ou code du téléphone")
            .setAllowedAuthenticators(AppLock.AUTH)
            .build()
        prompt.authenticate(info)
    }

    private fun toggleLock(want: Boolean) {
        if (want && !AppLock.available(this)) {
            VoiceHub.status = "Configurez d'abord un code ou une empreinte dans les réglages du téléphone"
            return
        }
        authenticate(if (want) "Activer le verrouillage" else "Désactiver le verrouillage") {
            AppLock.save(this, want)
        }
    }

    override fun onStart() {
        super.onStart()
        if (AppLock.enabled && AppLock.locked) {
            authenticate("SETONDJI est verrouillé") { AppLock.locked = false }
        }
    }

    override fun onResume() {
        super.onResume()
        skipRelock = false
    }

    override fun onStop() {
        super.onStop()
        // Quand l'appli passe en arrière-plan, elle se reverrouille
        // (sauf pendant le choix d'une photo ou une demande de permission)
        if (AppLock.enabled && !skipRelock) AppLock.locked = true
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
            skipRelock = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceHub.init(this)
        AppLock.load(this)
        // Sécurité : bloque les captures d'écran et masque l'aperçu dans les applis récentes
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        window.statusBarColor = 0xFF075E54.toInt()
        setContent {
            MaterialTheme(colorScheme = WaColors) {
                if (AppLock.enabled && AppLock.locked) {
                    LockScreen(onUnlock = { authenticate("SETONDJI est verrouillé") { AppLock.locked = false } })
                } else {
                    ChatScreen(
                        onAdvertise = { withPermissions { VoiceHub.advertise() } },
                        onDiscover = { withPermissions { VoiceHub.discover() } },
                        onPickPhoto = {
                            skipRelock = true
                            photoPicker.launch("image/*")
                        },
                        lockEnabled = AppLock.enabled,
                        onToggleLock = { toggleLock(it) },
                    )
                }
            }
        }
    }
}

private val WaGreenDark = Color(0xFF075E54)
private val WaGreen = Color(0xFF00A884)
private val WaBg = Color(0xFFECE5DD)
private val WaMine = Color(0xFFD9FDD3)
private val WaTick = Color(0xFF53BDEB)
private val WaGrey = Color(0xFF667781)
private val WaColors = lightColorScheme(primary = WaGreen, onPrimary = Color.White)

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun hour(t: Long): String = timeFmt.format(Date(t))
private val waveHeights = listOf(8, 14, 10, 18, 12, 20, 9, 16, 22, 11, 15, 8, 19, 13, 10, 17, 9, 14)

@Composable
fun ChatScreen(
    onAdvertise: () -> Unit,
    onDiscover: () -> Unit,
    onPickPhoto: () -> Unit,
    lockEnabled: Boolean,
    onToggleLock: (Boolean) -> Unit,
) {
    if (VoiceHub.peerName != null) ChatView(onPickPhoto)
    else HomeView(onAdvertise, onDiscover, lockEnabled, onToggleLock)

    VoiceHub.pairing?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Vérification de sécurité") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Connexion avec ${p.name}", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Text(p.code, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = WaGreenDark)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Vérifiez que le MÊME code s'affiche sur l'autre téléphone. S'il est différent, refusez.",
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        color = WaGrey,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { VoiceHub.confirmPairing() }) { Text("Le code est identique") } },
            dismissButton = { TextButton(onClick = { VoiceHub.refusePairing() }) { Text("Refuser") } },
        )
    }
}

@Composable
private fun Avatar(symbol: String) {
    Box(
        modifier = Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFB0BEC5)),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, fontSize = 20.sp) }
}

@Composable
private fun TopBar(title: String, subtitle: String?, onBack: (() -> Unit)? = null, avatar: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().background(WaGreenDark).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Text(
                "←",
                color = Color.White,
                fontSize = 24.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        } else {
            Spacer(Modifier.width(8.dp))
        }
        if (avatar != null) {
            Avatar(avatar)
            Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = Color(0xFFD0E8E4), fontSize = 12.sp)
        }
    }
}

// ───────────── Écran d'accueil (comme la liste de discussions) ─────────────

@Composable
private fun HomeView(
    onAdvertise: () -> Unit,
    onDiscover: () -> Unit,
    lockEnabled: Boolean,
    onToggleLock: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        TopBar("SETONDJI", VoiceHub.status)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleLock(!lockEnabled) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔒", fontSize = 22.sp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Verrouiller l'application", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("Empreinte ou code du téléphone", fontSize = 12.sp, color = WaGrey)
            }
            Switch(checked = lockEnabled, onCheckedChange = { onToggleLock(it) })
        }
        HorizontalDivider(color = Color(0xFFEEEEEE))

        if (!VoiceHub.active) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("💬", fontSize = 64.sp)
                Spacer(Modifier.height(12.dp))
                Text("Discutez sans internet", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sur un téléphone : « Attendre ». Sur l'autre : « Rechercher ».",
                    color = WaGrey,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = onAdvertise,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("Attendre") }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onDiscover,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("Rechercher") }
            }
        } else {
            if (VoiceHub.found.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = WaGreen)
                    Spacer(Modifier.height(16.dp))
                    Text(VoiceHub.status, color = WaGrey, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(VoiceHub.found.entries.toList(), key = { it.key }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { VoiceHub.connectTo(entry.key) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar("📱")
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(entry.value, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                Text("Touchez pour discuter", fontSize = 13.sp, color = WaGrey)
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 72.dp), color = Color(0xFFEEEEEE))
                    }
                }
            }
            TextButton(
                onClick = { VoiceHub.stopAll() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Arrêter et se déconnecter") }
        }
    }
}

// ───────────── Écran de discussion ─────────────

@Composable
private fun ChatView(onPickPhoto: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var confirmQuit by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(VoiceHub.messages.size) {
        if (VoiceHub.messages.isNotEmpty()) listState.animateScrollToItem(VoiceHub.messages.size - 1)
    }
    BackHandler { confirmQuit = true }

    Column(modifier = Modifier.fillMaxSize().background(WaBg)) {
        TopBar(VoiceHub.peerName ?: "", "en ligne", onBack = { confirmQuit = true }, avatar = "👤")

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(VoiceHub.messages, key = { it.id }) { msg ->
                MessageBubble(msg, playing = VoiceHub.playingId == msg.id)
            }
        }

        InputBar(
            draft = draft,
            onDraftChange = { draft = it },
            onSend = {
                VoiceHub.sendText(draft)
                draft = ""
            },
            onPickPhoto = onPickPhoto,
        )
    }

    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text("Se déconnecter ?") },
            text = { Text("La connexion avec ce téléphone sera fermée.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmQuit = false
                    VoiceHub.stopAll()
                }) { Text("Quitter") }
            },
            dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    val recording = VoiceHub.isRecording
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                Text(
                    "● Enregistrement… relâchez pour envoyer",
                    color = Color(0xFFD32F2F),
                    modifier = Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 16.dp),
                )
            } else {
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Text(
                    "📷",
                    fontSize = 22.sp,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onPickPhoto).padding(10.dp),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        if (draft.isNotBlank()) {
            Box(
                modifier = Modifier.size(50.dp).clip(CircleShape).background(WaGreen).clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Text("➤", color = Color.White, fontSize = 22.sp) }
        } else {
            // Maintenir pour parler, relâcher pour envoyer
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(if (recording) Color(0xFFD32F2F) else WaGreen)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            VoiceHub.startRecording()
                            tryAwaitRelease()
                            VoiceHub.stopRecording()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Text("🎤", fontSize = 22.sp) }
        }
    }
}

// ───────────── Bulles ─────────────

@Composable
fun MessageBubble(msg: ChatMessage, playing: Boolean) {
    val shape = RoundedCornerShape(
        topStart = if (msg.mine) 12.dp else 2.dp,
        topEnd = if (msg.mine) 2.dp else 12.dp,
        bottomStart = 12.dp,
        bottomEnd = 12.dp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(if (msg.mine) WaMine else Color.White)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            when (msg.kind) {
                Kind.TEXT -> Text(msg.text ?: "", fontSize = 16.sp)
                Kind.VOICE -> VoiceRow(msg, playing)
                Kind.PHOTO -> PhotoView(msg)
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(hour(msg.time), fontSize = 11.sp, color = WaGrey)
                if (msg.mine) {
                    Spacer(Modifier.width(3.dp))
                    Text("✓✓", fontSize = 11.sp, color = WaTick)
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(msg: ChatMessage, playing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(WaGreen).clickable { VoiceHub.play(msg) },
            contentAlignment = Alignment.Center,
        ) { Text(if (playing) "⏸" else "▶", color = Color.White, fontSize = 16.sp) }
        Spacer(Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            waveHeights.forEach { h ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(h.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (playing) WaGreen else Color(0xFF9E9E9E)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("🎤", fontSize = 14.sp)
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
                .width(if (expanded) 280.dp else 200.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded },
        )
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(WaGreenDark).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔒", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text("SETONDJI est verrouillé", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Utilisez votre empreinte ou le code de votre téléphone pour continuer.",
            color = Color(0xFFD0E8E4),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onUnlock,
            colors = ButtonDefaults.buttonColors(containerColor = WaGreen),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Déverrouiller") }
    }
}
