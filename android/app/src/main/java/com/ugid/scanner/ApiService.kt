package com.ugid.scanner

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class ParseRequest(val payload: String)

data class FingerprintModel(
    val finger_index: Int?,
    val minutiae_count: Int?,
    val minutiae_bytes: Int?,
    val sealed_block_bytes: Int?
)

data class ParseResponse(
    val surname: String,
    val given_name: String,
    val other_name: String,
    val full_name: String,
    val date_of_birth: String,
    val issue_date: String,
    val expiry_date: String,
    val nin: String,
    val sex: String,
    val card_number: String,
    val age: Int,
    val is_expired: Boolean,
    val fingerprint: FingerprintModel,
    val warnings: List<String>
)

interface ApiService {
    @POST("parse")
    suspend fun parseBarcode(@Body request: ParseRequest): Response<ParseResponse>
}

object RetrofitClient {
    // EMULATOR: use 10.0.2.2:8000
    // PHYSICAL DEVICE: use your computer's WiFi IP, e.g., "http://192.168.1.45:8000/"
    private const val BASE_URL = "http://10.0.2.2:8000/"

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
