package com.openclaw.visioncontroller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class SetupActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "KantokuPrefs"
        const val KEY_API_KEY = "anthropic_api_key"
        const val KEY_SETUP_COMPLETE = "setup_complete"
        const val KEY_IDLE_MODE = "idle_mode"
        
        // Idle mode options
        const val IDLE_WIKIPEDIA = 0
        const val IDLE_GOOGLE_MAPS = 1
        const val IDLE_NPR = 2
        const val IDLE_RANDOM = 3
        
        // URLs for getting credentials
        const val URL_GET_TOKEN = "https://docs.anthropic.com/en/docs/claude-code/getting-started"
        const val URL_GET_API_KEY = "https://console.anthropic.com/settings/keys"
        
        fun isSetupComplete(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        }
        
        fun getApiKey(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_API_KEY, "") ?: ""
        }
        
        fun saveApiKey(context: Context, apiKey: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_API_KEY, apiKey)
                .putBoolean(KEY_SETUP_COMPLETE, true)
                .apply()
        }
        
        fun getIdleMode(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_IDLE_MODE, IDLE_RANDOM)
        }
        
        fun saveIdleMode(context: Context, mode: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_IDLE_MODE, mode)
                .apply()
        }
    }

    private lateinit var etApiKey: TextInputEditText
    private lateinit var btnContinue: Button
    private lateinit var tvError: TextView
    private lateinit var btnGetToken: TextView
    private lateinit var btnGetApiKey: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        
        etApiKey = findViewById(R.id.etApiKey)
        btnContinue = findViewById(R.id.btnContinue)
        tvError = findViewById(R.id.tvError)
        btnGetToken = findViewById(R.id.btnGetToken)
        btnGetApiKey = findViewById(R.id.btnGetApiKey)
        
        btnContinue.setOnClickListener {
            validateAndContinue()
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
    
    private fun validateAndContinue() {
        val credential = etApiKey.text.toString().trim()
        
        if (credential.isEmpty()) {
            showError("Please enter your API key or setup token")
            return
        }
        
        // Basic validation - must be reasonably long
        if (credential.length < 20) {
            showError("Credential looks too short. Please paste the full key or token.")
            return
        }
        
        // Save and continue
        saveApiKey(this, credential)
        startMainActivity()
    }
    
    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }
    
    private fun startMainActivity() {
        startActivity(Intent(this, PlanActivity::class.java))
        finish()
    }
}
