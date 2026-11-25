package br.com.kyberfood.impressora

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import br.com.kyberfood.impressora.data.AppStore
import br.com.kyberfood.impressora.service.PrinterService
import br.com.kyberfood.impressora.ui.HomeScreen
import br.com.kyberfood.impressora.ui.KyberTheme
import br.com.kyberfood.impressora.ui.LoginScreen
import br.com.kyberfood.impressora.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // O topo das telas é azul-escuro: os ícones da barra do celular ficam claros.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { KyberTheme { AppRoot() } }
    }
}

private enum class Screen { Login, Setup, Home }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val store = remember { AppStore(context) }
    var screen by remember {
        mutableStateOf(
            when {
                !store.isLoggedIn -> Screen.Login
                !store.setupDone -> Screen.Setup
                else -> Screen.Home
            },
        )
    }

    // "Corrigir" na linha da impressora abre a configuração direto no passo dela.
    var setupAtPrinter by remember { mutableStateOf(false) }

    when (screen) {
        Screen.Login -> LoginScreen(
            store = store,
            onLoggedIn = { screen = if (store.setupDone) Screen.Home else Screen.Setup },
        )
        Screen.Setup -> SetupScreen(
            store = store,
            startAtPrinter = setupAtPrinter,
            onFinished = {
                store.setupDone = true
                PrinterService.startIfReady(context)
                screen = Screen.Home
            },
        )
        Screen.Home -> HomeScreen(
            store = store,
            onRedoSetup = { atPrinter ->
                setupAtPrinter = atPrinter
                screen = Screen.Setup
            },
            onReenterPassword = { screen = Screen.Login },
            onSignedOut = { screen = Screen.Login },
        )
    }
}
