package br.com.kyberfood.impressora.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import br.com.kyberfood.impressora.data.AppStore
import br.com.kyberfood.impressora.print.BluetoothPrinter
import br.com.kyberfood.impressora.print.PrintResult
import br.com.kyberfood.impressora.setup.DeviceSetup
import br.com.kyberfood.impressora.sound.OrderSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { Bluetooth, Printer, Notifications, Background, Sound }

/**
 * Configuração guiada, um passo por tela. Quem faz é o próprio lojista, sozinho: cada passo
 * diz POR QUE precisa, tem UM botão que já abre o lugar certo do celular e confere sozinho
 * quando a pessoa volta. Nenhum passo prende — sempre dá para pular e ajustar depois.
 */
@Composable
fun SetupScreen(store: AppStore, onFinished: () -> Unit, startAtPrinter: Boolean = false) {
    val steps = remember { Step.entries.toList() }
    var index by remember { mutableIntStateOf(if (startAtPrinter) Step.Printer.ordinal else 0) }
    val step = steps[index]
    val resumeTick = rememberResumeTick()

    fun next() {
        if (index == steps.lastIndex) onFinished() else index++
    }

    Column(Modifier.fillMaxSize()) {
        KyberHeader("Vamos configurar", "Passo ${index + 1} de ${steps.size}")
        LinearProgressIndicator(
            progress = { (index + 1f) / steps.size },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = KyberOrange,
            trackColor = Color(0xFFE3E6EF),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (step) {
                Step.Bluetooth -> BluetoothStep(resumeTick, onNext = ::next)
                Step.Printer -> PrinterStep(store, resumeTick, onNext = ::next)
                Step.Notifications -> NotificationsStep(resumeTick, onNext = ::next)
                Step.Background -> BackgroundStep(store, resumeTick, onNext = ::next)
                Step.Sound -> SoundStep(onNext = ::next)
            }
            if (index > 0) {
                TextButton(onClick = { index-- }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Voltar ao passo anterior", color = KyberMuted)
                }
            }
        }
    }
}

@Composable
private fun StepIntro(icon: ImageVector, title: String, why: String) {
    Box(
        modifier = Modifier.size(72.dp).background(Color(0xFFFFE9E0), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = KyberOrange, modifier = Modifier.size(38.dp))
    }
    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
    Text(why, color = KyberMuted, fontSize = 16.sp, lineHeight = 23.sp)
}

@Composable
private fun SkipButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text("Pular por enquanto", color = KyberMuted, fontSize = 15.sp)
    }
}

// ---------------------------------------------------------------- 1. Bluetooth

@Composable
private fun BluetoothStep(resumeTick: Int, onNext: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var deniedOnce by remember { mutableStateOf(false) }
    val hasAdapter = remember { BluetoothPrinter.adapter(context) != null }
    val allowed = remember(resumeTick, refresh) { BluetoothPrinter.hasPermission(context) }
    val on = remember(resumeTick, refresh) { BluetoothPrinter.isOn(context) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) deniedOnce = true
        refresh++
    }
    val askTurnOn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    StepIntro(
        Icons.Filled.Bluetooth,
        "Permitir o Bluetooth",
        "É pelo Bluetooth que o celular conversa com a impressora. Sem isso a comanda não sai.",
    )

    when {
        !hasAdapter -> Notice("Este celular não tem Bluetooth, então não consegue usar a impressora.", ok = false, icon = Icons.Filled.ErrorOutline)
        !allowed -> {
            if (deniedOnce) {
                Notice(
                    "O celular não deixou perguntar de novo. Toque no botão, entre em Permissões e libere \"Dispositivos por perto\".",
                    ok = false,
                    icon = Icons.Filled.ErrorOutline,
                )
                PrimaryButton("Abrir as permissões do app", onClick = { DeviceSetup.openAppDetails(context) })
            } else {
                PrimaryButton("Permitir Bluetooth", onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        askPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        refresh++
                    }
                })
                Text("Vai aparecer uma pergunta do celular. Toque em \"Permitir\".", color = KyberMuted, fontSize = 14.sp)
            }
        }
        !on -> {
            Notice("O Bluetooth do celular está desligado.", ok = false, icon = Icons.Filled.ErrorOutline)
            PrimaryButton("Ligar o Bluetooth", onClick = {
                runCatching { askTurnOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    .onFailure { DeviceSetup.openBluetoothSettings(context) }
            })
        }
        else -> {
            Notice("Bluetooth liberado e ligado.", ok = true, icon = Icons.Filled.CheckCircle)
            PrimaryButton("Continuar", onClick = onNext)
        }
    }
    if (hasAdapter && !(allowed && on)) SkipButton(onNext)
}

// ---------------------------------------------------------------- 2. Impressora

@Composable
private fun PrinterStep(store: AppStore, resumeTick: Int, onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(store.printerAddress) }
    var cols by remember { mutableIntStateOf(store.paperCols) }
    var printing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PrintResult?>(null) }
    val devices = remember(resumeTick) { BluetoothPrinter.pairedDevices(context) }

    StepIntro(
        Icons.Filled.Print,
        "Escolher a impressora",
        "Ligue a impressora e escolha ela na lista. Depois imprima um teste para ter certeza.",
    )

    if (devices.isEmpty()) {
        Notice("Nenhuma impressora pareada com este celular ainda.", ok = false, icon = Icons.Filled.ErrorOutline)
    } else {
        devices.forEach { device ->
            val isSelected = device.address == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(14.dp))
                    .border(
                        BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) KyberOrange else Color(0xFFE0E3EC)),
                        RoundedCornerShape(14.dp),
                    )
                    .clickable {
                        selected = device.address
                        store.printerAddress = device.address
                        store.printerName = device.name
                        result = null
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null, modifier = Modifier.padding(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (device.looksLikePrinter) "Parece ser uma impressora" else device.address,
                        color = if (device.looksLikePrinter) KyberGreen else KyberMuted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    KyberCard(color = Color(0xFFEEF0F6)) {
        Text("A impressora não aparece na lista?", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "1. Ligue a impressora.\n2. Toque no botão abaixo.\n3. Escolha a impressora na tela do celular. Se pedir senha, tente 0000 ou 1234.\n4. Volte para este app.",
            color = KyberMuted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        SecondaryButton("Parear a impressora", onClick = { DeviceSetup.openBluetoothSettings(context) })
    }

    Text("Tamanho do papel", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PaperOption("58 mm", "pequeno", cols == 32, Modifier.weight(1f)) { cols = 32; store.paperCols = 32 }
        PaperOption("80 mm", "grande", cols == 48, Modifier.weight(1f)) { cols = 48; store.paperCols = 48 }
    }

    PrimaryButton(
        if (printing) "Imprimindo…" else "Imprimir teste",
        enabled = selected != null,
        loading = printing,
        onClick = {
            printing = true
            result = null
            scope.launch {
                result = withContext(Dispatchers.IO) { printTestPage(context, store) }
                printing = false
            }
        },
    )

    when (val outcome = result) {
        null -> SkipButton(onNext)
        PrintResult.Ok -> {
            Notice(
                "Enviado! Saiu um papel escrito \"IMPRESSORA OK\"? Se a linha de números saiu quebrada em duas, troque o tamanho do papel e teste de novo.",
                ok = true,
                icon = Icons.Filled.CheckCircle,
            )
            PrimaryButton("Sim, saiu certinho", onClick = onNext, color = KyberGreen)
            Text(
                "Não saiu nada? Veja se tem papel, se a tampa está fechada e imprima o teste de novo.",
                color = KyberMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        else -> {
            Notice(describePrintResult(outcome), ok = false, icon = Icons.Filled.ErrorOutline)
            SkipButton(onNext)
        }
    }
}

@Composable
private fun PaperOption(title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .background(if (selected) Color(0xFFFFE9E0) else Color.White, RoundedCornerShape(14.dp))
            .border(
                BorderStroke(if (selected) 2.dp else 1.dp, if (selected) KyberOrange else Color(0xFFE0E3EC)),
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = KyberMuted, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- 3. Avisos

@Composable
private fun NotificationsStep(resumeTick: Int, onNext: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf(false) }
    val allowed = remember(resumeTick, refresh) { DeviceSetup.notificationsAllowed(context) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        refresh++
    }

    StepIntro(
        Icons.Filled.Notifications,
        "Permitir os avisos",
        "O app deixa um aviso fixo no topo do celular. É esse aviso que mantém a impressora funcionando com a tela apagada.",
    )

    if (allowed) {
        Notice("Avisos liberados.", ok = true, icon = Icons.Filled.CheckCircle)
        PrimaryButton("Continuar", onClick = onNext)
    } else {
        PrimaryButton("Permitir avisos", onClick = {
            if (DeviceSetup.needsNotificationPermission && !asked) {
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                DeviceSetup.openNotificationSettings(context)
            }
        })
        Text(
            if (asked) "Na tela que abrir, ligue a chave \"Permitir notificações\" e volte para cá."
            else "Vai aparecer uma pergunta do celular. Toque em \"Permitir\".",
            color = KyberMuted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        SkipButton(onNext)
    }
}

// ---------------------------------------------------------------- 4. Segundo plano

@Composable
private fun BackgroundStep(store: AppStore, resumeTick: Int, onNext: () -> Unit) {
    val context = LocalContext.current
    val batteryOk = remember(resumeTick) { DeviceSetup.batteryUnrestricted(context) }
    val hint = remember { DeviceSetup.autostartHint() }
    var autostartOk by remember { mutableStateOf(store.autostartConfirmed) }

    StepIntro(
        Icons.Filled.BatteryChargingFull,
        "Deixar o app sempre ligado",
        "Para economizar bateria, o celular desliga apps parados — e aí a comanda para de sair. Aqui você avisa o celular para não desligar este app.",
    )

    KyberCard {
        Text("Rodar em segundo plano", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (batteryOk) {
            Notice("Liberado.", ok = true, icon = Icons.Filled.CheckCircle)
        } else {
            Text("Toque no botão e depois em \"Permitir\".", color = KyberMuted, fontSize = 15.sp)
            PrimaryButton("Liberar segundo plano", onClick = { DeviceSetup.requestBatteryUnrestricted(context) })
        }
    }

    if (hint != null) {
        KyberCard {
            Text("Mais um ajuste neste modelo de celular", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(hint, color = KyberMuted, fontSize = 15.sp, lineHeight = 22.sp)
            SecondaryButton("Abrir essa configuração", onClick = { DeviceSetup.openAutostartSettings(context) })
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    autostartOk = !autostartOk
                    store.autostartConfirmed = autostartOk
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = autostartOk, onCheckedChange = null)
                Spacer(Modifier.width(10.dp))
                Text("Já fiz esse ajuste", fontSize = 16.sp)
            }
        }
    }

    Notice("Dica: deixe o celular e a impressora na tomada durante o expediente.", ok = true)

    val done = batteryOk && (hint == null || autostartOk)
    PrimaryButton("Continuar", onClick = onNext, enabled = done)
    if (!done) SkipButton(onNext)
}

// ---------------------------------------------------------------- 5. Som

@Composable
private fun SoundStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var played by remember { mutableStateOf(false) }

    StepIntro(
        Icons.Filled.VolumeUp,
        "Testar o som do pedido",
        "Quando chegar um pedido, o celular toca este alarme no volume máximo — mesmo que esteja no silencioso.",
    )

    SecondaryButton("Tocar o som de teste", onClick = {
        OrderSound.play(context)
        played = true
    })
    if (played) {
        Text(
            "Não ouviu? Veja se o celular não está no modo \"Não perturbe\" e toque de novo.",
            color = KyberMuted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
    PrimaryButton("Ouvi o som. Concluir", onClick = onNext, enabled = played, color = KyberGreen)
    if (!played) SkipButton(onNext)
}
