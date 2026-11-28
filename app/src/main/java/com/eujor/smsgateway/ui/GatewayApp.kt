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
    HOME("Home"), TOOLS("Tools"), LOGS("Logs"), API("API map"), REQUIREMENTS("Guide")
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
                        MainTab.API -> Icons.Filled.Link
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
                MainTab.API -> ApiMapScreen(config)
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
                        Button(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Disconnect")
                        }
                    } else {
                        Button(onClick = onConnect) {
                            Icon(Icons.Filled.Power, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Connect")
                        }
                    }
                }
            }
        }
        if (pendingUssdCallbacks.isNotEmpty()) {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unconfirmed USSD callbacks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("These events are awaiting the receiver server's acknowledgement. Stop retries to discard future delivery attempts for that event.", style = MaterialTheme.typography.bodySmall)
                    pendingUssdCallbacks.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${item.status} · attempts ${item.attempts}/6", fontWeight = FontWeight.SemiBold)
                                Text("Job: ${item.jobId} · Request: ${item.requestId}", style = MaterialTheme.typography.bodySmall)
                                Text("Event: ${item.eventId}", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onKillPendingUssd(item.eventId) }) { Text("Stop retries") }
                        }
                    }
                }
            }
        }
        StatusCard("Receiver server", serverStatus, Icons.Filled.Cloud)
        DeviceIdentityCard(config.deviceName, config.deviceId)
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
private fun DeviceIdentityCard(deviceName: String, deviceId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(27.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Device identity", style = MaterialTheme.typography.labelLarge)
                Text(deviceName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(deviceId, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = {
                clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                Toast.makeText(context, "Device ID copied.", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy device ID")
            }
        }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Activity logs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (logs.isNotEmpty()) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("EUJOR Activity Log", logs.joinToString("\n")))
                            Toast.makeText(context, "Activity log copied.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = logs.isNotEmpty()
                ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy activity log") }
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
private fun ApiMapScreen(config: AppConfig) {
    var baseUrl by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    val base = baseUrl.trim().trimEnd('/')
    val validBase = base.startsWith("https://", true)
    val pollUrl = if (validBase) {
        "$base/api/v1/agent/jobs/next?device_id=${config.deviceId}&wait_seconds=3&limit=20"
    } else ""

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Receiver API map", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "These are the HTTP routes the Android app calls. Your receiver/SMS server must implement them; the Android app does not host these routes.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Receiver base URL") },
            placeholder = { Text("https://gateway.example.com") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { baseUrl = config.serverUrl }) {
                    Icon(Icons.Filled.Restore, contentDescription = "Use configured receiver URL")
                }
            }
        )
        Text(
            "Preview only. The live connection uses the URL saved in Settings. Enter a base URL here to see the exact paths the app will append.",
            style = MaterialTheme.typography.bodySmall
        )
        if (!validBase) {
            Text("Enter a valid HTTPS base URL to build the endpoint list.", color = MaterialTheme.colorScheme.error)
        } else {
            ApiEndpointCard(
                title = "1. Server test — Home button",
                method = "GET",
                url = "$base/api/v1/agent/health",
                detail = "Called only when the user taps Test receiver server on Home. The response confirms that the receiver server is responding; it does not test the phone."
            )
            ApiEndpointCard(
                title = "2. Job polling — while connected",
                method = "GET",
                url = pollUrl,
                detail = "The Android agent holds this long-poll request for up to 3 seconds. If a job is available, the server returns it immediately. If no job is returned, the app immediately starts the next poll; 3 seconds is not a fixed delay between requests."
            )
            ApiEndpointCard(
                title = "3. Result callbacks — Android to server",
                method = "POST",
                url = "$base/api/v1/agent/results",
                detail = "The app posts SMS and USSD result events here. The receiver must save each event before responding with HTTP 200 and JSON {success:true,event_id:<same event_id>}."
            )
        }

        Text("Separate endpoint", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (config.heartbeatEnabled && config.heartbeatUrl.isNotBlank()) {
            ApiEndpointCard(
                title = "Optional heartbeat — independent URL",
                method = "POST",
                url = config.heartbeatUrl,
                detail = "Sent approximately once per minute only when Heartbeat is enabled. This complete URL is used exactly as entered; no path is appended to the receiver base URL."
            )
        } else {
            RequirementCard(
                "Optional heartbeat — disabled",
                "Heartbeat is off or has no URL. It is not sent. When enabled, the app POSTs to the complete HTTPS URL entered in Settings, independently of the receiver base URL."
            )
        }

        RequirementCard(
            "Not called by the Android app",
            "A receiver may also expose POST /api/v1/requests for third-party systems such as billing, OTP or receipt applications. That is an API hosted by the receiver server for those applications. The Android app does not call it; it obtains device jobs through the polling route above."
        )
        RequirementCard(
            "Request authentication",
            "Device requests use Authorization: Bearer <DEVICE_API_KEY> and X-Device-ID: <DEVICE_UUID>. Keep this device key separate from any API key issued to third-party applications."
        )
    }
}

@Composable
private fun ApiEndpointCard(title: String, method: String, url: String, detail: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        method,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(url, style = MaterialTheme.typography.bodySmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, url))
                    Toast.makeText(context, "Endpoint URL copied.", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy endpoint")
                }
            }
        }
    }
}

@Composable
private fun RequirementsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Application guide", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "The Android application is the device-side worker. The receiver server accepts third-party requests and queues them; the Android app retrieves each job and starts the operation using the phone's SIM.",
            style = MaterialTheme.typography.bodyMedium
        )

        GuideSection("1. Request flow", listOf(
            "Third-party application" to "Sends its request to the receiver server, for example POST /api/v1/requests. This endpoint belongs to the receiver server, not the Android app.",
            "Receiver server" to "Validates the requesting application's credentials and payload, then stores a job in the device queue.",
            "Android application" to "Polls GET /api/v1/agent/jobs/next, receives the queued job, and starts the SMS or USSD operation on the selected SIM. The server does not operate the SIM.",
            "Result callback" to "The Android app sends progress and final results to POST /api/v1/agent/results. The receiver stores each event and confirms it."
        ))

        GuideSection("2. USSD queue behavior", listOf(
            "No input chain" to "A missing, empty, or blank-only inputs array runs direct USSD through Android's TelephonyManager. It sends the code and receives one network response.",
            "Input chain supplied" to "A non-empty list of strings opens the visible carrier USSD session. The app submits each string as the matching prompt appears.",
            "Input format" to "Use a JSON array such as [\"1\", \"254712345678\"]. Blank entries are ignored. Do not add a fake value such as 0 to represent no input.",
            "Accessibility" to "Interactive/chained USSD requires EUJOR USSD Session Assistant to be enabled in Android Accessibility settings. Direct USSD does not require it.",
            "Final outcome" to "A completed callback means the USSD session ended. It does not by itself prove that a purchase, payment or subscription succeeded; inspect the returned carrier response."
        ))

        GuideSection("3. Application and device", listOf(
            "App version" to "Version \${BuildConfig.VERSION_NAME} (version code \${BuildConfig.VERSION_CODE}).",
            "Android version" to "Minimum Android 8.0 Oreo (API 26); compile SDK 36; target SDK 36.",
            "Device identity" to "The app generates a UUID for the phone. IMEI and IMSI are not required.",
            "SIM selection" to "Jobs specify sim_slot. The app uses that SIM slot to initiate the requested operation."
        ))

        GuideSection("4. Required permissions", listOf(
            "SEND_SMS" to "Allows the app to send SMS messages through the selected SIM.",
            "CALL_PHONE" to "Allows the app to start USSD sessions.",
            "READ_PHONE_STATE" to "Allows SIM/subscription selection.",
            "Notifications and foreground service" to "Keep the connection agent visible and running in the background.",
            "USSD Accessibility" to "Reads visible carrier prompts and submits the supplied input chain for interactive USSD.",
            "Internet" to "Connects to the receiver server and delivers result callbacks."
        ))

        GuideSection("5. Connection and endpoints", listOf(
            "Receiver base URL" to "Configure the receiver's HTTPS base URL in Settings. The app appends the API routes shown on the API map.",
            "Device API key" to "Use the device-specific bearer key and matching device UUID configured on the receiver server.",
            "Job polling" to "While connected, the Android app repeatedly long-polls GET /api/v1/agent/jobs/next. The receiver returns a job or HTTP 204 when no job is waiting.",
            "Health check" to "GET /api/v1/agent/health runs only when Test receiver server is tapped on Home.",
            "Heartbeat" to "Optional. When enabled, the app POSTs to the complete HTTPS heartbeat URL saved in Settings, approximately once per minute."
        ))

        GuideSection("6. Callback delivery and confirmation", listOf(
            "Event identity" to "Every result callback has a unique event_id.",
            "Required acknowledgement" to "The receiver must first save the event, then return HTTP 200 with {\"success\":true,\"event_id\":\"<same event_id>\"}.",
            "Retry policy" to "The app retries up to 6 times, 10 seconds apart; after a failed batch it waits 5 minutes before retrying.",
            "Retention" to "Unconfirmed callbacks remain in the encrypted outbox for up to 12 hours. They are not deleted by the daily log cleanup.",
            "Duplicate handling" to "The receiver must deduplicate by event_id and acknowledge a previously stored duplicate using the same event_id."
        ))

        GuideSection("7. Activity log and timestamps", listOf(
            "Incoming requests" to "The Activity Log records remote jobs received from the receiver queue, including their type, action, job ID and request ID. USSD codes and input values are not logged.",
            "Callback confirmation" to "The Activity Log records callback type and status after the receiver confirms the event with HTTP 200.",
            "Regional time" to "Activity log timestamps and timestamps sent in callback/heartbeat payloads use the phone's detected regional timezone and include the UTC offset, for example +03:00."
        ))

        GuideSection("8. Security and privacy", listOf(
            "Transport" to "Remote communication requires HTTPS with normal TLS certificate validation.",
            "Stored data" to "Device configuration, queued jobs and callback events are encrypted at rest using Android Keystore-backed AES-GCM.",
            "Credentials" to "Keep third-party application API keys separate from the Android device API key. Never put the device key in third-party requests.",
            "Sensitive values" to "The app excludes SMS message bodies, USSD codes and input values from diagnostic logs. Avoid putting SIM or mobile-money PINs in input chains."
        ))

        GuideSection("9. Installation and operation", listOf(
            "Install" to "Install the APK from the official EUJOR GitHub Releases page, then grant only the permissions needed for the features you use.",
            "Enable the agent" to "Open the Android application, configure the receiver URL and device API key, grant the required permissions, then start the connection agent from Home.",
            "Keep connected" to "Keep the phone powered, connected to the mobile network and online. Android battery management may restrict background operation.",
            "Queue safety" to "Interrupted SMS/USSD jobs are not automatically replayed because repeating a SIM operation could cause duplicate messages or charges."
        ))

        LinkButton("Open API protocol documentation") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eujor/smsgateway/blob/main/docs/API_PROTOCOL.md")))
        }
        LinkButton("Check latest GitHub release") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eujor/smsgateway/releases/latest")))
        }
        LinkButton("Open GitHub repository") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eujor/smsgateway")))
        }
    }
}

@Composable
private fun GuideSection(title: String, items: List<Pair<String, String>>) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            items.forEachIndexed { index, item ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.first, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(item.second, style = MaterialTheme.typography.bodyMedium)
                }
                if (index < items.lastIndex) Divider()
            }
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
    var confirmRegenerateDeviceId by remember { mutableStateOf(false) }
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
                    placeholder = { Text("https://example.com/heartbeat") },
                    singleLine = true
                )
                Text("Enter the complete HTTPS endpoint URL. The app sends POST to this exact URL and never appends a path or falls back to the receiver URL.", style = MaterialTheme.typography.bodySmall)
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
                    "Generate a unique key here, then configure the same key for this device in your receiver server. Generate a new key to rotate it.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(deviceName, { deviceName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Device name") }, singleLine = true)
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Device ID (UUID)") },
                    singleLine = true,
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                            Toast.makeText(context, "Device ID copied.", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy device ID")
                        }
                    }
                )
                Text("This app-generated ID is read-only. Copy it to register the phone on your receiver server.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmRegenerateDeviceId = true }) { Text("Regenerate device ID") }
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

    if (confirmRegenerateDeviceId) {
        AlertDialog(
            onDismissRequest = { confirmRegenerateDeviceId = false },
            title = { Text("Regenerate device ID?") },
            text = {
                Text("This creates a new device identity. You must update the device ID/key binding on the receiver server before reconnecting. The current ID will no longer be used by this app.")
            },
            confirmButton = {
                TextButton(onClick = {
                    deviceId = com.eujor.smsgateway.data.UuidV7.generate()
                    confirmRegenerateDeviceId = false
                }) { Text("Generate new ID") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerateDeviceId = false }) { Text("Keep current ID") }
            }
        )
    }
}
