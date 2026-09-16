package com.sai.mobileaiassistant.data.remote.model

import com.google.gson.annotations.SerializedName

data class ChatResponse(
    val response: String,
    @SerializedName("request_id") val requestId: String,
    @SerializedName("latency_ms") val latencyMs: Int
)
