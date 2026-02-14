package com.example.voiceinput

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.*
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

        setupModelSpinner()
        requestMicrophonePermission()
    }

    private val whisperModels = arrayOf("gpt-4o-transcribe", "whisper-1")

    private fun setupModelSpinner() {
        val spinner = findViewById<Spinner>(R.id.modelSpinner)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, whisperModels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val currentModel = prefsManager.getWhisperModel()
        val index = whisperModels.indexOf(currentModel)
        if (index >= 0) spinner.setSelection(index)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefsManager.saveWhisperModel(whisperModels[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
