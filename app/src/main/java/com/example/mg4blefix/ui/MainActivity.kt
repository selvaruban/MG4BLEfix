package com.example.mg4blefix.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.mg4blefix.data.DevicePreferencesRepository
import com.example.mg4blefix.service.BtAutoPairAccessibilityService
import com.example.mg4blefix.ui.theme.MG4BLEfixTheme
import com.example.mg4blefix.util.BtCleanupManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var preferencesRepository: DevicePreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesRepository = DevicePreferencesRepository(applicationContext)

        setContent {
            MG4BLEfixTheme {
                MainScreen(preferencesRepository = preferencesRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(preferencesRepository: DevicePreferencesRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var isServiceEnabled by remember { mutableStateOf(false) }
    var isBatteryUnrestricted by remember { mutableStateOf(false) }
    val savedDeviceName by preferencesRepository.targetDeviceName.collectAsState(initial = DevicePreferencesRepository.DEFAULT_DEVICE_NAME)
    val lastPairedTimestamp by preferencesRepository.lastPairedTimestamp.collectAsState(initial = 0L)
    val actionCount by preferencesRepository.actionCount.collectAsState(initial = 0)
    var deviceNameInput by remember(savedDeviceName) { mutableStateOf(savedDeviceName) }

    var bondedMgDeviceCount by remember { mutableIntStateOf(0) }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val removed = BtCleanupManager.cleanupStaleDevices(context, savedDeviceName)
            bondedMgDeviceCount = BtCleanupManager.getMatchingBondedDevices(context, savedDeviceName).size
            val msg = if (removed > 0) "Unpaired $removed stale '$savedDeviceName' device(s)!" else "No duplicate devices to clean."
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Bluetooth permission is required for cleanup", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerBtCleanup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                val removed = BtCleanupManager.cleanupStaleDevices(context, savedDeviceName)
                bondedMgDeviceCount = BtCleanupManager.getMatchingBondedDevices(context, savedDeviceName).size
                val msg = if (removed > 0) "Unpaired $removed stale '$savedDeviceName' device(s)!" else "No duplicate devices to clean."
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } else {
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            val removed = BtCleanupManager.cleanupStaleDevices(context, savedDeviceName)
            bondedMgDeviceCount = BtCleanupManager.getMatchingBondedDevices(context, savedDeviceName).size
            val msg = if (removed > 0) "Unpaired $removed stale '$savedDeviceName' device(s)!" else "No duplicate devices to clean."
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // Re-check status when activity comes to foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceEnabled = isAccessibilityServiceEnabled(
                    context,
                    BtAutoPairAccessibilityService::class.java
                )
                isBatteryUnrestricted = isBatteryOptimizationIgnored(context)
                bondedMgDeviceCount = BtCleanupManager.getMatchingBondedDevices(context, savedDeviceName).size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MG Bluetooth Auto-Pair") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Service Status Card
            ServiceStatusCard(
                isServiceEnabled = isServiceEnabled,
                savedDeviceName = savedDeviceName,
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Battery Optimization Card
            BatteryOptimizationCard(
                isBatteryOptimizationIgnored = isBatteryUnrestricted,
                onRequestUnrestrictedBattery = {
                    requestUnrestrictedBatteryAccess(context)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Automated Actions Summary Card (Counter + Timestamp)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Automated Actions Summary",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (actionCount > 0) {
                            TextButton(
                                onClick = {
                                    scope.launch { preferencesRepository.resetStats() }
                                }
                            ) {
                                Text("Reset", fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Total Automated Actions:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$actionCount ${if (actionCount == 1) "time" else "times"}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Last Action: ${formatTimestamp(lastPairedTimestamp)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bluetooth Stale Devices Cleanup Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Bluetooth Duplicate Cleanup",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Found $bondedMgDeviceCount paired '$savedDeviceName' ${if (bondedMgDeviceCount == 1) "entry" else "entries"} in Bluetooth settings.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { triggerBtCleanup() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = bondedMgDeviceCount > 1
                    ) {
                        Text(if (bondedMgDeviceCount > 1) "Unpair Duplicate '$savedDeviceName' Devices ($bondedMgDeviceCount)" else "No Duplicates Found")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Restricted Settings Helper Card (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isServiceEnabled) {
                RestrictedSettingsCard(
                    onOpenAppInfo = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Configurable Target Device Name Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Target Device Filter",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The accessibility service will only click 'Pair' / 'Allow' if the dialog contains this target device name.",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = deviceNameInput,
                        onValueChange = { deviceNameInput = it },
                        label = { Text("Target Device Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                preferencesRepository.updateTargetDeviceName(deviceNameInput)
                                bondedMgDeviceCount = BtCleanupManager.getMatchingBondedDevices(context, deviceNameInput).size
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = deviceNameInput.isNotBlank() && deviceNameInput != savedDeviceName
                    ) {
                        Text(if (deviceNameInput == savedDeviceName) "Saved" else "Save Changes")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "How It Works",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Enable the Accessibility Service in Android Settings.\n" +
                                "2. Grant Unrestricted Battery Access so background execution is never delayed when locked.\n" +
                                "3. When your MG car attempts to pair via Bluetooth, a prompt appears.\n" +
                                "4. The service auto-accepts the prompt and cleans up older duplicate 'MG' entries from your Bluetooth settings.",
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ServiceStatusCard(
    isServiceEnabled: Boolean,
    savedDeviceName: String,
    onOpenSettings: () -> Unit
) {
    val backgroundColor = if (isServiceEnabled) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
    val textColor = if (isServiceEnabled) Color(0xFF2E7D32) else Color(0xFFC62828)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isServiceEnabled) "Service Active" else "Service Disabled",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isServiceEnabled) {
                    "The service is running in background and monitoring for '$savedDeviceName' pairing requests."
                } else {
                    "Accessibility Service permission is required for automated pairing acceptance."
                },
                color = textColor,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isServiceEnabled) "Accessibility Settings" else "Enable Accessibility Service")
            }
        }
    }
}

@Composable
fun BatteryOptimizationCard(
    isBatteryOptimizationIgnored: Boolean,
    onRequestUnrestrictedBattery: () -> Unit
) {
    val backgroundColor = if (isBatteryOptimizationIgnored) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
    val textColor = if (isBatteryOptimizationIgnored) Color(0xFF2E7D32) else Color(0xFFE65100)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isBatteryOptimizationIgnored) "Battery Access: Unrestricted" else "Battery Access: Optimized / Restricted",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isBatteryOptimizationIgnored) {
                    "Unrestricted battery access granted. Android will not delay or kill background auto-pairing when phone is locked."
                } else {
                    "Battery optimization is active. Android may sleep or delay background accessibility services when the phone is locked."
                },
                color = textColor,
                fontSize = 14.sp
            )
            if (!isBatteryOptimizationIgnored) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestUnrestrictedBattery,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Unrestricted Battery Access")
                }
            }
        }
    }
}

@Composable
fun RestrictedSettingsCard(
    onOpenAppInfo: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Android 13+ Restricted Settings Warning",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "If the toggle in Accessibility Settings is greyed out ('Restricted setting'):\n" +
                        "1. Tap 'Open App Info' below.\n" +
                        "2. Tap the 3 dots (⋮) in top-right corner.\n" +
                        "3. Tap 'Allow restricted settings' and authenticate.\n" +
                        "4. Return to Accessibility Settings and enable the service.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenAppInfo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open App Info")
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp == 0L) {
        return "Never"
    }
    val sdf = SimpleDateFormat("MMM dd, yyyy - hh:mm:ss a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
}

private fun requestUnrestrictedBatteryAccess(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            context.startActivity(fallbackIntent)
        } catch (_: Exception) {}
    }
}

private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expectedComponentName = ComponentName(context, serviceClass)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledComponent = ComponentName.unflattenFromString(componentNameString)
        if (enabledComponent != null && enabledComponent == expectedComponentName) {
            return true
        }
    }
    return false
}
