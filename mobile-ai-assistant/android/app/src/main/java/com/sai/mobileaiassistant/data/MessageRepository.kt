package com.sai.mobileaiassistant.data

interface MessageRepository {
    suspend fun sendMessage(message: String): String
}
