package com.openclaw.visioncontroller

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    private lateinit var tilApiKey: TextInputLayout
    private lateinit var etApiKey: TextInputEditText
    private lateinit var tvCurrentKey: TextView
    private lateinit var btnSave: Button
    private lateinit var btnBack: ImageButton
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tilApiKey = findViewById(R.id.tilApiKey)
        etApiKey = findViewById(R.id.etApiKey)
        tvCurrentKey = findViewById(R.id.tvCurrentKey)
        btnSave = findViewById(R.id.btnSave)
        btnBack = findViewById(R.id.btnBack)
        tvError = findViewById(R.id.tvError)

        setupUI()
        displayCurrentKey()
    }

    private fun setupUI() {
        btnBack.setOnClickListener {
            finish()
        }

        btnSave.setOnClickListener {
            saveApiKey()
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

    private fun saveApiKey() {
        val apiKey = etApiKey.text.toString().trim()

        // Clear previous error
        tvError.visibility = View.GONE

        if (apiKey.isEmpty()) {
            showError("Please enter an API key")
            return
        }

        if (!apiKey.startsWith("sk-ant-")) {
            showError("Invalid API key format. Should start with sk-ant-")
            return
        }

        // Save the key
        SetupActivity.saveApiKey(this, apiKey)
        
        // Update display
        displayCurrentKey()
        etApiKey.text?.clear()
        
        Toast.makeText(this, "API key updated", Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }
}
