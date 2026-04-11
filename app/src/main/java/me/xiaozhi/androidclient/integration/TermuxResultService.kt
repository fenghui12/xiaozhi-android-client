package me.xiaozhi.androidclient.integration

import android.app.IntentService
import android.content.Intent

private const val EXTRA_RESULT_BUNDLE = "com.termux.RUN_COMMAND_RESULT"
private const val EXTRA_RESULT_STDOUT = "stdout"
private const val EXTRA_RESULT_STDERR = "stderr"
private const val EXTRA_RESULT_EXIT_CODE = "exitCode"
private const val EXTRA_RESULT_ERROR_CODE = "err"
private const val EXTRA_RESULT_ERROR_MESSAGE = "errmsg"
private const val EXTRA_RESULT_LABEL = "label"

class TermuxResultService : IntentService("TermuxResultService") {
    override fun onHandleIntent(intent: Intent?) {
        val result = intent?.getBundleExtra(EXTRA_RESULT_BUNDLE) ?: return
        TermuxCommandEvents.publish(
            TermuxCommandResult(
                label = result.getString(EXTRA_RESULT_LABEL).orEmpty(),
                stdout = result.getString(EXTRA_RESULT_STDOUT).orEmpty(),
                stderr = result.getString(EXTRA_RESULT_STDERR).orEmpty(),
                exitCode = if (result.containsKey(EXTRA_RESULT_EXIT_CODE)) {
                    result.getInt(EXTRA_RESULT_EXIT_CODE)
                } else {
                    null
                },
                errorCode = if (result.containsKey(EXTRA_RESULT_ERROR_CODE)) {
                    result.getInt(EXTRA_RESULT_ERROR_CODE)
                } else {
                    null
                },
                errorMessage = result.getString(EXTRA_RESULT_ERROR_MESSAGE),
            ),
        )
    }
}
