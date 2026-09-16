package com.sai.mobileaiassistant

import com.sai.mobileaiassistant.data.MessageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        // Replace the Android main dispatcher so viewModelScope.launch runs
        // eagerly on the JVM without needing an Android runtime.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------
    // Fake repositories — no Retrofit, no network, fully deterministic
    // ---------------------------------------------------------------------------

    private class FakeSuccessRepository(private val answer: String) : MessageRepository {
        override suspend fun sendMessage(message: String): String = answer
    }

    private class FakeFailureRepository(private val errorMessage: String) : MessageRepository {
        override suspend fun sendMessage(message: String): String =
            throw Exception(errorMessage)
    }

    /** Suspends until complete() is called — lets us observe isLoading mid-flight. */
    private class FakeSlowRepository : MessageRepository {
        private val deferred = CompletableDeferred<String>()
        fun complete(value: String) { deferred.complete(value) }
        override suspend fun sendMessage(message: String): String = deferred.await()
    }

    // ---------------------------------------------------------------------------
    // onInputChange
    // ---------------------------------------------------------------------------

    @Test
    fun `input update changes uiState input`() {
        val vm = AssistantViewModel(FakeSuccessRepository(""))
        vm.onInputChange("hello")
        assertEquals("hello", vm.uiState.value.input)
    }

    // ---------------------------------------------------------------------------
    // clear
    // ---------------------------------------------------------------------------

    @Test
    fun `clear resets input, response, loading, and error`() {
        val vm = AssistantViewModel(FakeSuccessRepository("answer"))
        vm.onInputChange("question")
        vm.send()
        vm.clear()
        assertEquals(AssistantUiState(), vm.uiState.value)
    }

    // ---------------------------------------------------------------------------
    // successful send
    // ---------------------------------------------------------------------------

    @Test
    fun `successful send stores the repository response`() {
        val vm = AssistantViewModel(FakeSuccessRepository("AI reply"))
        vm.onInputChange("a question")
        vm.send()
        assertEquals("AI reply", vm.uiState.value.response)
    }

    @Test
    fun `successful send finishes with isLoading false`() {
        val vm = AssistantViewModel(FakeSuccessRepository("ok"))
        vm.onInputChange("hi")
        vm.send()
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `successful send finishes with error null`() {
        val vm = AssistantViewModel(FakeSuccessRepository("ok"))
        vm.onInputChange("hi")
        vm.send()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `send sets isLoading true while request is in flight`() = runTest {
        // Switch to StandardTestDispatcher so coroutines only advance when we say so.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val repo = FakeSlowRepository()
        val vm = AssistantViewModel(repo)
        vm.onInputChange("question")

        // send() schedules the inner coroutine but the body hasn't run yet.
        vm.send()

        // Advance to the first suspension point: the isLoading=true update has run
        // but repo.sendMessage() is suspended on the CompletableDeferred.
        runCurrent()

        assertTrue("isLoading should be true while awaiting repository", vm.uiState.value.isLoading)

        // Unblock the repository and drain remaining work.
        repo.complete("the answer")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("the answer", vm.uiState.value.response)
    }

    // ---------------------------------------------------------------------------
    // failed send
    // ---------------------------------------------------------------------------

    @Test
    fun `failed send exposes the user-facing error message`() {
        val vm = AssistantViewModel(
            FakeFailureRepository("Unable to connect. Check your network and try again.")
        )
        vm.onInputChange("question")
        vm.send()
        assertEquals(
            "Unable to connect. Check your network and try again.",
            vm.uiState.value.error
        )
    }

    @Test
    fun `failed send finishes with isLoading false`() {
        val vm = AssistantViewModel(FakeFailureRepository("error"))
        vm.onInputChange("hi")
        vm.send()
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `failed send does not crash the ViewModel`() {
        val vm = AssistantViewModel(FakeFailureRepository("error"))
        vm.onInputChange("hi")
        vm.send() // must not throw
        assertFalse(vm.uiState.value.isLoading)
    }
}
