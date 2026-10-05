package com.example.voicemsg

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File

enum class Kind { VOICE, PHOTO, TEXT }

data class ChatMessage(
    val kind: Kind,
    val mine: Boolean,
    val file: File? = null,
    val text: String? = null,
    val id: Long = System.nanoTime(),
)

/**
 * Objet unique qui garde la connexion et les messages tant que le processus vit.
 * Le service au premier plan (VoiceService) empêche Android de le tuer
 * quand l'application n'est plus affichée.
 */
object VoiceHub : NearbyVoiceManager.Listener {

    private var ready = false
    private lateinit var app: Context
    private lateinit var nearby: NearbyVoiceManager
    private lateinit var recorder: VoiceRecorder
    private val player = VoicePlayer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordStart = 0L

    var status by mutableStateOf("Choisissez un rôle pour commencer")
    var peerName by mutableStateOf<String?>(null)
    var active by mutableStateOf(false)
    var isRecording by mutableStateOf(false)
    var playingId by mutableStateOf<Long?>(null)
    val messages = mutableStateListOf<ChatMessage>()

    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        nearby = NearbyVoiceManager(app, this)
        recorder = VoiceRecorder(app)
        ready = true
    }

    // ── Rôles ──
    fun advertise() {
        startService()
        active = true
        nearby.startAdvertising("Tel-${Build.MODEL}")
    }

    fun discover() {
        startService()
        active = true
        nearby.startDiscovery()
    }

    fun stopAll() {
        nearby.disconnect()
        peerName = null
        active = false
        isRecording = false
        status = "Déconnecté"
        app.stopService(Intent(app, VoiceService::class.java))
    }

    private fun startService() {
        ContextCompat.startForegroundService(app, Intent(app, VoiceService::class.java))
    }

    // ── Texte ──
    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || peerName == null) return
        nearby.sendText(clean)
        messages.add(ChatMessage(Kind.TEXT, mine = true, text = clean))
    }

    // ── Photo ──
    fun sendPhoto(uri: Uri) {
        if (peerName == null) return
        status = "Préparation de la photo…"
        Thread {
            val file = ImageUtil.prepare(app, uri)
            mainHandler.post {
                if (file == null) {
                    status = "Photo illisible"
                } else {
                    nearby.sendFile(file)
                    messages.add(ChatMessage(Kind.PHOTO, mine = true, file = file))
                    status = "Photo envoyée"
                }
            }
        }.start()
    }

    // ── Vocal ──
    fun startRecording() {
        if (peerName == null || isRecording) return
        try {
            recorder.start()
            recordStart = System.currentTimeMillis()
            isRecording = true
        } catch (e: Exception) {
            status = "Micro indisponible : ${e.message}"
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        val file = recorder.stop() ?: return
        if (System.currentTimeMillis() - recordStart < 500) {
            file.delete()
            return
        }
        nearby.sendFile(file)
        messages.add(ChatMessage(Kind.VOICE, mine = true, file = file))
    }

    fun play(msg: ChatMessage) {
        val file = msg.file ?: return
        playingId = msg.id
        try {
            player.play(file) { playingId = null }
        } catch (e: Exception) {
            playingId = null
            status = "Lecture impossible : ${e.message}"
        }
    }

    // ── Callbacks Nearby ──
    override fun onStatus(message: String) {
        status = message
    }

    override fun onConnected(endpointName: String) {
        peerName = endpointName
        status = "Connecté à $endpointName"
    }

    override fun onDisconnected() {
        peerName = null
        status = "Connexion perdue. Relancez « Attendre » / « Rechercher »."
        active = false
    }

    override fun onTextReceived(text: String) {
        messages.add(ChatMessage(Kind.TEXT, mine = false, text = text))
        Notifier.newMessage(app, "Nouveau message", text)
    }

    override fun onFileReceived(file: File, isPhoto: Boolean) {
        if (isPhoto) {
            messages.add(ChatMessage(Kind.PHOTO, mine = false, file = file))
            Notifier.newMessage(app, "Nouvelle photo")
        } else {
            messages.add(ChatMessage(Kind.VOICE, mine = false, file = file))
            Notifier.newMessage(app, "Nouveau message vocal")
        }
    }
}
