package me.xiaozhi.androidclient.integration

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class TermuxCommandResult(
    val label: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val errorCode: Int?,
    val errorMessage: String?,
)

object TermuxCommandEvents {
    private val _events = MutableSharedFlow<TermuxCommandResult>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun publish(result: TermuxCommandResult) {
        _events.tryEmit(result)
    }
}
