package com.example.mg4blefix.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.mg4blefix.data.DevicePreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BtAutoPairAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BtAutoPairService"
        private const val DEBOUNCE_DELAY_MS = 500L
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var preferencesRepository: DevicePreferencesRepository

    @Volatile
    private var currentTargetDeviceName: String = DevicePreferencesRepository.DEFAULT_DEVICE_NAME

    private var lastHandledTime: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "BtAutoPairAccessibilityService connected")
        preferencesRepository = DevicePreferencesRepository(applicationContext)

        serviceScope.launch {
            preferencesRepository.targetDeviceName.collectLatest { deviceName ->
                currentTargetDeviceName = deviceName
                Log.d(TAG, "Updated target device name to: $currentTargetDeviceName")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastHandledTime < DEBOUNCE_DELAY_MS) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        processWindowHierarchy(rootNode)
    }

    override fun onInterrupt() {
        Log.w(TAG, "BtAutoPairAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "BtAutoPairAccessibilityService destroyed")
    }

    private fun processWindowHierarchy(rootNode: AccessibilityNodeInfo) {
        val targetName = currentTargetDeviceName
        val allNodes = mutableListOf<AccessibilityNodeInfo>()

        try {
            collectAllNodes(rootNode, allNodes)

            val fullTextBuilder = StringBuilder()
            for (node in allNodes) {
                node.text?.let { fullTextBuilder.append(it).append(" ") }
                node.contentDescription?.let { fullTextBuilder.append(it).append(" ") }
            }
            val screenText = fullTextBuilder.toString()

            // Verify screen contains target device name (e.g. "MG")
            if (!screenText.contains(targetName, ignoreCase = true)) {
                return
            }

            // Verify if this is a Bluetooth pairing or permission window
            val isTargetDialog = screenText.contains("pair", ignoreCase = true) ||
                    screenText.contains("bluetooth", ignoreCase = true) ||
                    screenText.contains("passkey", ignoreCase = true) ||
                    screenText.contains("confirm", ignoreCase = true) ||
                    screenText.contains("code", ignoreCase = true) ||
                    screenText.contains("contact", ignoreCase = true) ||
                    screenText.contains("call history", ignoreCase = true) ||
                    screenText.contains("call log", ignoreCase = true) ||
                    screenText.contains("phonebook", ignoreCase = true) ||
                    screenText.contains("message", ignoreCase = true) ||
                    screenText.contains("access", ignoreCase = true)

            if (!isTargetDialog) {
                return
            }

            Log.i(TAG, "Detected Bluetooth pairing/permission dialog for '$targetName'")

            // 1. Check contact access / call history checkbox if present and unchecked
            for (node in allNodes) {
                if (node.isCheckable) {
                    val nodeText = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
                    val isContactCheck = nodeText.contains("contact", ignoreCase = true) ||
                            nodeText.contains("call history", ignoreCase = true) ||
                            nodeText.contains("access", ignoreCase = true) ||
                            nodeText.isBlank()

                    if (isContactCheck && !node.isChecked) {
                        Log.i(TAG, "Checking contact/call history access checkbox...")
                        performClick(node)
                    }
                }
            }

            // 2. Locate and click positive confirmation button ("Pair", "Allow", "OK", "Confirm", "Grant")
            val positiveKeywords = listOf("Pair", "Allow", "OK", "Confirm", "Yes", "Grant")
            val negativeKeywords = listOf("Cancel", "Don't allow", "Deny", "No", "Reject")

            for (node in allNodes) {
                val text = (node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()) ?: continue

                // Ignore explicit negative options
                if (negativeKeywords.any { text.equals(it, ignoreCase = true) }) {
                    continue
                }

                // Check for positive button text
                val matchesPositive = positiveKeywords.any { keyword ->
                    text.equals(keyword, ignoreCase = true) || text.startsWith("Pair", ignoreCase = true)
                }

                if (matchesPositive) {
                    Log.i(TAG, "Attempting ACTION_CLICK on positive button: '$text'")
                    if (performClick(node)) {
                        lastHandledTime = System.currentTimeMillis()
                        Log.i(TAG, "Successfully accepted prompt '$text' for device '$targetName'")
                        break
                    }
                }
            }

        } finally {
            // Safely recycle nodes to avoid memory leaks
            @Suppress("DEPRECATION")
            for (node in allNodes) {
                try {
                    node.recycle()
                } catch (_: Exception) {}
            }
        }
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllNodes(child, list)
            }
        }
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var isOriginalNode = true

        while (current != null) {
            if (current.isClickable) {
                val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!isOriginalNode) {
                    @Suppress("DEPRECATION")
                    try { current.recycle() } catch (_: Exception) {}
                }
                if (clicked) {
                    return true
                }
            }
            val parent = current.parent
            if (!isOriginalNode) {
                @Suppress("DEPRECATION")
                try { current.recycle() } catch (_: Exception) {}
            }
            current = parent
            isOriginalNode = false
        }
        return false
    }
}
