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
        private const val DEBOUNCE_DELAY_MS = 1000L

        // Whitelisted system & framework packages that render Bluetooth pairing & permission popups
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
            "com.samsung.android.permissioncontroller",
            "com.google.android.gms",                       // Google Play Services (renders "Check pairing codes")
            "com.google.android.companiondeviceprovider",
            "com.samsung.android.easysetup",
            "com.samsung.android.mobileservice"
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

        // 2. System & framework package whitelist
        val isSystemPackage = SYSTEM_PACKAGES.contains(pkgName) ||
                pkgName.endsWith(".settings") ||
                pkgName.endsWith(".bluetooth") ||
                pkgName.endsWith(".permissioncontroller") ||
                pkgName.contains("gms")

        if (!isSystemPackage) {
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

            // 1. Standalone word boundary device name matching (e.g. "MG")
            val wordBoundaryRegex = Regex("\\b" + Regex.escape(targetName) + "\\b", RegexOption.IGNORE_CASE)
            if (!wordBoundaryRegex.containsMatchIn(screenText)) {
                return
            }

            val lowerText = screenText.lowercase()
            val lowerTarget = targetName.lowercase()

            // 2. Match Popup #1: Pairing Confirmation Dialog
            // "Check pairing codes" - "Pair with MG? Confirm that this passkey is shown on MG." - "For security, don't enter this code anywhere."
            val isPairingDialog = lowerText.contains("check pairing codes") ||
                    lowerText.contains("pair with $lowerTarget") ||
                    (lowerText.contains("passkey") && lowerText.contains("shown on")) ||
                    lowerText.contains("don't enter this code anywhere") ||
                    (lowerText.contains("pairing code") && lowerText.contains("security"))

            // 3. Match Popup #2: Contact Access Permission Dialog
            // "Allow access to contacts?" - "A Bluetooth device (MG) wants to access your contacts and call history."
            val isContactDialog = lowerText.contains("allow access to contacts") ||
                    (lowerText.contains("wants to access your contacts") && lowerText.contains("call history")) ||
                    (lowerText.contains("incoming and outgoing calls") && lowerText.contains("connected to $lowerTarget")) ||
                    (lowerText.contains("contact") && lowerText.contains("call history") && lowerText.contains("allow"))

            if (!isPairingDialog && !isContactDialog) {
                return
            }

            Log.i(TAG, "Detected target dialog (Pairing: $isPairingDialog, Contact: $isContactDialog) in '$pkgName' for '$targetName'")

            // 4. Check contact access checkbox if present and unchecked
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

            // 5. Locate and click positive action button ("Pair", "Allow", "Confirm")
            val positiveKeywords = listOf("Pair", "Allow", "OK", "Confirm", "Yes", "Grant")
            val negativeKeywords = listOf("Cancel", "Don't allow", "Deny", "No", "Reject")

            for (node in allNodes) {
                val text = (node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()) ?: continue

                // Skip negative options ("Cancel", "Deny")
                if (negativeKeywords.any { text.equals(it, ignoreCase = true) }) {
                    continue
                }

                // Match positive button text ("Pair", "Allow")
                val matchesPositive = positiveKeywords.any { keyword ->
                    text.equals(keyword, ignoreCase = true)
                }

                if (matchesPositive) {
                    Log.i(TAG, "Attempting ACTION_CLICK on button: '$text'")
                    if (performClick(node)) {
                        val now = System.currentTimeMillis()
                        lastHandledTime = now
                        Log.i(TAG, "Successfully accepted '$text' for '$targetName'")

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
