package com.nuvio.tv.data.simkl

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.data.local.ProfileDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class AndroidSimklAuthStorage @Inject constructor(
    @ApplicationContext context: Context,
    profileDataStore: ProfileDataStore
) : SimklAuthStorage, ProfileScopedCredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SimklAuthState())
    private var activeProfileId = 1
    private var currentAccessToken: String? = null

    override val state: StateFlow<SimklAuthState> = _state.asStateFlow()

    init {
        load(activeProfileId)
        scope.launch {
            profileDataStore.activeProfileId.collect { profileId ->
                activeProfileId = profileId
                load(profileId)
            }
        }
    }

    override fun accessToken(): String? = currentAccessToken

    override fun savePinSession(session: SimklPinSession) {
        val metadata = metadata().copy(pinSession = session)
        saveMetadata(metadata)
        publish(metadata = metadata)
    }

    override fun clearPinSession(error: SimklAuthError?) {
        val metadata = metadata().copy(pinSession = null)
        saveMetadata(metadata)
        publish(metadata = metadata, error = error)
    }

    override fun saveAccessToken(token: String) {
        currentAccessToken = token.trim().takeIf(String::isNotBlank)
        saveEncrypted(TOKEN_KEY, currentAccessToken)
        publish()
    }

    override fun saveIdentity(username: String?, accountId: Long?, settingsActivityWatermark: String?) {
        val current = metadata()
        val metadata = current.copy(
            username = username,
            accountId = accountId,
            hasFetchedUserSettings = true,
            settingsActivityWatermark = settingsActivityWatermark ?: current.settingsActivityWatermark
        )
        saveMetadata(metadata)
        publish(metadata = metadata)
    }

    override fun recordSettingsActivityWatermark(watermark: String) {
        val metadata = metadata().copy(settingsActivityWatermark = watermark)
        saveMetadata(metadata)
        publish(metadata = metadata)
    }

    override fun clearAuth(error: SimklAuthError?) {
        currentAccessToken = null
        saveEncrypted(TOKEN_KEY, null)
        val metadata = SimklStoredAuthMetadata()
        saveMetadata(metadata)
        publish(metadata = metadata, error = error)
    }

    override fun removeProfile(profileId: Int) {
        preferences.edit()
            .remove(profileKey(METADATA_KEY, profileId))
            .remove(profileKey(TOKEN_KEY, profileId))
            .apply()
        if (profileId == activeProfileId) {
            currentAccessToken = null
            publish(metadata = SimklStoredAuthMetadata())
        }
    }

    override fun clearAllProfiles() {
        preferences.edit().clear().apply()
        currentAccessToken = null
        publish(metadata = SimklStoredAuthMetadata())
    }

    private fun load(profileId: Int) {
        val metadata = preferences.getString(profileKey(METADATA_KEY, profileId), null)
            ?.let { runCatching { json.decodeFromString<SimklStoredAuthMetadata>(it) }.getOrNull() }
            ?: SimklStoredAuthMetadata()
        currentAccessToken = loadEncrypted(TOKEN_KEY, profileId)
        publish(metadata = metadata)
    }

    private fun metadata(): SimklStoredAuthMetadata = SimklStoredAuthMetadata(
        username = _state.value.username,
        accountId = _state.value.accountId,
        hasFetchedUserSettings = _state.value.hasFetchedUserSettings,
        settingsActivityWatermark = _state.value.settingsActivityWatermark,
        pinSession = _state.value.pinSession
    )

    private fun publish(
        metadata: SimklStoredAuthMetadata = metadata(),
        error: SimklAuthError? = null
    ) {
        _state.value = SimklAuthState(
            isAuthenticated = !currentAccessToken.isNullOrBlank(),
            username = metadata.username,
            accountId = metadata.accountId,
            hasFetchedUserSettings = metadata.hasFetchedUserSettings,
            settingsActivityWatermark = metadata.settingsActivityWatermark,
            pinSession = metadata.pinSession,
            error = error
        )
    }

    private fun saveMetadata(metadata: SimklStoredAuthMetadata) {
        preferences.edit()
            .putString(profileKey(METADATA_KEY, activeProfileId), json.encodeToString(metadata))
            .apply()
    }

    private fun loadEncrypted(key: String, profileId: Int): String? {
        val scopedKey = profileKey(key, profileId)
        val stored = preferences.getString(scopedKey, null) ?: return null
        return runCatching { decrypt(stored) }
            .onFailure { preferences.edit().remove(scopedKey).apply() }
            .getOrNull()
    }

    private fun saveEncrypted(key: String, value: String?) {
        val scopedKey = profileKey(key, activeProfileId)
        val editor = preferences.edit()
        if (value.isNullOrBlank()) editor.remove(scopedKey) else editor.putString(scopedKey, encrypt(value))
        editor.apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return "${cipher.iv.toBase64()}.${cipher.doFinal(value.toByteArray()).toBase64()}"
    }

    private fun decrypt(value: String): String {
        val separator = value.indexOf('.')
        require(separator > 0 && separator < value.lastIndex)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_BITS, value.substring(0, separator).fromBase64())
        )
        return cipher.doFinal(value.substring(separator + 1).fromBase64()).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun profileKey(key: String, profileId: Int): String = "$key.p$profileId"

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val PREFERENCES_NAME = "nuvio_simkl_auth"
        const val METADATA_KEY = "metadata"
        const val TOKEN_KEY = "access_token"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "com.nuvio.tv.simkl.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
