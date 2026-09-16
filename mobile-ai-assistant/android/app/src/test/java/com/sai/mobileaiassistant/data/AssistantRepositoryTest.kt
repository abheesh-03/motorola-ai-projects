package com.sai.mobileaiassistant.data

import com.google.gson.JsonParseException
import com.sai.mobileaiassistant.data.remote.ApiService
import com.sai.mobileaiassistant.data.remote.model.ChatRequest
import com.sai.mobileaiassistant.data.remote.model.ChatResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

class AssistantRepositoryTest {

    // ---------------------------------------------------------------------------
    // Minimal fake: delegates the call to a lambda so each test controls the outcome.
    // ---------------------------------------------------------------------------

    private class FakeApiService(
        private val result: suspend () -> ChatResponse
    ) : ApiService {
        override suspend fun chat(request: ChatRequest): ChatResponse = result()
    }

    private fun errorBody() = "".toResponseBody("application/json".toMediaType())

    // ---------------------------------------------------------------------------
    // Helper: run the repository call and assert the expected exception message.
    // ---------------------------------------------------------------------------

    private suspend fun assertMappedMessage(expectedMessage: String, api: ApiService) {
        val repository = AssistantRepository(api)
        try {
            repository.sendMessage("test input")
            fail("Expected an exception but none was thrown")
        } catch (e: Exception) {
            assertEquals(expectedMessage, e.message)
        }
    }

    // ---------------------------------------------------------------------------
    // Success path
    // ---------------------------------------------------------------------------

    @Test
    fun `successful response returns the response text`() = runTest {
        val api = FakeApiService { ChatResponse("Hello world", "req-1", 42) }
        val repository = AssistantRepository(api)
        assertEquals("Hello world", repository.sendMessage("hi"))
    }

    // ---------------------------------------------------------------------------
    // HTTP error mapping
    // ---------------------------------------------------------------------------

    @Test
    fun `HTTP 500 maps to service unavailable message`() = runTest {
        val api = FakeApiService {
            throw HttpException(Response.error<ChatResponse>(500, errorBody()))
        }
        assertMappedMessage(
            "The AI service is temporarily unavailable. Please try again.",
            api
        )
    }

    @Test
    fun `HTTP 503 maps to service unavailable message`() = runTest {
        val api = FakeApiService {
            throw HttpException(Response.error<ChatResponse>(503, errorBody()))
        }
        assertMappedMessage(
            "The AI service is temporarily unavailable. Please try again.",
            api
        )
    }

    @Test
    fun `HTTP 400 maps to generic error message`() = runTest {
        val api = FakeApiService {
            throw HttpException(Response.error<ChatResponse>(400, errorBody()))
        }
        assertMappedMessage("Something went wrong. Please try again.", api)
    }

    // ---------------------------------------------------------------------------
    // Network / IO error mapping
    // ---------------------------------------------------------------------------

    @Test
    fun `SocketTimeoutException maps to timeout message`() = runTest {
        val api = FakeApiService { throw SocketTimeoutException("connect timed out") }
        assertMappedMessage(
            "The request took too long. Please try again.",
            api
        )
    }

    @Test
    fun `IOException maps to connection error message`() = runTest {
        val api = FakeApiService { throw IOException("Connection refused") }
        assertMappedMessage(
            "Unable to connect. Check your network and try again.",
            api
        )
    }

    // ---------------------------------------------------------------------------
    // Malformed-response mapping
    //
    // JsonParseException is thrown by Gson when it cannot deserialize the response
    // body. The fake simulates this directly — no MockWebServer is needed because
    // the mapping logic lives in the catch block, not in the Retrofit/Gson layer.
    // ---------------------------------------------------------------------------

    @Test
    fun `JsonParseException maps to invalid response message`() = runTest {
        val api = FakeApiService { throw JsonParseException("Unexpected token") }
        assertMappedMessage(
            "Received an invalid response. Please try again.",
            api
        )
    }

    // ---------------------------------------------------------------------------
    // Fallback
    // ---------------------------------------------------------------------------

    @Test
    fun `unexpected exception maps to generic error message`() = runTest {
        val api = FakeApiService { throw RuntimeException("something unexpected") }
        assertMappedMessage("Something went wrong. Please try again.", api)
    }
}
