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
    private var durationMs = 5 * 60 * 1000L
    private var startTimeMs = 0L
    private var lastActionTimeMs = 0L
    private var lastMeaningfulAction = ""
    private var consecutiveWaits = 0
    private val actionHistory = mutableListOf<String>()
    private var isTaskComplete = false
    
    // Subtask system
    private val subtasks = mutableListOf<String>()
    private var currentSubtaskIndex = 0
    private var iterationsSinceSubtaskCheck = 0
    private val checkSubtaskEveryN = 5  // Check if subtask is done every 5 iterations
    
    // Track completed subtasks with how they were done
    private val completedSubtasks = mutableListOf<Pair<String, String>>() // (subtask, how completed)
    
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
    private val postClickWaitMs = 3_000L  // Wait 3 seconds after clicks
    
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
    private val idleIntervalMs = 60_000L           // 60 seconds between vision checks
    
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
    
    private val maxLogLines = 200
    
    private fun checkForCreditError(errorMessage: String): Boolean {
        if (errorMessage.contains("credit balance is too low", ignoreCase = true) ||
            errorMessage.contains("insufficient_quota", ignoreCase = true) ||
            errorMessage.contains("rate_limit", ignoreCase = true)) {
            
            val isCredit = errorMessage.contains("credit", ignoreCase = true)
            val title = if (isCredit) "Out of API Credits" else "API Rate Limited"
            val message = if (isCredit) {
                "Your Anthropic API credit balance is too low. Please add credits at console.anthropic.com to continue."
            } else {
                "API rate limit reached. The app will pause and retry shortly."
            }
            
            runOnUiThread {
                updateStatus("⚠️ $title")
                appendToLog("🚨 $title")
                
                if (isCredit) {
                    // Stop the loop — no point retrying with no credits
                    isRunning = false
                    isIdleMode = false
                    binding.btnStart.text = "Begin"
                }
                
                try {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not show alert dialog: ${e.message}")
                }
            }
            return true
        }
        return false
    }
    
    private fun appendToLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        actionLog.append("[$timestamp] $message\n")
        
        // Trim log to prevent OOM — keep last maxLogLines lines
        val lines = actionLog.lines()
        if (lines.size > maxLogLines) {
            actionLog.clear()
            actionLog.append(lines.takeLast(maxLogLines).joinToString("\n"))
            actionLog.append("\n")
        }
        
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
        subtasks.clear()
        currentSubtaskIndex = 0
        iterationsSinceSubtaskCheck = 0
        
        binding.btnStart.text = "Stop"
        updateStatus("Planning: $currentTask")
        Log.d(TAG, "Vision loop starting, HID connected: ${connectedDevice != null}")
        appendToLog("🚀 Starting vision loop...")
        appendToLog("Duration: ${durationMs / 60000} minutes")
        
        scope.launch {
            // First, plan subtasks
            appendToLog("📋 Planning subtasks...")
            try {
                val planned = withContext(Dispatchers.IO) { planSubtasks(currentTask) }
                subtasks.addAll(planned)
                appendToLog("📋 ${subtasks.size} subtasks planned:")
                subtasks.forEachIndexed { i, task -> 
                    appendToLog("  ${i+1}. $task")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subtask planning failed: ${e.message}")
                if (checkForCreditError(e.message ?: "")) return@launch
                appendToLog("⚠️ Planning failed, proceeding with main task")
                subtasks.add(currentTask)  // Fall back to original task
            }
            updateStatus("Working: ${getCurrentSubtask()}")
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
                    val upper = nextAction.uppercase()
                    if (upper.startsWith("CLICKTARGET:") || upper.startsWith("CLICK:")) {
                        // Handle CLICKTARGET in the main loop so it blocks
                        val description = if (upper.startsWith("CLICKTARGET:")) {
                            nextAction.substring(12)
                        } else {
                            nextAction.substring(6)
                        }
                        withContext(Dispatchers.Main) {
                            appendToLog("🎯 Targeting: $description")
                        }
                        val clickSucceeded = visualNudgeAndClick(description)
                        if (clickSucceeded) {
                            actionHistory.add("CLICK_SUCCESS:$description")
                        } else {
                            // Click failed — clear remaining plan and force re-evaluation
                            pendingActions.clear()
                            withContext(Dispatchers.Main) {
                                appendToLog("🔄 Click failed, re-planning...")
                            }
                            actionHistory.add("CLICK_FAILED:$description")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            processAction(nextAction)
                        }
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
                idleActionCount++
                
                runOnUiThread {
                    binding.tvLastAction.text = "Idle mode • Action #$idleActionCount"
                    updateStatus("🌙 Idle mode active")
                }
                
                // Every action is vision-driven
                idleVisionAction()
                
                lastIdleActionMs = System.currentTimeMillis()
                
                // Wait 60 seconds before next action
                delay(idleIntervalMs)
            }
            
            Log.d(TAG, "Idle mode ended")
        }
    }
    
    private suspend fun idleVisionAction() {
        try {
            val bitmap = withContext(Dispatchers.Main) { binding.viewFinder.bitmap }
            if (bitmap == null) {
                Log.e(TAG, "Idle vision: bitmap is NULL")
                appendToLog("💤 Waiting (no camera)...")
                return
            }
            
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            
            val response = withContext(Dispatchers.IO) {
                callIdleVisionAI(base64Image)
            }
            
            val actions = parseAllActions(response)
            
            // Execute up to 3 actions per cycle
            for (action in actions.take(3)) {
                val upper = action.uppercase()
                if (upper.startsWith("CLICKTARGET:") || (upper.startsWith("CLICK:") && !upper.contains(","))) {
                    // CLICKTARGET needs visual nudge — handle as suspend
                    val description = if (upper.startsWith("CLICKTARGET:")) {
                        action.substring(12)
                    } else {
                        action.substring(6)
                    }
                    withContext(Dispatchers.Main) {
                        appendToLog("🎯 Targeting: $description")
                    }
                    visualNudgeAndClick(description)
                } else {
                    withContext(Dispatchers.Main) {
                        appendToLog("→ $action")
                        executeAction(action)
                    }
                }
                delay(1000)
            }
            
            if (actions.isEmpty()) {
                appendToLog("💤 Nothing to do")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Idle vision failed: ${e.message}")
            if (checkForCreditError(e.message ?: "")) return
            appendToLog("⚠️ Idle error: ${e.message}")
            // Fallback: small mouse wiggle to prevent sleep
            withContext(Dispatchers.Main) {
                val dx = (-15..15).random()
                val dy = (-15..15).random()
                moveMouse(dx, dy)
                Thread.sleep(200)
                moveMouse(-dx, -dy)
            }
        }
    }
    
    private fun callIdleVisionAI(base64Image: String): String {
        val recentIdleActions = actionHistory.takeLast(10).joinToString("\n")
        
        val prompt = """You are a human sitting at a computer during idle time. Act naturally — like a real person who just finished a task and is now casually using the computer.
            |
            |PRIORITIES (in order):
            |1. NOTIFICATIONS: Look for any notification banners, badges, or alerts (new emails, messages, calendar reminders, system alerts). If you see any, use NOTIFY: to report them and interact with them.
            |2. POPUPS/DIALOGS: Dismiss any popups, cookie banners, or dialogs.
            |3. ACT HUMAN: Do something a person would naturally do — scroll the page, click an interesting link, read content, check something. Vary your actions. Don't repeat the same thing.
            |
            |RECENT ACTIONS:
            |${if (recentIdleActions.isEmpty()) "None yet" else recentIdleActions}
            |
            |Respond with 1-3 commands:
            |- KEY:keyname (scroll with pagedown/pageup, press escape, cmd+tab to switch apps, etc.)
            |- CLICKTARGET:description (click something visible — a link, button, tab, menu item)
            |- CLICK:x,y (click at specific coordinates)
            |- MOVE:dx,dy (move the mouse naturally)
            |- TYPE:text (only if a text field is clearly focused)
            |- NOTIFY:message (report something notable — a notification, new email badge, alert)
            |- WAIT (pause, like reading something)
            |
            |BEHAVE LIKE A REAL PERSON:
            |- Scroll through pages to read them
            |- Click interesting links or articles
            |- Switch between open tabs or apps occasionally
            |- Move the mouse to different areas naturally
            |- Don't just do MOVE:5,0 every time — that looks robotic
            |- Mix up your actions: scroll, click, read, explore
            |
            |⚠️ Don't do anything destructive (delete files, close important windows, change settings).
            |⚠️ You'll be called again in 60 seconds, so keep actions small.
            |⚠️ POPUPS/DIALOGS: Never use CLICKTARGET on popup buttons (cookie consent, legal terms, sign-in). Use KEY:tab then KEY:enter, or KEY:escape. If a popup persists, ignore it and move on.""".trimMargin()
        
        val json = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 200)
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
                val actions = parseAllActions(response)
                Log.d(TAG, "Parsed ${actions.size} actions")
                
                withContext(Dispatchers.Main) {
                    if (actions.isNotEmpty()) {
                        pendingActions.clear()
                        pendingActions.addAll(actions)
                        appendToLog("📋 Planned ${actions.size} steps")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vision analysis failed: ${e.message}", e)
                if (checkForCreditError(e.message ?: "")) return@withContext
                withContext(Dispatchers.Main) {
                    updateStatus("Error: ${e.message}")
                    appendToLog("⚠️ Error: ${e.message}")
                }
            }
        }
    }
    
    private fun getCurrentSubtask(): String {
        return if (subtasks.isNotEmpty() && currentSubtaskIndex < subtasks.size) {
            subtasks[currentSubtaskIndex]
        } else {
            currentTask
        }
    }
    
    private fun getSubtaskProgress(): String {
        return if (subtasks.size > 1) {
            "Subtask ${currentSubtaskIndex + 1}/${subtasks.size}"
        } else {
            ""
        }
    }
    
    private fun planSubtasks(task: String): List<String> {
        val prompt = """Break this task into 2-5 simple sequential subtasks.
            |
            |TASK: $task
            |
            |Rules:
            |- Each subtask should be a meaningful phase of work, not a single click
            |- Group related actions into one subtask (e.g. "Click all buttons on the page" not "Click button 1", "Click button 2", etc.)
            |- Keep the total to 2-5 subtasks maximum
            |- Include opening apps and navigating as subtasks only if needed
            |
            |Respond with ONLY the subtasks, one per line, numbered:
            |1. First subtask
            |2. Second subtask
            |etc.""".trimMargin()
        
        val json = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 200)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
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
        val text = content.getJSONObject(0).getString("text")
        
        // Parse numbered list
        return text.lines()
            .map { it.trim() }
            .filter { it.matches(Regex("^\\d+\\..*")) }
            .map { it.replace(Regex("^\\d+\\.\\s*"), "") }
            .filter { it.isNotBlank() }
    }
    
    private fun callVisionAI(base64Image: String, isRecoveryMode: Boolean): String {
        val recentActions = actionHistory.takeLast(15).joinToString("\n")
        
        // Build full plan view
        val planView = if (subtasks.size > 1) {
            val planLines = subtasks.mapIndexed { i, task ->
                val status = when {
                    i < currentSubtaskIndex -> "✅"
                    i == currentSubtaskIndex -> "👉"
                    else -> "⬜"
                }
                "$status ${i+1}. $task"
            }.joinToString("\n|")
            "\n|FULL PLAN:\n|$planLines\n|OVERALL GOAL: $currentTask"
        } else ""
        
        // Build completed subtask context
        val completedContext = if (completedSubtasks.isNotEmpty()) {
            val lines = completedSubtasks.joinToString("\n|") { (name, how) ->
                "- $name → Done via: $how"
            }
            "\n|COMPLETED SO FAR:\n|$lines"
        } else ""
        
        val currentSubtask = getCurrentSubtask()
        
        val prompt = if (isRecoveryMode) {
            """You are controlling a computer via keyboard and mouse. You seem stuck.
            |
            |CURRENT SUBTASK: $currentSubtask$planView$completedContext
            |
            |RECENT ACTIONS (may not have worked):
            |$recentActions
            |
            |⚠️ FIRST: Close anything blocking:
            |- KEY:escape (close dialogs, popups, menus)
            |- KEY:cmd+w (close unrelated windows)
            |- For web popups (cookie consent, legal terms): use KEY:tab then KEY:enter — NEVER CLICKTARGET on popup buttons
            |
            |Try a DIFFERENT approach than what you already tried.
            |⚠️ To open apps: USE SPOTLIGHT (KEY:cmd+space, then TYPE:appname, then KEY:enter). Do NOT click dock icons.
            |⚠️ If a popup won't dismiss, IGNORE IT. Use KEY:cmd+l to focus the address bar directly.
            |⚠️ If CLICK_FAILED is in recent actions: the page is STILL OPEN. DO NOT navigate, open Spotlight, or type URLs. Just mark SUBTASK_DONE and move to the next element.
            |⚠️ If CLICK_SUCCESS:X is in recent actions: X was ALREADY CLICKED. Do NOT click it again. Move to the next element or mark SUBTASK_DONE.
            |
            |Respond with 5-10 commands, one per line:
            |- TYPE:text (type text into focused field)
            |- KEY:keyname (enter, tab, escape, space, cmd+space, cmd+tab, cmd+w, cmd+n, etc.)
            |- CLICKTARGET:description (click a UI element, describe what to click)
            |- WAIT (pause for something to load)
            |- SUBTASK_DONE (current subtask complete)
            |- DONE (all tasks complete)
            |
            |⚠️ NEVER type unless you can SEE the focused text field.
            |
            |Example plan:
            |KEY:escape
            |KEY:cmd+space
            |TYPE:Safari
            |KEY:enter
            |WAIT""".trimMargin()
        } else {
            """You are controlling a computer via keyboard and mouse to complete a task.
            |
            |CURRENT SUBTASK: $currentSubtask$planView$completedContext
            |
            |RECENT ACTIONS:
            |${if (recentActions.isEmpty()) "None yet" else recentActions}
            |
            |⚠️ FIRST PRIORITY: Close any distractions (popups, unrelated windows, dialogs).
            |
            |Then plan the NEXT 5-10 STEPS to make progress on the task.
            |
            |⚠️ IMPORTANT TIPS:
            |- To open apps: USE SPOTLIGHT (KEY:cmd+space, then TYPE:appname, then KEY:enter). This is MORE RELIABLE than clicking dock icons.
            |- NEVER type unless you can SEE the focused input field
            |- Clicking dock icons is unreliable — prefer keyboard shortcuts
            |- POPUPS/DIALOGS (cookie consent, legal terms, sign-in prompts): NEVER use CLICKTARGET on popup buttons — mouse clicks on web dialogs are unreliable. Instead use KEY:tab to focus the button, then KEY:enter to press it. Or KEY:escape to dismiss. If a popup persists after 2 attempts, IGNORE IT and work around it (e.g. KEY:cmd+l to focus address bar directly).
            |- If you see CLICK_FAILED in recent actions: the page is STILL OPEN. DO NOT navigate, DO NOT open Spotlight, DO NOT type URLs. Just mark SUBTASK_DONE and move to the next element.
            |- NEVER use KEY:cmd+tab, KEY:cmd+space, KEY:cmd+l, or TYPE a URL after a click failure. The page hasn't changed.
            |- If CLICK_SUCCESS:X appears in recent actions, that element WAS ALREADY CLICKED. Do NOT click it again. Move to the NEXT element.
            |
            |Respond with multiple commands, one per line:
            |- TYPE:text (type text into focused field)
            |- KEY:keyname (enter, tab, escape, space, cmd+space, cmd+tab, cmd+w, cmd+n, shift+tab, etc.)
            |- CLICKTARGET:description (click a UI element — describe what to click, e.g. "Send button", "address bar")
            |- WAIT (if waiting for something to load)
            |- SUBTASK_DONE (if current subtask is complete, move to next)
            |- DONE (if ALL tasks are complete)
            |
            |Example plan to open a browser and search:
            |KEY:cmd+space
            |TYPE:Google Chrome
            |KEY:enter
            |WAIT
            |CLICKTARGET:address bar
            |TYPE:google.com
            |KEY:enter""".trimMargin()
        }
        
        val json = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 500)
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
                upper.startsWith("CLICKTARGET:") ||
                upper.startsWith("CLICK:") ||
                upper.startsWith("MOVE:") ||
                upper.startsWith("NOTIFY:") ||
                upper == "LEFTCLICK" ||
                upper == "RIGHTCLICK" ||
                upper == "DOUBLECLICK" ||
                upper == "WAIT" ||
                upper == "SUBTASK_DONE" ||
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
        if (actionHistory.size > 20) {
            actionHistory.removeAt(0)
        }
        
        // Log the action to the scrollable log
        appendToLog("→ $action")
        
        // Handle action
        if (action.uppercase() == "DONE") {
            Log.d(TAG, "Task marked as DONE")
            appendToLog("✅ All tasks complete!")
            pendingActions.clear() // Clear any remaining queued actions
            isTaskComplete = true
        } else if (action.uppercase() == "SUBTASK_DONE") {
            Log.d(TAG, "Subtask marked as DONE")
            pendingActions.clear() // Clear any remaining queued actions
            resetClickStuckCounter()
            
            // Save completed subtask name, will summarize async
            val completedName = getCurrentSubtask()
            appendToLog("📸 Summarizing what was done...")
            
            // Launch summary coroutine
            scope.launch {
                val summary = try {
                    summarizeSubtaskCompletion(completedName)
                } catch (e: Exception) {
                    Log.e(TAG, "Summary failed: ${e.message}")
                    if (checkForCreditError(e.message ?: "")) return@launch
                    "Completed (summary unavailable)"
                }
                completedSubtasks.add(Pair(completedName, summary))
                actionHistory.clear()
                
                withContext(Dispatchers.Main) {
                    appendToLog("📝 $summary")
                    if (currentSubtaskIndex < subtasks.size - 1) {
                        currentSubtaskIndex++
                        appendToLog("✅ Moving to: ${getCurrentSubtask()}")
                        updateStatus("Working: ${getCurrentSubtask()}")
                    } else {
                        appendToLog("✅ All subtasks complete!")
                        isTaskComplete = true
                    }
                }
            }
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
    
    private suspend fun summarizeSubtaskCompletion(subtaskName: String): String {
        try {
            val bitmap = withContext(Dispatchers.Main) { binding.viewFinder.bitmap }
            if (bitmap == null) return "Completed (no screenshot available)"
            
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            
            val prompt = """I just finished this subtask: "$subtaskName"
                |
                |Look at the screen and describe in ONE short sentence:
                |1. What was accomplished
                |2. What is currently visible on screen
                |
                |Be specific and brief. One sentence only.""".trimMargin()
            
            val json = JSONObject().apply {
                put("model", "claude-sonnet-4-20250514")
                put("max_tokens", 100)
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
            
            val response = withContext(Dispatchers.IO) {
                val request = buildApiRequest(json.toString())
                val resp = client.newCall(request).execute()
                val body = resp.body?.string() ?: throw Exception("Empty response")
                if (!resp.isSuccessful) throw Exception("API error: $body")
                val respJson = JSONObject(body)
                respJson.getJSONArray("content").getJSONObject(0).getString("text")
            }
            
            return response.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Summary failed: ${e.message}")
            return "Completed (summary unavailable)"
        }
    }
    
    // Track consecutive click attempts on SAME target for stuck detection
    private var consecutiveClickAttempts = 0
    private var lastClickTargetNormalized = ""
    
    private fun normalizeTarget(desc: String): String {
        // Extract key words, ignoring modifiers like "in the popup dialog"
        return desc.lowercase()
            .replace(Regex("\\b(button|the|in|on|a|an|of|for|dialog|popup|section|area|corner|top|bottom|left|right|pink|red|blue|green|white|black)\\b"), "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }
    
    fun resetClickStuckCounter() {
        consecutiveClickAttempts = 0
        lastClickTargetNormalized = ""
    }
    
    // Returns true if click succeeded, false if target not found / aborted
    private suspend fun visualNudgeAndClick(targetDescription: String, maxNudges: Int = 6): Boolean {
        // Stuck detection: count consecutive attempts on the same normalized target
        val normalized = normalizeTarget(targetDescription)
        if (normalized == lastClickTargetNormalized) {
            consecutiveClickAttempts++
        } else {
            lastClickTargetNormalized = normalized
            consecutiveClickAttempts = 1
        }
        
        if (consecutiveClickAttempts >= 4) {
            withContext(Dispatchers.Main) {
                appendToLog("🔄 Stuck clicking — trying keyboard fallback (Tab+Enter)")
                sendKey("tab")
            }
            delay(300)
            withContext(Dispatchers.Main) {
                sendKey("enter")
            }
            delay(500)
            
            // If still stuck after 6 attempts, try Escape to dismiss instead
            if (consecutiveClickAttempts >= 6) {
                withContext(Dispatchers.Main) {
                    appendToLog("🔄 Still stuck — trying Escape")
                    sendKey("escape")
                }
                delay(500)
                consecutiveClickAttempts = 0
            }
            return false // stuck fallback attempted
        }
        
        // Reset cursor to center of screen before targeting
        val resetDx = 720 - mouseX
        val resetDy = 450 - mouseY
        if (Math.abs(resetDx) > 10 || Math.abs(resetDy) > 10) {
            withContext(Dispatchers.Main) {
                moveMouse(resetDx, resetDy)
            }
            mouseX = 720
            mouseY = 450
            delay(200)
        }
        
        for (attempt in 1..maxNudges) {
            // Capture current view
            val bitmap = withContext(Dispatchers.Main) { binding.viewFinder.bitmap }
            if (bitmap == null) {
                Log.e(TAG, "visualNudgeAndClick: bitmap is NULL")
                return false
            }
            
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            
            withContext(Dispatchers.Main) {
                appendToLog("👁️ Looking for target (attempt $attempt/$maxNudges)...")
            }
            
            val response = try {
                withContext(Dispatchers.IO) {
                    callNudgeAI(base64Image, targetDescription, attempt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Nudge API failed: ${e.message}")
                if (checkForCreditError(e.message ?: "")) return false
                withContext(Dispatchers.Main) {
                    appendToLog("⚠️ Nudge error: ${e.message?.take(80)}")
                }
                delay(5000) // Back off on error
                continue
            }
            
            val trimmed = response.trim()
            val upper = trimmed.uppercase()
            Log.d(TAG, "Nudge response: $trimmed")
            
            if (upper.startsWith("CLICK")) {
                // Cursor is on target — click with retry for reliability
                withContext(Dispatchers.Main) {
                    appendToLog("✅ On target, clicking!")
                    mouseClickWithRetry()
                    lastClickTimeMs = System.currentTimeMillis()
                    appendToLog("⏳ Waiting for response...")
                }
                return true
            } else if (upper.startsWith("NUDGE:")) {
                // Parse relative movement: NUDGE:dx,dy
                val parts = trimmed.substring(6).split(",")
                if (parts.size == 2) {
                    val dx = parts[0].trim().toIntOrNull() ?: 0
                    val dy = parts[1].trim().toIntOrNull() ?: 0
                    withContext(Dispatchers.Main) {
                        appendToLog("🔧 Nudging ($dx, $dy)")
                        moveMouse(dx, dy)
                    }
                    mouseX += dx
                    mouseY += dy
                    delay(200) // Let cursor settle
                    continue
                }
                // Couldn't parse, try again
                withContext(Dispatchers.Main) {
                    appendToLog("⚠️ Bad nudge format: $trimmed")
                }
            } else if (upper.startsWith("ABORT")) {
                // Target not visible on screen
                withContext(Dispatchers.Main) {
                    appendToLog("❌ Target not found: $targetDescription")
                }
                return false
            } else {
                // Fallback: try to extract action from verbose response
                val nudgeMatch = Regex("NUDGE:\\s*(-?\\d+)\\s*,\\s*(-?\\d+)", RegexOption.IGNORE_CASE).find(upper)
                if (upper.contains("CLICK") && !upper.contains("NUDGE")) {
                    withContext(Dispatchers.Main) {
                        appendToLog("✅ On target (extracted), clicking!")
                        mouseClickWithRetry()
                        lastClickTimeMs = System.currentTimeMillis()
                        appendToLog("⏳ Waiting for response...")
                    }
                    return true
                } else if (nudgeMatch != null) {
                    val dx = nudgeMatch.groupValues[1].toIntOrNull() ?: 0
                    val dy = nudgeMatch.groupValues[2].toIntOrNull() ?: 0
                    withContext(Dispatchers.Main) {
                        appendToLog("🔧 Nudging (extracted) ($dx, $dy)")
                        moveMouse(dx, dy)
                    }
                    mouseX += dx
                    mouseY += dy
                    delay(200)
                    continue
                } else if (upper.contains("ABORT")) {
                    withContext(Dispatchers.Main) {
                        appendToLog("❌ Target not found (extracted): $targetDescription")
                    }
                    return false
                } else {
                    withContext(Dispatchers.Main) {
                        appendToLog("⚠️ Unexpected: ${trimmed.take(100)}")
                    }
                }
            }
        }
        
        // Max nudges reached
        withContext(Dispatchers.Main) {
            appendToLog("⚠️ Max nudge attempts for: $targetDescription")
        }
        return false
    }
    
    private fun callNudgeAI(base64Image: String, targetDescription: String, attempt: Int): String {
        val prompt = """Look at this screen photo. Find the mouse cursor and the target element.
            |
            |TARGET: $targetDescription
            |ESTIMATED CURSOR POSITION: approximately ($mouseX, $mouseY) on a ~1440x900 screen
            |ATTEMPT: $attempt (I will nudge the cursor in small steps until it's on the target)
            |
            |Instructions:
            |1. Find where the mouse cursor currently is on screen
            |2. Find where the target element is
            |3. Estimate the RELATIVE pixel distance to move the cursor to the target
            |
            |RESPOND WITH EXACTLY ONE LINE. No explanation. No thinking. No description.
            |Valid responses (pick one):
            |CLICK
            |NUDGE:dx,dy
            |ABORT
            |
            |CLICK = cursor is on or very close to target, click now
            |NUDGE:dx,dy = move cursor by relative pixels (e.g. NUDGE:-50,30 = left 50 down 30). Keep 10-200px.
            |ABORT = target not visible on screen
            |
            |YOUR RESPONSE MUST START WITH CLICK, NUDGE:, OR ABORT. Nothing else.""".trimMargin()
        
        val json = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 50)
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
            upper.startsWith("CLICKTARGET:") || upper.startsWith("CLICK:") -> {
                // Should be handled in the main loop for blocking behavior
                // This is a fallback if called directly from processAction
                Log.w(TAG, "CLICKTARGET in executeAction fallback — should be handled in main loop")
                appendToLog("⚠️ CLICKTARGET fallback")
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
            upper.startsWith("NOTIFY:") -> {
                val message = action.substring(7).trim()
                Log.d(TAG, "NOTIFY: $message")
                appendToLog("🔔 $message")
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
