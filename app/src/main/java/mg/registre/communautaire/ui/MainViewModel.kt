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
import mg.registre.communautaire.data.OfflineAwareRegistreRepository
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
    val bootstrapRequired: Boolean = false,
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
    private val repository = OfflineAwareRegistreRepository(application)
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
        if (_uiState.value.backendConfigured) restoreSession()
        else _uiState.value = _uiState.value.copy(authLoading = false, loading = false)
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            fail("Email et mot de passe requis.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(authLoading = true, error = null)
            runCatching { auth.signIn(email, password) }
                .onSuccess(::applySession)
                .onFailure { authFailure(it, "Connexion impossible.") }
        }
    }

    fun signUp(email: String, password: String) {
        if (email.isBlank() || password.length < 8) {
            fail("Utilisez un email valide et un mot de passe d'au moins 8 caractères.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(authLoading = true, error = null)
            runCatching { auth.signUp(email, password) }
                .onSuccess { result ->
                    if (result.signedIn) {
                        auth.restoreSession()?.let(::applySession)
                            ?: run { _uiState.value = _uiState.value.copy(authLoading = false) }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            authLoading = false,
                            notice = if (result.needsEmailConfirmation)
                                "Compte créé. Confirmez votre email puis connectez-vous."
                            else "Compte créé. Connectez-vous.",
                        )
                    }
                }
                .onFailure { authFailure(it, "Création du compte impossible.") }
        }
    }

    fun bootstrapAdmin(code: String) {
        if (code.isBlank()) {
            fail("Saisissez le code d'amorçage administrateur.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(authLoading = true, error = null)
            runCatching { auth.bootstrapAdmin(code) }
                .onSuccess(::applySession)
                .onFailure { authFailure(it, "Impossible d'activer le premier administrateur.") }
        }
    }

    fun signOut() {
        stopRegistryJobs()
        auth.signOut()
        _uiState.value = MainUiState(
            backendConfigured = SupabaseConfig.isConfigured(),
            deviceId = repository.deviceId,
            authLoading = false,
            loading = false,
            fontScale = prefs.getFloat("font_scale", 1f),
        )
    }

    fun addMember(email: String, role: AccessRole) {
        if (_uiState.value.role != AccessRole.ADMIN) {
            fail("Action réservée à l'administrateur.")
            return
        }
        viewModelScope.launch {
            runCatching { auth.addMember(email, role) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        notice = "Accès ${role.wireValue} accordé à $email."
                    )
                }
                .onFailure { fail(it.message ?: "Impossible d'ajouter ce membre.") }
        }
    }

    fun selectType(type: RegisterType) {
        _uiState.value = _uiState.value.copy(selectedType = type, counterLoading = true)
        restartObservation()
        restartOpenMovements()
        refreshCounterStatus()
    }

    fun selectYear(year: Int) {
        _uiState.value = _uiState.value.copy(selectedYear = year, counterLoading = true)
        restartObservation()
        refreshCounterStatus()
    }

    fun initializeCounter(lastUsedNumber: Long, onReady: () -> Unit) {
        val state = _uiState.value
        if (state.role != AccessRole.ADMIN) {
            fail("Seul un administrateur peut initialiser un cahier.")
            return
        }
        if (state.selectedYear != LocalDate.now().year) {
            fail("Seule l'année courante peut être initialisée.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(counterLoading = true, error = null)
            runCatching {
                repository.initializeCounter(
                    state.selectedYear,
                    state.selectedType,
                    lastUsedNumber.coerceAtLeast(0L),
                )
            }.onSuccess { status ->
                _uiState.value = _uiState.value.copy(
                    counterInitialized = status.initialized,
                    lastOfficialNumber = status.lastNumber,
                    nextOfficialNumber = status.nextNumber,
                    counterLoading = false,
                    notice = if (status.lastNumber == 0L)
                        "Numérotation initialisée : le premier numéro sera 1${state.selectedType.code}."
                    else
                        "Numérotation initialisée après ${status.lastNumber}${state.selectedType.code}. " +
                            "Le prochain numéro sera ${status.nextNumber}${state.selectedType.code}.",
                )
                onReady()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    counterLoading = false,
                    error = it.message ?: "Impossible d'initialiser la numérotation.",
                )
            }
        }
    }

    fun setFontScale(scale: Float) {
        val safe = scale.coerceIn(0.85f, 1.6f)
        prefs.edit().putFloat("font_scale", safe).apply()
        _uiState.value = _uiState.value.copy(fontScale = safe)
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
    fun clearNotice() { _uiState.value = _uiState.value.copy(notice = null) }

    fun saveStandard(input: StandardEntryInput, onSaved: () -> Unit) {
        if (!canWriteCurrentYear()) return
        if (!_uiState.value.integrityOk) {
            fail("Enregistrement bloqué : un doublon de numéro a été détecté dans ce cahier.")
            return
        }
        val state = _uiState.value
        savingAction(
            action = { repository.createStandard(state.selectedYear, state.selectedType, input) },
            failure = "Enregistrement impossible.",
        ) {
            _uiState.value = _uiState.value.copy(
                notice = if (input.messageKind == RegisterEntry.MESSAGE_AVAILABILITY) {
                    if (input.relatedMovementId.isBlank())
                        "Message de disponibilité simple enregistré."
                    else "Message de disponibilité enregistré et lié au déplacement sélectionné."
                } else null,
            )
            onSaved()
            refreshAfterMutation()
        }
    }

    fun savePermission(input: PermissionEntryInput, onSaved: () -> Unit) {
        if (!canWriteCurrentYear()) return
        if (!_uiState.value.integrityOk) {
            fail("Enregistrement bloqué : un doublon de numéro a été détecté dans /3.PERM.")
            return
        }
        val state = _uiState.value
        savingAction(
            action = { repository.createPermission(state.selectedYear, input) },
            failure = "Enregistrement impossible.",
        ) {
            _uiState.value = _uiState.value.copy(
                notice = "Permission enregistrée. Il faut maintenant créer dans /2 ou /4 le message de déplacement « déplacement perm »."
            )
            onSaved()
            refreshAfterMutation()
        }
    }

    fun cancelEntry(entryId: String, reason: String) {
        writeAction(
            action = { repository.cancelEntry(entryId, reason) },
            success = "Entrée annulée. Son numéro reste définitivement réservé.",
            failure = "Impossible d'annuler l'entrée.",
        ) { refreshAfterMutation() }
    }

    fun deleteEntry(entryId: String) {
        writeAction(
            action = { repository.deleteEntry(entryId) },
            success = "Entrée supprimée de la liste active. Son numéro reste définitivement réservé.",
            failure = "Impossible de supprimer l'entrée.",
        ) { refreshAfterMutation() }
    }

    fun closeMovement(entryId: String) {
        writeAction(
            action = { repository.closeMovement(entryId) },
            success = "Retour confirmé. Les rappels locaux de ce déplacement sont arrêtés.",
            failure = "Impossible de clôturer le déplacement.",
        ) {
            restartObservation()
            restartOpenMovements()
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
                            bootstrapRequired = false,
                            loading = false,
                        )
                    } else applySession(session)
                }
                .onFailure { authFailure(it, "Session expirée.") }
        }
    }

    private fun applySession(session: AuthSession) {
        _uiState.value = _uiState.value.copy(
            authLoading = false,
            authenticated = true,
            accessApproved = session.approved,
            bootstrapRequired = session.bootstrapRequired,
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
        listOf(observationJob, openMovementsJob, availablePermissionsJob, communityAlertsJob)
            .forEach { it?.cancel() }
        observationJob = null
        openMovementsJob = null
        availablePermissionsJob = null
        communityAlertsJob = null
    }

    private fun refreshCounterStatus() {
        if (!ready()) return
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(counterLoading = true)
            runCatching { repository.getCounterStatus(state.selectedYear, state.selectedType) }
                .onSuccess { status ->
                    _uiState.value = _uiState.value.copy(
                        counterInitialized = status.initialized,
                        lastOfficialNumber = status.lastNumber,
                        nextOfficialNumber = status.nextNumber,
                        counterLoading = false,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(counterLoading = false)
                }
        }
    }

    private fun restartObservation() {
        if (!ready()) return
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            val state = _uiState.value
            runCatching { repository.checkIntegrity(state.selectedYear, state.selectedType) }
                .onSuccess { ok -> _uiState.value = _uiState.value.copy(integrityOk = ok) }

            repository.observeEntries(state.selectedYear, state.selectedType).collect { entries ->
                val duplicate = entries.mapNotNull { it.officialNumber }
                    .groupingBy { it }.eachCount().any { it.value > 1 }
                _uiState.value = _uiState.value.copy(
                    entries = entries,
                    loading = false,
                    integrityOk = _uiState.value.integrityOk && !duplicate,
                    error = if (duplicate)
                        "Doublon de numéro détecté. Les nouveaux enregistrements sont bloqués."
                    else _uiState.value.error,
                )
            }
        }
    }

    private fun restartOpenMovements() {
        if (!ready()) return
        openMovementsJob?.cancel()
        val type = _uiState.value.selectedType
        if (type != RegisterType.R2 && type != RegisterType.R4) {
            _uiState.value = _uiState.value.copy(openMovements = emptyList())
            return
        }
        openMovementsJob = viewModelScope.launch {
            repository.observeOpenMovements(LocalDate.now().year, type).collect {
                _uiState.value = _uiState.value.copy(openMovements = it)
            }
        }
    }

    private fun restartAvailablePermissions() {
        if (!ready()) return
        availablePermissionsJob?.cancel()
        availablePermissionsJob = viewModelScope.launch {
            repository.observeAvailablePermissions(LocalDate.now().year).collect {
                _uiState.value = _uiState.value.copy(availablePermissions = it)
            }
        }
    }

    private fun startForegroundCommunityAlerts() {
        communityAlertsJob?.cancel()
        communityAlertsJob = viewModelScope.launch {
            while (isActive) {
                runCatching { repository.checkCommunityNotifications() }
                    .onSuccess { CommunityNotificationHelper.notifyNewEntries(getApplication(), it) }
                delay(60_000)
            }
        }
    }

    private fun refreshAfterMutation() {
        restartObservation()
        restartOpenMovements()
        restartAvailablePermissions()
        refreshCounterStatus()
    }

    private fun canWriteCurrentYear(): Boolean {
        val state = _uiState.value
        if (state.role?.canWrite != true) {
            fail("Votre rôle est en lecture seule.")
            return false
        }
        if (state.selectedYear != LocalDate.now().year) {
            fail("Cette année est clôturée et reste en lecture seule.")
            return false
        }
        return true
    }

    private fun writeAction(
        action: suspend () -> Unit,
        success: String,
        failure: String,
        after: () -> Unit,
    ) {
        if (_uiState.value.role?.canWrite != true) {
            fail("Votre rôle est en lecture seule.")
            return
        }
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(notice = success)
                    after()
                }
                .onFailure { fail(it.message ?: failure) }
        }
    }

    private fun <T> savingAction(
        action: suspend () -> T,
        failure: String,
        success: (T) -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saving = true, error = null)
            runCatching { action() }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(saving = false)
                    success(it)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        saving = false,
                        error = it.message ?: failure,
                    )
                }
        }
    }

    private fun ready(): Boolean =
        _uiState.value.backendConfigured && _uiState.value.accessApproved

    private fun fail(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    private fun authFailure(error: Throwable, fallback: String) {
        _uiState.value = _uiState.value.copy(
            authLoading = false,
            authenticated = false,
            accessApproved = false,
            bootstrapRequired = false,
            loading = false,
            error = error.message ?: fallback,
        )
    }
}
