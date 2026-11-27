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
                UssdChainState.finish("completed")
                UssdResultStore.finish()
                runCatching {
                    GatewayApi.postResult(
                        this, SecureConfigStore.load(this), jobId, sessionId, "ussd", "completed",
                        JSONObject().put("session_id", sessionId).put("response", response)
                    )
                }
                LogStore.append(this, "USSD dialog closed; session marked completed.")
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
                    JSONObject().put("session_id", UssdChainState.sessionId).put("response", screenText)
                )
            }
        }

        val submitButton = findSubmitButton(root)
        if (inputNode != null && submitButton != null) {
            val promptFingerprint = fingerprint + ":" + (inputNode.viewIdResourceName ?: "edit")
            if (promptFingerprint != lastInputFingerprint) {
                val next = UssdChainState.takeNext()
                if (next != null) {
                    lastInputFingerprint = promptFingerprint
                    inputNode.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }
                    )
                    submitButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    LogStore.append(this, "Submitted next USSD chain item; input value redacted.")
                    runCatching {
                        GatewayApi.postResult(
                            this, SecureConfigStore.load(this), UssdChainState.jobId, UssdChainState.sessionId,
                            "ussd", "input_submitted", JSONObject().put("session_id", UssdChainState.sessionId)
                        )
                    }
                }
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
        val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().lowercase()
        if (node.isClickable && listOf("send", "reply", "ok", "submit", "continue", "send response").any { label.contains(it) }) return node
        for (index in 0 until node.childCount) {
            val found = node.getChild(index)?.let(::findSubmitButton)
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile private var activeService: UssdAccessibilityService? = null

        fun submitInput(sessionId: String, input: String) {
            require(UssdChainState.matches(sessionId)) { "No active USSD session matches this session_id." }
            UssdChainState.enqueue(input)
            activeService?.let { service ->
                Handler(Looper.getMainLooper()).post {
                    service.lastInputFingerprint = ""
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
