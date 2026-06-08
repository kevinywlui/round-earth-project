package com.roundearth.bikecomputer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roundearth.bikecomputer.data.BikeRepository
import com.roundearth.bikecomputer.data.PreferencesStore
import com.roundearth.bikecomputer.data.db.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SettingsUiState(
    val wheelCircumferenceM: Double = PreferencesStore.DEFAULT_CIRCUMFERENCE,
    val useImperial: Boolean = true,
    val magneticDeclinationDeg: Double = PreferencesStore.DEFAULT_DECLINATION,
    val headingOffsetDeg: Double = PreferencesStore.DEFAULT_HEADING_OFFSET,
    val recordedEventCount: Int = 0,
    val sessions: List<SessionSummary> = emptyList(),
)

class SettingsViewModel(
    private val prefs: PreferencesStore,
    private val repository: BikeRepository,
) : ViewModel() {

    // Fold the two repository flows into a pair so this stays within the 5-arg typed
    // combine; the prefs make up the other four.
    private val recorded = combine(repository.recordedEventCount, repository.sessions) { count, sessions ->
        count to sessions
    }

    val uiState = combine(
        prefs.wheelCircumferenceMeters,
        prefs.useImperial,
        prefs.magneticDeclinationDeg,
        prefs.headingOffsetDeg,
        recorded,
    ) { circumference, imperial, declination, offset, (count, sessions) ->
        SettingsUiState(
            wheelCircumferenceM = circumference,
            useImperial = imperial,
            magneticDeclinationDeg = declination,
            headingOffsetDeg = offset,
            recordedEventCount = count,
            sessions = sessions,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setWheelCircumference(meters: Double) {
        viewModelScope.launch { prefs.setWheelCircumference(meters) }
    }

    fun setUseImperial(imperial: Boolean) {
        viewModelScope.launch { prefs.setUseImperial(imperial) }
    }

    fun setMagneticDeclination(degrees: Double) {
        viewModelScope.launch { prefs.setMagneticDeclination(degrees) }
    }

    fun setHeadingOffset(degrees: Double) {
        viewModelScope.launch { prefs.setHeadingOffset(degrees) }
    }

    /**
     * Streams the CSV off the main thread, then hands the file to [onReady] for sharing.
     * Writes to a temp file and renames it onto [target] only after a complete write, so a
     * cancellation mid-export (e.g. the user leaves the screen) can never leave a truncated
     * "final" file that a later share would silently pick up. [onError] runs (on the caller's
     * dispatcher) when the export fails so the UI can avoid sharing stale/absent bytes.
     */
    fun exportCsv(target: File, onReady: (File) -> Unit, onError: (Throwable) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val tmp = File(target.parentFile, "${target.name}.tmp")
                    try {
                        tmp.bufferedWriter().use { writer -> repository.exportCsvTo(writer) }
                        // The rename is the commit point. If it fails (target locked by a prior
                        // share, odd cache filesystem), fall back to a copy so we never hand the
                        // share sheet a stale or missing "final" file — the exact hole the
                        // temp-then-rename guard exists to close.
                        if (!tmp.renameTo(target)) {
                            // The copy fallback is not atomic: if it throws partway (e.g. disk
                            // full) it can leave a truncated target. Delete that so a later share
                            // can't pick up a partial file; onError still surfaces the failure.
                            try {
                                tmp.copyTo(target, overwrite = true)
                            } catch (e: Throwable) {
                                target.delete()
                                throw e
                            }
                        }
                    } finally {
                        // Drop the temp on any exit (success-via-copy, throw, or cancellation)
                        // so cacheDir doesn't accumulate orphaned .tmp files.
                        tmp.delete()
                    }
                }
            }
            result.fold(onSuccess = { onReady(target) }, onFailure = onError)
        }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    class Factory(
        private val prefs: PreferencesStore,
        private val repository: BikeRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(prefs, repository) as T
    }
}
