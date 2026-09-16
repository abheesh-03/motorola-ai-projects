package com.sai.mobileaiassistant

data class AssistantUiState(
    val input: String = "",
    val response: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)
