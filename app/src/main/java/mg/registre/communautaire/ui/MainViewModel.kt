package mg.registre.communautaire.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mg.registre.communautaire.data.RegistreRepository
import mg.registre.communautaire.data.SupabaseConfig
import mg.registre.communautaire.domain.PermissionEntryInput
import mg.registre.communautaire.domain.RegisterEntry
import mg.registre.communautaire.domain.RegisterType
import mg.registre.communautaire.domain.StandardEntryInput
import mg.registre.communautaire.reminder.CommunityNotificationHelper

data class MainUiState(
    val backendConfigured: Boolean = false,
    val deviceId: String = "",
    val selectedYear: Int = LocalDate.now().year,
    val selectedType: RegisterType = RegisterType.R2,
    val entries: List<RegisterEntry> = emptyList(),
    val openMovements: List<RegisterEntry> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val integrityOk: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val fontScale: Float = 1f,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RegistreRepository(application)
    private val prefs = application.getSharedPreferences("settings", 0)
    private val _uiState = MutableStateFlow(
        MainUiState(
            backendConfigured = SupabaseConfig.isConfigured(),
            deviceId = repository.deviceId,
            fontScale = prefs.getFloat("font_scale", 1f),
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var observationJob: Job? = null
    private var openMovementsJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(loading = false)
        if (_uiState.value.backendConfigured) {
            restartObservation()
            restartOpenMovements()
            startForegroundCommunityAlerts()
        }
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

    fun clearNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    fun saveStandard(input: StandardEntryInput, onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.selectedYear != LocalDate.now().year) {
            _uiState.value = state.copy(error = "Cette année est clôturée et reste en lecture seule.")
            return
        }
        if (!state.integrityOk) {
            _uiState.value = state.copy(
                error = "Enregistrement bloqué : un doublon de numéro a été détecté dans ce cahier."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saving = true, error = null)
            runCatching {
                repository.createStandard(state.selectedYear, state.selectedType, input)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    notice = if (input.messageKind == RegisterEntry.MESSAGE_AVAILABILITY)
                        "Message de disponibilité enregistré et lié au déplacement sélectionné."
                    else null,
                )
                onSaved()
                restartObservation()
                restartOpenMovements()
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
        if (!state.integrityOk) {
            _uiState.value = state.copy(
                error = "Enregistrement bloqué : un doublon de numéro a été détecté dans /3.PERM."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saving = true, error = null)
            runCatching {
                repository.createPermission(state.selectedYear, input)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    notice = "Permission enregistrée. Il faut maintenant créer dans /2 le message de déplacement « déplacement perm »."
                )
                onSaved()
                restartObservation()
                restartOpenMovements()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = it.message ?: "Enregistrement impossible."
                )
            }
        }
    }

    fun cancelEntry(entryId: String, reason: String) {
        viewModelScope.launch {
            runCatching { repository.cancelEntry(entryId, reason) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(notice = "Entrée annulée. Son numéro reste réservé et ne sera pas réutilisé.")
                    restartObservation()
                    restartOpenMovements()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        error = it.message ?: "Impossible d'annuler l'entrée."
                    )
                }
        }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteEntry(entryId) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        notice = "Entrée supprimée de l'affichage. Le numéro reste définitivement réservé dans l'historique."
                    )
                    restartObservation()
                    restartOpenMovements()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        error = it.message ?: "Impossible de supprimer l'entrée."
                    )
                }
        }
    }

    fun closeMovement(entryId: String) {
        viewModelScope.launch {
            runCatching { repository.closeMovement(entryId) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        notice = "Retour confirmé. Les rappels locaux de ce déplacement sont arrêtés."
                    )
                    restartObservation()
                    restartOpenMovements()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        error = it.message ?: "Impossible de clôturer le déplacement."
                    )
                }
        }
    }

    private fun restartObservation() {
        if (!_uiState.value.backendConfigured) return
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            val state = _uiState.value
            runCatching { repository.checkIntegrity(state.selectedYear, state.selectedType) }
                .onSuccess { ok ->
                    _uiState.value = _uiState.value.copy(integrityOk = ok)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(integrityOk = true)
                }

            repository.observeEntries(state.selectedYear, state.selectedType).collect { entries ->
                val localDuplicates = entries
                    .mapNotNull { it.officialNumber }
                    .groupingBy { it }
                    .eachCount()
                    .any { it.value > 1 }

                _uiState.value = _uiState.value.copy(
                    entries = entries,
                    loading = false,
                    integrityOk = _uiState.value.integrityOk && !localDuplicates,
                    error = if (localDuplicates)
                        "Doublon de numéro détecté. Les nouveaux enregistrements sont bloqués."
                    else _uiState.value.error,
                )
            }
        }
    }

    private fun restartOpenMovements() {
        if (!_uiState.value.backendConfigured) return
        openMovementsJob?.cancel()
        openMovementsJob = viewModelScope.launch {
            repository.observeOpenMovements(LocalDate.now().year).collect { movements ->
                _uiState.value = _uiState.value.copy(openMovements = movements)
            }
        }
    }

    private fun startForegroundCommunityAlerts() {
        viewModelScope.launch {
            while (isActive) {
                runCatching { repository.checkCommunityNotifications() }
                    .onSuccess { entries ->
                        CommunityNotificationHelper.notifyNewEntries(getApplication(), entries)
                    }
                delay(10_000)
            }
        }
    }
}
