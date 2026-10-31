package mg.registre.communautaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import mg.registre.communautaire.security.AccessRole

@Composable
fun AuthenticationGate(
    state: MainUiState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onSignOut: () -> Unit,
) {
    if (state.authLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.authenticated && !state.accessApproved) {
        Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card {
                Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Accès au registre non autorisé", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Votre compte est valide, mais un administrateur du Registre Communautaire doit encore vous ajouter."
                    )
                    if (state.email.isNotBlank()) Text(state.email, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                        Text("Se déconnecter")
                    }
                }
            }
        }
        return
    }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Registre communautaire", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Connectez-vous pour accéder aux données administratives partagées.")
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    onClick = { onSignIn(email, password) },
                    enabled = email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Se connecter")
                }
                TextButton(
                    onClick = { onSignUp(email, password) },
                    enabled = email.isNotBlank() && password.length >= 8,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Créer un compte")
                }
                Text(
                    "Le premier compte autorisé après la migration devient administrateur. Les comptes suivants doivent être approuvés par cet administrateur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AdminAccessControl(
    onAddMember: (String, AccessRole) -> Unit,
    onSignOut: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(AccessRole.AGENT) }

    ExtendedFloatingActionButton(
        onClick = { open = true },
        icon = { androidx.compose.material3.Icon(Icons.Default.AdminPanelSettings, contentDescription = null) },
        text = { Text("Accès") },
    )

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Gérer les accès") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ajoutez un compte Supabase existant au Registre Communautaire.")
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email du membre") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccessRole.entries.forEach { item ->
                            FilterChip(
                                selected = role == item,
                                onClick = { role = item },
                                label = {
                                    Text(
                                        when (item) {
                                            AccessRole.ADMIN -> "Admin"
                                            AccessRole.AGENT -> "Agent"
                                            AccessRole.VIEWER -> "Lecture"
                                        }
                                    )
                                },
                            )
                        }
                    }
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                        Text("Se déconnecter")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = email.isNotBlank(),
                    onClick = {
                        onAddMember(email, role)
                        email = ""
                        open = false
                    },
                ) {
                    Text("Ajouter")
                }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Fermer") }
            },
        )
    }
}
