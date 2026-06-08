package com.zevclip.sender.airplay

import android.content.Context
import java.util.Base64

object AirPlayIdentityStore {
    private const val PREF_NAME = "zevclip_airplay_identity"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PRIVATE_KEY = "private_key"

    fun getOrCreate(context: Context): AirPlayIdentity {
        return read(context) ?: AirPlayIdentity.generate().also { write(context, it) }
    }

    fun read(context: Context): AirPlayIdentity? {
        val preferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val pairingId = preferences.getString(KEY_PAIRING_ID, null) ?: return null
        val privateKeyBase64 = preferences.getString(KEY_PRIVATE_KEY, null) ?: return null
        val privateKey = runCatching {
            Base64.getDecoder().decode(privateKeyBase64)
        }.getOrNull() ?: return null
        return runCatching {
            AirPlayIdentity.fromPrivateKey(pairingId, privateKey)
        }.getOrNull()
    }

    fun write(context: Context, identity: AirPlayIdentity) {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIRING_ID, identity.pairingId)
            .putString(KEY_PRIVATE_KEY, Base64.getEncoder().encodeToString(identity.privateKey))
            .apply()
    }

    fun reset(context: Context): AirPlayIdentity {
        val identity = AirPlayIdentity.generate()
        write(context, identity)
        return identity
    }
}
