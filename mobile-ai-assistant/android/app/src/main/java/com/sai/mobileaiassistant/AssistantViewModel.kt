package com.sai.mobileaiassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sai.mobileaiassistant.data.AssistantRepository
import com.sai.mobileaiassistant.data.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AssistantViewModel(
    private val repository: MessageRepository = AssistantRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    fun onInputChange(input: String) {
        _uiState.update { it.copy(input = input) }
    }

    fun send() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, response = "") }
            try {
                val result = repository.sendMessage(_uiState.value.input)
                _uiState.update { it.copy(response = result, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = e.message ?: "Something went wrong. Please try again.",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun clear() {
        _uiState.update { AssistantUiState() }
    }
}
