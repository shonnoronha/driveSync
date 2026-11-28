package io.github.drivesync.feature.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.pairs.PairRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class ActivityViewModel
    @Inject
    constructor(
        private val activity: ActivityRepository,
        private val pairs: PairRepository,
    ) : ViewModel() {
        private val filter = MutableStateFlow(ActivityFilter.ALL)

        val state: StateFlow<ActivityUiState> =
            combine(activity.observe(), pairs.observePairs(), filter) { entries, list, selected ->
                val zone = ZoneId.systemDefault()
                ActivityUiState(
                    loading = false,
                    filter = selected,
                    days = activityDays(entries, list.associate { it.id to it.name }, selected, zone),
                    today = LocalDate.now(zone),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ActivityUiState())

        fun setFilter(value: ActivityFilter) {
            filter.value = value
        }

        /** The log as shared text (FR-M2), with the filter the screen shows. */
        suspend fun exportText(
            hideNames: Boolean,
            header: ExportHeader,
        ): String {
            val names = pairs.observePairs().first().associate { it.id to it.name }
            val entries = activity.observe(limit = MAX_EXPORT_ROWS * 2).first()
            return activityLogText(entries, names, filter.value, hideNames, header)
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

@Composable
fun ActivityRoute(viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ActivityScreen(state, viewModel::setFilter, onShare = { hideNames ->
        scope.launch {
            val text = viewModel.exportText(hideNames, exportHeader(context))
            context.shareLog(text)
        }
    })
}

private fun exportHeader(context: Context): ExportHeader {
    val version =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    return ExportHeader(
        appVersion = version ?: "unknown",
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
    )
}

private fun Context.shareLog(text: String) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.activity_share_subject))
            .putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, getString(R.string.activity_share)))
}
