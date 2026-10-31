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
import mg.registre.communautaire.security.AccessRole
import mg.registre.communautaire.security.AuthSession
import mg.registre.communautaire.security.AuthSessionManager

data class MainUiState(
    val backendConfigured: Boolean = false,
    val deviceId: String = "",
    val authLoading: Boolean = true,
    val authenticated: Boolean = false,
    val accessApproved: Boolean = false,
    val userId: String = "",
    val email: String = "",
    val role: AccessRole? = null,
    val selectedYear: Int = LocalDate.now().year,
    val selectedType: RegisterType = RegisterType.R2,
    val entries: List<RegisterEntry> = emptyList(),
    val openMovements: List<RegisterEntry> = emptyList(),
    val availablePermissions: List<RegisterEntry> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val integrityOk: Boolean = true,
    val counterInitialized: Boolean = false,
    val lastOfficialNumber: Long = 0L,
    val nextOfficialNumber: Long = 1L,
    val counterLoading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val fontScale: Float = 1f,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RegistreRepository(application)
    private val auth = AuthSessionManager(application)
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
    private var availablePermissionsJob: Job? = null
    private var communityAlertsJob: Job? = null

    init {
        if (!_uiState.value.backendConfigured) {
            _uiState.value = _uiState.value.copy(authLoading = false, loading = false)
        } else {
            restoreSession()
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Email et mot de passe requis.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(authLoading = true, error = null)
            runCatching { auth.signIn(email, password) }
                .onSuccess(::applySession)
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        authLoading = false,
                        authenticated = false,
                        accessApproved = false,
                        error = it.message ?: "Connexion impossible.",
                    )
                }
        }
    }

    fun signUp(email: String, password: String) {
        if (email.isBlank() || password.length < 8) {
            _uiState.value = _uiState.value.copy(
                error = "Utilisez un email valide et un mot de passe d'au moins 8 caractères."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(authLoading = true, error = null)
            runCatching { auth.signUp(email, password) }
                .onSuccess { result ->
                    if (result.signedIn) {
                        val session = auth.restoreSession()
                        if (session != null) applySession(session)
                        else _uiState.value = _uiState.value.copy(authLoading = false)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            authLoading = false,
                            notice = if (result.needsEmailConfirmation)
                                "Compte créé. Confirmez votre email puis connectez-vous."
                            else
                                "Compte créé. Connectez-vous.",
                        )
                    }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        authLoading = false,
                        error = it.message ?: "Création du compte impossible.",
                    )
                }
        }
    }

    fun signOut() {
        stopRegistryJobs()
        auth.signOut()
        _uiState.value = _uiState.value.copy(
            authLoading = false,
            authenticated = false,
            accessApproved = false,
            userId = "",
            email = "",
            role = null,
            entries = emptyList(),
            openMovements = emptyList(),
            availablePermissions = emptyList(),
            counterInitialized = false,
            lastOfficialNumber = 0,
            nextOfficialNumber = 1,
            loading = false,
            error = null,
        )
    }

    fun addMember(email: String, role: AccessRole) {
        if (_uiState.value.role != AccessRole.ADMIN) {
            _uiState.value = _uiState.value.copy(error = "Action réservée à l'administrateur.")
            return
        }
        viewModelScope.launch {
            runCatching { auth.addMember(email, role) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        notice = "Accès ${role.wireValue} accordé à $email."
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        error = it.message ?: "Impossible d'ajouter ce membre."
                    )
                }
        }
    }

    fun selectType(type: RegisterType) {
        _uiState.value = _uiState.value.copy(
            selectedType = type,
            counterLoading = true,
        )
        restartObservation()
        restartOpenMovements()
        refreshCounterStatus()
    }

    fun selectYear(year: Int) {
        _uiState.value = _uiState.value.copy(
            selectedYear = year,
            counterLoading = true,
        )
        restartObservation()
        refreshCounterStatus()
    }

    fun initializeCounter(lastUsedNumber: Long, onReady: () -> Unit) {
        val state = _uiState.value
        if (state.role != AccessRole.ADMIN) {
            _uiState.value = state.copy(error = "Seul un administrateur peut initialiser un cahier.")
            return
        }
        if (state.selectedYear != LocalDate.now().year) {
            _uiState.value = state.copy(
                error = "Seule l'année courante peut être initialisée."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                counterLoading = true,
                error = null,
            )
            runCatching {
                repository.initializeCounter(
                    year = state.selectedYear,
                    type = state.selectedType,
                    lastUsedNumber = lastUsedNumber.coerceAtLeast(0L),
                )
            }.onSuccess { status ->
                val notice = if (status.lastNumber == 0L) {
                    "Numérotation initialisée : le premier numéro sera 1" +
                        state.selectedType.code + "."
                } else {
                    "Numérotation initialisée après " +
                        status.lastNumber +
                        state.selectedType.code +
                        ". Le prochain numéro sera " +
                        status.nextNumber +
                        state.selectedType.code + "."
                }

                _uiState.value = _uiState.value.copy(
                    counterInitialized = status.initialized,
                    lastOfficialNumber = status.lastNumber,
                    nextOfficialNumber = status.nextNumber,
                    counterLoading = false,
                    notice = notice,
                )
                onReady()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    counterLoading = false,
                    error = it.message ?: "Impossible d'initialiser la numérotation."
                )
            }
        }
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
        if (state.role?.canWrite != true) {
            _uiState.value = state.copy(error = "Votre rôle est en lecture seule.")
            return
        }
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
                    notice = if (input.messageKind == RegisterEntry.MESSAGE_AVAILABILITY) {
                        if (input.relatedMovementId.isBlank())
                            "Message de disponibilité simple enregistré."
                        else
                            "Message de disponibilité enregistré et lié au déplacement sélectionné."
                    } else null,
                )
                onSaved()
                restartObservation()
                restartOpenMovements()
                restartAvailablePermissions()
                refreshCounterStatus()
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
        if (state.role?.canWrite != true) {
            _uiState.value = state.copy(error = "Votre rôle est en lecture seule.")
            return
        }
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
                    notice = "Permission enregistrée. Il faut maintenant créer dans /2 ou /4 le message de déplacement « déplacement perm »."
                )
                onSaved()
                restartObservation()
                restartOpenMovements()
                restartAvailablePermissions()
                refreshCounterStatus()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = it.message ?: "Enregistrement impossible."
                )
            }
        }
    }

    fun cancelEntry(entryId: String, reason: String) {
        if (_uiState.value.role?.canWrite != true) {
            _uiState.value = _uiState.value.copy(error = "Votre rôle est en lecture seule.")
            return
        }
        viewModelScope.launch {
            runCatching { repository.cancelEntry(entryId, reason) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        notice = "Entrée annulée. Son numéro reste définitivement réservé."
                    )
                    restartObservation()
                    restartOpenMovements()
                    restartAvailablePermissions()
                    refreshCounterStatus()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        error = it.message ?: "Impossible d'annuler l'entrée."
                    )
                }
        }
    }

    fun deleteEntry(entryId: String) {
        if (_uiState.value.role?.canWrite != true) {
            _uiState.value = _uiState.value.copy(error = "Votre rôle est en lecture seule.")
            return
        }
        viewModelScope.launch {
            runCatching { repository.deleteEntry(entryId) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        notice = "Entrée supprimée de la liste active. Son numéro reste définitivement réservé."
                    )
                    restartObservation()
                    restartOpenMovements()
                    refreshCounterStatus()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        error = it.message ?: "Impossible de supprimer l'entrée."
                    )
                }
        }
    }

    fun closeMovement(entryId: String) {
        if (_uiState.value.role?.canWrite != true) {
            _uiState.value = _uiState.value.copy(error = "Votre rôle est en lecture seule.")
            return
        }
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

    private fun restoreSession() {
        viewModelScope.launch {
            runCatching { auth.restoreSession() }
                .onSuccess { session ->
                    if (session == null) {
                        _uiState.value = _uiState.value.copy(
                            authLoading = false,
                            authenticated = false,
                            accessApproved = false,
                            loading = false,
                        )
                    } else {
                        applySession(session)
                    }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        authLoading = false,
                        authenticated = false,
                        accessApproved = false,
                        loading = false,
                    )
                }
        }
    }

    private fun applySession(session: AuthSession) {
        _uiState.value = _uiState.value.copy(
            authLoading = false,
            authenticated = true,
            accessApproved = session.approved,
            userId = session.userId,
            email = session.email,
            role = session.role,
            loading = session.approved,
            error = null,
        )

        if (session.approved) startRegistryJobs() else stopRegistryJobs()
    }

    private fun startRegistryJobs() {
        restartObservation()
        restartOpenMovements()
        restartAvailablePermissions()
        refreshCounterStatus()
        startForegroundCommunityAlerts()
    }

    private fun stopRegistryJobs() {
        observationJob?.cancel()
        openMovementsJob?.cancel()
        availablePermissionsJob?.cancel()
        communityAlertsJob?.cancel()
        observationJob = null
        openMovementsJob = null
        availablePermissionsJob = null
        communityAlertsJob = null
    }

    private fun refreshCounterStatus() {
        if (!_uiState.value.backendConfigured || !_uiState.value.accessApproved) return

        val year = _uiState.value.selectedYear
        val type = _uiState.value.selectedType

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(counterLoading = true)
            runCatching {
                repository.getCounterStatus(year, type)
            }.onSuccess { status ->
                _uiState.value = _uiState.value.copy(
                    counterInitialized = status.initialized,
                    lastOfficialNumber = status.lastNumber,
                    nextOfficialNumber = status.nextNumber,
                    counterLoading = false,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    counterLoading = false,
                    error = it.message ?: "Impossible de vérifier la numérotation."
                )
            }
        }
    }

    private fun restartObservation() {
        if (!_uiState.value.backendConfigured || !_uiState.value.accessApproved) return
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
        if (!_uiState.value.backendConfigured || !_uiState.value.accessApproved) return
        openMovementsJob?.cancel()

        val type = _uiState.value.selectedType
        if (type != RegisterType.R2 && type != RegisterType.R4) {
            _uiState.value = _uiState.value.copy(openMovements = emptyList())
            return
        }

        openMovementsJob = viewModelScope.launch {
            repository.observeOpenMovements(LocalDate.now().year, type).collect { movements ->
                _uiState.value = _uiState.value.copy(openMovements = movements)
            }
        }
    }

    private fun restartAvailablePermissions() {
        if (!_uiState.value.backendConfigured || !_uiState.value.accessApproved) return
        availablePermissionsJob?.cancel()
        availablePermissionsJob = viewModelScope.launch {
            repository.observeAvailablePermissions(LocalDate.now().year).collect { permissions ->
                _uiState.value = _uiState.value.copy(availablePermissions = permissions)
            }
        }
    }

    private fun startForegroundCommunityAlerts() {
        communityAlertsJob?.cancel()
        communityAlertsJob = viewModelScope.launch {
            while (isActive) {
                runCatching { repository.checkCommunityNotifications() }
                    .onSuccess { entries ->
                        CommunityNotificationHelper.notifyNewEntries(getApplication(), entries)
                    }
                delay(60_000)
            }
        }
    }
}
