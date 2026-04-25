package com.heirloom.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Firebase Auth + Google Sign-In via the modern Credential Manager API.
 *
 * Activated once `google-services.json` is dropped into `app/` and the
 * `com.google.gms.google-services` plugin is uncommented in build.gradle.kts.
 * Before that, Firebase calls throw IllegalStateException — the manager
 * gracefully reports unauthenticated.
 *
 * The web client ID is required for ID token issuance. It's the OAuth 2.0
 * client ID of type "Web application" auto-created in your Firebase project.
 * Replace the placeholder string before launch.
 */
class AuthManager(private val context: Context) {

    private val _user = MutableStateFlow<FirebaseUser?>(null)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()

    private val auth: FirebaseAuth?
        get() = runCatching { FirebaseAuth.getInstance() }.getOrNull()

    init {
        _user.value = auth?.currentUser
        auth?.addAuthStateListener { _user.value = it.currentUser }
    }

    suspend fun signIn(): Boolean = withContext(Dispatchers.Main) {
        val firebase = auth ?: return@withContext false

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val credentialManager = CredentialManager.create(context)
        val response = runCatching {
            credentialManager.getCredential(context, request)
        }.getOrNull() ?: return@withContext false

        val credential = response.credential as? CustomCredential
            ?: return@withContext false
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return@withContext false
        }

        val googleIdCredential = GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCred = GoogleAuthProvider.getCredential(googleIdCredential.idToken, null)
        runCatching { firebase.signInWithCredential(firebaseCred).await() }.isSuccess
    }

    fun signOut() {
        auth?.signOut()
    }

    /** Fresh ID token for authenticated requests to the Worker. */
    suspend fun idToken(forceRefresh: Boolean = false): String? {
        val current = auth?.currentUser ?: return null
        return runCatching { current.getIdToken(forceRefresh).await().token }.getOrNull()
    }

    companion object {
        // TODO: replace with the real Web application OAuth 2.0 client ID
        // from Firebase project settings. Format: "...apps.googleusercontent.com".
        private const val WEB_CLIENT_ID = "REPLACE_WITH_WEB_CLIENT_ID.apps.googleusercontent.com"
    }
}
