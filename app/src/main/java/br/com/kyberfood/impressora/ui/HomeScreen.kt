package br.com.kyberfood.impressora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.kyberfood.impressora.BuildConfig
import br.com.kyberfood.impressora.data.AccountState
import br.com.kyberfood.impressora.data.AppStore
import br.com.kyberfood.impressora.data.SessionKeeper
import br.com.kyberfood.impressora.data.UpdateCheck
import br.com.kyberfood.impressora.data.UpdateInfo
import br.com.kyberfood.impressora.data.Updater
import br.com.kyberfood.impressora.print.BluetoothPrinter
import br.com.kyberfood.impressora.print.PrintResult
import br.com.kyberfood.impressora.service.PrinterService
import br.com.kyberfood.impressora.service.WorkerStatus
import br.com.kyberfood.impressora.setup.DeviceSetup
import br.com.kyberfood.impressora.sound.OrderSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela inicial: diz em uma olhada se está tudo certo e, quando não está, QUAL item falta e
 * o botão que resolve. O lojista não usa este app no dia a dia — ele só confere aqui.
 */
@Composable
fun HomeScreen(
    store: AppStore,
    onRedoSetup: (atPrinter: Boolean) -> Unit,
    onReenterPassword: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resumeTick = rememberResumeTick()

    var account by remember { mutableStateOf<AccountState>(SessionKeeper.state) }
    var serviceOn by remember { mutableStateOf(PrinterService.running) }
    var printing by remember { mutableStateOf(false) }
    var printMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    val bluetoothOk = remember(resumeTick) { BluetoothPrinter.hasPermission(context) && BluetoothPrinter.isOn(context) }
    val batteryOk = remember(resumeTick) { DeviceSetup.batteryUnrestricted(context) }
    val notificationsOk = remember(resumeTick) { DeviceSetup.notificationsAllowed(context) }
    val printerChosen = store.printerAddress != null

    // A cada volta para o app: confere a conta e religa o serviço se o celular o desligou.
    LaunchedEffect(resumeTick) {
        PrinterService.startIfReady(context)
        account = withContext(Dispatchers.IO) {
            SessionKeeper(store).validToken()
            SessionKeeper.state
        }
        delay(600)
        serviceOn = PrinterService.running
    }

    // A busca de pedidos roda no serviço; a tela só relê o estado dela de tempos em tempos.
    var workerTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            workerTick++
            serviceOn = PrinterService.running
        }
    }
    val workerProblem = remember(workerTick) { WorkerStatus.problem }
    val printerProblem = remember(workerTick) { WorkerStatus.printerProblem }
    val pendingCount = remember(workerTick) { WorkerStatus.pendingCount }
    val lastPollOkAt = remember(workerTick) { WorkerStatus.lastPollOkAtMs }
    val lastPrinted = remember(workerTick) { store.lastPrintedLabel?.let { "$it às ${formatTime(store.lastPrintedAtMs)}" } }

    val ordersOk = serviceOn && workerProblem == null && printerProblem == null
    val ordersDetail = when {
        !serviceOn -> "A busca de pedidos está parada"
        pendingCount > 0 && printerProblem != null -> "$pendingCount comanda(s) esperando a impressora. $printerProblem"
        workerProblem != null -> workerProblem
        lastPollOkAt == 0L -> "Começando a buscar pedidos…"
        lastPrinted != null -> "Buscando pedidos. Última impressão: $lastPrinted"
        else -> "Buscando pedidos. Os novos saem sozinhos na impressora."
    }

    val printerOk = printerChosen && bluetoothOk
    val backgroundOk = serviceOn && batteryOk
    val allOk = account == AccountState.Connected && printerOk && backgroundOk && notificationsOk && ordersOk

    Column(Modifier.fillMaxSize()) {
        KyberHeader("KyberFood Impressora", store.storeName)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KyberCard(color = if (allOk) KyberOkBg else KyberWarnBg) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (allOk) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = if (allOk) KyberGreen else KyberWarnText,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (allOk) "Tudo pronto" else "Falta um ajuste",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (allOk) Color(0xFF14653A) else KyberWarnText,
                        )
                        Text(
                            if (allOk) "Os pedidos saem sozinhos. Pode apagar a tela." else "Veja abaixo o item em laranja.",
                            fontSize = 15.sp,
                            color = if (allOk) Color(0xFF14653A) else KyberWarnText,
                        )
                    }
                }
            }

            KyberCard {
                StatusRow(
                    icon = Icons.Filled.Person,
                    title = "Conta",
                    detail = when (val state = account) {
                        AccountState.Connected -> "Conectada"
                        AccountState.PasswordRefused -> "A senha do painel mudou. Digite a senha nova."
                        is AccountState.Waiting -> "${state.reason}. O app tenta de novo sozinho."
                    },
                    ok = account == AccountState.Connected,
                    action = if (account == AccountState.PasswordRefused) "Digitar a senha" else null,
                    onAction = onReenterPassword,
                )
                HorizontalDivider(color = Color(0xFFEEF0F6))
                StatusRow(
                    icon = Icons.Filled.ReceiptLong,
                    title = "Pedidos",
                    detail = ordersDetail,
                    ok = ordersOk,
                    action = null,
                    onAction = {},
                )
                HorizontalDivider(color = Color(0xFFEEF0F6))
                StatusRow(
                    icon = Icons.Filled.Print,
                    title = "Impressora",
                    detail = when {
                        !printerChosen -> "Nenhuma impressora escolhida"
                        !bluetoothOk -> "${store.printerName}: o Bluetooth está desligado ou sem permissão"
                        else -> "${store.printerName} · papel ${if (store.paperCols == 48) "80" else "58"} mm"
                    },
                    ok = printerOk,
                    action = if (!printerOk) "Corrigir" else null,
                    onAction = { onRedoSetup(true) },
                )
                HorizontalDivider(color = Color(0xFFEEF0F6))
                StatusRow(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = "Ligado em segundo plano",
                    detail = when {
                        !batteryOk -> "O celular pode desligar o app para economizar bateria"
                        !serviceOn -> "O app não está ligado em segundo plano"
                        else -> "Ligado sem parar desde ${formatSince(store.serviceStartedAtMs)}"
                    },
                    ok = backgroundOk,
                    action = if (!backgroundOk) "Corrigir" else null,
                    onAction = {
                        if (!batteryOk) DeviceSetup.requestBatteryUnrestricted(context)
                        PrinterService.startIfReady(context)
                        scope.launch {
                            delay(800)
                            serviceOn = PrinterService.running
                        }
                    },
                )
                HorizontalDivider(color = Color(0xFFEEF0F6))
                StatusRow(
                    icon = Icons.Filled.Notifications,
                    title = "Avisos",
                    detail = if (notificationsOk) "Liberados" else "Sem os avisos o celular desliga o app",
                    ok = notificationsOk,
                    action = if (!notificationsOk) "Corrigir" else null,
                    onAction = { DeviceSetup.openNotificationSettings(context) },
                )
            }

            PrimaryButton(
                if (printing) "Imprimindo…" else "Imprimir teste",
                loading = printing,
                onClick = {
                    printing = true
                    printMessage = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { printTestPage(context, store) }
                        printMessage = describePrintResult(result) to (result == PrintResult.Ok)
                        printing = false
                    }
                },
            )
            printMessage?.let { (text, ok) ->
                Notice(text, ok = ok, icon = if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline)
            }
            SecondaryButton("Tocar o som do pedido", onClick = { OrderSound.play(context) })
            SecondaryButton("Refazer a configuração", onClick = { onRedoSetup(false) })

            UpdateCard()

            TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Sair da conta", color = KyberMuted)
            }
        }
    }

    if (confirmSignOut) {
        // O botão SEGURO é o de destaque: sair é a exceção, e a pessoa precisa saber o que para.
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sair da conta?") },
            text = {
                Text(
                    "Se você sair, a comanda para de sair e o som para de tocar neste celular. " +
                        "Os pedidos continuam entrando no sistema normalmente.\n\n" +
                        "Para só fechar a tela, aperte o botão de início do celular: o app continua ligado.",
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            },
            confirmButton = {
                PrimaryButton("Continuar conectado", onClick = { confirmSignOut = false })
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        PrinterService.stop(context)
                        store.signOut()
                        onSignedOut()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sair mesmo assim", color = Color(0xFFD93025))
                }
            },
        )
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    detail: String,
    ok: Boolean,
    action: String?,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(42.dp).background(if (ok) KyberOkBg else KyberWarnBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (ok) KyberGreen else KyberOrange, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 14.sp, lineHeight = 19.sp, color = if (ok) KyberMuted else KyberWarnText)
        }
        if (action != null) {
            TextButton(onClick = onAction) {
                Text(action, color = KyberOrange, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

private sealed class UpdateUi {
    data object Idle : UpdateUi()
    data object Checking : UpdateUi()
    data object UpToDate : UpdateUi()
    data object Unknown : UpdateUi()
    data class Available(val info: UpdateInfo) : UpdateUi()
    data class NeedsPermission(val info: UpdateInfo) : UpdateUi()
    data class Downloading(val percent: Int) : UpdateUi()
    data object Installing : UpdateUi()
    data class Failed(val info: UpdateInfo) : UpdateUi()
}

/**
 * "Procurar atualização": confere a versão publicada, baixa e entrega ao Android. Também
 * confere sozinho ao abrir a tela, sem incomodar quando não há nada novo.
 */
@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }

    fun check(silent: Boolean) {
        if (!silent) ui = UpdateUi.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { Updater.check() }
            ui = when (result) {
                is UpdateCheck.Available -> UpdateUi.Available(result.info)
                UpdateCheck.UpToDate -> if (silent) UpdateUi.Idle else UpdateUi.UpToDate
                UpdateCheck.Unknown -> if (silent) UpdateUi.Idle else UpdateUi.Unknown
            }
        }
    }

    fun update(info: UpdateInfo) {
        if (!Updater.canInstall(context)) {
            ui = UpdateUi.NeedsPermission(info)
            return
        }
        ui = UpdateUi.Downloading(0)
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { Updater.download(context, info) { percent -> ui = UpdateUi.Downloading(percent) } }.getOrNull()
            }
            if (file == null) {
                ui = UpdateUi.Failed(info)
                return@launch
            }
            ui = UpdateUi.Installing
            runCatching { Updater.install(context, file) }.onFailure { ui = UpdateUi.Failed(info) }
        }
    }

    LaunchedEffect(Unit) { check(silent = true) }

    KyberCard {
        Text("Atualização do app", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("Versão instalada: ${BuildConfig.VERSION_NAME}", color = KyberMuted, fontSize = 14.sp)

        when (val state = ui) {
            UpdateUi.Idle -> SecondaryButton("Procurar atualização", onClick = { check(silent = false) })
            UpdateUi.Checking -> SecondaryButton("Procurando…", onClick = {}, enabled = false)
            UpdateUi.UpToDate -> {
                Notice("Você já está na versão mais nova.", ok = true, icon = Icons.Filled.CheckCircle)
                SecondaryButton("Procurar de novo", onClick = { check(silent = false) })
            }
            UpdateUi.Unknown -> {
                Notice("Não consegui conferir agora. Veja a internet e tente de novo.", ok = false, icon = Icons.Filled.ErrorOutline)
                SecondaryButton("Procurar de novo", onClick = { check(silent = false) })
            }
            is UpdateUi.Available -> {
                Notice("Tem versão nova: ${state.info.version}. O login e a configuração são mantidos.", ok = true, icon = Icons.Filled.CheckCircle)
                PrimaryButton("Atualizar agora", onClick = { update(state.info) })
            }
            is UpdateUi.NeedsPermission -> {
                Notice(
                    "O celular precisa deixar este app instalar a atualização. Toque no botão, ligue \"Permitir desta fonte\" e volte para cá.",
                    ok = false,
                    icon = Icons.Filled.ErrorOutline,
                )
                PrimaryButton("Permitir a instalação", onClick = { Updater.openInstallPermission(context) })
                SecondaryButton("Já permiti. Atualizar", onClick = { update(state.info) })
            }
            is UpdateUi.Downloading -> PrimaryButton("Baixando… ${state.percent}%", onClick = {}, loading = true)
            UpdateUi.Installing -> {
                Notice("Na tela que abriu, toque em \"Atualizar\". O app volta sozinho depois.", ok = true, icon = Icons.Filled.CheckCircle)
                SecondaryButton("Procurar de novo", onClick = { check(silent = false) })
            }
            is UpdateUi.Failed -> {
                Notice("Não consegui baixar a atualização. Veja a internet e tente de novo.", ok = false, icon = Icons.Filled.ErrorOutline)
                PrimaryButton("Tentar de novo", onClick = { update(state.info) })
            }
        }
    }
}

private fun formatTime(atMs: Long): String = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(atMs))

private fun formatSince(startedAtMs: Long): String =
    if (startedAtMs <= 0L) "agora" else SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR")).format(Date(startedAtMs))
