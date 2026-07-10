package com.foodfridge.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// 扩展属性，创建名为 "user_prefs" 的 DataStore
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class AuthTokenBundle(
    val accessToken: String?,
    val refreshToken: String?,
    val wsToken: String?,
    val tokenType: String,
    val accessTokenExpiresAt: Long?,
    val refreshTokenExpiresAt: Long?,
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_ID = stringPreferencesKey("user_id")
        val LAST_LOGIN_PASSWORD = stringPreferencesKey("last_login_password")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val WS_TOKEN = stringPreferencesKey("ws_token")
        val TOKEN_TYPE = stringPreferencesKey("token_type")
        val ACCESS_TOKEN_EXPIRES_AT = longPreferencesKey("access_token_expires_at")
        val REFRESH_TOKEN_EXPIRES_AT = longPreferencesKey("refresh_token_expires_at")
        val DUAL_FACE_AUTH_ENABLED = booleanPreferencesKey("dual_face_auth_enabled")
        val ADMIN_PASSWORD = stringPreferencesKey("admin_password")

        // 温度传感器覆盖配置（现场调试使用）
        val THERMAL_ZONE_OVERRIDE = stringPreferencesKey("thermal_zone_override")
        val THERMAL_ZONE_SCALE = intPreferencesKey("thermal_zone_scale")

        // 平台 API 地址（可在设置页修改）
        val API_BASE_URL = stringPreferencesKey("api_base_url")
    }

    @Volatile
    private var cachedAuthorizationHeader: String? = null

    @Volatile
    private var authorizationHeaderCacheInitialized = false

    val isLoggedIn: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[IS_LOGGED_IN] ?: false
        }

    val currentUserName: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[USER_NAME]
        }

    val currentUserId: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[USER_ID]
        }

    val dualFaceAuthEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[DUAL_FACE_AUTH_ENABLED] ?: false
        }

    val adminPassword: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[ADMIN_PASSWORD]
        }

    val thermalZoneOverride: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[THERMAL_ZONE_OVERRIDE]
        }

    val thermalZoneScale: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[THERMAL_ZONE_SCALE] ?: -1
        }

    /** 平台 API 地址，为空时使用 BuildConfig 默认值。 */
    val apiBaseUrl: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[API_BASE_URL]
        }

    suspend fun saveApiBaseUrl(url: String?) {
        context.dataStore.edit { preferences ->
            if (url.isNullOrBlank()) {
                preferences.remove(API_BASE_URL)
            } else {
                preferences[API_BASE_URL] = url.trimEnd('/')
            }
        }
    }

    suspend fun saveThermalZoneOverride(path: String?, scale: Int = -1) {
        context.dataStore.edit { preferences ->
            if (path.isNullOrBlank()) {
                preferences.remove(THERMAL_ZONE_OVERRIDE)
                preferences.remove(THERMAL_ZONE_SCALE)
            } else {
                preferences[THERMAL_ZONE_OVERRIDE] = path
                preferences[THERMAL_ZONE_SCALE] = scale
            }
        }
    }

    val lastLoginPassword: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_LOGIN_PASSWORD]
        }

    val authTokens: Flow<AuthTokenBundle> = context.dataStore.data
        .map { preferences ->
            AuthTokenBundle(
                accessToken = preferences[ACCESS_TOKEN],
                refreshToken = preferences[REFRESH_TOKEN],
                wsToken = preferences[WS_TOKEN],
                tokenType = preferences[TOKEN_TYPE] ?: "Bearer",
                accessTokenExpiresAt = preferences[ACCESS_TOKEN_EXPIRES_AT],
                refreshTokenExpiresAt = preferences[REFRESH_TOKEN_EXPIRES_AT],
            )
        }

    val authorizationHeader: Flow<String?> = authTokens
        .map { tokens ->
            buildAuthorizationHeader(tokens.tokenType, tokens.accessToken)
        }

    fun hasCachedAuthorizationHeader(): Boolean {
        return authorizationHeaderCacheInitialized
    }

    fun peekCachedAuthorizationHeader(): String? {
        return if (authorizationHeaderCacheInitialized) cachedAuthorizationHeader else null
    }

    suspend fun getAuthorizationHeader(): String? {
        if (authorizationHeaderCacheInitialized) {
            return cachedAuthorizationHeader
        }

        val header = authorizationHeader.first()
        cachedAuthorizationHeader = header
        authorizationHeaderCacheInitialized = true
        return header
    }

    suspend fun saveUserSession(
        userId: String,
        userName: String,
        lastLoginPassword: String? = null,
    ) {
        context.dataStore.edit { preferences ->
            preferences[IS_LOGGED_IN] = true
            preferences[USER_ID] = userId
            preferences[USER_NAME] = userName
            when {
                lastLoginPassword == null -> Unit
                lastLoginPassword.isBlank() -> preferences.remove(LAST_LOGIN_PASSWORD)
                else -> preferences[LAST_LOGIN_PASSWORD] = lastLoginPassword
            }
        }
    }

    suspend fun saveAuthTokens(
        accessToken: String,
        tokenType: String = "Bearer",
        refreshToken: String? = null,
        wsToken: String? = null,
        expiresInSeconds: Long? = null,
        refreshExpiresInSeconds: Long? = null,
    ) {
        val now = System.currentTimeMillis()
        val normalizedTokenType = tokenType.trim().ifBlank { "Bearer" }
        val nextAuthorizationHeader = buildAuthorizationHeader(normalizedTokenType, accessToken)

        context.dataStore.edit { preferences ->
            preferences[ACCESS_TOKEN] = accessToken
            preferences[TOKEN_TYPE] = normalizedTokenType

            if (refreshToken.isNullOrBlank()) {
                preferences.remove(REFRESH_TOKEN)
            } else {
                preferences[REFRESH_TOKEN] = refreshToken
            }

            val normalizedWsToken = wsToken ?: accessToken
            preferences[WS_TOKEN] = normalizedWsToken

            if (expiresInSeconds == null) {
                preferences.remove(ACCESS_TOKEN_EXPIRES_AT)
            } else {
                preferences[ACCESS_TOKEN_EXPIRES_AT] = now + expiresInSeconds * 1000
            }

            if (refreshExpiresInSeconds == null) {
                preferences.remove(REFRESH_TOKEN_EXPIRES_AT)
            } else {
                preferences[REFRESH_TOKEN_EXPIRES_AT] = now + refreshExpiresInSeconds * 1000
            }
        }

        cachedAuthorizationHeader = nextAuthorizationHeader
        authorizationHeaderCacheInitialized = true
    }

    suspend fun setDualFaceAuthEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DUAL_FACE_AUTH_ENABLED] = enabled
        }
    }

    suspend fun saveAdminPassword(password: String) {
        context.dataStore.edit { preferences ->
            if (password.isBlank()) {
                preferences.remove(ADMIN_PASSWORD)
            } else {
                preferences[ADMIN_PASSWORD] = password
            }
        }
    }

    suspend fun clearAuthTokens() {
        context.dataStore.edit { preferences ->
            preferences.remove(ACCESS_TOKEN)
            preferences.remove(REFRESH_TOKEN)
            preferences.remove(WS_TOKEN)
            preferences.remove(TOKEN_TYPE)
            preferences.remove(ACCESS_TOKEN_EXPIRES_AT)
            preferences.remove(REFRESH_TOKEN_EXPIRES_AT)
        }

        cachedAuthorizationHeader = null
        authorizationHeaderCacheInitialized = true
    }

    suspend fun clearLoginFlag() {
        context.dataStore.edit { preferences ->
            preferences.remove(IS_LOGGED_IN)
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }

        cachedAuthorizationHeader = null
        authorizationHeaderCacheInitialized = true
    }

    private fun buildAuthorizationHeader(tokenType: String, accessToken: String?): String? {
        val normalizedAccessToken = accessToken?.trim().orEmpty()
        if (normalizedAccessToken.isBlank()) return null
        val normalizedTokenType = tokenType.trim().ifBlank { "Bearer" }
        return "$normalizedTokenType $normalizedAccessToken"
    }
}