package com.mygallery.app.pro

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mygallery.app.R
import java.text.DateFormat
import java.util.Date

/** Remembers when a backup last succeeded and what the last failure was. */
object BackupHistory {
    private const val OK_AT = "last_backup_ok_at"
    private const val ERR_MESSAGE = "last_backup_error"
    private const val ERR_AT = "last_backup_error_at"

    data class Summary(val lastSuccessAt: Long?, val lastErrorMessage: String?, val lastErrorAt: Long?)

    fun record(context: Context, result: BackupOperationResult) {
        val editor = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        val now = System.currentTimeMillis()
        if (result.success) {
            editor.putLong(OK_AT, now)
        } else {
            editor.putString(ERR_MESSAGE, result.message).putLong(ERR_AT, now)
        }
        editor.apply()
    }

    fun load(context: Context): Summary {
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return Summary(
            lastSuccessAt = p.getLong(OK_AT, 0L).takeIf { it > 0L },
            lastErrorMessage = p.getString(ERR_MESSAGE, null),
            lastErrorAt = p.getLong(ERR_AT, 0L).takeIf { it > 0L },
        )
    }
}

/** One-line status under the backup buttons. [refreshKey] changes after each operation. */
@Composable
fun BackupHistoryLine(refreshKey: Any?) {
    val context = LocalContext.current
    val summary = remember(refreshKey) { BackupHistory.load(context) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Text(
        summary.lastSuccessAt?.let { stringResource(R.string.s_last_backup, format.format(Date(it))) }
            ?: stringResource(R.string.s_no_backup_yet),
        style = MaterialTheme.typography.bodySmall,
    )
    val error = summary.lastErrorMessage
    if (error != null && summary.lastErrorAt != null) {
        Text(
            stringResource(R.string.s_last_backup_failed, format.format(Date(summary.lastErrorAt)), error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
