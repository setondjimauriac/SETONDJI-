package com.example.voicemsg

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File

// ───────────────────────── Enregistrement / lecture ─────────────────────────

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    @Suppress("DEPRECATION")
    fun start(): File {
        val file = File(context.cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
        recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(32_000)
            setAudioSamplingRate(16_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        output = file
        return file
    }

    fun stop(): File? {
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
            output?.delete()
            output = null
        }
        recorder?.release()
        recorder = null
        return output
    }
}

class VoicePlayer {
    private var player: MediaPlayer? = null

    fun play(file: File, onDone: () -> Unit = {}) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { onDone() }
            prepare()
            start()
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}

// ───────────────────────────── Nearby Connections ───────────────────────────

class NearbyVoiceManager(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(message: String)
        fun onConnected(endpointName: String)
        fun onDisconnected()
        fun onTextReceived(text: String)
        fun onFileReceived(file: File, isPhoto: Boolean)
        fun onEndpointFound(id: String, name: String) {}
        fun onEndpointLost(id: String) {}
        fun onPairingRequest(id: String, name: String, code: String) {}
        fun onPairingEnded() {}
    }

    private val client = Nearby.getConnectionsClient(context)
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val serviceId = "com.example.voicemsg"

    private var endpointId: String? = null
    private var peerName: String = ""
    private val incoming = mutableMapOf<Long, Payload>()

    fun startAdvertising(myName: String) {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(myName, serviceId, connectionCallback, options)
            .addOnSuccessListener { listener.onStatus("En attente de l'autre téléphone…") }
            .addOnFailureListener { listener.onStatus("Erreur : ${it.message}") }
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnSuccessListener { listener.onStatus("Recherche de l'autre téléphone…") }
            .addOnFailureListener { listener.onStatus("Erreur : ${it.message}") }
    }

    /** Texte : envoyé comme petit paquet d'octets (limite Nearby ≈ 32 Ko). */
    fun sendText(text: String) {
        val id = endpointId ?: return listener.onStatus("Pas de connexion")
        val bytes = ("TXT|" + text.take(5000)).toByteArray(Charsets.UTF_8)
        client.sendPayload(id, Payload.fromBytes(bytes))
            .addOnFailureListener { listener.onStatus("Échec de l'envoi : ${it.message}") }
    }

    /** Vocal (.m4a) ou photo (.jpg) : envoyé comme fichier. */
    fun sendFile(file: File) {
        val id = endpointId ?: return listener.onStatus("Pas de connexion")
        try {
            client.sendPayload(id, Payload.fromFile(file))
                .addOnFailureListener { listener.onStatus("Échec de l'envoi : ${it.message}") }
        } catch (e: Exception) {
            listener.onStatus("Échec de l'envoi : ${e.message}")
        }
    }

    fun connectTo(id: String, name: String) {
        peerName = name
        listener.onStatus("Connexion à $name…")
        client.requestConnection("Moi", id, connectionCallback)
            .addOnFailureListener { listener.onStatus("Erreur : ${it.message}") }
    }

    fun acceptPairing(id: String) {
        client.acceptConnection(id, payloadCallback)
            .addOnFailureListener { listener.onStatus("Erreur : ${it.message}") }
    }

    fun rejectPairing(id: String) {
        client.rejectConnection(id)
        listener.onPairingEnded()
    }

    fun disconnect() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        endpointId = null
        incoming.clear()
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            // On ne se connecte plus automatiquement : on ajoute à la liste
            listener.onEndpointFound(id, info.endpointName)
        }

        override fun onEndpointLost(id: String) {
            listener.onEndpointLost(id)
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            // Sécurité : on n'accepte PAS automatiquement.
            // Les deux utilisateurs comparent le même code avant de confirmer.
            peerName = info.endpointName
            listener.onPairingRequest(id, info.endpointName, info.authenticationDigits)
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            listener.onPairingEnded()
            if (result.status.isSuccess) {
                endpointId = id
                client.stopAdvertising()
                client.stopDiscovery()
                listener.onConnected(peerName)
            } else {
                listener.onStatus("Connexion échouée, réessayez")
            }
        }

        override fun onDisconnected(id: String) {
            endpointId = null
            listener.onDisconnected()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.FILE -> incoming[payload.id] = payload
                Payload.Type.BYTES -> {
                    val message = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
                    if (message.startsWith("TXT|")) listener.onTextReceived(message.removePrefix("TXT|"))
                }
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            val payload = incoming[update.payloadId] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    incoming.remove(update.payloadId)
                    saveReceived(payload)?.let { (file, isPhoto) -> listener.onFileReceived(file, isPhoto) }
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    incoming.remove(update.payloadId)
                    listener.onStatus("Réception interrompue")
                }
                else -> {}
            }
        }
    }

    /** Copie le fichier reçu dans le stockage de l'app. Une photo JPEG commence par FF D8. */
    private fun saveReceived(payload: Payload): Pair<File, Boolean>? {
        val uri: Uri = payload.asFile()?.asUri() ?: return null
        val temp = File(context.filesDir, "recu_${System.currentTimeMillis()}.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            val header = ByteArray(2)
            temp.inputStream().use { it.read(header) }
            val isPhoto = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
            val dest = File(temp.parentFile, temp.nameWithoutExtension + if (isPhoto) ".jpg" else ".m4a")
            temp.renameTo(dest)
            Pair(dest, isPhoto)
        } catch (e: Exception) {
            listener.onStatus("Erreur de sauvegarde : ${e.message}")
            null
        }
    }
}
