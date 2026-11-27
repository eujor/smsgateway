package com.eujor.smsgateway.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import android.widget.Toast
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eujor.smsgateway.BuildConfig
import com.eujor.smsgateway.data.AppConfig
import com.eujor.smsgateway.data.LogStore
import com.eujor.smsgateway.data.DailyMaintenance
import com.eujor.smsgateway.data.SecureConfigStore
import com.eujor.smsgateway.gateway.GatewayApi
import com.eujor.smsgateway.gateway.ResultOutbox
import com.eujor.smsgateway.gateway.PendingCallbackSummary
import com.eujor.smsgateway.gateway.ResultOutboxWorker
import com.eujor.smsgateway.gateway.GatewayRequest
import com.eujor.smsgateway.gateway.ManualRequestParser
import com.eujor.smsgateway.gateway.RequestParseResult
import com.eujor.smsgateway.service.GatewayAgentService
import com.eujor.smsgateway.telephony.*
import org.json.JSONObject
import java.util.UUID
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

private enum class MainTab(val label: String) {
    HOME("Home"), TOOLS("Tools"), LOGS("Logs"), REQUIREMENTS("Requirements")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var config by remember { mutableStateOf(SecureConfigStore.load(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var localWarning by remember { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<GatewayRequest?>(null) }
    var serverStatus by remember { mutableStateOf("Not checked") }
    var activityStatus by remember { mutableStateOf("Ready") }
    var logs by remember { mutableStateOf(LogStore.read(context)) }
    var agentConnected by remember { mutableStateOf(GatewayAgentService.isRunning()) }
    var ussdResponse by remember { mutableStateOf(UssdResultStore.latest) }
    var pendingUssdCallbacks by remember { mutableStateOf(emptyList<PendingCallbackSummary>()) }
    fun refreshPendingUssd() { ResultOutbox(context).use { pendingUssdCallbacks = it.pendingUssdCallbacks() } }
    fun killPendingUssd(eventId: String) {
        ResultOutboxWorker.cancel(context, eventId)
        LogStore.append(context, "Pending USSD callback retry cancelled by user: " + eventId)
        refreshPendingUssd()
        logs = LogStore.read(context)
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    fun refreshLogs() { logs = LogStore.read(context) }

    fun startAgent() {
        if (!GatewayApi.agentConfigured(config)) {
            activityStatus = "Enter a device API key and configure an HTTPS receiver URL or an enabled heartbeat URL in Settings."
            return
        }
        ContextCompat.startForegroundService(context, Intent(context, GatewayAgentService::class.java))
        serverStatus = "Agent starting"
        activityStatus = "Connection agent started."
        LogStore.append(context, "Connection agent start requested.")
        refreshLogs()
    }

    fun stopAgent() {
        context.stopService(Intent(context, GatewayAgentService::class.java))
        serverStatus = "Disconnected"
        activityStatus = "Connection agent stopped."
        LogStore.append(context, "Connection agent stopped by user.")
        refreshLogs()
    }

    fun checkServer() {
        if (config.serverUrl.isBlank()) {
            serverStatus = "Receiver URL is not configured."
            return
        }
        serverStatus = "Checking..."
        executor.execute {
            val result = runCatching { GatewayApi.testConnection(config) }
                .getOrElse { "Connection failed: " + (it.message ?: "unknown error") }
            android.os.Handler(android.os.Looper.getMainLooper()).post { serverStatus = result }
        }
    }

    fun execute(request: GatewayRequest) {
        activityStatus = "Preparing " + request.type.uppercase() + " request..."
        executor.execute {
            try {
                when {
                    request.type == "sms" -> {
                        check(ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            "Grant Send SMS permission from Home before sending."
                        }
                        SmsExecutor.send(context, config, request.jobId, request.requestId, request.recipient, request.message, request.simSlot)
                    }
                    request.type == "ussd" && request.action == "interactive" -> {
                        check(!UssdChainState.isActive()) { "Another USSD session is already active. Finish it before starting another." }
                        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            "Grant Phone permission from Home before testing USSD."
                        }
                        check(UssdAccessibilityService.isEnabled(context)) {
                            "Enable EUJOR USSD Session Assistant from Home so the app can display the live USSD screen."
                        }
                        UssdChainState.begin(request.requestId, request.jobId, request.simSlot, request.inputs)
                        UssdResultStore.update("Opening the carrier USSD screen...")
                        UssdExecutor.startInteractive(context, request.ussdCode, request.simSlot)
                        LogStore.append(context, "Quick USSD test opened a visible USSD session; input values redacted.")
                    }
                    request.type == "ussd" && request.action == "continue" -> {
                        check(UssdChainState.matches(request.sessionId)) { "No active USSD session matches this session_id." }
                        UssdAccessibilityService.submitInput(request.sessionId, request.inputs.first())
                        GatewayApi.postResult(context, config, request.jobId, request.requestId, "ussd", "input_queued",
                            JSONObject().put("session_id", request.sessionId).put("tracking_id", UssdChainState.trackingId))
                    }
                    request.type == "ussd" && request.action == "cancel" -> {
                        UssdChainState.finish("cancelled")
                        UssdResultStore.finish("USSD session cancelled.")
                        val lastResponse = UssdResultStore.latest
                        GatewayApi.postResult(context, config, request.jobId, request.requestId, "ussd", "cancelled",
                            JSONObject()
                                .put("session_id", request.sessionId)
                                .put("response", lastResponse)
                                .put("last_response", lastResponse)
                                .put("session_ended", true)
                                .put("end_reason", "cancelled")
                                .put("carrier_outcome", UssdResultStore.classifyCarrierOutcome(lastResponse))
                                .put("tracking_id", UssdChainState.trackingId))
                    }
                    request.type == "ussd" && request.inputs.isNotEmpty() -> {
                        check(!UssdChainState.isActive()) { "Another USSD session is already active. Finish it before starting another." }
                        check(UssdAccessibilityService.isEnabled(context)) {
                            "Enable EUJOR USSD Session Assistant from Home before using a chain."
                        }
                        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            "Grant Phone permission from Home before starting chained USSD."
                        }
                        UssdChainState.begin(request.requestId, request.jobId, request.simSlot, request.inputs)
                        UssdResultStore.update("Opening USSD session...")
                        UssdExecutor.startInteractive(context, request.ussdCode, request.simSlot)
                        LogStore.append(context, "USSD chain started with " + request.inputs.size + " inputs; values redacted.")
                    }
                    request.type == "ussd" -> {
                        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            "Grant Phone permission from Home before testing USSD."
                        }
                        check(!UssdChainState.isActive()) { "Another USSD session is already active. Finish it before starting another." }
                        UssdChainState.begin(request.requestId, request.jobId, request.simSlot, emptyList())
                        UssdResultStore.update("USSD request sent. Waiting for the network...")
                        UssdExecutor.sendDirect(context, config, request.jobId, request.requestId, request.ussdCode, request.simSlot, UssdChainState.trackingId)
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activityStatus = "Request triggered. Check the response panel and Logs."
                    refreshLogs()
                }
            } catch (e: Exception) {
                if (UssdChainState.matches(request.requestId)) UssdChainState.finish("failed")
                LogStore.append(context, "Manual trigger failed: " + (e.message ?: "unknown error"))
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activityStatus = e.message ?: "Manual trigger failed."
                    refreshLogs()
                }
            }
        }
    }

    fun requestManual(request: GatewayRequest) {
        pendingRequest = request
        if (!GatewayApi.remoteConfigured(config)) localWarning = true else execute(request)
    }

    LaunchedEffect(Unit) {
        DailyMaintenance.runIfDue(context)
        ResultOutboxWorker.kick(context)
        refreshPendingUssd()
        while (true) {
            ussdResponse = UssdResultStore.latest
            agentConnected = GatewayAgentService.isRunning()
            refreshPendingUssd()
            delay(1500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("EUJOR SMS Gateway", fontWeight = FontWeight.Bold)
                        Text("SIM messaging & USSD control", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEachIndexed { index, item ->
                    val icon = when (item) {
                        MainTab.HOME -> Icons.Filled.Home
                        MainTab.TOOLS -> Icons.Filled.Terminal
                        MainTab.LOGS -> Icons.Filled.ListAlt
                        MainTab.REQUIREMENTS -> Icons.Filled.Info
                    }
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            when (MainTab.entries[tab]) {
                MainTab.HOME -> HomeScreen(
                    config, serverStatus, activityStatus,
                    onConnect = { startAgent() },
                    onDisconnect = { stopAgent() },
                    onCheckServer = { checkServer() },
                    onSettings = { settingsOpen = true },
                    agentConnected = agentConnected,
                    pendingUssdCallbacks = pendingUssdCallbacks,
                    onKillPendingUssd = { killPendingUssd(it) }
                )
                MainTab.TOOLS -> ToolsScreen(
                    config, ussdResponse, activityStatus,
                    onTrigger = { requestManual(it) },
                    onCancelSession = {
                        val id = UssdChainState.sessionId
                        if (id.isNotBlank()) requestManual(
                            GatewayRequest(id, id, "ussd", "cancel", UssdChainState.simSlot, sessionId = id)
                        )
                    }
                )
                MainTab.LOGS -> LogsScreen(
                    logs,
                    onRefresh = { refreshLogs() },
                    onClear = { LogStore.clear(context); refreshLogs() }
                )
                MainTab.REQUIREMENTS -> RequirementsScreen()
            }
        }
    }

    if (settingsOpen) {
        SettingsDialog(config, onDismiss = { settingsOpen = false }, onSave = {
            SecureConfigStore.save(context, it)
            config = it
            settingsOpen = false
            activityStatus = "Configuration saved securely on this device."
            LogStore.append(context, "Device configuration updated.")
            refreshLogs()
        })
    }

    if (localWarning && pendingRequest != null) {
        AlertDialog(
            onDismissRequest = { localWarning = false; pendingRequest = null },
            title = { Text("Run locally without a receiver?") },
            text = {
                Text("No complete receiver URL and device API key are configured. This will run on this phone only; its result will not be sent to a server. Continue?")
            },
            confirmButton = {
                TextButton(onClick = {
                    localWarning = false
                    pendingRequest?.let { execute(it) }
                    pendingRequest = null
                }) { Text("Run on this phone") }
            },
            dismissButton = {
                TextButton(onClick = { localWarning = false; pendingRequest = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    config: AppConfig,
    serverStatus: String,
    activityStatus: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCheckServer: () -> Unit,
    onSettings: () -> Unit,
    agentConnected: Boolean,
    pendingUssdCallbacks: List<PendingCallbackSummary>,
    onKillPendingUssd: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissions = buildList {
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRefresh++
    }
    val refreshKey = permissionRefresh
    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val granted = permissions.count { hasPermission(it) }
    val smsSendGranted = hasPermission(Manifest.permission.SEND_SMS)
    val phoneGranted = hasPermission(Manifest.permission.CALL_PHONE)
    val phoneStateGranted = hasPermission(Manifest.permission.READ_PHONE_STATE)
    val notificationsGranted = Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    val batteryAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    } else true
    val accessibility = UssdAccessibilityService.isEnabled(context)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Your SIM gateway", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("Connect this phone to a self-hosted SMS and USSD receiver server.")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (agentConnected) androidx.compose.ui.graphics.Color(0xFFE8F5E9) else androidx.compose.ui.graphics.Color(0xFFFFEBEE),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            if (agentConnected) "CONNECTED" else "DISCONNECTED",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            color = if (agentConnected) androidx.compose.ui.graphics.Color(0xFF16803C) else androidx.compose.ui.graphics.Color(0xFFB3261E),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (agentConnected) {
                        Button(onClick = onConnect, colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF16803C))) { Text("Connect") }
                    } else OutlinedButton(onClick = onConnect) { Text("Connect") }
                    if (!agentConnected) {
                        Button(onClick = onDisconnect, colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFB3261E))) { Text("Disconnect") }
                    } else OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Unconfirmed USSD callbacks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("All USSD callbacks awaiting server acknowledgement, including screen updates. Kill stops future retries for that event.", style = MaterialTheme.typography.bodySmall)
                if (pendingUssdCallbacks.isEmpty()) Text("No unconfirmed USSD callbacks.")
                pendingUssdCallbacks.forEach { item ->
                    Divider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Status: ${item.status} · retry attempts: ${item.attempts}/6", fontWeight = FontWeight.SemiBold)
                            Text("Job: ${item.jobId} · Request: ${item.requestId}", style = MaterialTheme.typography.bodySmall)
                            Text("Event: ${item.eventId}", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { onKillPendingUssd(item.eventId) }) { Text("Kill") }
                    }
                }
            }
        }
        StatusCard("Receiver server", serverStatus, Icons.Filled.Cloud)
        StatusCard("Device identity", config.deviceName + "\n" + config.deviceId, Icons.Filled.Devices)
        StatusCard("Runtime permissions", "$granted of ${permissions.size} granted", Icons.Filled.Security, granted == permissions.size)
        StatusCard("USSD accessibility", if (accessibility) "Enabled" else "Needs activation", Icons.Filled.PhoneAndroid, accessibility)
        StatusCard("Battery optimisation", if (batteryAllowed) "Exemption active" else "May restrict background service", Icons.Filled.BatteryChargingFull, batteryAllowed)

        Card {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Setup & connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (granted == permissions.size) {
                    Button(onClick = { permissionLauncher.launch(permissions.toTypedArray()) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF16803C))) {
                        Text("SMS / Phone permissions granted")
                    }
                } else {
                    OutlinedButton(onClick = { permissionLauncher.launch(permissions.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant SMS / Phone permissions")
                    }
                }
                if (accessibility) {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF16803C))) {
                        Text("USSD accessibility enabled")
                    }
                } else OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable USSD accessibility")
                }
                if (batteryAllowed) {
                    Button(onClick = {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
                            else context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF16803C))) {
                        Text("Background battery exemption active")
                    }
                } else OutlinedButton(onClick = {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
                        else context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow background operation (battery)") }
                if (serverStatus.contains("responded", true)) {
                    Button(onClick = onCheckServer, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF16803C))) { Text("Receiver server responding") }
                } else OutlinedButton(onClick = onCheckServer, modifier = Modifier.fillMaxWidth()) { Text("Test receiver server") }
                OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Configure server URL and device key") }
                Divider()
                Text("Permission status", fontWeight = FontWeight.Bold)
                PermissionStatusRow("Send SMS", smsSendGranted)
                PermissionStatusRow("Phone / USSD", phoneGranted)
                PermissionStatusRow("SIM information", phoneStateGranted)
                if (Build.VERSION.SDK_INT >= 33) PermissionStatusRow("Notifications", notificationsGranted)
                PermissionStatusRow("USSD accessibility", accessibility)
                PermissionStatusRow("Battery exemption", batteryAllowed)
            }
        }
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Sensitive access notice", fontWeight = FontWeight.Bold)
                Text("Ordinary incoming SMS messages are not read or forwarded. The USSD assistant reads the visible carrier USSD window only during an active USSD session so it can report carrier prompts and enter responses you provide.")
            }
        }
        Text("Latest activity: " + activityStatus, style = MaterialTheme.typography.bodyMedium)
        Text("Keep the phone powered, online and connected to the mobile network while the gateway is active.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    allowed: Boolean? = null
) {
    val statusColor = when (allowed) {
        true -> androidx.compose.ui.graphics.Color(0xFF16803C)
        false -> androidx.compose.ui.graphics.Color(0xFFB3261E)
        null -> MaterialTheme.colorScheme.secondary
    }
    val background = when (allowed) {
        true -> androidx.compose.ui.graphics.Color(0xFFE8F5E9)
        false -> androidx.compose.ui.graphics.Color(0xFFFFEBEE)
        null -> MaterialTheme.colorScheme.surface
    }
    Card(colors = CardDefaults.cardColors(containerColor = background)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = statusColor, modifier = Modifier.size(27.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = statusColor)
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, allowed: Boolean) {
    val color = if (allowed) androidx.compose.ui.graphics.Color(0xFF16803C)
        else androidx.compose.ui.graphics.Color(0xFFB3261E)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (allowed) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = color
        )
        Spacer(Modifier.size(9.dp))
        Text(label, modifier = Modifier.weight(1f))
        Text(if (allowed) "Allowed" else "Not allowed", color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ToolsScreen(
    config: AppConfig,
    latestUssdResult: String,
    activityStatus: String,
    onTrigger: (GatewayRequest) -> Unit,
    onCancelSession: () -> Unit
) {
    var json by remember {
        mutableStateOf(
            """{
  "request_id": "manual-test-001",
  "type": "ussd",
  "action": "execute",
  "sim_slot": 1,
  "code": "*188#",
  "inputs": ["1", "254712345678"]
}"""
        )
    }
    var validation by remember { mutableStateOf("Paste or edit a JSON request, then validate it.") }
    var isValid by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("*188#") }
    var inputs by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf("1") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Manual JSON trigger", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("The app validates the request before any SIM action. A receiver server is optional for a local test.")
        OutlinedTextField(
            value = json, onValueChange = { json = it; isValid = false },
            modifier = Modifier.fillMaxWidth().height(250.dp), label = { Text("Expected request JSON") }, minLines = 7
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                when (val parsed = ManualRequestParser.parse(json)) {
                    is RequestParseResult.Valid -> { isValid = true; validation = "Valid ${parsed.request.type.uppercase()} request." }
                    is RequestParseResult.Invalid -> { isValid = false; validation = parsed.issue }
                }
            }) { Text("Validate JSON") }
            Button(onClick = {
                when (val parsed = ManualRequestParser.parse(json)) {
                    is RequestParseResult.Valid -> { isValid = true; validation = "Request validated and triggered."; onTrigger(parsed.request) }
                    is RequestParseResult.Invalid -> { isValid = false; validation = parsed.issue }
                }
            }) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.size(5.dp)); Text("Trigger") }
        }
        Text(validation, color = if (isValid) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
        Text("Latest action: " + activityStatus, style = MaterialTheme.typography.bodySmall)
        Divider()
        Text("Quick USSD test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(code, { code = it }, modifier = Modifier.fillMaxWidth(), label = { Text("USSD code, e.g. *188#") })
        OutlinedTextField(
            slot, { slot = it.filter(Char::isDigit).take(1) },
            modifier = Modifier.fillMaxWidth(), label = { Text("SIM slot (1 = SIM 1)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            inputs, { inputs = it }, modifier = Modifier.fillMaxWidth().height(110.dp),
            label = { Text("Optional input chain: one response per line") }, minLines = 3
        )
        Text("No inputs uses direct USSD. Add inputs to feed one response at a time as network prompts appear.")
        Button(
            onClick = {
                val ussd = code.trim()
                if (!ussd.startsWith("*") || !ussd.endsWith("#")) {
                    isValid = false
                    validation = "Enter a USSD code such as *188#."
                } else {
                    val id = com.eujor.smsgateway.data.UuidV7.generate()
                    val chain = inputs.lines().filter { it.isNotBlank() }
                    isValid = true
                    validation = "USSD test starting..."
                    onTrigger(GatewayRequest(id, id, "ussd", "interactive", slot.toIntOrNull() ?: 1, ussdCode = ussd, inputs = chain))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Icon(Icons.Filled.PhoneAndroid, null); Spacer(Modifier.size(6.dp)); Text("Run USSD test") }

        if (UssdChainState.isActive()) {
            OutlinedButton(onClick = onCancelSession, modifier = Modifier.fillMaxWidth()) { Text("Cancel active USSD session") }
        }
        if (latestUssdResult.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Latest USSD screen / response", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { UssdResultStore.clear() }) { Text("Clear") }
                    }
                    Text(latestUssdResult)
                }
            }
        }
        Text(
            "Receiver: " + if (GatewayApi.remoteConfigured(config)) config.serverUrl else "not configured — result stays on this phone",
            style = MaterialTheme.typography.bodySmall
        )
        Text("USSD may initiate purchases, subscriptions or money transfers. Only use commands and inputs you understand.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LogsScreen(logs: List<String>, onRefresh: () -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Activity logs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                TextButton(onClick = { confirmClear = true }) { Text("Clear logs") }
            }
        }
        Text("Up to 300 entries. Message bodies, API keys and USSD input values are excluded.", style = MaterialTheme.typography.bodySmall)
        if (logs.isEmpty()) Text("No log entries yet.")
        else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            logs.forEach { entry -> Card { Text(entry, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall) } }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear local logs?") },
            text = { Text("This permanently clears the activity history stored on this phone.") },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RequirementsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Requirements & protocol", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        RequirementCard("Application", "Version ${BuildConfig.VERSION_NAME} (version code ${BuildConfig.VERSION_CODE})\nMinimum Android: Android 8.0 Oreo (API 26)\nCompile SDK: 36\nTarget SDK: 36\nDevice identity: app-generated UUID; IMEI/IMSI not required.")
        RequirementCard("Permissions", "SMS send: SEND_SMS\nUSSD / call chain: CALL_PHONE\nSIM selection: READ_PHONE_STATE\nBackground agent: foreground service and notifications\nMulti-step USSD: enable EUJOR USSD Session Assistant in Android Accessibility settings.")
        RequirementCard("Server → app input", "SMS: type=sms, action=send, to and message. USSD: type=ussd, action=execute, code and unique tracking_id. Optional inputs is an array of strings entered one prompt at a time with a 2-second minimum delay. Continuation and cancellation must repeat the same tracking_id and session_id.")
        RequirementCard("App → server output", "Every callback has a unique event_id. The receiver MUST return HTTP 200 with JSON {success:true,event_id:<same event_id>} to confirm receipt. HTTP 202, other status codes, missing JSON or mismatched event_id are not accepted. The encrypted outbox retries up to 6 times, 10 seconds apart, then waits 5 minutes before another batch; restored connectivity triggers an immediate retry. Events expire after 12 hours. Pending callbacks are separate from 02:00 log/job maintenance and are not truncated by it. Receiver must deduplicate event_id.")
        RequirementCard("USSD modes", "Direct mode uses TelephonyManager.sendUssdRequest() for a single request and response. Chained mode opens the visible carrier USSD dialog and enters each queued response with a 2-second minimum interval. Every server-triggered USSD callback carries its tracking_id. OEM and carrier behaviour varies.")
        RequirementCard("Heartbeat", "Heartbeat is optional and has its own on/off switch and complete HTTPS endpoint URL in Settings. When enabled, the app sends POST directly to that exact URL about every 60 seconds; it does not append a path or use the SMS receiver URL. When disabled, no heartbeat request is sent.")
        RequirementCard("Security", "HTTPS only. Per-device bearer key encrypted with Android Keystore AES-GCM. The app can generate a random 256-bit device key; configure the same key and device UUID on the receiver server. The app sends no registration request. UUID device ID; no IMEI/IMSI. Queue payloads are encrypted at rest. Avoid storing SIM PINs or mobile-money PINs in sample JSON. Inputs are excluded from local logs.")
        RequirementCard("Installation notice", "This app requests SMS and optional Accessibility capabilities for its core gateway functions. Google Play Protect may review installations from internet-sideloading sources because the app uses SMS and Accessibility capabilities. Install only builds from the trusted EUJOR repository; keep device protection enabled whenever possible.")
        LinkButton("Open API protocol documentation") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eujor/smsgateway/blob/main/docs/API_PROTOCOL.md")))
        }
        LinkButton("Check latest GitHub release / tag") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eujor/smsgateway/releases/latest")))
        }
        LinkButton("Open GitHub repository") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eujor/smsgateway")))
        }
    }
}

@Composable
private fun RequirementCard(title: String, body: String) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Info, null)
        Spacer(Modifier.size(7.dp))
        Text(label)
    }
}

@Composable
private fun SettingsDialog(initial: AppConfig, onDismiss: () -> Unit, onSave: (AppConfig) -> Unit) {
    var server by remember { mutableStateOf(initial.serverUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var deviceId by remember { mutableStateOf(initial.deviceId) }
    var deviceName by remember { mutableStateOf(initial.deviceName) }
    var heartbeatUrl by remember { mutableStateOf(initial.heartbeatUrl) }
    var heartbeatEnabled by remember { mutableStateOf(initial.heartbeatEnabled) }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gateway settings") },
        text = {
            Column(Modifier.fillMaxWidth().height(640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(server, { server = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Receiver server URL") }, placeholder = { Text("https://gateway.example.com") }, singleLine = true)
                Text("Enter the base URL, not an API path. Remote connections require HTTPS.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Heartbeat", fontWeight = FontWeight.SemiBold)
                        Text(if (heartbeatEnabled) "Enabled — sent only to the URL below." else "Disabled — no heartbeat requests are sent.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = heartbeatEnabled, onCheckedChange = { heartbeatEnabled = it })
                }
                OutlinedTextField(
                    value = heartbeatUrl,
                    onValueChange = { heartbeatUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Complete heartbeat URL") },
                    placeholder = { Text("https://example.com/heartbeat.php") },
                    singleLine = true
                )
                Text("Enter the complete endpoint, including any PHP filename or path. The app sends POST to this exact URL and never appends a path or falls back to the receiver URL.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Device API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showKey) "Hide API key" else "Show API key"
                                )
                            }
                            IconButton(onClick = {
                                if (apiKey.isNotBlank()) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Device API key", apiKey))
                                    Toast.makeText(context, "Device API key copied.", Toast.LENGTH_SHORT).show()
                                } else error = "Generate or enter a device API key first."
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy API key")
                            }
                        }
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val bytes = ByteArray(32)
                        SecureRandom().nextBytes(bytes)
                        apiKey = Base64.encodeToString(
                            bytes,
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                        )
                        error = ""
                        showKey = false
                    }) { Text("Generate key") }
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                }
                Text(
                    "Generate a unique key here, then register the same key for this device in your receiver server. Generate a new key to rotate it.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(deviceName, { deviceName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Device name") }, singleLine = true)
                OutlinedTextField(deviceId, { deviceId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Device ID (UUID)") }, singleLine = true)
                TextButton(onClick = { deviceId = com.eujor.smsgateway.data.UuidV7.generate() }) { Text("Generate new device ID") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalized = server.trim().trimEnd('/')
                if (normalized.isNotEmpty() && !normalized.startsWith("https://", true)) error = "Receiver URL must start with https://"
                else if (heartbeatEnabled && !heartbeatUrl.trim().startsWith("https://", true)) error = "Enabled heartbeat requires its own complete HTTPS URL."
                else if (deviceId.isBlank() || deviceName.isBlank()) error = "Device ID and device name are required."
                else onSave(AppConfig(normalized, apiKey.trim(), deviceId.trim(), deviceName.trim(), heartbeatUrl.trim(), heartbeatEnabled))
            }) { Text("Save securely") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
