package com.eujor.smsgateway.telephony

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.eujor.smsgateway.data.LogStore
import com.eujor.smsgateway.data.SecureConfigStore
import com.eujor.smsgateway.gateway.GatewayApi
import org.json.JSONObject

class UssdAccessibilityService : AccessibilityService() {
    private var lastScreenFingerprint = ""
    private var lastInputFingerprint = ""
    private var scheduledPromptFingerprint = ""
    private val chainHandler = Handler(Looper.getMainLooper())
    private var sawUssdWindow = false
    private val closeHandler = Handler(Looper.getMainLooper())
    private var closeCheck: Runnable? = null

    override fun onServiceConnected() {
        activeService = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!UssdChainState.isActive()) {
            sawUssdWindow = false
            closeCheck?.let(closeHandler::removeCallbacks)
            closeCheck = null
            return
        }

        val root = rootInActiveWindow
        val sourcePackage = root?.packageName?.toString().orEmpty().lowercase()
        if (isUssdPackage(sourcePackage)) {
            sawUssdWindow = true
            closeCheck?.let(closeHandler::removeCallbacks)
            closeCheck = null
            if (root != null) processActiveWindow(root)
        } else if (sawUssdWindow && event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleCloseCheck()
        }
    }

    private fun scheduleCloseCheck() {
        closeCheck?.let(closeHandler::removeCallbacks)
        val currentSession = UssdChainState.sessionId
        closeCheck = Runnable {
            if (!UssdChainState.matches(currentSession)) return@Runnable
            val root = rootInActiveWindow
            val packageName = root?.packageName?.toString().orEmpty().lowercase()
            if (!isUssdPackage(packageName)) {
                val sessionId = UssdChainState.sessionId
                val jobId = UssdChainState.jobId
                val response = UssdResultStore.latest
                val trackingId = UssdChainState.trackingId
                val outcome = UssdResultStore.classifyCarrierOutcome(response)
                UssdChainState.finish("completed")
                UssdResultStore.finish()
                runCatching {
                    GatewayApi.postResult(
                        this, SecureConfigStore.load(this), jobId, sessionId, "ussd", "completed",
                        JSONObject()
                            .put("session_id", sessionId)
                            .put("response", response)
                            .put("last_response", response)
                            .put("session_ended", true)
                            .put("end_reason", "dialog_closed")
                            .put("carrier_outcome", outcome)
                            .put("tracking_id", trackingId)
                    )
                }
                LogStore.append(this, "USSD dialog closed; final carrier response queued (" + outcome + ").")
                sawUssdWindow = false
                lastScreenFingerprint = ""
                lastInputFingerprint = ""
            } else if (root != null) {
                processActiveWindow(root)
            }
        }.also { closeHandler.postDelayed(it, 1_500) }
    }

    private fun isUssdPackage(packageName: String): Boolean =
        listOf("phone", "telecom", "dialer", "telephony").any { packageName.contains(it) }

    private fun processActiveWindow(root: AccessibilityNodeInfo) {
        if (!UssdChainState.isActive()) return
        val sourcePackage = root.packageName?.toString().orEmpty().lowercase()
        if (!isUssdPackage(sourcePackage)) return

        val screenText = collectText(root).trim()
        if (screenText.isBlank()) return
        val inputNode = findEditable(root)
        val isDialog = root.className?.toString()?.contains("dialog", true) == true
        if (inputNode == null && !isDialog) return

        val fingerprint = screenText.hashCode().toString()
        if (fingerprint != lastScreenFingerprint) {
            lastScreenFingerprint = fingerprint
            UssdResultStore.update(screenText)
            runCatching {
                GatewayApi.postResult(
                    this, SecureConfigStore.load(this), UssdChainState.jobId, UssdChainState.sessionId,
                    "ussd", "screen_updated",
                    JSONObject().put("session_id", UssdChainState.sessionId).put("response", screenText).put("tracking_id", UssdChainState.trackingId)
                )
            }
        }

        if (inputNode != null) {
            val promptFingerprint = fingerprint + ":" + (inputNode.viewIdResourceName ?: "edit")
            if (promptFingerprint != lastInputFingerprint && promptFingerprint != scheduledPromptFingerprint &&
                UssdChainState.peekNext() != null
            ) {
                scheduledPromptFingerprint = promptFingerprint
                chainHandler.postDelayed({
                    if (!UssdChainState.isActive() || promptFingerprint != scheduledPromptFingerprint) return@postDelayed
                    val currentRoot = rootInActiveWindow ?: run { scheduledPromptFingerprint = ""; return@postDelayed }
                    if (!isUssdPackage(currentRoot.packageName?.toString().orEmpty().lowercase())) { scheduledPromptFingerprint = ""; return@postDelayed }
                    val currentText = collectText(currentRoot).trim()
                    val currentInput = findEditable(currentRoot)
                    val currentFingerprint = currentText.hashCode().toString() + ":" + (currentInput?.viewIdResourceName ?: "edit")
                    if (currentFingerprint != promptFingerprint) {
                        scheduledPromptFingerprint = ""
                        return@postDelayed
                    }
                    val editable = currentInput ?: run { scheduledPromptFingerprint = ""; return@postDelayed }
                    val button = findSubmitButton(currentRoot)
                    val nextInput = UssdChainState.peekNext() ?: run { scheduledPromptFingerprint = ""; return@postDelayed }
                    val textSet = editable.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, nextInput) }
                    )
                    val textVerified = textSet && editable.refresh() && editable.text?.toString() == nextInput
                    val clicked = if (!textVerified) false
                        else if (button != null) button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        else if (android.os.Build.VERSION.SDK_INT >= 30) editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        else false
                    if (clicked) {
                        UssdChainState.takeNext()
                        lastInputFingerprint = promptFingerprint
                        scheduledPromptFingerprint = ""
                        LogStore.append(this, "Submitted next USSD chain item after 2-second pacing; input value redacted.")
                        GatewayApi.postResult(
                            this, SecureConfigStore.load(this), UssdChainState.jobId, UssdChainState.sessionId,
                            "ussd", "input_submitted",
                            JSONObject().put("session_id", UssdChainState.sessionId).put("tracking_id", UssdChainState.trackingId)
                        )
                    } else {
                        LogStore.append(this, "Android did not accept the USSD input action; retrying.")
                        scheduledPromptFingerprint = ""
                        chainHandler.postDelayed({ rootInActiveWindow?.let(::processActiveWindow) }, 500L)
                    }
                }, 2_000L)
            }
        }
    }

    override fun onInterrupt() {
        LogStore.append(this, "USSD accessibility interaction interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeService === this) activeService = null
        closeCheck?.let(closeHandler::removeCallbacks)
        closeCheck = null
        chainHandler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    private fun collectText(node: AccessibilityNodeInfo): String {
        val output = StringBuilder()
        fun walk(current: AccessibilityNodeInfo) {
            current.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                if (output.isNotEmpty()) output.append('\n')
                output.append(it)
            }
            for (index in 0 until current.childCount) current.getChild(index)?.let(::walk)
        }
        walk(node)
        return output.toString().take(3000)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (index in 0 until node.childCount) {
            val found = node.getChild(index)?.let(::findEditable)
            if (found != null) return found
        }
        return null
    }

    private fun findSubmitButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun walk(current: AccessibilityNodeInfo) {
            val label = (current.text?.toString().orEmpty() + " " + current.contentDescription?.toString().orEmpty()).lowercase()
            val className = current.className?.toString().orEmpty()
            if (current.isClickable && !current.isEditable) {
                val negative = listOf("cancel", "close", "back", "dismiss", "no").any { label.contains(it) }
                val labelled = listOf("send","reply","ok","submit","continue","send response","respond","answer","go","yes").any { label.contains(it) }
                val buttonLike = label.isBlank() && (className.contains("button", true) || className.contains("imagebutton", true))
                if (!negative && (labelled || buttonLike)) candidates.add(current)
            }
            for (index in 0 until current.childCount) current.getChild(index)?.let(::walk)
        }
        walk(node)
        return candidates.firstOrNull { candidate ->
            val label = (candidate.text?.toString().orEmpty() + " " + candidate.contentDescription?.toString().orEmpty()).lowercase()
            listOf("send","reply","ok","submit","continue","send response","respond","answer","go","yes").any { label.contains(it) }
        } ?: candidates.firstOrNull()
    }

    companion object {
        @Volatile private var activeService: UssdAccessibilityService? = null

        fun submitInput(sessionId: String, input: String) {
            require(UssdChainState.matches(sessionId)) { "No active USSD session matches this session_id." }
            UssdChainState.enqueue(input)
            activeService?.let { service ->
                Handler(Looper.getMainLooper()).post {
                    service.lastInputFingerprint = ""
                    service.scheduledPromptFingerprint = ""
                    service.rootInActiveWindow?.let(service::processActiveWindow)
                }
            }
        }

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, UssdAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
