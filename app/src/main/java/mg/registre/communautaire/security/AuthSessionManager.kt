package mg.registre.communautaire.security

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mg.registre.communautaire.data.SupabaseConfig
import org.json.JSONObject

data class AuthSession(
    val userId: String,
    val email: String,
    val role: AccessRole?,
    val approved: Boolean,
    val bootstrapRequired: Boolean = false,
)

data class SignUpResult(
    val signedIn: Boolean,
    val needsEmailConfirmation: Boolean,
)

class AuthSessionManager(context: Context) {
    private val store = SecureStore(context, "registre_auth")

    suspend fun restoreSession(): AuthSession? = withContext(Dispatchers.IO) {
        if (!hasStoredSession()) return@withContext null
        runCatching {
            val token = validAccessToken()
            claimAccess(token)
        }.getOrElse {
            clearSession()
            null
        }
    }

    suspend fun signIn(email: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .toString()
        val response = authRequest("POST", "/auth/v1/token?grant_type=password", body)
        saveAuthResponse(response)
        claimAccess(validAccessToken())
    }

    suspend fun signUp(email: String, password: String): SignUpResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .toString()
        val response = authRequest("POST", "/auth/v1/signup", body)
        val json = JSONObject(response)
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        if (access.isNotBlank() && refresh.isNotBlank()) {
            saveAuthResponse(response)
            SignUpResult(signedIn = true, needsEmailConfirmation = false)
        } else {
            SignUpResult(signedIn = false, needsEmailConfirmation = true)
        }
    }

    suspend fun bootstrapAdmin(code: String): AuthSession = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val body = JSONObject()
            .put("p_bootstrap_code", code.trim().uppercase())
            .toString()
        apiRequest("POST", "/rest/v1/rpc/registre_claim_admin", body, token)
        claimAccess(token)
    }

    suspend fun addMember(email: String, role: AccessRole): AuthSession = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val body = JSONObject()
            .put("p_email", email.trim())
            .put("p_role", role.wireValue)
            .toString()
        apiRequest("POST", "/rest/v1/rpc/registre_add_member_by_email", body, token)
        claimAccess(token)
    }

    fun signOut() {
        clearSession()
    }

    fun hasStoredSession(): Boolean = store.getString(KEY_REFRESH_TOKEN).isNotBlank()

    fun currentUserId(): String = store.getString(KEY_USER_ID)

    fun currentEmail(): String = store.getString(KEY_EMAIL)

    fun currentRole(): AccessRole? = AccessRole.fromWire(store.getString(KEY_ROLE))

    fun currentAccessTokenBlocking(): String = validAccessToken()

    private fun claimAccess(token: String): AuthSession {
        val response = apiRequest("POST", "/rest/v1/rpc/registre_claim_access", "{}", token)
        val obj = JSONObject(response)
        val approved = obj.optBoolean("approved", false)
        val role = AccessRole.fromWire(obj.optString("role"))
        val userId = obj.optString("userId").ifBlank { store.getString(KEY_USER_ID) }
        val email = obj.optString("email").ifBlank { store.getString(KEY_EMAIL) }
        val bootstrapRequired = obj.optBoolean("bootstrapRequired", false)

        store.putString(KEY_USER_ID, userId)
        store.putString(KEY_EMAIL, email)
        store.putString(KEY_ROLE, role?.wireValue.orEmpty())

        return AuthSession(
            userId = userId,
            email = email,
            role = role,
            approved = approved,
            bootstrapRequired = bootstrapRequired,
        )
    }

    @Synchronized
    private fun validAccessToken(): String {
        val access = store.getString(KEY_ACCESS_TOKEN)
        val expiresAt = store.getString(KEY_EXPIRES_AT).toLongOrNull() ?: 0L
        val now = System.currentTimeMillis() / 1000L
        if (access.isNotBlank() && expiresAt > now + 60L) return access

        val refresh = store.getString(KEY_REFRESH_TOKEN)
        if (refresh.isBlank()) throw IOException("Session expirée. Reconnectez-vous.")

        val body = JSONObject().put("refresh_token", refresh).toString()
        val response = authRequest("POST", "/auth/v1/token?grant_type=refresh_token", body)
        saveAuthResponse(response)
        return store.getString(KEY_ACCESS_TOKEN).ifBlank {
            throw IOException("Impossible de renouveler la session.")
        }
    }

    private fun saveAuthResponse(response: String) {
        val obj = JSONObject(response)
        val access = obj.optString("access_token")
        val refresh = obj.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) {
            throw IOException("Réponse d'authentification incomplète.")
        }
        val expiresIn = obj.optLong("expires_in", 3600L)
        val user = obj.optJSONObject("user")

        store.putString(KEY_ACCESS_TOKEN, access)
        store.putString(KEY_REFRESH_TOKEN, refresh)
        store.putString(
            KEY_EXPIRES_AT,
            ((System.currentTimeMillis() / 1000L) + expiresIn).toString(),
        )
        user?.optString("id")?.takeIf { it.isNotBlank() }?.let {
            store.putString(KEY_USER_ID, it)
        }
        user?.optString("email")?.takeIf { it.isNotBlank() }?.let {
            store.putString(KEY_EMAIL, it)
        }
    }

    private fun authRequest(method: String, path: String, body: String?): String =
        rawRequest(method, path, body, null)

    private fun apiRequest(method: String, path: String, body: String?, token: String): String =
        rawRequest(method, path, body, token)

    private fun rawRequest(method: String, path: String, body: String?, token: String?): String {
        check(SupabaseConfig.isConfigured()) { "Configuration Supabase absente." }
        val connection = URL(SupabaseConfig.url + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("apikey", SupabaseConfig.publishableKey)
            connection.setRequestProperty("Accept", "application/json")
            token?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (status !in 200..299) {
                val message = runCatching {
                    val json = JSONObject(response)
                    json.optString("msg")
                        .ifBlank { json.optString("message") }
                        .ifBlank { json.optString("error_description") }
                        .ifBlank { response }
                }.getOrDefault(response)
                throw IOException(message.ifBlank { "Erreur HTTP $status" })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun clearSession() {
        store.clear()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_ROLE = "role"
    }
}
