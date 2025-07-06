@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.wifistrengthdetector

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wifistrengthdetector.ui.theme.WiFiStrengthDetectorTheme
import org.json.JSONArray
import java.util.*
import androidx.compose.ui.platform.LocalContext
import android.app.AlertDialog
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.combinedClickable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import android.app.Activity
import android.graphics.BitmapFactory

import android.widget.ImageView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource // (optional if you're using icons)
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.os.Handler
import android.os.Looper
import android.os.Environment



data class BleDevice(val name: String, val rssi: Int, val device: BluetoothDevice)
data class WiFiNetwork(val ssid: String, val rssi: Int, val security: String)
data class OptimizePoint(
    val x: Int,
    val y: Int,
    val rssi: Int
)


sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
    object WiFi : Screen("wifi")
    object History : Screen("history")
    object Optimize : Screen("optimize/{ssid}") { fun createRoute(ssid: String): String = "optimize/$ssid" }
    object Heatmap : Screen("heatmap")

}

class MainActivity : ComponentActivity() {

    private val scannedDevices = mutableStateListOf<BleDevice>()
    private val wifiNetworks = mutableStateListOf<WiFiNetwork>()
    private val scanHistory = mutableStateListOf<List<WiFiNetwork>>() // In-memory temp history
    private var lastUpdated by mutableStateOf<String?>(null)


    private var connectedDevice by mutableStateOf<BluetoothDevice?>(null)
    private var bluetoothGatt: BluetoothGatt? = null

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHARACTERISTIC_UUID = UUID.fromString("abcdefab-1234-5678-9abc-abcdef123456")
    private val NOTIFY_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val COMMAND_UUID = UUID.fromString("cdef1234-5678-90ab-cdef-1234567890ab")
    private val bleDataBuffer = StringBuilder()






    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val heatmapImageBytes = mutableStateOf<ByteArray?>(null)


        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, 1001)
        }

        setContent {
            var useDarkTheme by remember { mutableStateOf(true) }
            val navController = rememberNavController()

            WiFiStrengthDetectorTheme(darkTheme = useDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavHost(navController, startDestination = Screen.Home.route) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                onScanClicked = { startBleScan() },
                                scannedDevices = scannedDevices,
                                onDeviceClick = { device ->
                                    connectToDevice(device)
                                    navController.navigate(Screen.WiFi.route)
                                },
                                navController = navController
                            )
                        }

                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                navController = navController,
                                connectedDevice = connectedDevice,
                                onDisconnect = {
                                    disconnectDevice()
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Home.route) { inclusive = true }
                                    }
                                },
                                useDarkTheme = useDarkTheme,
                                onToggleTheme = { useDarkTheme = !useDarkTheme }
                            )
                        }
                        composable(Screen.WiFi.route) {
                            WiFiScreen(
                                wifiList = wifiNetworks,
                                navController = navController,
                                lastUpdated = lastUpdated,
                                onManualRefresh = { callback -> sendManualRefreshCommand(callback) } // 👈 add this
                            )
                        }
                        composable(Screen.History.route) {
                            ScanHistoryScreen(
                                navController = navController,
                                scanHistory = scanHistory,
                                onExportClick = {
                                    showExportDialog(
                                        context = this@MainActivity,
                                        scanHistory = scanHistory
                                    )
                                }

                            )
                        }
                        composable(
                            route = Screen.Optimize.route,
                            arguments = listOf(navArgument("ssid") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val ssid = backStackEntry.arguments?.getString("ssid") ?: ""
                            OptimizeScreen(
                                ssid = ssid,
                                navController = navController,
                                onSendSSID = { targetSSID -> sendSSIDToESP(targetSSID) },
                                onStartRSSICapture = { callback -> startRSSIAveraging(callback) },
                                onHeatmapGenerated = { bytes ->
                                    heatmapImageBytes.value = bytes
                                    navController.navigate(Screen.Heatmap.route)
                                }
                            )
                        }

                        composable(Screen.Heatmap.route) {
                            HeatmapScreen(navController, heatmapImageBytes.value)
                        }



                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendSSIDToESP(ssid: String) {
        val service = bluetoothGatt?.getService(SERVICE_UUID)
        val commandChar = service?.getCharacteristic(UUID.fromString("cdef1234-5678-90ab-cdef-1234567890ab"))

        if (commandChar != null) {
            commandChar.value = ssid.toByteArray(Charsets.UTF_8)
            bluetoothGatt?.writeCharacteristic(commandChar)
            Log.d("BLE_WRITE", "Sent SSID to ESP: $ssid")
        } else {
            Log.e("BLE_WRITE", "Command characteristic not found")
        }
    }
    private fun startRSSIAveraging(onComplete: (Int) -> Unit) {
        val tempRSSI = mutableListOf<Int>()

        val handler = android.os.Handler(mainLooper)
        val endTime = System.currentTimeMillis() + 5000

        val listener: (List<WiFiNetwork>) -> Unit = { list ->
            val matching = list.firstOrNull()  // only one SSID expected
            if (matching != null) {
                tempRSSI.add(matching.rssi)
            }
        }

        val timer = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() < endTime) {
                    listener(wifiNetworks)
                    handler.postDelayed(this, 500)
                } else {
                    val avgRssi = if (tempRSSI.isNotEmpty()) tempRSSI.sum() / tempRSSI.size else -100
                    onComplete(avgRssi)
                }
            }
        }
        handler.post(timer)
    }





    private fun showExportDialog(context: Context, scanHistory: List<List<WiFiNetwork>>) {
        val items = arrayOf("Export as JSON", "Export as Plain Text")
        AlertDialog.Builder(context)
            .setTitle("Choose Export Format")
            .setItems(items) { _, which ->
                val asJson = (which == 0)
                exportScanHistory(context, scanHistory, asJson)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    fun sendManualRefreshCommand(callback: (Boolean) -> Unit) {
        val gatt = bluetoothGatt ?: return callback(false)

        val service = gatt.getService(UUID.fromString("12345678-1234-1234-1234-1234567890ab"))
        val commandChar = service?.getCharacteristic(UUID.fromString("cdef1234-5678-90ab-cdef-1234567890ab"))

        if (commandChar != null) {
            commandChar.setValue("refresh")
            val success = gatt.writeCharacteristic(commandChar)
            Log.d("BLE_WRITE", "Manual refresh sent: $success")
            callback(success)
        } else {
            Log.e("BLE_WRITE", "Command characteristic not found")
            callback(false)
        }
    }




    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        connectedDevice = device

        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                    runOnUiThread {
                        Toast.makeText(applicationContext, "Connected to ${device.name}", Toast.LENGTH_SHORT).show()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread {
                        Toast.makeText(applicationContext, "Disconnected from ${device.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)

                if (characteristic != null &&
                    (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(NOTIFY_DESCRIPTOR_UUID)
                    descriptor?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val chunk = characteristic.value?.toString(Charsets.UTF_8)
                Log.d("BLE_CHUNK", "Received chunk: $chunk")

                if (chunk != null) {
                    Log.d("BLE_CHUNK", "Received chunk: $chunk")

                    val current = chunk.trim()

                    if (current.contains("<START>")) {
                        bleDataBuffer.clear()
                        bleDataBuffer.append(current.substringAfter("<START>"))
                    } else if (current.contains("<END>")) {
                        bleDataBuffer.append(current.substringBefore("<END>"))
                        val fullJson = bleDataBuffer.toString()
                        bleDataBuffer.clear()
                        try {
                            val jsonArray = JSONArray(fullJson)
                            Log.d("BLE_JSON", "Complete JSON received:\n$fullJson")
                            updateWiFiListFromJson(jsonArray.toString())
                        } catch (e: Exception) {
                            Log.e("BLE_JSON", "Invalid JSON", e)
                        }
                    } else {
                        bleDataBuffer.append(current)
                    }

                    // Safety net: if buffer grows too long without ending, clear it
                    if (bleDataBuffer.length > 3000) {
                        Log.w("BLE_JSON", "Buffer too long without receiving <END>. Clearing.")
                        bleDataBuffer.clear()
                    }
                }

            }

        })
    }







    private fun updateWiFiListFromJson(jsonString: String) {
        try {
            Log.d("BLE_JSON", "Raw input: $jsonString")

            val jsonArray = JSONArray(jsonString.trim())
            val newList = mutableListOf<WiFiNetwork>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ssid = obj.optString("ssid", "Unnamed")
                val rssi = obj.optInt("rssi", -100) // Default to weak signal if missing
                val security = obj.optString("security", "Unknown")
                newList.add(WiFiNetwork(ssid, rssi, security))
            }

            runOnUiThread {
                wifiNetworks.clear()
                wifiNetworks.addAll(newList)
                scanHistory.add(newList) // Save this scan to history
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                lastUpdated = formatter.format(Date())

                Log.d("BLE_JSON", "WiFi list updated with ${newList.size} networks")
                Log.d("BLE_JSON", "WiFi list updated with ${newList.size} networks")
            }
        } catch (e: Exception) {
            Log.e("BLE_JSON", "Failed to parse JSON", e)
        }
    }



    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        bluetoothGatt?.disconnect()
        connectedDevice = null
        wifiNetworks.clear()
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 102)
            Toast.makeText(this, "Missing Bluetooth permissions", Toast.LENGTH_SHORT).show()
            return
        }

        val bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = bluetoothAdapter.bluetoothLeScanner

        Toast.makeText(this, "Scanning for BLE devices...", Toast.LENGTH_SHORT).show()
        scannedDevices.clear()

        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                val deviceName = result.device.name ?: "Unnamed Device"
                val rssi = result.rssi
                val device = BleDevice(deviceName, rssi, result.device)

                if (scannedDevices.none { it.device.address == result.device.address }) {
                    scannedDevices.add(device)
                }
            }
        })
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }





    fun exportScanHistory(context: Context, scanHistory: List<List<WiFiNetwork>>, asJson: Boolean) {
        val exportData = if (asJson) {
            val jsonArray = JSONArray()
            scanHistory.forEach { scan ->
                val scanArray = JSONArray()
                scan.forEach {
                    val obj = org.json.JSONObject()
                    obj.put("ssid", it.ssid)
                    obj.put("rssi", it.rssi)
                    scanArray.put(obj)
                }
                jsonArray.put(scanArray)
            }
            jsonArray.toString(4)
        } else {
            buildString {
                scanHistory.forEachIndexed { index, scan ->
                    append("Scan #${index + 1}\n")
                    scan.forEach {
                        append("SSID: ${it.ssid}, RSSI: ${it.rssi}\n")
                    }
                    append("\n")
                }
            }
        }

        val fileName = if (asJson) "ScanHistory.json" else "ScanHistory.txt"
        val directory = context.getExternalFilesDir(null)
        val file = java.io.File(directory, fileName)

        try {
            file.writeText(exportData)
            Toast.makeText(context, "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("EXPORT", "Error writing export file", e)
        }
    }

}




@Composable
fun HomeScreen(
    onScanClicked: () -> Unit,
    scannedDevices: List<BleDevice>,
    onDeviceClick: (BluetoothDevice) -> Unit,
    navController: NavHostController
) {
    var isScanning by remember { mutableStateOf(false) }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            kotlinx.coroutines.delay(5000)
            isScanning = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("WiFi Strength Measuring Device", fontSize = 24.sp)

            Spacer(modifier = Modifier.height(8.dp))
            Text("Tap scan to find nearby BLE devices", fontSize = 14.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = {
                isScanning = true
                onScanClicked()
            }) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Text("Scanning...")
                } else {
                    Text("Scan for BLE Devices")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Found Devices:", fontSize = 18.sp)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                items(scannedDevices) { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clickable {
                                onDeviceClick(device.device)
                                navController.navigate(Screen.WiFi.route)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = device.name,
                            modifier = Modifier
                                .padding(4.dp)
                                .fillMaxWidth()
                        )
                    }
                    Divider()
                }
            }
        }

        IconButton(
            onClick = { navController.navigate(Screen.Settings.route) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 16.dp)
                .size(36.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)

@Composable
fun WiFiScreen(
    wifiList: List<WiFiNetwork>,
    navController: NavHostController,
    lastUpdated: String?,
    onManualRefresh: ((Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var selectedSSID by remember { mutableStateOf<String?>(null) }

    // Filtered list
    val filteredList = wifiList.filter { it.ssid.contains(searchQuery, ignoreCase = true) }

    Box(modifier = Modifier.fillMaxSize()) {

        // Top icons
        IconButton(
            onClick = {
                isRefreshing = true
                Toast.makeText(context, "Refreshing WiFi list...", Toast.LENGTH_SHORT).show()
                onManualRefresh { success ->
                    isRefreshing = false
                    Toast.makeText(context,
                        if (success) "Scan completed" else "Scan failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp, start = 16.dp)
                .size(36.dp),
            enabled = !isRefreshing
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.Gray,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.Gray)
            }
        }

        IconButton(
            onClick = { navController.navigate(Screen.Settings.route) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 16.dp)
                .size(36.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
        }

        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 100.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("WiFi Strength Measuring Device", fontSize = 24.sp)
            Spacer(modifier = Modifier.height(24.dp))
            val focusManager = LocalFocusManager.current

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("Nearby WiFi Networks:", fontSize = 18.sp)
            Spacer(modifier = Modifier.height(16.dp))

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No results found", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(filteredList) { wifi ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .combinedClickable(
                                    onClick = { /* Normal click if needed */ },
                                    onLongClick = {
                                        navController.navigate(Screen.Optimize.createRoute(wifi.ssid))
                                    }
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(wifi.ssid, fontSize = 16.sp)
                                Text("RSSI: ${wifi.rssi}", fontSize = 12.sp, color = Color.Gray)
                                val securityColor = when {
                                    wifi.security.equals("Open", ignoreCase = true) -> Color.Red
                                    wifi.security.contains("WPA", ignoreCase = true) -> Color(0xFF4CAF50)
                                    else -> Color.Gray
                                }
                                Text("Security: ${wifi.security}", fontSize = 12.sp, color = securityColor)
                            }
                            SignalBars(wifi.rssi)
                        }
                        Divider()
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            lastUpdated?.let {
                Text("Last scan: $it", fontSize = 12.sp, color = Color.DarkGray)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Note: ESP32-C6 cannot detect 5GHz Wi-Fi networks.", fontSize = 12.sp, color = Color.Gray)
        }

        // Long Press Popup
        if (showPopup && selectedSSID != null) {
            AlertDialog(
                onDismissRequest = { showPopup = false },
                title = { Text("Optimize Network") },
                text = { Text("Do you want to optimize this network?\nSSID: $selectedSSID") },
                confirmButton = {
                    TextButton(onClick = {
                        showPopup = false
                        navController.navigate("optimize/${selectedSSID}")
                    }) {
                        Text("Optimize")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPopup = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}





@Composable
fun SignalBars(rssi: Int) {
    val bars = when {
        rssi >= -40 -> 5  // Excellent
        rssi >= -55 -> 4  // Very Good
        rssi >= -65 -> 3  // Good
        rssi >= -75 -> 2  // Fair
        else -> 1         // Weak
    }

    val color = when {
        bars >= 5 -> Color(0xFF4CAF50)  // 🟩 Green
        bars == 4 -> Color(0xFF8BC34A)  // 🟨 Light Green
        bars == 3 -> Color(0xFFFFEB3B)  // 🟨 Yellow
        bars == 2 -> Color(0xFFFF9800)  // 🟧 Orange
        else -> Color(0xFFF44336)       // 🟥 Red
    }

    val heights = listOf(8.dp, 14.dp, 20.dp, 26.dp, 32.dp)

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .height(34.dp)
            .padding(start = 4.dp)
    ) {
        for (i in 0 until 5) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(heights[i])
                    .background(if (i < bars) color else Color.LightGray)
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
    }
}




@Composable
fun SettingsScreen(
    navController: NavHostController,
    connectedDevice: BluetoothDevice?,
    onDisconnect: () -> Unit,
    useDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (connectedDevice != null) {
                Text("Bluetooth", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = Color.Blue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connected Device: ${connectedDevice.name}", fontSize = 18.sp)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(onClick = { onDisconnect() }) {
                            Text("Disconnect")
                        }
                    }
                }
            } else {
                Text("No device connected", fontSize = 18.sp, color = Color.Gray)
            }

            Spacer(modifier = Modifier.height(32.dp))
            Text("App Theme", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Icon(Icons.Default.WbSunny, contentDescription = "Light", tint = Color(0xFFFFC107))
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = useDarkTheme,
                    onCheckedChange = { onToggleTheme() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        uncheckedThumbColor = Color.Black
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Icon(Icons.Default.DarkMode, contentDescription = "Dark", tint = Color(0xFF90CAF9))
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text("Tools", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)

            Button(
                onClick = { navController.navigate(Screen.History.route) },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("View Scan History")
            }


            Spacer(modifier = Modifier.height(32.dp))
            Text("About", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Version 1.0", color = Color.Gray)
        }
    }
}

@Composable
fun ScanHistoryScreen(
    navController: NavHostController,
    scanHistory: List<List<WiFiNetwork>>,
    onExportClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan History") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onExportClick) {
                        Icon(Icons.Default.Share, contentDescription = "Export", tint = Color.White)
                    }
                }
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
            if (scanHistory.isEmpty()) {
                Text("No past scans available", color = Color.Gray)
            } else {
                scanHistory.forEachIndexed { index, scan ->
                    Text(
                        text = "Scan #${index + 1}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Divider()

                    scan.forEach { wifi ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(wifi.ssid, fontSize = 16.sp)
                                Text("RSSI: ${wifi.rssi}", fontSize = 12.sp, color = Color.Gray)
                                val securityColor = when {
                                    wifi.security.equals("Open", ignoreCase = true) -> Color.Red
                                    wifi.security.contains("WPA", ignoreCase = true) -> Color(0xFF4CAF50) // Green
                                    else -> Color.Gray
                                }

                                Text(
                                    "Security: ${wifi.security}",
                                    fontSize = 12.sp,
                                    color = securityColor
                                )
                            }
                            SignalBars(wifi.rssi)
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

fun exportScanHistory(context: Context, scanHistory: List<List<WiFiNetwork>>, asJson: Boolean) {
    val exportData = if (asJson) {
        val jsonArray = JSONArray()
        scanHistory.forEach { scan ->
            val scanArray = JSONArray()
            scan.forEach {
                val obj = org.json.JSONObject()
                obj.put("ssid", it.ssid)
                obj.put("rssi", it.rssi)
                scanArray.put(obj)
            }
            jsonArray.put(scanArray)
        }
        jsonArray.toString(4)
    } else {
        buildString {
            scanHistory.forEachIndexed { index, scan ->
                append("Scan #${index + 1}\n")
                scan.forEach {
                    append("SSID: ${it.ssid}, RSSI: ${it.rssi}\n")
                }
                append("\n")
            }
        }
    }

    val fileName = if (asJson) "ScanHistory.json" else "ScanHistory.txt"
    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val file = java.io.File(directory, fileName)

    try {
        file.writeText(exportData)
        Toast.makeText(context, "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        Log.e("EXPORT", "Error writing export file", e)
    }
}

private val optimizationResults = mutableStateListOf<OptimizePoint>()
fun generateHeatmap(context: Context, optimizationResults: List<OptimizePoint>,
                    onResult: (ByteArray?) -> Unit
) {
    val client = OkHttpClient()

    val jsonArray = JSONArray()
    for (point in optimizationResults) {
        val obj = JSONObject()
        obj.put("x", point.x)
        obj.put("y", point.y)
        obj.put("rssi", point.rssi)
        jsonArray.put(obj)
    }

    val requestBody = RequestBody.create(
        "application/json".toMediaTypeOrNull(),
        jsonArray.toString()
    )

    val request = Request.Builder()
        .url("http://192.168.166.233:5000/heatmap") // Replace with your Python server IP
        .post(requestBody)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            e.printStackTrace()
            onResult(null)
        }

        override fun onResponse(call: Call, response: Response) {
            val imageBytes = response.body?.bytes()

            Handler(Looper.getMainLooper()).post {
                if (response.isSuccessful && imageBytes != null) {
                    onResult(imageBytes)  // safe now
                } else {
                    onResult(null)
                }
            }
        }
    })
}

@Composable
fun OptimizeScreen(
    ssid: String,
    navController: NavHostController,
    onSendSSID: (String) -> Unit,
    onStartRSSICapture: ((Int) -> Unit) -> Unit,
    onHeatmapGenerated: (ByteArray?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var xCoord by remember { mutableStateOf("") }
    var yCoord by remember { mutableStateOf("") }
    val rssiDataList = remember { optimizationResults }
    val context = LocalContext.current
    val imageBytesState = remember { mutableStateOf<ByteArray?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Optimize: $ssid") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Button(onClick = { showDialog = true }) {
                Text("Add New Location")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Recorded Locations:", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rssiDataList) { point ->
                    Text("X: ${point.x}, Y: ${point.y} → Avg RSSI: ${point.rssi} dBm")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (optimizationResults.isEmpty()) {
                        Toast.makeText(context, "No data to generate heatmap", Toast.LENGTH_SHORT).show()
                    } else {
                        coroutineScope.launch {
                            generateHeatmap(context, optimizationResults) { imageBytes ->
                                if (imageBytes != null) {
                                    imageBytesState.value = imageBytes
                                    onHeatmapGenerated(imageBytes)

                                    // Check if we're still on the Optimize screen before navigating
                                    if (navController.currentDestination?.route == Screen.Optimize.route) {
                                        navController.navigate(Screen.Heatmap.route)
                                    }
                                } else {
                                    Toast.makeText(context, "Heatmap generation failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generate Heatmap")
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Enter Coordinates") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = xCoord,
                            onValueChange = { xCoord = it },
                            label = { Text("X Coordinate") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = yCoord,
                            onValueChange = { yCoord = it },
                            label = { Text("Y Coordinate") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val x = xCoord.toIntOrNull()
                        val y = yCoord.toIntOrNull()

                        if (x == null || y == null) {
                            Toast.makeText(context, "Enter valid coordinates", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }

                        showDialog = false
                        xCoord = ""
                        yCoord = ""

                        onSendSSID(ssid)
                        onStartRSSICapture { avgRssi ->
                            Toast.makeText(context, "Captured RSSI $avgRssi at ($x, $y)", Toast.LENGTH_SHORT).show()
                            rssiDataList.add(OptimizePoint(x, y, avgRssi))
                        }

                    }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}




fun showHeatmapDialog(context: Context, imageBytes: ByteArray) {
    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    val imageView = ImageView(context).apply {
        setImageBitmap(bitmap)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    AlertDialog.Builder(context)
        .setTitle("WiFi Signal Heatmap")
        .setView(imageView)
        .setPositiveButton("Close", null)
        .show()
}



@Composable
fun HeatmapScreen(navController: NavHostController, imageBytes: ByteArray?) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Heatmap") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (imageBytes != null) {
                val bitmap = remember(imageBytes) {
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Heatmap Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            } else {
                Text("No heatmap available", color = Color.Gray)
            }
        }
    }
}
