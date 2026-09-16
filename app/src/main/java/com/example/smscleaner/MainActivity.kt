package com.example.smscleaner

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var batchSizeInput: EditText
    private lateinit var sessionCapInput: EditText
    private lateinit var dryRunCheckbox: CheckBox
    private lateinit var autoContinueCheckbox: CheckBox

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        CleanerConfig.init(applicationContext)

        statusText = findViewById(R.id.statusText)
        batchSizeInput = findViewById(R.id.batchSizeInput)
        sessionCapInput = findViewById(R.id.sessionCapInput)
        dryRunCheckbox = findViewById(R.id.dryRunCheckbox)
        autoContinueCheckbox = findViewById(R.id.autoContinueCheckbox)

        batchSizeInput.setText(CleanerConfig.batchSize.toString())
        sessionCapInput.setText(CleanerConfig.sessionCap.toString())
        dryRunCheckbox.isChecked = CleanerConfig.dryRun
        autoContinueCheckbox.isChecked = CleanerConfig.autoContinue

        findViewById<Button>(R.id.openAccessibilitySettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.dumpTreeButton).setOnClickListener {
            CleanerConfig.dumpRequested = true
            Toast.makeText(this, "Dump requested -- switch to Google Messages now, check logcat -s SMSCleanerTree", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            saveSettingsFromInputs()
            CleanerConfig.resetSessionCounters()
            CleanerConfig.isRunning = true
            Toast.makeText(this, "Started. Switch to Google Messages now.", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            CleanerConfig.isRunning = false
            Toast.makeText(this, "Stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
        saveSettingsFromInputs()
    }

    private fun saveSettingsFromInputs() {
        CleanerConfig.batchSize = batchSizeInput.text.toString().toIntOrNull() ?: 1000
        CleanerConfig.sessionCap = sessionCapInput.text.toString().toIntOrNull() ?: 1000
        CleanerConfig.dryRun = dryRunCheckbox.isChecked
        CleanerConfig.autoContinue = autoContinueCheckbox.isChecked
    }

    private fun refreshStatus() {
        val running = if (CleanerConfig.isRunning) "RUNNING" else "stopped"
        statusText.text = "Status: $running\n" +
            "Deleted this session: ${CleanerConfig.totalDeletedThisSession}\n" +
            "Last: ${CleanerConfig.lastStatus}"
    }
}
