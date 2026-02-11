package com.openclaw.visioncontroller

import android.content.Intent
import android.net.Uri
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
        
        const val URL_GET_TOKEN = "https://docs.anthropic.com/en/docs/claude-code/getting-started"
        const val URL_GET_API_KEY = "https://console.anthropic.com/settings/keys"
    }

    private lateinit var tilApiKey: TextInputLayout
    private lateinit var etApiKey: TextInputEditText
    private lateinit var tvCurrentKey: TextView
    private lateinit var spIdleMode: Spinner
    private lateinit var btnSave: Button
    private lateinit var btnBack: ImageButton
    private lateinit var tvError: TextView
    private lateinit var btnGetToken: TextView
    private lateinit var btnGetApiKey: TextView

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
        btnGetToken = findViewById(R.id.btnGetToken)
        btnGetApiKey = findViewById(R.id.btnGetApiKey)

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

        btnGetToken.setOnClickListener {
            openUrl(URL_GET_TOKEN)
        }

        btnGetApiKey.setOnClickListener {
            openUrl(URL_GET_API_KEY)
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayCurrentKey() {
        val currentKey = SetupActivity.getApiKey(this)
        if (currentKey.isNotEmpty()) {
            // Detect type and mask appropriately
            val isApiKey = currentKey.startsWith("sk-ant-")
            val typeLabel = if (isApiKey) "API Key" else "Token"
            val masked = if (currentKey.length > 14) {
                "${currentKey.take(10)}...${currentKey.takeLast(4)}"
            } else {
                "****"
            }
            tvCurrentKey.text = "Current ($typeLabel): $masked"
            tvCurrentKey.visibility = View.VISIBLE
        } else {
            tvCurrentKey.text = "No credentials configured"
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

        // Save credentials only if provided
        val credential = etApiKey.text.toString().trim()
        if (credential.isNotEmpty()) {
            // Basic validation - must be reasonably long
            if (credential.length < 20) {
                showError("Credential looks too short. Please paste the full key or token.")
                return
            }
            SetupActivity.saveApiKey(this, credential)
            displayCurrentKey()
            etApiKey.text?.clear()
            
            val credType = if (credential.startsWith("sk-ant-")) "API key" else "setup token"
            Toast.makeText(this, "Saved $credType", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }
}
