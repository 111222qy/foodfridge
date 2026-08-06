package com.foodfridge.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.foodfridge.data.hardware.ModbusByteOrder
import com.foodfridge.data.hardware.ModbusTemperatureValueMode
import com.foodfridge.data.hardware.ModbusValueType
import com.foodfridge.data.hardware.ModbusWordOrder
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

data class TemperaturePreferenceSnapshot(
    val thermalZoneOverride: String?,
    val thermalZoneScale: Int,
    val modbusDevicePath: String?,
    val modbusBaudRate: Int,
    val modbusParity: Int,
    val modbusStopBits: Int,
    val modbusSlaveAddress: Int,
    val modbusFunctionCode: Int,
    val modbusRegisterAddress: Int,
    val modbusRegisterCount: Int,
    val modbusTemperatureRegisterOffset: Int,
    val modbusValueType: ModbusValueType,
    val modbusByteOrder: ModbusByteOrder,
    val modbusWordOrder: ModbusWordOrder,
    val modbusValueMode: ModbusTemperatureValueMode,
    val modbusTemperatureScale: Float,
    val modbusCalibrationOffset: Float,
    val modbusEnabled: Boolean,
)

typealias ModbusPreferenceSnapshot = TemperaturePreferenceSnapshot

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

        val THERMAL_ZONE_OVERRIDE = stringPreferencesKey("thermal_zone_override")
        val THERMAL_ZONE_SCALE = intPreferencesKey("thermal_zone_scale")

        // 平台 API 地址（可在设置页修改）
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val API_DEVICE_KEY = stringPreferencesKey("api_device_key")

        // Modbus 温度传感器配置
        val MODBUS_DEVICE_PATH = stringPreferencesKey("modbus_device_path")
        val MODBUS_BAUD_RATE = intPreferencesKey("modbus_baud_rate")
        val MODBUS_PARITY = intPreferencesKey("modbus_parity")
        val MODBUS_STOP_BITS = intPreferencesKey("modbus_stop_bits")
        val MODBUS_SLAVE_ADDRESS = intPreferencesKey("modbus_slave_address")
        val MODBUS_FUNCTION_CODE = intPreferencesKey("modbus_function_code")
        val MODBUS_REGISTER_ADDRESS = intPreferencesKey("modbus_register_address")
        val MODBUS_REGISTER_COUNT = intPreferencesKey("modbus_register_count")
        val MODBUS_TEMPERATURE_REGISTER_OFFSET = intPreferencesKey("modbus_temperature_register_offset")
        val MODBUS_VALUE_TYPE = stringPreferencesKey("modbus_value_type")
        val MODBUS_BYTE_ORDER = stringPreferencesKey("modbus_byte_order")
        val MODBUS_WORD_ORDER = stringPreferencesKey("modbus_word_order")
        val MODBUS_VALUE_MODE = stringPreferencesKey("modbus_value_mode")
        val MODBUS_TEMPERATURE_SCALE = floatPreferencesKey("modbus_temperature_scale")
        val MODBUS_CALIBRATION_OFFSET = floatPreferencesKey("modbus_calibration_offset")
        val MODBUS_ENABLED = booleanPreferencesKey("modbus_enabled")
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
        .map { preferences -> preferences[THERMAL_ZONE_OVERRIDE] }

    val thermalZoneScale: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[THERMAL_ZONE_SCALE] ?: -1 }

    /** 平台 API 地址，为空时使用 BuildConfig 默认值。 */
    val apiBaseUrl: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[API_BASE_URL]
        }

    val apiDeviceKey: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[API_DEVICE_KEY]?.trim()?.takeIf { it.isNotEmpty() }
        }

    /** 单个 DataStore 快照，避免一次保存产生新旧字段混合的临时串口配置。 */
    val temperaturePreferenceSnapshot: Flow<TemperaturePreferenceSnapshot> = context.dataStore.data
        .map { preferences ->
            TemperaturePreferenceSnapshot(
                thermalZoneOverride = preferences[THERMAL_ZONE_OVERRIDE],
                thermalZoneScale = preferences[THERMAL_ZONE_SCALE] ?: -1,
                modbusDevicePath = preferences[MODBUS_DEVICE_PATH],
                modbusBaudRate = preferences[MODBUS_BAUD_RATE] ?: 115200,
                modbusParity = preferences[MODBUS_PARITY] ?: 0,
                modbusStopBits = preferences[MODBUS_STOP_BITS] ?: 1,
                modbusSlaveAddress = preferences[MODBUS_SLAVE_ADDRESS] ?: 0xFF,
                modbusFunctionCode = preferences[MODBUS_FUNCTION_CODE] ?: 0x03,
                modbusRegisterAddress = preferences[MODBUS_REGISTER_ADDRESS] ?: 0x0000,
                modbusRegisterCount = preferences[MODBUS_REGISTER_COUNT] ?: 2,
                modbusTemperatureRegisterOffset =
                    preferences[MODBUS_TEMPERATURE_REGISTER_OFFSET] ?: 1,
                modbusValueType = preferences.enumValueOrDefault(
                    MODBUS_VALUE_TYPE,
                    ModbusValueType.INT16,
                ),
                modbusByteOrder = preferences.enumValueOrDefault(
                    MODBUS_BYTE_ORDER,
                    ModbusByteOrder.BIG_ENDIAN,
                ),
                modbusWordOrder = preferences.enumValueOrDefault(
                    MODBUS_WORD_ORDER,
                    ModbusWordOrder.HIGH_WORD_FIRST,
                ),
                modbusValueMode = preferences.enumValueOrDefault(
                    MODBUS_VALUE_MODE,
                    ModbusTemperatureValueMode.DIRECT_CELSIUS,
                ),
                modbusTemperatureScale = preferences[MODBUS_TEMPERATURE_SCALE] ?: 0.1f,
                modbusCalibrationOffset = preferences[MODBUS_CALIBRATION_OFFSET] ?: 0f,
                modbusEnabled = preferences[MODBUS_ENABLED] ?: false,
            )
        }

    val modbusPreferenceSnapshot: Flow<ModbusPreferenceSnapshot> = temperaturePreferenceSnapshot

    suspend fun saveModbusConfig(
        devicePath: String?,
        baudRate: Int,
        parity: Int,
        stopBits: Int,
        slaveAddress: Int,
        functionCode: Int,
        registerAddress: Int,
        registerCount: Int,
        temperatureRegisterOffset: Int,
        valueType: ModbusValueType,
        byteOrder: ModbusByteOrder,
        wordOrder: ModbusWordOrder,
        valueMode: ModbusTemperatureValueMode,
        temperatureScale: Float,
        calibrationOffset: Float,
        enabled: Boolean,
    ) {
        context.dataStore.edit { preferences ->
            if (devicePath.isNullOrBlank()) {
                preferences.remove(MODBUS_DEVICE_PATH)
            } else {
                preferences[MODBUS_DEVICE_PATH] = devicePath.trim()
            }
            preferences[MODBUS_BAUD_RATE] = baudRate
            preferences[MODBUS_PARITY] = parity
            preferences[MODBUS_STOP_BITS] = stopBits
            preferences[MODBUS_SLAVE_ADDRESS] = slaveAddress
            preferences[MODBUS_FUNCTION_CODE] = functionCode
            preferences[MODBUS_REGISTER_ADDRESS] = registerAddress
            preferences[MODBUS_REGISTER_COUNT] = registerCount
            preferences[MODBUS_TEMPERATURE_REGISTER_OFFSET] = temperatureRegisterOffset
            preferences[MODBUS_VALUE_TYPE] = valueType.name
            preferences[MODBUS_BYTE_ORDER] = byteOrder.name
            preferences[MODBUS_WORD_ORDER] = wordOrder.name
            preferences[MODBUS_VALUE_MODE] = valueMode.name
            preferences[MODBUS_TEMPERATURE_SCALE] = temperatureScale
            preferences[MODBUS_CALIBRATION_OFFSET] = calibrationOffset
            preferences[MODBUS_ENABLED] = enabled
        }
    }

    suspend fun saveApiConnection(url: String?, apiDeviceKey: String?) {
        context.dataStore.edit { preferences ->
            if (url.isNullOrBlank()) {
                preferences.remove(API_BASE_URL)
            } else {
                preferences[API_BASE_URL] = url.trimEnd('/')
            }
            if (apiDeviceKey != null) {
                preferences[API_DEVICE_KEY] = apiDeviceKey.trim()
            }
        }
    }

    suspend fun saveThermalZoneOverride(path: String?, scale: Int = -1) {
        context.dataStore.edit { preferences ->
            if (path.isNullOrBlank()) {
                preferences.remove(THERMAL_ZONE_OVERRIDE)
                preferences.remove(THERMAL_ZONE_SCALE)
            } else {
                preferences[THERMAL_ZONE_OVERRIDE] = path.trim()
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

            val normalizedWsToken = wsToken?.takeIf { it.isNotBlank() } ?: accessToken
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
            // 只清除认证相关数据，保留配置（API地址、Modbus配置、管理员密码等）
            preferences.remove(IS_LOGGED_IN)
            preferences.remove(USER_ID)
            preferences.remove(USER_NAME)
            preferences.remove(LAST_LOGIN_PASSWORD)
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

    private fun buildAuthorizationHeader(tokenType: String, accessToken: String?): String? {
        val normalizedAccessToken = accessToken?.trim().orEmpty()
        if (normalizedAccessToken.isBlank()) return null
        val normalizedTokenType = tokenType.trim().ifBlank { "Bearer" }
        return "$normalizedTokenType $normalizedAccessToken"
    }
}

private inline fun <reified T : Enum<T>> Preferences.enumValueOrDefault(
    key: Preferences.Key<String>,
    defaultValue: T,
): T {
    val storedValue = this[key] ?: return defaultValue
    return enumValues<T>().firstOrNull { it.name == storedValue } ?: defaultValue
}
