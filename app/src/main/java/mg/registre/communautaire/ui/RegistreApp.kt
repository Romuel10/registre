package mg.registre.communautaire.ui

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import mg.registre.communautaire.domain.MovementRules
import mg.registre.communautaire.domain.PermissionEntryInput
import mg.registre.communautaire.domain.RegisterEntry
import mg.registre.communautaire.domain.RegisterType
import mg.registre.communautaire.domain.StandardEntryInput

private enum class Page { LIST, FORM, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistreApp(
    state: MainUiState,
    onSelectType: (RegisterType) -> Unit,
    onSelectYear: (Int) -> Unit,
    onFontScale: (Float) -> Unit,
    onSaveStandard: (StandardEntryInput, () -> Unit) -> Unit,
    onSavePermission: (PermissionEntryInput, () -> Unit) -> Unit,
    onCloseMovement: (String) -> Unit,
    onCancelEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onClearError: () -> Unit,
    onClearNotice: () -> Unit,
) {
    var page by remember { mutableStateOf(Page.LIST) }
    var query by remember { mutableStateOf("") }

    if (!state.backendConfigured) {
        SupabaseSetupScreen()
        return
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text("Attention") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onClearError) { Text("Fermer") }
            },
        )
    }

    state.notice?.let { message ->
        AlertDialog(
            onDismissRequest = onClearNotice,
            title = { Text("Information") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onClearNotice) { Text("D'accord") }
            },
        )
    }

    val title = when (page) {
        Page.LIST -> "Registre communautaire"
        Page.FORM -> if (state.selectedType == RegisterType.R3PERM) "Nouvelle permission" else "Nouvel enregistrement"
        Page.SETTINGS -> "Lisibilité"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        if (page == Page.LIST) {
                            Text(
                                state.selectedType.title + " · " + state.selectedYear,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (page != Page.LIST) {
                        IconButton(onClick = { page = Page.LIST }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                actions = {
                    if (page == Page.LIST) {
                        IconButton(onClick = { page = Page.SETTINGS }) {
                            Icon(Icons.Default.Settings, contentDescription = "Réglage du texte")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (
                page == Page.LIST &&
                state.selectedYear == LocalDate.now().year &&
                state.integrityOk
            ) {
                ExtendedFloatingActionButton(
                    onClick = { page = Page.FORM },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Nouvelle entrée") },
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = page,
            modifier = Modifier.padding(padding),
            label = "page",
        ) { current ->
            when (current) {
                Page.LIST -> RegisterListPage(
                    state = state,
                    query = query,
                    onQuery = { query = it },
                    onSelectType = onSelectType,
                    onSelectYear = onSelectYear,
                    onCloseMovement = onCloseMovement,
                    onCancelEntry = onCancelEntry,
                    onDeleteEntry = onDeleteEntry,
                )
                Page.FORM -> {
                    if (state.selectedType == RegisterType.R3PERM) {
                        PermissionForm(
                            saving = state.saving,
                            onSave = { input ->
                                onSavePermission(input) { page = Page.LIST }
                            },
                        )
                    } else {
                        StandardForm(
                            type = state.selectedType,
                            saving = state.saving,
                            openMovements = state.openMovements,
                            onSave = { input ->
                                onSaveStandard(input) { page = Page.LIST }
                            },
                        )
                    }
                }
                Page.SETTINGS -> SettingsPage(
                    scale = state.fontScale,
                    onScale = onFontScale,
                )
            }
        }
    }
}

@Composable
private fun SupabaseSetupScreen() {
    Box(
        Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Configuration Supabase requise", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "L'application doit être reliée à Supabase pour partager les numéros officiels entre les téléphones."
                )
                Text(
                    "Vérifiez SUPABASE_URL et SUPABASE_PUBLISHABLE_KEY puis reconstruisez l'APK."
                )
            }
        }
    }
}

@Composable
private fun RegisterListPage(
    state: MainUiState,
    query: String,
    onQuery: (String) -> Unit,
    onSelectType: (RegisterType) -> Unit,
    onSelectYear: (Int) -> Unit,
    onCloseMovement: (String) -> Unit,
    onCancelEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    val now = LocalDate.now().year
    val years = (now downTo now - 4).toList()
    val filtered = state.entries.filter {
        query.isBlank() || listOf(
            it.displayNumber,
            it.pieceNumber,
            it.origin,
            it.label,
            it.observation,
            it.fullName,
            it.matricule,
            it.numberR3,
        ).any { value -> value.contains(query, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Numérotation partagée",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Un seul numéro officiel pour tous les utilisateurs connectés.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!state.integrityOk) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Enregistrement bloqué",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "Un doublon de numéro a été détecté. Les nouvelles entrées sont bloquées jusqu'à correction.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RegisterType.entries) { type ->
                    FilterChip(
                        selected = type == state.selectedType,
                        onClick = { onSelectType(type) },
                        label = { Text(type.code) },
                    )
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(years) { year ->
                    FilterChip(
                        selected = year == state.selectedYear,
                        onClick = { onSelectYear(year) },
                        label = {
                            Text(if (year == now) year.toString() else year.toString() + " · clôturée")
                        },
                    )
                }
            }
        }

        item {
            SummaryCard(state)
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("Rechercher") },
                singleLine = true,
            )
        }

        if (filtered.isEmpty()) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Aucune entrée", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.selectedYear == now)
                                "Le cahier est prêt. La première validation recevra le numéro 1."
                            else
                                "Aucune donnée enregistrée pour cet exercice.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { entry ->
                EntryCard(
                    entry = entry,
                    type = state.selectedType,
                    deviceId = state.deviceId,
                    onCloseMovement = onCloseMovement,
                    onCancelEntry = onCancelEntry,
                    onDeleteEntry = onDeleteEntry,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(state: MainUiState) {
    val last = state.entries.mapNotNull { it.officialNumber }.maxOrNull()
    val pending = state.entries.count { it.officialNumber == null }
    val cancelled = state.entries.count { it.status == RegisterEntry.STATUS_CANCELLED }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Dernier numéro", style = MaterialTheme.typography.labelLarge)
                Text(
                    last?.let { it.toString() + state.selectedType.code } ?: "Aucun",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (state.integrityOk) "Intégrité numérotation : OK" else "Intégrité numérotation : anomalie",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Entrées", style = MaterialTheme.typography.labelLarge)
                Text(state.entries.size.toString(), style = MaterialTheme.typography.headlineSmall)
                if (pending > 0) Text(pending.toString() + " en attente", style = MaterialTheme.typography.labelMedium)
                if (cancelled > 0) Text(cancelled.toString() + " annulée(s)", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: RegisterEntry,
    type: RegisterType,
    deviceId: String,
    onCloseMovement: (String) -> Unit,
    onCancelEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<String?>(null) }
    var cancelReason by remember { mutableStateOf("") }

    if (action == "cancel") {
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text("Annuler cette entrée ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Le numéro " + entry.displayNumber +
                            " restera réservé et ne sera jamais réutilisé."
                    )
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { cancelReason = it },
                        label = { Text("Motif de l'annulation") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCancelEntry(entry.id, cancelReason)
                        action = null
                    },
                ) {
                    Text("Confirmer")
                }
            },
            dismissButton = {
                TextButton(onClick = { action = null }) { Text("Retour") }
            },
        )
    }

    if (action == "delete") {
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text("Supprimer cette entrée ?") },
            text = {
                Text(
                    if (entry.officialNumber == null)
                        "Le brouillon local sera supprimé."
                    else
                        "L'entrée sera retirée de la liste active. Son numéro " +
                            entry.displayNumber +
                            " restera réservé dans l'historique pour éviter toute réutilisation."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteEntry(entry.id)
                        action = null
                    },
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { action = null }) { Text("Retour") }
            },
        )
    }

    OutlinedCard(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        entry.displayNumber.ifBlank { "Numéro en attente" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    when (entry.messageKind) {
                        RegisterEntry.MESSAGE_MOVEMENT ->
                            Text("Message de déplacement", style = MaterialTheme.typography.labelMedium)
                        RegisterEntry.MESSAGE_AVAILABILITY ->
                            Text("Message de disponibilité", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { },
                        label = {
                            Text(
                                when {
                                    entry.officialNumber == null -> "Synchronisation"
                                    entry.isCancelled -> "Annulé"
                                    else -> "Validé"
                                }
                            )
                        },
                    )

                    if (entry.creatorDeviceId == deviceId || entry.officialNumber == null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                if (!entry.isCancelled && entry.officialNumber != null) {
                                    DropdownMenuItem(
                                        text = { Text("Annuler l'entrée") },
                                        onClick = {
                                            menuOpen = false
                                            action = "cancel"
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Supprimer l'entrée") },
                                    onClick = {
                                        menuOpen = false
                                        action = "delete"
                                    },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            if (entry.isCancelled) {
                Text(
                    "Entrée annulée" +
                        if (entry.cancelledReason.isNotBlank())
                            " · " + entry.cancelledReason
                        else "",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (type == RegisterType.R3PERM) {
                DataLine("Grade", entry.grade)
                DataLine("Nom et prénoms", entry.fullName)
                DataLine("Matricule", entry.matricule)
                DataLine("N° /3", entry.numberR3)
                DataLine("Départ", entry.departureDate)
                if (entry.durationIndefinite) {
                    DataLine("Durée", "Indéterminée")
                    DataLine(
                        "Suivi",
                        if (entry.movementClosedAt.isBlank())
                            "Retour à confirmer · rappel tous les 3 jours"
                        else
                            "Retour confirmé",
                    )
                } else {
                    DataLine("Arrivée", entry.arrivalDate)
                    if (entry.durationDays > 0) {
                        DataLine("Durée", entry.durationDays.toString() + " jour(s)")
                    }
                }
                if (entry.annualRightYear > 0) {
                    DataLine("Droit année", entry.annualRightYear.toString())
                }
                DataLine("Droit consommé", entry.consumedRightDetail)
            } else {
                DataLine("N° de la pièce", entry.pieceNumber)
                DataLine("Date de la pièce", entry.pieceDate)
                DataLine("Origine de la pièce", entry.origin)
                DataLine("Libellé ou objet", entry.label)
                DataLine("Observation", entry.observation)

                if (entry.messageKind == RegisterEntry.MESSAGE_AVAILABILITY) {
                    DataLine("Personne concernée", entry.beneficiary)
                    DataLine("Lié au déplacement", entry.relatedMovementId)
                }

                if (entry.durationIndefinite || entry.durationDays > 0) {
                    DataLine("Déplacement", entry.beneficiary)
                    if (entry.durationIndefinite) {
                        DataLine("Durée", "Indéterminée")
                        DataLine(
                            "Suivi",
                            if (entry.movementClosedAt.isBlank())
                                "Retour à confirmer · rappel tous les 3 jours"
                            else
                                "Retour confirmé",
                        )
                    } else {
                        DataLine("Durée", entry.durationDays.toString() + " jour(s)")
                        DataLine("Arrivée", entry.arrivalDate)
                    }
                }
            }

            if (
                entry.durationIndefinite &&
                entry.movementClosedAt.isBlank() &&
                entry.creatorDeviceId == deviceId &&
                !entry.isCancelled
            ) {
                OutlinedButton(
                    onClick = { onCloseMovement(entry.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Personne revenue · arrêter les rappels")
                }
            }
        }
    }
}

@Composable
private fun DataLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(128.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StandardForm(
    type: RegisterType,
    saving: Boolean,
    openMovements: List<RegisterEntry>,
    onSave: (StandardEntryInput) -> Unit,
) {
    var pieceNumber by remember { mutableStateOf("") }
    var pieceDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var origin by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var observation by remember { mutableStateOf("") }

    var messageMode by remember { mutableStateOf(RegisterEntry.MESSAGE_ORDINARY) }
    var movementSubtype by remember { mutableStateOf("déplacement") }
    var isMovement by remember { mutableStateOf(false) }
    var beneficiary by remember { mutableStateOf("") }
    var departureDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var durationText by remember { mutableStateOf("1") }
    var durationIndefinite by remember { mutableStateOf(false) }
    var selectedMovementId by remember { mutableStateOf("") }

    val duration = durationText.toIntOrNull() ?: 0
    val movementMode = if (type == RegisterType.R2) {
        messageMode == RegisterEntry.MESSAGE_MOVEMENT
    } else {
        isMovement
    }
    val availabilityMode =
        type == RegisterType.R2 && messageMode == RegisterEntry.MESSAGE_AVAILABILITY
    val selectedMovement = openMovements.firstOrNull { it.id == selectedMovementId }
    val availabilitySubtype =
        if (selectedMovement?.movementKind == "déplacement perm")
            "disponibilité perm"
        else
            "disponibilité"

    val arrival = if (durationIndefinite) null else runCatching {
        MovementRules.arrivalDate(departureDate, duration)
    }.getOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    type.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Le numéro officiel est attribué après contrôle automatique des doublons.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        if (type == RegisterType.R2) {
            Text("Nature de l'enregistrement", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = messageMode == RegisterEntry.MESSAGE_ORDINARY,
                        onClick = {
                            messageMode = RegisterEntry.MESSAGE_ORDINARY
                            selectedMovementId = ""
                        },
                        label = { Text("Pièce ordinaire") },
                    )
                }
                item {
                    FilterChip(
                        selected = messageMode == RegisterEntry.MESSAGE_MOVEMENT,
                        onClick = {
                            messageMode = RegisterEntry.MESSAGE_MOVEMENT
                            selectedMovementId = ""
                        },
                        label = { Text("Message de déplacement") },
                    )
                }
                item {
                    FilterChip(
                        selected = messageMode == RegisterEntry.MESSAGE_AVAILABILITY,
                        onClick = {
                            messageMode = RegisterEntry.MESSAGE_AVAILABILITY
                            durationIndefinite = false
                        },
                        label = { Text("Message de disponibilité") },
                    )
                }
            }
        }

        if (availabilityMode) {
            OutlinedCard(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Déplacement concerné",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Cette liste montre les messages de déplacement qui n'ont pas encore reçu de message de disponibilité.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (openMovements.isEmpty()) {
                        Text("Aucun déplacement en attente de disponibilité.")
                    } else {
                        openMovements.forEach { movement ->
                            FilterChip(
                                selected = selectedMovementId == movement.id,
                                onClick = {
                                    selectedMovementId = movement.id
                                    beneficiary = movement.beneficiary
                                    if (label.isBlank()) {
                                        label = "Message de disponibilité - " +
                                            movement.beneficiary.ifBlank { movement.label }
                                    }
                                    if (origin.isBlank()) {
                                        origin = "Suite au " + movement.displayNumber
                                    }
                                },
                                label = {
                                    Text(
                                        movement.displayNumber + " · " +
                                            movement.beneficiary.ifBlank { movement.label } +
                                            if (movement.movementKind.isNotBlank())
                                                " · " + movement.movementKind
                                            else
                                                "" +
                                            if (movement.departureDate.isNotBlank())
                                                " · départ " + movement.departureDate
                                            else
                                                ""
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        FormField(pieceNumber, { pieceNumber = it }, "N° de la pièce")
        DatePickerField(pieceDate, { pieceDate = it }, "Date de la pièce")
        FormField(origin, { origin = it }, "Origine de la pièce")
        FormField(label, { label = it }, "Libellé ou objet")
        FormField(observation, { observation = it }, "Observation", singleLine = false)

        if (availabilityMode) {
            FormField(beneficiary, { beneficiary = it }, "Nom et prénoms")
        }

        if (type == RegisterType.R3) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Message de déplacement", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Activer le suivi du déplacement et les rappels.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = isMovement,
                        onCheckedChange = { isMovement = it },
                    )
                }
            }
        }

        if (movementMode) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (type == RegisterType.R2) {
                        Text("Type de déplacement", fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = movementSubtype == "déplacement",
                                    onClick = { movementSubtype = "déplacement" },
                                    label = { Text("Déplacement") },
                                )
                            }
                            item {
                                FilterChip(
                                    selected = movementSubtype == "déplacement perm",
                                    onClick = { movementSubtype = "déplacement perm" },
                                    label = { Text("Déplacement perm") },
                                )
                            }
                        }
                    }
                    FormField(beneficiary, { beneficiary = it }, "Nom et prénoms")
                    DatePickerField(
                        value = departureDate,
                        onValue = { departureDate = it },
                        label = "Date de départ",
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Durée indéterminée", fontWeight = FontWeight.Medium)
                            Text(
                                "Rappel hors connexion tous les 3 jours jusqu'au retour.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = durationIndefinite,
                            onCheckedChange = { durationIndefinite = it },
                        )
                    }

                    if (!durationIndefinite) {
                        NumericField(
                            value = durationText,
                            onValue = { durationText = it },
                            label = "Nombre de jours",
                        )
                        Text(
                            "Date d'arrivée calculée : " +
                                (arrival ?: "durée ou date invalide"),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Le jour du départ compte comme jour 1.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "Aucune date d'arrivée imposée. Le rappel reste actif jusqu'à confirmation du retour.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                onSave(
                    StandardEntryInput(
                        pieceNumber = pieceNumber,
                        pieceDate = pieceDate,
                        origin = origin,
                        label = label,
                        observation = observation,
                        movementKind = when {
                            availabilityMode -> availabilitySubtype
                            type == RegisterType.R2 && movementMode -> movementSubtype
                            movementMode -> "déplacement"
                            else -> ""
                        },
                        durationDays =
                            if (movementMode && !durationIndefinite) duration else 0,
                        durationIndefinite = movementMode && durationIndefinite,
                        beneficiary =
                            if (movementMode || availabilityMode) beneficiary else "",
                        departureDate = if (movementMode) departureDate else "",
                        arrivalDate =
                            if (movementMode && !durationIndefinite)
                                arrival.orEmpty()
                            else
                                "",
                        messageKind = when {
                            availabilityMode -> RegisterEntry.MESSAGE_AVAILABILITY
                            type == RegisterType.R2 && movementMode ->
                                RegisterEntry.MESSAGE_MOVEMENT
                            else -> RegisterEntry.MESSAGE_ORDINARY
                        },
                        relatedMovementId =
                            if (availabilityMode) selectedMovementId else "",
                    )
                )
            },
            enabled = !saving &&
                pieceNumber.isNotBlank() &&
                pieceDate.isNotBlank() &&
                origin.isNotBlank() &&
                label.isNotBlank() &&
                (
                    !availabilityMode ||
                    (
                        selectedMovementId.isNotBlank() &&
                        beneficiary.isNotBlank()
                    )
                ) &&
                (
                    !movementMode ||
                    (
                        beneficiary.isNotBlank() &&
                        departureDate.isNotBlank() &&
                        (durationIndefinite || arrival != null)
                    )
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (saving)
                    "Enregistrement..."
                else
                    "Enregistrer et réserver le numéro"
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionForm(
    saving: Boolean,
    onSave: (PermissionEntryInput) -> Unit,
) {
    val currentYear = LocalDate.now().year
    var grade by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var matricule by remember { mutableStateOf("") }
    var numberR3 by remember { mutableStateOf("") }
    var departureDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var durationText by remember { mutableStateOf("1") }
    var durationIndefinite by remember { mutableStateOf(false) }
    var annualRightYearText by remember { mutableStateOf(currentYear.toString()) }
    var consumedRightDetail by remember { mutableStateOf(currentYear.toString() + "-0 jours") }

    val duration = durationText.toIntOrNull() ?: 0
    val annualRightYear = annualRightYearText.toIntOrNull() ?: 0
    val arrival = if (durationIndefinite) null else runCatching {
        MovementRules.arrivalDate(departureDate, duration)
    }.getOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cahier /3.PERM", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "La date de départ se choisit dans le calendrier. Le rappel de disponibilité reste local sur le téléphone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormField(grade, { grade = it }, "Grade")
        FormField(fullName, { fullName = it }, "Nom et prénoms")
        FormField(matricule, { matricule = it }, "Matricule")
        FormField(numberR3, { numberR3 = it }, "N° /3")

        DatePickerField(
            value = departureDate,
            onValue = { departureDate = it },
            label = "Date de départ",
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Durée indéterminée", fontWeight = FontWeight.Medium)
                Text(
                    "Rappel hors connexion tous les 3 jours jusqu'au retour.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = durationIndefinite,
                onCheckedChange = { durationIndefinite = it },
            )
        }

        if (!durationIndefinite) {
            NumericField(
                value = durationText,
                onValue = { durationText = it },
                label = "Nombre de jours",
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Date d'arrivée", style = MaterialTheme.typography.labelLarge)
                    Text(arrival ?: "Durée invalide", style = MaterialTheme.typography.titleLarge)
                    Text("Le jour du départ est le jour 1.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Retour non daté", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tous les 3 jours, une notification demandera si la personne est revenue afin de préparer le message de disponibilité.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        NumericField(
            value = annualRightYearText,
            onValue = { annualRightYearText = it },
            label = "Droit année (ex. 2026)",
        )
        FormField(
            value = consumedRightDetail,
            onValue = { consumedRightDetail = it },
            label = "Droit consommé (ex. 2025-20 jours)",
        )

        Button(
            onClick = {
                onSave(
                    PermissionEntryInput(
                        grade = grade,
                        fullName = fullName,
                        matricule = matricule,
                        numberR3 = numberR3,
                        departureDate = departureDate,
                        arrivalDate = if (durationIndefinite) "" else arrival.orEmpty(),
                        annualRightYear = annualRightYear,
                        consumedRightDetail = consumedRightDetail,
                        durationDays = if (durationIndefinite) 0 else duration,
                        durationIndefinite = durationIndefinite,
                    )
                )
            },
            enabled = !saving &&
                grade.isNotBlank() &&
                fullName.isNotBlank() &&
                matricule.isNotBlank() &&
                numberR3.isNotBlank() &&
                departureDate.isNotBlank() &&
                annualRightYear in 1900..2200 &&
                consumedRightDetail.isNotBlank() &&
                (durationIndefinite || arrival != null),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saving) "Enregistrement..." else "Enregistrer la permission")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FormField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
    )
}

@Composable
private fun NumericField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            onValue(next.filter { it.isDigit() })
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun DatePickerField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
) {
    val context = LocalContext.current
    val initial = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now() }

    OutlinedButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    onValue(
                        LocalDate.of(year, month + 1, day).toString()
                    )
                },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth,
            ).show()
        },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SettingsPage(
    scale: Float,
    onScale: (Float) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Taille des textes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Ajustez la lisibilité sans modifier les données du cahier.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = scale,
            onValueChange = onScale,
            valueRange = 0.85f..1.6f,
        )
        Text("Taille actuelle : " + (scale * 100).toInt() + " %")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Aperçu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("N° 124/3 · Message de déplacement")
                Text("Ce réglage est mémorisé sur ce téléphone.")
            }
        }
    }
}
