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
        setupSshSettings()
        requestMicrophonePermission()
    }

    private fun setupSshSettings() {
        val sshSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.sshContextSwitch)
        val hostInput = findViewById<EditText>(R.id.sshHostInput)
        val portInput = findViewById<EditText>(R.id.sshPortInput)
        val usernameInput = findViewById<EditText>(R.id.sshUsernameInput)
        val tmuxSessionInput = findViewById<EditText>(R.id.sshTmuxSessionInput)
        val privateKeyInput = findViewById<EditText>(R.id.sshPrivateKeyInput)
        val saveButton = findViewById<Button>(R.id.sshSaveButton)
        val testButton = findViewById<Button>(R.id.sshTestButton)

        // Load existing values
        sshSwitch.isChecked = prefsManager.isSshContextEnabled()
        prefsManager.getSshHost()?.let { hostInput.setText(it) }
        portInput.setText(prefsManager.getSshPort().toString())
        prefsManager.getSshUsername()?.let { usernameInput.setText(it) }
        tmuxSessionInput.setText(prefsManager.getSshTmuxSession())
        prefsManager.getSshPrivateKey()?.let { privateKeyInput.setText(it) }

        saveButton.setOnClickListener {
            prefsManager.saveSshContextEnabled(sshSwitch.isChecked)
            prefsManager.saveSshHost(hostInput.text.toString().trim())
            prefsManager.saveSshPort(portInput.text.toString().toIntOrNull() ?: 22)
            prefsManager.saveSshUsername(usernameInput.text.toString().trim())
            prefsManager.saveSshTmuxSession(tmuxSessionInput.text.toString().trim())
            prefsManager.saveSshPrivateKey(privateKeyInput.text.toString())
            Toast.makeText(this, R.string.ssh_saved, Toast.LENGTH_SHORT).show()
        }

        testButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().toIntOrNull() ?: 22
            val username = usernameInput.text.toString().trim()
            val key = privateKeyInput.text.toString()
            val tmuxSession = tmuxSessionInput.text.toString().trim()

            Thread {
                try {
                    val provider = SshContextProvider(host, port, username, key, tmuxSession)
                    val result = provider.fetchContext()
                    provider.disconnect()
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.ssh_test_success) + " (${result?.length ?: 0} chars)", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "${getString(R.string.ssh_test_fail)}: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
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
