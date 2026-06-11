package com.foodfridge.data.remote

import com.foodfridge.data.remote.dto.AccountItem
import com.foodfridge.data.remote.dto.AccountListRequest
import com.foodfridge.data.remote.dto.ActivateRequest
import com.foodfridge.data.remote.dto.ActivateResponseData
import com.foodfridge.data.remote.dto.AdminAccountData
import com.foodfridge.data.remote.dto.AdminAccountRequest
import com.foodfridge.data.remote.dto.AllMenuFoodListData
import com.foodfridge.data.remote.dto.ApiResponse
import com.foodfridge.data.remote.dto.ClearNotEntryFoodsRequest
import com.foodfridge.data.remote.dto.FoodEarlyWarningsData
import com.foodfridge.data.remote.dto.FoodEarlyWarningsRequest
import com.foodfridge.data.remote.dto.FoodEntryCupboardRequest
import com.foodfridge.data.remote.dto.FoodExitCupboardRequest
import com.foodfridge.data.remote.dto.FoodItem
import com.foodfridge.data.remote.dto.GetAllMenuFoodListRequest
import com.foodfridge.data.remote.dto.GetFoodListRequest
import com.foodfridge.data.remote.dto.OrgMealTimeData
import com.foodfridge.data.remote.dto.OrgMealTimeRequest
import com.foodfridge.data.remote.dto.RecognizeData
import com.foodfridge.data.remote.dto.RecognizeRequest
import com.foodfridge.data.remote.dto.RecvLogRequest
import com.foodfridge.data.remote.dto.SettingSecretData
import com.foodfridge.data.remote.dto.SettingSecretRequest
import com.foodfridge.data.remote.dto.UploadResponseData
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {

    @POST("/api/client/common/activate")
    suspend fun activateDevice(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: ActivateRequest
    ): ApiResponse<ActivateResponseData>

    @POST("/api/client/buffet/account_list")
    suspend fun getAccountList(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: AccountListRequest
    ): ApiResponse<List<AccountItem>>

    @POST("/api/client/common/setting_secret")
    suspend fun getSettingSecret(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: SettingSecretRequest
    ): ApiResponse<SettingSecretData>

    @Multipart
    @POST("/api/client/common/upload")
    suspend fun uploadFile(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Part("prefix") prefix: RequestBody,
        @Part("key") key: RequestBody,
        @Part file: MultipartBody.Part
    ): ApiResponse<UploadResponseData>

    @POST("/api/client/common/recv_log")
    suspend fun uploadLog(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: RecvLogRequest
    ): ApiResponse<Any?>

    @POST("/api/client/common/recognize")
    suspend fun recognizeFace(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: RecognizeRequest
    ): ApiResponse<RecognizeData?>

    @POST("/api/client/buffet/admin_account")
    suspend fun verifyAdmin(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: AdminAccountRequest
    ): ApiResponse<AdminAccountData>

    @POST("/api/client/common/org_meal_time")
    suspend fun getOrgMealTime(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: OrgMealTimeRequest
    ): ApiResponse<OrgMealTimeData>

    @POST("/api/client/reserved_sample/get_all_menu_food_list")
    suspend fun getAllMenuFoodList(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: GetAllMenuFoodListRequest
    ): ApiResponse<AllMenuFoodListData>

    @POST("/api/client/reserved_sample/get_food_list")
    suspend fun getFoodList(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: GetFoodListRequest
    ): ApiResponse<List<FoodItem>>

    @POST("/api/client/reserved_sample/food_entry_cupboard")
    suspend fun foodEntryCupboard(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: FoodEntryCupboardRequest
    ): ApiResponse<Any?>

    @POST("/api/client/reserved_sample/food_exit_cupboard")
    suspend fun foodExitCupboard(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: FoodExitCupboardRequest
    ): ApiResponse<Any?>

    @POST("/api/client/reserved_sample/food_early_warnings")
    suspend fun getFoodEarlyWarnings(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: FoodEarlyWarningsRequest
    ): ApiResponse<FoodEarlyWarningsData>

    @POST("/api/client/reserved_sample/clear_not_entry_foods")
    suspend fun clearNotEntryFoods(
        @Header("apisix") apisix: String,
        @Header("Token") token: String,
        @Header("SmKeys") smKeys: String,
        @Body request: ClearNotEntryFoodsRequest
    ): ApiResponse<Any?>
}
