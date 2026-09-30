package mg.registre.communautaire.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mg.registre.communautaire.data.RegistreRepository
import mg.registre.communautaire.data.SupabaseConfig
import mg.registre.communautaire.domain.PermissionEntryInput
import mg.registre.communautaire.domain.RegisterEntry
import mg.registre.communautaire.domain.RegisterType
import mg.registre.communautaire.domain.StandardEntryInput

data class MainUiState(
    val backendConfigured: Boolean = false,
    val selectedYear: Int = LocalDate.now().year,
    val selectedType: RegisterType = RegisterType.R2,
    val entries: List<RegisterEntry> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val fontScale: Float = 1f,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RegistreRepository(application)
    private val prefs = application.getSharedPreferences("settings", 0)
    private val _uiState = MutableStateFlow(
        MainUiState(
            backendConfigured = SupabaseConfig.isConfigured(),
            fontScale = prefs.getFloat("font_scale", 1f),
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var observationJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(loading = false)
        if (_uiState.value.backendConfigured) restartObservation()
    }

    fun selectType(type: RegisterType) {
        _uiState.value = _uiState.value.copy(selectedType = type)
        restartObservation()
    }

    fun selectYear(year: Int) {
        _uiState.value = _uiState.value.copy(selectedYear = year)
        restartObservation()
    }

    fun setFontScale(scale: Float) {
        val safe = scale.coerceIn(0.85f, 1.6f)
        prefs.edit().putFloat("font_scale", safe).apply()
        _uiState.value = _uiState.value.copy(fontScale = safe)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun saveStandard(input: StandardEntryInput, onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.selectedYear != LocalDate.now().year) {
            _uiState.value = state.copy(error = "Cette année est clôturée et reste en lecture seule.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saving = true, error = null)
            runCatching {
                repository.createStandard(state.selectedYear, state.selectedType, input)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(saving = false)
                onSaved()
                restartObservation()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = it.message ?: "Enregistrement impossible."
                )
            }
        }
    }

    fun savePermission(input: PermissionEntryInput, onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.selectedYear != LocalDate.now().year) {
            _uiState.value = state.copy(error = "Cette année est clôturée et reste en lecture seule.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saving = true, error = null)
            runCatching {
                repository.createPermission(state.selectedYear, input)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(saving = false)
                onSaved()
                restartObservation()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = it.message ?: "Enregistrement impossible."
                )
            }
        }
    }

    private fun restartObservation() {
        if (!_uiState.value.backendConfigured) return
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            val state = _uiState.value
            repository.observeEntries(state.selectedYear, state.selectedType).collect { entries ->
                _uiState.value = _uiState.value.copy(entries = entries, loading = false)
            }
        }
    }
}
