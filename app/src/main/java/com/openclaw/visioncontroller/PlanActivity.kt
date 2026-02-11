package com.openclaw.visioncontroller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class PlanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlanActivity"
        const val EXTRA_TASK = "task"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        
        private const val REQUEST_PERMISSIONS = 1002
        
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        
        // Duration options: display name -> milliseconds
        private val DURATION_OPTIONS = listOf(
            "5 minutes" to 5 * 60 * 1000L,
            "15 minutes" to 15 * 60 * 1000L,
            "30 minutes" to 30 * 60 * 1000L,
            "1 hour" to 60 * 60 * 1000L,
            "2 hours" to 2 * 60 * 60 * 1000L,
            "4 hours" to 4 * 60 * 60 * 1000L,
            "8 hours" to 8 * 60 * 60 * 1000L
        )
    }

    private lateinit var etTask: TextInputEditText
    private lateinit var spDuration: Spinner
    private lateinit var tvBluetoothStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnStart: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var selectedDeviceAddress: String? = null
    private var selectedDurationMs: Long = DURATION_OPTIONS[0].second

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan)

        etTask = findViewById(R.id.etTask)
        spDuration = findViewById(R.id.spDuration)
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnStart = findViewById(R.id.btnStart)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setupUI()
        setupDurationSpinner()
        
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
        }
        
        updateBluetoothStatus("📡 Select a device to control")
    }

    private fun setupUI() {
        btnConnect.setOnClickListener {
            showDevicePicker()
        }

        btnStart.setOnClickListener {
            startExecution()
        }
    }
    
    private fun setupDurationSpinner() {
        val durationNames = DURATION_OPTIONS.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, durationNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spDuration.adapter = adapter
        
        spDuration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDurationMs = DURATION_OPTIONS[position].second
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedDurationMs = DURATION_OPTIONS[0].second
            }
        }
    }

    private fun showDevicePicker() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices = bluetoothAdapter?.bondedDevices
        if (pairedDevices.isNullOrEmpty()) {
            Toast.makeText(this, "No paired devices found. Pair via Bluetooth settings first.", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = pairedDevices.map { it.name ?: it.address }.toTypedArray()
        val devices = pairedDevices.toList()

        AlertDialog.Builder(this)
            .setTitle("Select computer to control")
            .setItems(deviceNames) { _, which ->
                selectDevice(devices[which])
            }
            .show()
    }

    private fun selectDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        selectedDeviceAddress = device.address
        updateBluetoothStatus("✅ Selected: ${device.name}")
        btnConnect.text = "Selected"
    }

    private fun updateBluetoothStatus(status: String) {
        tvBluetoothStatus.text = status
    }

    private fun startExecution() {
        val task = etTask.text.toString().trim()
        
        if (task.isEmpty()) {
            Toast.makeText(this, "Please describe what you want me to do", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (selectedDeviceAddress == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_TASK, task)
            putExtra(EXTRA_DURATION_MS, selectedDurationMs)
            putExtra(EXTRA_DEVICE_ADDRESS, selectedDeviceAddress)
        }
        startActivity(intent)
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
        // Permissions handled, user can now select device
    }
}
