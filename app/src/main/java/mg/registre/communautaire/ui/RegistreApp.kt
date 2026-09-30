package mg.registre.communautaire.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onClearError: () -> Unit,
) {
    var page by remember { mutableStateOf(Page.LIST) }
    var query by remember { mutableStateOf("") }

    if (!state.firebaseConfigured) {
        FirebaseSetupScreen()
        return
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text("Information") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onClearError) { Text("Fermer") }
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
            if (page == Page.LIST && state.selectedYear == LocalDate.now().year) {
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
private fun FirebaseSetupScreen() {
    Box(
        Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Configuration Firebase requise", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "L'application est construite, mais elle doit être reliée à votre projet Firebase pour partager les numéros entre téléphones."
                )
                Text(
                    "Renseignez FIREBASE_API_KEY, FIREBASE_APPLICATION_ID et FIREBASE_PROJECT_ID, activez l'authentification anonyme et Firestore, puis reconstruisez l'APK."
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
                EntryCard(entry, state.selectedType)
            }
        }
    }
}

@Composable
private fun SummaryCard(state: MainUiState) {
    val last = state.entries.mapNotNull { it.officialNumber }.maxOrNull()
    val pending = state.entries.count { it.officialNumber == null }

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
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Entrées", style = MaterialTheme.typography.labelLarge)
                Text(state.entries.size.toString(), style = MaterialTheme.typography.headlineSmall)
                if (pending > 0) Text(pending.toString() + " en attente", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EntryCard(entry: RegisterEntry, type: RegisterType) {
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
                Text(
                    entry.displayNumber.ifBlank { "Numéro en attente" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                AssistChip(
                    onClick = { },
                    label = {
                        Text(if (entry.officialNumber == null) "Synchronisation" else "Validé")
                    },
                )
            }
            HorizontalDivider()

            if (type == RegisterType.R3PERM) {
                DataLine("Grade", entry.grade)
                DataLine("Nom et prénoms", entry.fullName)
                DataLine("Matricule", entry.matricule)
                DataLine("N° /3", entry.numberR3)
                DataLine("Départ", entry.departureDate)
                DataLine("Arrivée", entry.arrivalDate)
                DataLine("Droit année", entry.annualRight.toString())
                DataLine("Droit consommé", entry.consumedRight.toString())
            } else {
                DataLine("N° de la pièce", entry.pieceNumber)
                DataLine("Date de la pièce", entry.pieceDate)
                DataLine("Origine de la pièce", entry.origin)
                DataLine("Libellé ou objet", entry.label)
                DataLine("Observation", entry.observation)
                if (entry.durationDays > 0) {
                    DataLine("Déplacement", entry.beneficiary)
                    DataLine("Durée", entry.durationDays.toString() + " jour(s)")
                    DataLine("Arrivée", entry.arrivalDate)
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
    onSave: (StandardEntryInput) -> Unit,
) {
    var pieceNumber by remember { mutableStateOf("") }
    var pieceDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var origin by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var observation by remember { mutableStateOf("") }
    var isMovement by remember { mutableStateOf(false) }
    var beneficiary by remember { mutableStateOf("") }
    var departureDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var duration by remember { mutableIntStateOf(1) }

    val arrival = runCatching {
        MovementRules.arrivalDate(departureDate, duration)
    }.getOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(type.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Le numéro du cahier sera attribué automatiquement et de façon communautaire.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FormField(pieceNumber, { pieceNumber = it }, "N° de la pièce")
        FormField(pieceDate, { pieceDate = it }, "Date de la pièce (AAAA-MM-JJ)")
        FormField(origin, { origin = it }, "Origine de la pièce")
        FormField(label, { label = it }, "Libellé ou objet")
        FormField(observation, { observation = it }, "Observation", singleLine = false)

        if (type == RegisterType.R3) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Message de déplacement", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Active le calcul des jours et le rappel de disponibilité.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = isMovement, onCheckedChange = { isMovement = it })
                    }

                    if (isMovement) {
                        FormField(beneficiary, { beneficiary = it }, "Nom et prénoms")
                        FormField(departureDate, { departureDate = it }, "Date de départ (AAAA-MM-JJ)")
                        NumberStepper("Nombre de jours", duration, { duration = it.coerceAtLeast(1) })
                        Text(
                            "Date d'arrivée calculée : " + (arrival ?: "date invalide"),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Le jour du départ compte comme jour 1.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        movementKind = if (isMovement) "déplacement" else "",
                        durationDays = if (isMovement) duration else 0,
                        beneficiary = if (isMovement) beneficiary else "",
                        departureDate = if (isMovement) departureDate else "",
                        arrivalDate = if (isMovement) arrival.orEmpty() else "",
                    )
                )
            },
            enabled = !saving &&
                pieceNumber.isNotBlank() &&
                pieceDate.isNotBlank() &&
                origin.isNotBlank() &&
                label.isNotBlank() &&
                (!isMovement || (beneficiary.isNotBlank() && arrival != null)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saving) "Enregistrement..." else "Enregistrer et réserver le numéro")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionForm(
    saving: Boolean,
    onSave: (PermissionEntryInput) -> Unit,
) {
    var grade by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var matricule by remember { mutableStateOf("") }
    var numberR3 by remember { mutableStateOf("") }
    var departureDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var duration by remember { mutableIntStateOf(1) }
    var annualRight by remember { mutableIntStateOf(0) }
    var consumedRight by remember { mutableIntStateOf(0) }

    val arrival = runCatching {
        MovementRules.arrivalDate(departureDate, duration)
    }.getOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cahier /3.PERM", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "L'arrivée et le rappel sont calculés automatiquement à partir du nombre de jours.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormField(grade, { grade = it }, "Grade")
        FormField(fullName, { fullName = it }, "Nom et prénoms")
        FormField(matricule, { matricule = it }, "Matricule")
        FormField(numberR3, { numberR3 = it }, "N° /3")
        FormField(departureDate, { departureDate = it }, "Date de départ (AAAA-MM-JJ)")
        NumberStepper("Nombre de jours", duration, { duration = it.coerceAtLeast(1) })
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Date d'arrivée", style = MaterialTheme.typography.labelLarge)
                Text(arrival ?: "Date invalide", style = MaterialTheme.typography.titleLarge)
                Text("Le jour du départ est le jour 1.", style = MaterialTheme.typography.bodySmall)
            }
        }
        NumberStepper("Droit année", annualRight, { annualRight = it.coerceAtLeast(0) })
        NumberStepper("Droit consommé", consumedRight, { consumedRight = it.coerceAtLeast(0) })

        Button(
            onClick = {
                onSave(
                    PermissionEntryInput(
                        grade = grade,
                        fullName = fullName,
                        matricule = matricule,
                        numberR3 = numberR3,
                        departureDate = departureDate,
                        arrivalDate = arrival.orEmpty(),
                        annualRight = annualRight,
                        consumedRight = consumedRight,
                        durationDays = duration,
                    )
                )
            },
            enabled = !saving &&
                grade.isNotBlank() &&
                fullName.isNotBlank() &&
                matricule.isNotBlank() &&
                numberR3.isNotBlank() &&
                arrival != null,
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
private fun NumberStepper(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onValue(value - 1) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("−")
                }
                Text(
                    value.toString(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedButton(onClick = { onValue(value + 1) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("+")
                }
            }
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
