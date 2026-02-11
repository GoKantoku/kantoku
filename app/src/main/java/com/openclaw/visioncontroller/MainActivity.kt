package com.openclaw.visioncontroller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openclaw.visioncontroller.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    
    private var isRunning = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    // Vision AI API configuration
    private var apiKey = ""
    private val apiEndpoint = "https://api.anthropic.com/v1/messages"
    
    // Task execution state
    private var currentTask = ""
    private var currentSkill: Skill? = null
    private var durationMs = 5 * 60 * 1000L
    private var startTimeMs = 0L
    private var lastActionTimeMs = 0L
    private var lastMeaningfulAction = ""
    private var consecutiveWaits = 0
    private val actionHistory = mutableListOf<String>()
    private var isTaskComplete = false
    
    // Action log for display
    private val actionLog = StringBuilder()
    
    // Heartbeat configuration - tightened for faster execution
    private val minIntervalMs = 2_000L       // Min 2 seconds between API calls
    private val maxIntervalMs = 5_000L       // Max 5 seconds
    private val stallThresholdMs = 15_000L   // Consider stalled after 15s of no progress
    private val maxConsecutiveWaits = 2      // After 2 WAITs, try a recovery prompt
    
    // Action queue for multi-step plans
    private val pendingActions = mutableListOf<String>()
    
    // Track if we just performed a click (for longer wait)
    private var lastClickTimeMs = 0L
    private val postClickWaitMs = 10_000L  // Wait 10 seconds after clicks
    
    /**
     * Build API request with correct auth headers.
     * Auto-detects API key vs setup token based on prefix.
     */
    private fun buildApiRequest(jsonBody: String): Request {
        val isApiKey = apiKey.startsWith("sk-ant-")
        
        val builder = Request.Builder()
            .url(apiEndpoint)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
        
        if (isApiKey) {
            builder.addHeader("x-api-key", apiKey)
        } else {
            // Setup token uses Bearer auth + OAuth beta header
            builder.addHeader("Authorization", "Bearer $apiKey")
            builder.addHeader("anthropic-beta", "oauth-2025-04-20")
        }
        
        return builder.build()
    }
    
    // Idle mode configuration
    private var isIdleMode = false
    private var lastIdleActionMs = 0L
    private var idleActionCount = 0
    private val idleIntervalMs = 60_000L           // 60 seconds between simple actions
    private val idleBrowseIntervalMs = 5 * 60_000L // 5 minutes between vision-guided browsing
    private var idleModePreference = SetupActivity.IDLE_RANDOM
    
    // Idle mode URL options
    private val idleUrlWikipedia = "https://en.wikipedia.org/wiki/Special:Random"
    private val idleUrlGoogleMaps = "https://www.google.com/maps/@0,0,3z"
    private val idleUrlNpr = "https://www.npr.org"
    private val allIdleUrls = listOf(idleUrlWikipedia, idleUrlGoogleMaps, idleUrlNpr)
    
    companion object {
        private const val TAG = "Kantoku"  // Easy to grep
        private const val REQUEST_PERMISSIONS = 1001
        
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        
        // Combined HID descriptor for Keyboard (Report ID 1) and Mouse (Report ID 2)
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            // ========== KEYBOARD (Report ID 1) ==========
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x06,        // Usage (Keyboard)
            0xA1.toByte(), 0x01,  // Collection (Application)
            0x85.toByte(), 0x01,  // Report ID (1)
            // Modifier keys
            0x05, 0x07,        // Usage Page (Key Codes)
            0x19, 0xE0.toByte(),  // Usage Minimum (224) - Left Control
            0x29, 0xE7.toByte(),  // Usage Maximum (231) - Right GUI
            0x15, 0x00,        // Logical Minimum (0)
            0x25, 0x01,        // Logical Maximum (1)
            0x75, 0x01,        // Report Size (1)
            0x95.toByte(), 0x08,  // Report Count (8)
            0x81.toByte(), 0x02,  // Input (Data, Variable, Absolute)
            // Reserved byte
            0x95.toByte(), 0x01,  // Report Count (1)
            0x75, 0x08,        // Report Size (8)
            0x81.toByte(), 0x01,  // Input (Constant)
            // Key array (6 keys)
            0x95.toByte(), 0x06,  // Report Count (6)
            0x75, 0x08,        // Report Size (8)
            0x15, 0x00,        // Logical Minimum (0)
            0x25, 0x65,        // Logical Maximum (101)
            0x05, 0x07,        // Usage Page (Key Codes)
            0x19, 0x00,        // Usage Minimum (0)
            0x29, 0x65,        // Usage Maximum (101)
            0x81.toByte(), 0x00,  // Input (Data, Array)
            0xC0.toByte(),     // End Collection
            
            // ========== MOUSE (Report ID 2) ==========
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x02,        // Usage (Mouse)
            0xA1.toByte(), 0x01,  // Collection (Application)
            0x85.toByte(), 0x02,  // Report ID (2)
            0x09, 0x01,        // Usage (Pointer)
            0xA1.toByte(), 0x00,  // Collection (Physical)
            // Buttons (3 buttons)
            0x05, 0x09,        // Usage Page (Button)
            0x19, 0x01,        // Usage Minimum (Button 1)
            0x29, 0x03,        // Usage Maximum (Button 3)
            0x15, 0x00,        // Logical Minimum (0)
            0x25, 0x01,        // Logical Maximum (1)
            0x95.toByte(), 0x03,  // Report Count (3)
            0x75, 0x01,        // Report Size (1)
            0x81.toByte(), 0x02,  // Input (Data, Variable, Absolute)
            // Padding (5 bits)
            0x95.toByte(), 0x01,  // Report Count (1)
            0x75, 0x05,        // Report Size (5)
            0x81.toByte(), 0x01,  // Input (Constant)
            // X,Y movement (relative, -127 to 127)
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x30,        // Usage (X)
            0x09, 0x31,        // Usage (Y)
            0x15, 0x81.toByte(),  // Logical Minimum (-127)
            0x25, 0x7F,        // Logical Maximum (127)
            0x75, 0x08,        // Report Size (8)
            0x95.toByte(), 0x02,  // Report Count (2)
            0x81.toByte(), 0x06,  // Input (Data, Variable, Relative)
            0xC0.toByte(),     // End Collection (Physical)
            0xC0.toByte()      // End Collection (Application)
        )
        
        // Report IDs
        const val REPORT_ID_KEYBOARD = 1
        const val REPORT_ID_MOUSE = 2
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Get task parameters from intent
        currentTask = intent.getStringExtra(PlanActivity.EXTRA_TASK) ?: ""
        durationMs = intent.getLongExtra(PlanActivity.EXTRA_DURATION_MS, 5 * 60 * 1000L)
        val deviceAddress = intent.getStringExtra(PlanActivity.EXTRA_DEVICE_ADDRESS)
        
        // Load API key and preferences
        apiKey = SetupActivity.getApiKey(this)
        idleModePreference = SetupActivity.getIdleMode(this)
        
        // Load skill
        val skillId = intent.getStringExtra(PlanActivity.EXTRA_SKILL_ID) ?: "general"
        currentSkill = SkillManager.getSkillById(this, skillId)
        
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        setupUI()
        
        if (allPermissionsGranted()) {
            startCamera()
            setupBluetoothHid(deviceAddress)
            // Vision loop will start when HID connects (see onConnectionStateChanged)
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
        }
    }
    
    private fun setupUI() {
        binding.btnStart.text = "Stop"
        binding.tvStatus.text = "Task: $currentTask"
        
        binding.btnStart.setOnClickListener {
            if (isRunning) {
                stopVisionLoop()
                finish()
            }
        }
        
        binding.btnConnect.visibility = android.view.View.GONE
        
        // Initialize action log
        actionLog.clear()
        appendToLog("Task: $currentTask")
        appendToLog("Waiting for connection...")
    }
    
    private fun appendToLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        actionLog.append("[$timestamp] $message\n")
        
        runOnUiThread {
            binding.tvActionLog.text = actionLog.toString()
            // Auto-scroll to bottom
            binding.svActionLog.post {
                binding.svActionLog.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }
    
    private fun setupBluetoothHid(deviceAddress: String?) {
        Log.d(TAG, "=== setupBluetoothHid called, deviceAddress: $deviceAddress ===")
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Bluetooth permission not granted!")
            return
        }
        
        Log.d(TAG, "Getting HID_DEVICE profile proxy...")
        bluetoothAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                Log.d(TAG, "Profile service connected, profile: $profile")
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    Log.d(TAG, "HID Device profile obtained")
                    registerHidApp(deviceAddress)
                }
            }
            
            override fun onServiceDisconnected(profile: Int) {
                Log.d(TAG, "Profile service disconnected, profile: $profile")
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }
    
    private fun registerHidApp(deviceAddress: String?) {
        Log.d(TAG, "=== registerHidApp called ===")
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Bluetooth permission not granted!")
            return
        }
        
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Kantoku",
            "AI-powered computer control",
            "OpenClaw",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HID_REPORT_DESCRIPTOR
        )
        
        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                Log.d(TAG, "onAppStatusChanged: registered=$registered, pluggedDevice=${pluggedDevice?.name}")
                if (registered) {
                    Log.d(TAG, "HID app registered successfully!")
                    if (deviceAddress != null) {
                        runOnUiThread {
                            Log.d(TAG, "Initiating connection to $deviceAddress")
                            connectToDevice(deviceAddress)
                        }
                    }
                } else {
                    Log.e(TAG, "HID app registration failed or unregistered")
                }
            }
            
            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                val stateStr = when(state) {
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    else -> "UNKNOWN($state)"
                }
                Log.d(TAG, "onConnectionStateChanged: device=${device.name}, state=$stateStr")
                
                runOnUiThread {
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connectedDevice = device
                            updateStatus("✅ HID Connected to ${device.name}")
                            Log.d(TAG, "HID CONNECTED! Starting vision loop...")
                            appendToLog("✅ Connected to ${device.name}")
                            if (!isRunning) {
                                startVisionLoop()
                            }
                        }
                        BluetoothProfile.STATE_CONNECTING -> {
                            updateStatus("Connecting HID to ${device.name}...")
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "HID Disconnected")
                            connectedDevice = null
                            updateStatus("❌ HID Disconnected")
                            // Don't stop vision loop - try to reconnect
                        }
                    }
                }
            }
            
            override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                Log.d(TAG, "onGetReport: type=$type, id=$id")
            }
            
            override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
                Log.d(TAG, "onSetReport: type=$type, id=$id")
            }
        }
        
        Log.d(TAG, "Calling hidDevice.registerApp...")
        val result = hidDevice?.registerApp(sdpSettings, null, null, Executors.newSingleThreadExecutor(), callback)
        Log.d(TAG, "registerApp returned: $result")
    }
    
    private fun connectToDevice(address: String) {
        Log.d(TAG, "=== connectToDevice: $address ===")
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Bluetooth permission not granted!")
            return
        }
        
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device != null) {
            Log.d(TAG, "Got remote device: ${device.name}, calling hidDevice.connect...")
            val result = hidDevice?.connect(device)
            Log.d(TAG, "hidDevice.connect returned: $result")
            updateStatus("Connecting to ${device.name}...")
        } else {
            Log.e(TAG, "Could not get remote device for address: $address")
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun startVisionLoop() {
        Log.d(TAG, "=== startVisionLoop called ===")
        Log.d(TAG, "currentTask: $currentTask")
        Log.d(TAG, "apiKey length: ${apiKey.length}")
        Log.d(TAG, "connectedDevice: ${connectedDevice?.name}")
        
        if (currentTask.isEmpty()) {
            Log.e(TAG, "No task specified!")
            Toast.makeText(this, "No task specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        isRunning = true
        isTaskComplete = false
        startTimeMs = System.currentTimeMillis()
        lastActionTimeMs = startTimeMs
        consecutiveWaits = 0
        actionHistory.clear()
        
        binding.btnStart.text = "Stop"
        updateStatus("Starting: $currentTask")
        Log.d(TAG, "Vision loop starting, HID connected: ${connectedDevice != null}")
        appendToLog("🚀 Starting vision loop...")
        appendToLog("Duration: ${durationMs / 60000} minutes")
        
        scope.launch {
            Log.d(TAG, "Vision loop coroutine started")
            var loopCount = 0
            while (isRunning && !isTaskComplete) {
                loopCount++
                Log.d(TAG, "=== Loop iteration $loopCount ===")
                
                // Check if time is up
                val elapsed = System.currentTimeMillis() - startTimeMs
                if (elapsed >= durationMs) {
                    Log.d(TAG, "Time limit reached after ${elapsed}ms")
                    updateStatus("Time limit reached")
                    stopVisionLoop()
                    break
                }
                
                // Calculate remaining time
                val remainingMs = durationMs - elapsed
                val remainingMin = remainingMs / 60000
                val remainingSec = (remainingMs % 60000) / 1000
                Log.d(TAG, "Time remaining: ${remainingMin}m ${remainingSec}s")
                
                runOnUiThread {
                    binding.tvLastAction.text = "Time remaining: ${remainingMin}m ${remainingSec}s"
                }
                
                // Check if we have queued actions to execute first
                if (pendingActions.isNotEmpty()) {
                    val nextAction = pendingActions.removeAt(0)
                    Log.d(TAG, "Executing queued action: $nextAction")
                    runOnUiThread {
                        processAction(nextAction)
                    }
                } else {
                    // Capture and analyze for new plan
                    Log.d(TAG, "Calling captureAndAnalyze...")
                    captureAndAnalyze()
                    Log.d(TAG, "captureAndAnalyze completed")
                }
                
                // Dynamic interval based on activity
                val interval = calculateNextInterval()
                Log.d(TAG, "Waiting ${interval}ms before next iteration")
                delay(interval)
            }
            Log.d(TAG, "Vision loop ended. isRunning=$isRunning, isTaskComplete=$isTaskComplete")
            
            if (isTaskComplete) {
                updateStatus("✅ Task completed! Starting idle mode...")
                appendToLog("🌙 Entering idle mode...")
                startIdleMode()
            }
        }
    }
    
    private fun startIdleMode() {
        isIdleMode = true
        isRunning = true
        lastIdleActionMs = System.currentTimeMillis()
        idleActionCount = 0
        
        binding.btnStart.text = "Stop Idle"
        
        scope.launch {
            Log.d(TAG, "Idle mode started")
            
            while (isRunning && isIdleMode) {
                val now = System.currentTimeMillis()
                val timeSinceLastAction = now - lastIdleActionMs
                
                // Every 5 minutes: vision-guided browsing
                if (idleActionCount > 0 && idleActionCount % 5 == 0) {
                    Log.d(TAG, "Idle: Vision-guided browsing")
                    appendToLog("🔍 Exploring...")
                    idleBrowseWithVision()
                } else {
                    // Simple keep-alive action
                    Log.d(TAG, "Idle: Simple keep-alive action")
                    performIdleAction()
                }
                
                lastIdleActionMs = System.currentTimeMillis()
                idleActionCount++
                
                runOnUiThread {
                    binding.tvLastAction.text = "Idle mode • Action #$idleActionCount"
                    updateStatus("🌙 Idle mode active")
                }
                
                // Wait 60 seconds before next action
                delay(idleIntervalMs)
            }
            
            Log.d(TAG, "Idle mode ended")
        }
    }
    
    private fun performIdleAction() {
        // Simple actions that don't need vision API
        val action = when ((0..4).random()) {
            0 -> {
                // Mouse wiggle
                appendToLog("🖱️ Mouse wiggle")
                val dx = (-20..20).random()
                val dy = (-20..20).random()
                moveMouse(dx, dy)
                Thread.sleep(200)
                moveMouse(-dx, -dy)  // Return to original position
            }
            1 -> {
                // Scroll down then up
                appendToLog("📜 Scroll")
                sendKey("pagedown")
                Thread.sleep(500)
                sendKey("pageup")
            }
            2 -> {
                // Small mouse movement
                appendToLog("🖱️ Mouse drift")
                val dx = (-30..30).random()
                val dy = (-30..30).random()
                moveMouse(dx, dy)
                mouseX += dx
                mouseY += dy
            }
            3 -> {
                // Press and release shift (harmless)
                appendToLog("⌨️ Key tap")
                sendKeyReport(0x02, 0)  // Shift down
                Thread.sleep(50)
                sendKeyReport(0, 0)     // Release
            }
            else -> {
                // Just wait (do nothing visible)
                appendToLog("💤 Waiting...")
            }
        }
    }
    
    private suspend fun idleBrowseWithVision() {
        // Select URL based on preference
        val url = when (idleModePreference) {
            SetupActivity.IDLE_WIKIPEDIA -> idleUrlWikipedia
            SetupActivity.IDLE_GOOGLE_MAPS -> idleUrlGoogleMaps
            SetupActivity.IDLE_NPR -> idleUrlNpr
            else -> allIdleUrls.random()  // IDLE_RANDOM
        }
        appendToLog("🌐 Opening $url")
        
        // Open new tab and navigate
        withContext(Dispatchers.Main) {
            sendKey("cmd+t")
        }
        delay(500)
        withContext(Dispatchers.Main) {
            sendText(url)
        }
        delay(200)
        withContext(Dispatchers.Main) {
            sendKey("enter")
        }
        
        // Wait for page to load
        delay(3000)
        
        // Now use vision to explore the page
        withContext(Dispatchers.IO) {
            try {
                val bitmap = withContext(Dispatchers.Main) { binding.viewFinder.bitmap }
                if (bitmap != null) {
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                    val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                    
                    val response = callIdleVisionAI(base64Image)
                    val actions = parseAllActions(response)
                    
                    // Execute just a few casual actions
                    for (action in actions.take(3)) {
                        withContext(Dispatchers.Main) {
                            appendToLog("→ $action")
                            executeAction(action)
                        }
                        delay(2000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Idle browse failed: ${e.message}")
                appendToLog("⚠️ Browse error: ${e.message}")
            }
        }
    }
    
    private fun callIdleVisionAI(base64Image: String): String {
        val prompt = """You are casually browsing the web to keep a computer active.
            |
            |⚠️ FIRST: If you see any popups, dismiss them:
            |- Cookie banners → click "Accept" or "OK" or X
            |- Newsletter popups → click "No thanks" or X
            |- Any overlay/modal → close it
            |
            |Then do something interesting but harmless:
            |- Scroll around to read content
            |- Click an interesting link
            |- Explore the page naturally
            |
            |Respond with 2-3 simple commands:
            |- KEY:pagedown / KEY:pageup (scroll)
            |- KEY:escape (dismiss dialogs)
            |- CLICK:x,y (click something interesting or close a popup)
            |- MOVE:dx,dy (move mouse around)
            |- WAIT (pause to "read")
            |
            |Be casual and curious, like a human browsing.""".trimMargin()
        
        val json = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 150)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                    })
                })
            })
        }
        
        val request = buildApiRequest(json.toString())
        
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        
        if (!response.isSuccessful) {
            throw Exception("API error: $responseBody")
        }
        
        val responseJson = JSONObject(responseBody)
        val content = responseJson.getJSONArray("content")
        return content.getJSONObject(0).getString("text")
    }
    
    private fun calculateNextInterval(): Long {
        // If we just clicked, wait 10 seconds for slow computers to respond
        val timeSinceClick = System.currentTimeMillis() - lastClickTimeMs
        if (lastClickTimeMs > 0 && timeSinceClick < postClickWaitMs) {
            val remainingWait = postClickWaitMs - timeSinceClick
            Log.d(TAG, "Post-click wait: ${remainingWait}ms remaining")
            return remainingWait
        }
        
        // If we have pending actions in the queue, execute quickly
        if (pendingActions.isNotEmpty()) {
            return 1_000L  // 1 second between queued actions
        }
        
        // If we just did a meaningful action, brief pause to see results
        if (lastMeaningfulAction.isNotEmpty() && lastMeaningfulAction != "WAIT") {
            return maxIntervalMs
        }
        
        // If stalled, check more frequently
        val timeSinceLastAction = System.currentTimeMillis() - lastActionTimeMs
        if (timeSinceLastAction > stallThresholdMs || consecutiveWaits >= maxConsecutiveWaits) {
            return minIntervalMs
        }
        
        // Default interval
        return 3_000L
    }
    
    private fun stopVisionLoop() {
        isRunning = false
        isIdleMode = false
        binding.btnStart.text = "Done"
        updateStatus("Stopped")
    }
    
    private suspend fun captureAndAnalyze() {
        Log.d(TAG, "captureAndAnalyze: getting bitmap...")
        val bitmap = binding.viewFinder.bitmap
        if (bitmap == null) {
            Log.e(TAG, "captureAndAnalyze: bitmap is NULL!")
            return
        }
        Log.d(TAG, "captureAndAnalyze: bitmap size ${bitmap.width}x${bitmap.height}")
        
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val imageBytes = outputStream.toByteArray()
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        Log.d(TAG, "captureAndAnalyze: base64 length ${base64Image.length}, raw bytes ${imageBytes.size}")
        
        withContext(Dispatchers.IO) {
            try {
                val isRecoveryMode = consecutiveWaits >= maxConsecutiveWaits
                Log.d(TAG, "Calling Vision API (recovery=$isRecoveryMode)...")
                val response = callVisionAI(base64Image, isRecoveryMode)
                Log.d(TAG, "Vision API response: $response")
                val action = parseAction(response)
                Log.d(TAG, "Parsed action: $action")
                
                withContext(Dispatchers.Main) {
                    processAction(action)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vision analysis failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    updateStatus("Error: ${e.message}")
                    appendToLog("⚠️ Error: ${e.message}")
                }
            }
        }
    }
    
    private fun callVisionAI(base64Image: String, isRecoveryMode: Boolean): String {
        val recentActions = actionHistory.takeLast(5).joinToString("\n")
        
        // Build skill instructions section if a skill is selected
        val skillSection = if (currentSkill != null && currentSkill!!.instructions.isNotEmpty()) {
            """
            |
            |SKILL KNOWLEDGE (${currentSkill!!.name}):
            |${currentSkill!!.instructions}
            |""".trimMargin()
        } else {
            ""
        }
        
        val prompt = if (isRecoveryMode) {
            """You are controlling a computer via keyboard and mouse. You seem to be stuck.
            |
            |YOUR TASK: $currentTask
            |$skillSection
            |RECENT ACTIONS (may not have worked):
            |$recentActions
            |
            |⚠️ FIRST: Check for popups/dialogs blocking the screen:
            |- Cookie consent banners → click "Accept" or "OK" or close button
            |- System notifications → dismiss them
            |- Permission dialogs → click "Allow" or "OK" 
            |- Update prompts → click "Later" or "Not Now" or close
            |- Any modal/overlay → close it before continuing
            |
            |Then try a DIFFERENT approach. Maybe:
            |- Click somewhere with the mouse
            |- Use a keyboard shortcut (KEY:escape often closes dialogs)
            |- Try a different method
            |
            |Respond with a PLAN of 3-5 commands to try, one per line:
            |- TYPE:text (type text)
            |- KEY:keyname (press key: enter, tab, escape, space, cmd+space, cmd+n, etc.)
            |- CLICK:x,y (click at screen coordinates, e.g., CLICK:100,200)
            |- MOVE:dx,dy (move mouse by relative amount)
            |- LEFTCLICK / RIGHTCLICK / DOUBLECLICK (click at current position)
            |- WAIT (if waiting for something)
            |- DONE (if task is complete)
            |
            |Example plan:
            |KEY:escape
            |CLICK:850,520
            |WAIT""".trimMargin()
        } else {
            """You are controlling a computer via keyboard and mouse to complete a task.
            |
            |YOUR TASK: $currentTask
            |$skillSection
            |RECENT ACTIONS:
            |${if (recentActions.isEmpty()) "None yet" else recentActions}
            |
            |⚠️ FIRST PRIORITY: Check for and dismiss any popups/dialogs:
            |- Cookie consent banners → click "Accept", "OK", "Got it", or X button
            |- System notifications/alerts → dismiss or close them
            |- Permission dialogs → click "Allow" or "OK"
            |- Software update prompts → click "Later", "Not Now", or close
            |- Newsletter/signup popups → click X or "No thanks"
            |- Any modal or overlay blocking the screen → close it first
            |
            |After clearing popups, plan the NEXT 3-5 STEPS to make progress on the task.
            |
            |Respond with multiple commands, one per line:
            |- TYPE:text (type text)
            |- KEY:keyname (press key: enter, tab, escape, space, cmd+space, cmd+n, etc.)
            |- CLICK:x,y (click at screen coordinates, e.g., CLICK:100,200)
            |- MOVE:dx,dy (move mouse by relative amount)
            |- LEFTCLICK / RIGHTCLICK / DOUBLECLICK (click at current position)
            |- WAIT (if waiting for something to load)
            |- DONE (if task is complete)
            |
            |Prefer keyboard shortcuts when available. KEY:escape often closes dialogs.
            |
            |Example plan:
            |CLICK:920,45
            |WAIT
            |KEY:cmd+space
            |TYPE:Notes
            |KEY:enter""".trimMargin()
        }
        
        val json = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 300)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                    })
                })
            })
        }
        
        Log.d(TAG, "Making API request to $apiEndpoint")
        Log.d(TAG, "Credential starts with: ${apiKey.take(20)}...")
        Log.d(TAG, "Auth type: ${if (apiKey.startsWith("sk-ant-")) "API Key" else "Setup Token"}")
        
        val request = buildApiRequest(json.toString())
        
        Log.d(TAG, "Executing HTTP request...")
        val response = client.newCall(request).execute()
        Log.d(TAG, "HTTP response code: ${response.code}")
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        Log.d(TAG, "Response body length: ${responseBody.length}")
        
        if (!response.isSuccessful) {
            Log.e(TAG, "API error response: $responseBody")
            throw Exception("API error: $responseBody")
        }
        
        val responseJson = JSONObject(responseBody)
        val content = responseJson.getJSONArray("content")
        val text = content.getJSONObject(0).getString("text")
        Log.d(TAG, "API returned text: $text")
        return text
    }
    
    private fun parseAction(response: String): String {
        // Parse first action for backward compatibility, but queue the rest
        val actions = parseAllActions(response)
        if (actions.isEmpty()) return "WAIT"
        
        // Queue all but the first action
        if (actions.size > 1) {
            pendingActions.clear()
            pendingActions.addAll(actions.drop(1))
            appendToLog("📋 Planned ${actions.size} steps")
        }
        
        return actions.first()
    }
    
    private fun parseAllActions(response: String): List<String> {
        val actions = mutableListOf<String>()
        val lines = response.trim().split("\n")
        
        for (line in lines) {
            val trimmed = line.trim()
            val upper = trimmed.uppercase()
            if (upper.startsWith("TYPE:") || 
                upper.startsWith("KEY:") || 
                upper.startsWith("CLICK:") ||
                upper.startsWith("MOVE:") ||
                upper == "LEFTCLICK" ||
                upper == "RIGHTCLICK" ||
                upper == "DOUBLECLICK" ||
                upper == "WAIT" ||
                upper == "DONE") {
                actions.add(trimmed) // Keep original case for TYPE content
            }
        }
        
        return actions
    }
    
    private fun processAction(action: String) {
        updateStatus("Action: $action")
        
        // Track action history
        actionHistory.add(action)
        if (actionHistory.size > 10) {
            actionHistory.removeAt(0)
        }
        
        // Log the action to the scrollable log
        appendToLog("→ $action")
        
        // Handle action
        if (action.uppercase() == "DONE") {
            Log.d(TAG, "Task marked as DONE")
            appendToLog("✅ Task complete!")
            pendingActions.clear() // Clear any remaining queued actions
            isTaskComplete = true
        } else if (action.uppercase() == "WAIT") {
            consecutiveWaits++
            Log.d(TAG, "WAIT action, consecutiveWaits=$consecutiveWaits")
            // On WAIT, clear pending actions and re-evaluate with fresh screenshot
            if (consecutiveWaits >= maxConsecutiveWaits) {
                pendingActions.clear()
                appendToLog("⚡ Re-evaluating...")
            }
        } else {
            consecutiveWaits = 0
            lastActionTimeMs = System.currentTimeMillis()
            lastMeaningfulAction = action
            Log.d(TAG, "Executing action: $action")
            executeAction(action)
        }
    }
    
    // Estimated mouse position (assume screen is ~1440x900 ish, start at center)
    private var mouseX = 720
    private var mouseY = 450
    
    private fun executeAction(action: String) {
        val upper = action.uppercase()
        when {
            upper.startsWith("TYPE:") -> {
                val text = action.substring(5)
                Log.d(TAG, "Sending TYPE via HID: $text")
                sendText(text)
            }
            upper.startsWith("KEY:") -> {
                val key = action.substring(4)
                Log.d(TAG, "Sending KEY via HID: $key")
                sendKey(key)
            }
            upper.startsWith("CLICK:") -> {
                // Format: CLICK:x,y
                val coords = action.substring(6).split(",")
                if (coords.size == 2) {
                    val targetX = coords[0].trim().toIntOrNull() ?: return
                    val targetY = coords[1].trim().toIntOrNull() ?: return
                    Log.d(TAG, "CLICK at ($targetX, $targetY), current mouse at ($mouseX, $mouseY)")
                    appendToLog("🖱️ Click ($targetX, $targetY)")
                    
                    // Calculate relative movement
                    val deltaX = targetX - mouseX
                    val deltaY = targetY - mouseY
                    
                    // Move to position with settle time
                    moveMouse(deltaX, deltaY)
                    Thread.sleep(150)  // Let cursor settle
                    
                    // Robust click
                    mouseClick()
                    
                    // Update estimated position
                    mouseX = targetX
                    mouseY = targetY
                    
                    appendToLog("⏳ Waiting 10s for response...")
                }
            }
            upper.startsWith("MOVE:") -> {
                // Format: MOVE:dx,dy (relative movement)
                val deltas = action.substring(5).split(",")
                if (deltas.size == 2) {
                    val dx = deltas[0].trim().toIntOrNull() ?: return
                    val dy = deltas[1].trim().toIntOrNull() ?: return
                    Log.d(TAG, "MOVE by ($dx, $dy)")
                    moveMouse(dx, dy)
                    mouseX += dx
                    mouseY += dy
                }
            }
            upper == "LEFTCLICK" -> {
                Log.d(TAG, "Left click at current position")
                appendToLog("🖱️ Left click")
                mouseClick(1)
                appendToLog("⏳ Waiting 10s for response...")
            }
            upper == "RIGHTCLICK" -> {
                Log.d(TAG, "Right click at current position")
                appendToLog("🖱️ Right click")
                mouseClick(2)
                appendToLog("⏳ Waiting 10s for response...")
            }
            upper == "DOUBLECLICK" -> {
                Log.d(TAG, "Double click at current position")
                appendToLog("🖱️ Double click")
                mouseClick(1)
                Thread.sleep(80)
                mouseClick(1)
                appendToLog("⏳ Waiting 10s for response...")
            }
        }
    }
    
    private fun sendText(text: String) {
        for (char in text) {
            val keyCode = charToHidKeyCode(char)
            if (keyCode != null) {
                sendKeyReport(keyCode.first, keyCode.second)
                Thread.sleep(30) // Small delay between keys
                sendKeyReport(0, 0)
                Thread.sleep(20)
            }
        }
    }
    
    private fun sendKey(keyName: String) {
        val lower = keyName.lowercase().trim()
        
        // Handle modifier combos like cmd+space
        if (lower.contains("+")) {
            val parts = lower.split("+")
            val modifier = when (parts[0]) {
                "cmd", "command", "meta", "gui" -> 0x08 // Left GUI/Command
                "ctrl", "control" -> 0x01
                "shift" -> 0x02
                "alt", "option" -> 0x04
                else -> 0
            }
            val key = parts.getOrNull(1) ?: ""
            val keyCode = getKeyCode(key)
            if (keyCode != null) {
                sendKeyReport(modifier, keyCode)
                Thread.sleep(50)
                sendKeyReport(0, 0)
            }
            return
        }
        
        val keyCode = getKeyCode(lower)
        if (keyCode != null) {
            sendKeyReport(0, keyCode)
            Thread.sleep(50)
            sendKeyReport(0, 0)
        }
    }
    
    private fun getKeyCode(key: String): Int? {
        return when (key) {
            "enter", "return" -> 0x28
            "escape", "esc" -> 0x29
            "backspace", "delete" -> 0x2A
            "tab" -> 0x2B
            "space" -> 0x2C
            "up" -> 0x52
            "down" -> 0x51
            "left" -> 0x50
            "right" -> 0x4F
            "home" -> 0x4A
            "end" -> 0x4D
            "pageup" -> 0x4B
            "pagedown" -> 0x4E
            "a" -> 0x04
            "b" -> 0x05
            "c" -> 0x06
            "d" -> 0x07
            "e" -> 0x08
            "f" -> 0x09
            "g" -> 0x0A
            "h" -> 0x0B
            "i" -> 0x0C
            "j" -> 0x0D
            "k" -> 0x0E
            "l" -> 0x0F
            "m" -> 0x10
            "n" -> 0x11
            "o" -> 0x12
            "p" -> 0x13
            "q" -> 0x14
            "r" -> 0x15
            "s" -> 0x16
            "t" -> 0x17
            "u" -> 0x18
            "v" -> 0x19
            "w" -> 0x1A
            "x" -> 0x1B
            "y" -> 0x1C
            "z" -> 0x1D
            else -> null
        }
    }
    
    private fun sendKeyReport(modifier: Int, keyCode: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "sendKeyReport: no permission")
            return
        }
        
        // Keyboard report: [modifier, reserved, key1, key2, key3, key4, key5, key6]
        val report = byteArrayOf(
            modifier.toByte(),
            0,
            keyCode.toByte(),
            0, 0, 0, 0, 0
        )
        
        if (connectedDevice == null) {
            Log.e(TAG, "sendKeyReport: connectedDevice is NULL!")
            return
        }
        
        if (hidDevice == null) {
            Log.e(TAG, "sendKeyReport: hidDevice is NULL!")
            return
        }
        
        connectedDevice?.let { device ->
            val result = hidDevice?.sendReport(device, REPORT_ID_KEYBOARD, report)
            Log.d(TAG, "sendKeyReport(modifier=$modifier, keyCode=$keyCode) returned: $result")
        }
    }
    
    // Mouse report: [buttons, deltaX, deltaY]
    private fun sendMouseReport(buttons: Int, deltaX: Int, deltaY: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "sendMouseReport: no permission")
            return
        }
        
        // Clamp delta values to -127 to 127
        val clampedX = deltaX.coerceIn(-127, 127)
        val clampedY = deltaY.coerceIn(-127, 127)
        
        val report = byteArrayOf(
            buttons.toByte(),
            clampedX.toByte(),
            clampedY.toByte()
        )
        
        if (connectedDevice == null) {
            Log.e(TAG, "sendMouseReport: connectedDevice is NULL!")
            return
        }
        
        connectedDevice?.let { device ->
            val result = hidDevice?.sendReport(device, REPORT_ID_MOUSE, report)
            Log.d(TAG, "sendMouseReport(buttons=$buttons, x=$clampedX, y=$clampedY) returned: $result")
        }
    }
    
    // Move mouse by relative amount (breaks into multiple reports if needed)
    private fun moveMouse(deltaX: Int, deltaY: Int) {
        var remainingX = deltaX
        var remainingY = deltaY
        
        while (remainingX != 0 || remainingY != 0) {
            val moveX = remainingX.coerceIn(-127, 127)
            val moveY = remainingY.coerceIn(-127, 127)
            sendMouseReport(0, moveX, moveY)
            remainingX -= moveX
            remainingY -= moveY
            Thread.sleep(10) // Small delay between movements
        }
    }
    
    // Click at current position - robust version with settle and longer hold
    private fun mouseClick(button: Int = 1) {
        // Settle - send zero movement to ensure cursor is stable
        sendMouseReport(0, 0, 0)
        Thread.sleep(50)
        
        // Press button
        sendMouseReport(button, 0, 0)
        Thread.sleep(100)  // Longer hold for reliability
        
        // Release button
        sendMouseReport(0, 0, 0)
        Thread.sleep(50)
        
        // Record click time for post-click wait
        lastClickTimeMs = System.currentTimeMillis()
        Log.d(TAG, "Click completed, will wait ${postClickWaitMs}ms before next action")
    }
    
    // Click with retry and jitter if needed
    private fun mouseClickWithRetry(button: Int = 1, retries: Int = 2) {
        for (attempt in 0..retries) {
            if (attempt > 0) {
                // Jitter: small random offset on retry
                val jitterX = (-5..5).random()
                val jitterY = (-5..5).random()
                Log.d(TAG, "Click retry $attempt with jitter ($jitterX, $jitterY)")
                appendToLog("🔄 Click retry $attempt")
                moveMouse(jitterX, jitterY)
                Thread.sleep(100)
            }
            mouseClick(button)
            
            // Only retry once for now (can make smarter with verification later)
            if (attempt == 0) break
        }
    }
    
    // Move and click (relative movement)
    private fun moveAndClick(deltaX: Int, deltaY: Int) {
        moveMouse(deltaX, deltaY)
        Thread.sleep(100)  // Longer settle time
        mouseClick()
    }
    
    private fun charToHidKeyCode(char: Char): Pair<Int, Int>? {
        return when (char) {
            in 'a'..'z' -> Pair(0, char - 'a' + 4)
            in 'A'..'Z' -> Pair(0x02, char - 'A' + 4)
            in '1'..'9' -> Pair(0, char - '1' + 0x1E)
            '0' -> Pair(0, 0x27)
            ' ' -> Pair(0, 0x2C)
            '\n' -> Pair(0, 0x28)
            '.' -> Pair(0, 0x37)
            ',' -> Pair(0, 0x36)
            '-' -> Pair(0, 0x2D)
            '=' -> Pair(0, 0x2E)
            '/' -> Pair(0, 0x38)
            ';' -> Pair(0, 0x33)
            '\'' -> Pair(0, 0x34)
            else -> null
        }
    }
    
    private fun updateStatus(status: String) {
        binding.tvStatus.text = status
        Log.d(TAG, status)
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int, 
        permissions: Array<String>, 
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && allPermissionsGranted()) {
            startCamera()
            val deviceAddress = intent.getStringExtra(PlanActivity.EXTRA_DEVICE_ADDRESS)
            setupBluetoothHid(deviceAddress)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cameraExecutor.shutdown()
        scope.cancel()
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
            == PackageManager.PERMISSION_GRANTED) {
            hidDevice?.unregisterApp()
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        }
    }
}
