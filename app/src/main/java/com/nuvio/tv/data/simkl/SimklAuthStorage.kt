package com.nuvio.tv.data.simkl

import kotlinx.coroutines.flow.StateFlow

interface SimklAuthStorage {
    val state: StateFlow<SimklAuthState>

    fun accessToken(): String?
    fun savePinSession(session: SimklPinSession)
    fun clearPinSession(error: SimklAuthError? = null)
    fun saveAccessToken(token: String)
    fun saveIdentity(username: String?, accountId: Long?)
    fun clearAuth(error: SimklAuthError? = null)
    fun removeProfile(profileId: Int)
}
