package com.example.whisperflow.network

import android.content.Context
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

// Model for the response
data class TranscriptionResponse(val text: String)

interface GroqApiService {

    @Multipart
    @POST("openai/v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") authHeader: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody? = null // Optional: specify language for faster processing
    ): TranscriptionResponse

    companion object {
        private const val BASE_URL = "https://api.groq.com/"

        @Volatile
        private var instance: GroqApiService? = null

        fun getInstance(context: Context): GroqApiService {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(context: Context): GroqApiService {
            // Dynamically detect if the application is running in a debuggable environment
            val isDebug = try {
                (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            } catch (e: Exception) {
                false
            }

            val logger = HttpLoggingInterceptor().apply {
                level = if (isDebug) {
                    HttpLoggingInterceptor.Level.HEADERS // Only log basic headers in debug mode
                } else {
                    HttpLoggingInterceptor.Level.NONE // Log absolutely nothing in production builds
                }
                // Never leak the Authorization bearer token to logcat
                redactHeader("Authorization")
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logger)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GroqApiService::class.java)
        }
    }
}
