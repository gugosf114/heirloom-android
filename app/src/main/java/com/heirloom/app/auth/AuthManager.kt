package com.heirloom.app.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stripped-down AuthManager for V1 fast-launch.
 * Firebase is removed. User is always null, and we do not sign in.
 */
class AuthManager(private val context: Context) {

    // Dummy user object representation since FirebaseUser is removed
    class User

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    suspend fun signIn(): Boolean {
        // No-op for fast launch
        return false
    }

    fun signOut() {
        // No-op
    }

    suspend fun idToken(forceRefresh: Boolean = false): String? {
        return null
    }
}
