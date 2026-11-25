package br.com.kyberfood.impressora.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.kyberfood.impressora.data.AppStore
import br.com.kyberfood.impressora.data.AuthResult
import br.com.kyberfood.impressora.data.Credentials
import br.com.kyberfood.impressora.data.Session
import br.com.kyberfood.impressora.data.SessionKeeper
import br.com.kyberfood.impressora.data.StoreInfo
import br.com.kyberfood.impressora.data.SupabaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class LoginOutcome {
    data class Stores(val credentials: Credentials, val session: Session, val stores: List<StoreInfo>) : LoginOutcome()
    data class Failed(val message: String) : LoginOutcome()
}

private fun attemptLogin(email: String, password: String): LoginOutcome {
    if (!SupabaseAuth.isConfigured) {
        return LoginOutcome.Failed("Este instalador está incompleto. Peça um novo ao suporte do KyberFood.")
    }
    return when (val result = SupabaseAuth.signIn(email, password)) {
        AuthResult.WrongCredentials ->
            LoginOutcome.Failed("E-mail ou senha incorretos. Use os mesmos do painel do KyberFood.")
        AuthResult.RateLimited ->
            LoginOutcome.Failed("Muitas tentativas seguidas. Espere 1 minuto e tente de novo.")
        is AuthResult.Unavailable ->
            LoginOutcome.Failed("Sem conexão com a internet. Confira o Wi-Fi ou os dados móveis e tente de novo.")
        is AuthResult.Ok -> try {
            val stores = SupabaseAuth.fetchStores(result.session)
            if (stores.isEmpty()) {
                LoginOutcome.Failed("Não encontrei uma loja ativa nesta conta. Fale com o suporte do KyberFood.")
            } else {
                LoginOutcome.Stores(Credentials(email, password), result.session, stores)
            }
        } catch (_: Exception) {
            LoginOutcome.Failed("Entrei na conta, mas não consegui ler a loja. Tente de novo em instantes.")
        }
    }
}

@Composable
fun LoginScreen(store: AppStore, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(store.lastEmail) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<LoginOutcome.Stores?>(null) }

    fun finish(outcome: LoginOutcome.Stores, chosen: StoreInfo) {
        SessionKeeper(store).onManualLogin(outcome.credentials, outcome.session)
        store.storeId = chosen.id
        store.storeName = chosen.name
        onLoggedIn()
    }

    fun submit() {
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty() || password.isEmpty()) {
            error = "Preencha o e-mail e a senha."
            return
        }
        loading = true
        error = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { attemptLogin(cleanEmail, password) }
            loading = false
            when (outcome) {
                is LoginOutcome.Failed -> error = outcome.message
                is LoginOutcome.Stores ->
                    if (outcome.stores.size == 1) finish(outcome, outcome.stores.first()) else choosing = outcome
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        KyberHeader("KyberFood Impressora", "Comanda e som de pedido no celular")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Entrar", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Use o mesmo e-mail e a mesma senha do painel do KyberFood. Você só faz isso uma vez: o app fica conectado.",
                color = KyberMuted,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Senha") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "Esconder a senha" else "Mostrar a senha",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { Notice(it, ok = false, icon = Icons.Filled.ErrorOutline) }

            PrimaryButton(if (loading) "Entrando…" else "Entrar", onClick = { submit() }, loading = loading)
        }
    }

    choosing?.let { outcome ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            confirmButton = {},
            title = { Text("Qual loja usa este celular?") },
            text = {
                Column {
                    outcome.stores.forEach { item ->
                        Text(
                            item.name,
                            fontSize = 17.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    choosing = null
                                    finish(outcome, item)
                                }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            },
        )
    }
}
