package com.sai.mobileaiassistant.data

import com.google.gson.JsonParseException
import com.sai.mobileaiassistant.data.remote.ApiService
import com.sai.mobileaiassistant.data.remote.RetrofitClient
import com.sai.mobileaiassistant.data.remote.model.ChatRequest
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

class AssistantRepository(
    private val api: ApiService = RetrofitClient.api
) : MessageRepository {

    override suspend fun sendMessage(message: String): String {
        return try {
            api.chat(ChatRequest(message)).response
        } catch (e: SocketTimeoutException) {
            throw NetworkException("The request took too long. Please try again.")
        } catch (e: IOException) {
            throw NetworkException("Unable to connect. Check your network and try again.")
        } catch (e: HttpException) {
            val msg = if (e.code() in 500..599)
                "The AI service is temporarily unavailable. Please try again."
            else
                "Something went wrong. Please try again."
            throw NetworkException(msg)
        } catch (e: JsonParseException) {
            throw NetworkException("Received an invalid response. Please try again.")
        } catch (e: Exception) {
            throw NetworkException("Something went wrong. Please try again.")
        }
    }
}

private class NetworkException(message: String) : Exception(message)
