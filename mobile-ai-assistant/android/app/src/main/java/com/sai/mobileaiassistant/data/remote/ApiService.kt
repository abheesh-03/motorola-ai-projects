package com.sai.mobileaiassistant.data.remote

import com.sai.mobileaiassistant.data.remote.model.ChatRequest
import com.sai.mobileaiassistant.data.remote.model.ChatResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("chat")
    suspend fun chat(@Body request: ChatRequest): ChatResponse
}
