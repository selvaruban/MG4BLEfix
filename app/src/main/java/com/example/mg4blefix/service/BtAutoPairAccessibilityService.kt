package com.example.mg4blefix.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.mg4blefix.data.DevicePreferencesRepository
import com.example.mg4blefix.util.BtCleanupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BtAutoPairAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BtAutoPairService"
        private const val DEBOUNCE_DELAY_MS = 1500L

        // Whitelisted system packages responsible for Bluetooth pairing & permission dialogs
        private val SYSTEM_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.settings.intelligence",
            "com.samsung.android.settings",
            "com.android.systemui",
            "com.android.bluetooth",
            "com.google.android.bluetooth",
            "com.samsung.android.bluetooth",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.samsung.android.permissioncontroller"
        )
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

        val pkgName = event.packageName?.toString() ?: return

        // 1. Ignore events originating from our own app UI
        if (pkgName == packageName) return

        // 2. Strict system package whitelist:
        // System Bluetooth pairing and permission prompts ONLY come from system settings, system UI, bluetooth stack, or permission controllers.
        val isSystemPackage = SYSTEM_PACKAGES.contains(pkgName) ||
                pkgName.endsWith(".settings") ||
                pkgName.endsWith(".bluetooth") ||
                pkgName.endsWith(".permissioncontroller")

        if (!isSystemPackage) {
            // Ignore all third-party app events (SmartThings, browsers, social apps, games, etc.)
            return
        }

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
        processWindowHierarchy(rootNode, pkgName)
    }

    override fun onInterrupt() {
        Log.w(TAG, "BtAutoPairAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "BtAutoPairAccessibilityService destroyed")
    }

    private fun processWindowHierarchy(rootNode: AccessibilityNodeInfo, pkgName: String) {
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

            // 3. Strict standalone word boundary device name matching (e.g. "MG", not "management" or "img")
            val wordBoundaryRegex = Regex("\\b" + Regex.escape(targetName) + "\\b", RegexOption.IGNORE_CASE)
            if (!wordBoundaryRegex.containsMatchIn(screenText)) {
                return
            }

            val lowerText = screenText.lowercase()

            // 4. Strict system dialog prompt verification
            val isPairingPrompt = lowerText.contains("pair") ||
                    lowerText.contains("passkey") ||
                    lowerText.contains("pairing code") ||
                    (lowerText.contains("bluetooth") && lowerText.contains("confirm"))

            val isContactPermissionPrompt = (lowerText.contains("contact") || lowerText.contains("call log")) &&
                    (lowerText.contains("access") || lowerText.contains("allow"))

            if (!isPairingPrompt && !isContactPermissionPrompt) {
                return
            }

            Log.i(TAG, "Detected valid system Bluetooth dialog from '$pkgName' for target '$targetName'")

            // 1. Check contact access checkbox if present and unchecked
            for (node in allNodes) {
                if (node.isCheckable) {
                    val nodeText = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
                    val isContactCheck = nodeText.contains("contact", ignoreCase = true) ||
                            nodeText.contains("call history", ignoreCase = true)

                    if (isContactCheck && !node.isChecked) {
                        Log.i(TAG, "Checking contact access checkbox...")
                        performClick(node)
                    }
                }
            }

            // 2. Locate and click positive confirmation button ("Pair", "Allow", "OK", "Confirm")
            val positiveKeywords = listOf("Pair", "Allow", "OK", "Confirm", "Yes", "Grant")
            val negativeKeywords = listOf("Cancel", "Don't allow", "Deny", "No", "Reject")

            for (node in allNodes) {
                val text = (node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()) ?: continue

                if (negativeKeywords.any { text.equals(it, ignoreCase = true) }) {
                    continue
                }

                val matchesPositive = positiveKeywords.any { keyword ->
                    text.equals(keyword, ignoreCase = true) || text.startsWith("Pair", ignoreCase = true)
                }

                if (matchesPositive) {
                    Log.i(TAG, "Attempting ACTION_CLICK on system dialog button: '$text'")
                    if (performClick(node)) {
                        val now = System.currentTimeMillis()
                        lastHandledTime = now
                        Log.i(TAG, "Successfully accepted system Bluetooth dialog '$text' for '$targetName'")

                        serviceScope.launch {
                            preferencesRepository.recordAutomatedAction(now)

                            // Automatically cleanup stale/duplicate "MG" entries in background
                            BtCleanupManager.cleanupStaleDevices(applicationContext, targetName)
                        }
                        break
                    }
                }
            }

        } finally {
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
