package com.example.voiceinput

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsManager = PreferencesManager(
            getSharedPreferences("voice_input_prefs", MODE_PRIVATE)
        )

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val imeButton = findViewById<Button>(R.id.imeSettingsButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        prefsManager.getApiKey()?.let {
            apiKeyInput.setText(it)
        }

        saveButton.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, R.string.api_key_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefsManager.saveApiKey(key)
            Toast.makeText(this, R.string.api_key_saved, Toast.LENGTH_SHORT).show()
        }

        imeButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        requestMicrophonePermission()
    }

    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val enabled = isImeEnabled()
        statusText.setText(
            if (enabled) R.string.ime_enabled else R.string.ime_disabled
        )
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any {
            it.packageName == packageName
        }
    }
}
