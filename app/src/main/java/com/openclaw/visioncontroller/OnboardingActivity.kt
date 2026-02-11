package com.openclaw.visioncontroller

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        
        fun isOnboardingComplete(context: Context): Boolean {
            val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        }
        
        fun setOnboardingComplete(context: Context) {
            val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
        }
    }

    private lateinit var page1: LinearLayout
    private lateinit var page2: LinearLayout
    private lateinit var btnNext: Button
    private lateinit var tvPageIndicator: TextView

    private var currentPage = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Skip if already completed onboarding and setup
        if (isOnboardingComplete(this) && SetupActivity.isSetupComplete(this)) {
            startActivity(Intent(this, PlanActivity::class.java))
            finish()
            return
        }
        
        // Skip to API key setup if onboarding done but no API key yet
        if (isOnboardingComplete(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        
        setContentView(R.layout.activity_onboarding)
        
        page1 = findViewById(R.id.page1)
        page2 = findViewById(R.id.page2)
        btnNext = findViewById(R.id.btnNext)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        
        showPage(1)
        
        btnNext.setOnClickListener {
            when (currentPage) {
                1 -> showPage(2)
                2 -> {
                    setOnboardingComplete(this)
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                }
            }
        }
    }
    
    private fun showPage(page: Int) {
        currentPage = page
        
        page1.visibility = if (page == 1) View.VISIBLE else View.GONE
        page2.visibility = if (page == 2) View.VISIBLE else View.GONE
        
        tvPageIndicator.text = "$page / 2"
        
        btnNext.text = if (page == 2) "Set Up API Key" else "Next"
    }
}
