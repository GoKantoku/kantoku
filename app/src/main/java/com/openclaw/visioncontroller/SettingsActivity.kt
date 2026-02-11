package com.openclaw.visioncontroller

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    companion object {
        val IDLE_MODE_OPTIONS = listOf(
            "Browse Wikipedia" to SetupActivity.IDLE_WIKIPEDIA,
            "Scroll around on Google Maps" to SetupActivity.IDLE_GOOGLE_MAPS,
            "Read NPR.org \uD83E\uDD13" to SetupActivity.IDLE_NPR,
            "Randomly select" to SetupActivity.IDLE_RANDOM
        )
    }

    private lateinit var tilApiKey: TextInputLayout
    private lateinit var etApiKey: TextInputEditText
    private lateinit var tvCurrentKey: TextView
    private lateinit var spIdleMode: Spinner
    private lateinit var btnSave: Button
    private lateinit var btnBack: ImageButton
    private lateinit var tvError: TextView

    private var selectedIdleMode = SetupActivity.IDLE_RANDOM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tilApiKey = findViewById(R.id.tilApiKey)
        etApiKey = findViewById(R.id.etApiKey)
        tvCurrentKey = findViewById(R.id.tvCurrentKey)
        spIdleMode = findViewById(R.id.spIdleMode)
        btnSave = findViewById(R.id.btnSave)
        btnBack = findViewById(R.id.btnBack)
        tvError = findViewById(R.id.tvError)

        setupUI()
        displayCurrentKey()
        setupIdleModeSpinner()
    }

    private fun setupUI() {
        btnBack.setOnClickListener {
            finish()
        }

        btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun displayCurrentKey() {
        val currentKey = SetupActivity.getApiKey(this)
        if (currentKey.isNotEmpty()) {
            // Mask the key, showing only first 10 and last 4 characters
            val masked = if (currentKey.length > 14) {
                "${currentKey.take(10)}...${currentKey.takeLast(4)}"
            } else {
                "****"
            }
            tvCurrentKey.text = "Current: $masked"
            tvCurrentKey.visibility = View.VISIBLE
        } else {
            tvCurrentKey.text = "No API key configured"
            tvCurrentKey.visibility = View.VISIBLE
        }
    }

    private fun setupIdleModeSpinner() {
        val modeNames = IDLE_MODE_OPTIONS.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modeNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spIdleMode.adapter = adapter

        // Set current selection
        val currentMode = SetupActivity.getIdleMode(this)
        val currentIndex = IDLE_MODE_OPTIONS.indexOfFirst { it.second == currentMode }
        if (currentIndex >= 0) {
            spIdleMode.setSelection(currentIndex)
        }

        spIdleMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedIdleMode = IDLE_MODE_OPTIONS[position].second
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedIdleMode = SetupActivity.IDLE_RANDOM
            }
        }
    }

    private fun saveSettings() {
        // Clear previous error
        tvError.visibility = View.GONE

        // Save idle mode (always)
        SetupActivity.saveIdleMode(this, selectedIdleMode)

        // Save API key only if provided
        val apiKey = etApiKey.text.toString().trim()
        if (apiKey.isNotEmpty()) {
            if (!apiKey.startsWith("sk-ant-")) {
                showError("Invalid API key format. Should start with sk-ant-")
                return
            }
            SetupActivity.saveApiKey(this, apiKey)
            displayCurrentKey()
            etApiKey.text?.clear()
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }
}
