package com.smash.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main launcher activity.
 * Handles initial setup, permission requests, and starting the foreground service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            SmashLogger.info("All permissions granted")
            checkDefaultSmsApp()
        } else {
            SmashLogger.warning("Some permissions denied")
            updateStatus("Some permissions were denied. SMS forwarding may not work correctly.")
        }
    }

    private val defaultSmsAppLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            SmashLogger.info("Set as default SMS app")
            startServiceAndFinish()
        } else {
            SmashLogger.warning("Not set as default SMS app")
            updateStatus("Warning: smash is not the default SMS app. SMS receiving may not work.")
            // Still start the service, but it may not receive SMS
            startServiceAndFinish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        updateStatus("Checking permissions...")
        checkPermissions()
    }

    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            SmashLogger.info("All permissions already granted")
            checkDefaultSmsApp()
        } else {
            SmashLogger.info("Requesting permissions: ${missingPermissions.joinToString()}")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkDefaultSmsApp() {
        updateStatus("Checking default SMS app...")
        
        if (PhoneUtils.isDefaultSmsApp(this)) {
            SmashLogger.info("Already default SMS app")
            startServiceAndFinish()
        } else {
            SmashLogger.info("Requesting to be default SMS app")
            promptForDefaultSmsApp()
        }
    }

    private fun promptForDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ uses RoleManager
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                defaultSmsAppLauncher.launch(intent)
            } else {
                // Role not available, try legacy method
                promptForDefaultSmsAppLegacy()
            }
        } else {
            promptForDefaultSmsAppLegacy()
        }
    }

    @Suppress("DEPRECATION")
    private fun promptForDefaultSmsAppLegacy() {
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
        intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        defaultSmsAppLauncher.launch(intent)
    }

    private fun startServiceAndFinish() {
        updateStatus("Starting service...")
        SmashService.start(this)
        
        Toast.makeText(this, "smash service started", Toast.LENGTH_SHORT).show()
        
        // Open status activity
        startActivity(Intent(this, StatusActivity::class.java))
        finish()
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }
}
