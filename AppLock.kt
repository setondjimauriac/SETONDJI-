package com.example.voicemsg

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Verrouillage de l'appli : empreinte, visage ou code/schéma du téléphone. */
object AppLock {
    const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    private const val PREFS = "setondji_prefs"
    private const val KEY = "lock_enabled"

    var enabled by mutableStateOf(false)
    var locked by mutableStateOf(false)

    fun load(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        locked = enabled
    }

    fun save(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        enabled = value
        if (!value) locked = false
    }

    /** Vrai si le téléphone a une empreinte, un visage ou un code configuré. */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS
}
