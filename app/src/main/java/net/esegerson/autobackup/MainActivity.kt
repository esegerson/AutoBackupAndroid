package net.esegerson.autobackup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager

class MainActivity : AppCompatActivity() {

    private var isSchedulerInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val logTextView = findViewById<android.widget.TextView>(R.id.logTextView)
        val scrollView = logTextView.parent as android.widget.ScrollView

        AppLogger.logData.observe(this) { logs ->
            logTextView.text = logs
            scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }

        val actionButton = findViewById<android.widget.Button>(R.id.actionButton)
        actionButton.setOnClickListener {
            SmbBackupWorker.runManualSyncNow(applicationContext)
        }

        ensurePermissionsOrInit()
    }

    override fun onResume() {
        super.onResume()
        // Handles returning from system Settings screen (Android 11+)
        ensurePermissionsOrInit()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Handles user granting runtime permission dialogs
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE || requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            ensurePermissionsOrInit()
        }
    }

    private fun ensurePermissionsOrInit() {
        if (!hasStoragePermission()) {
            if (!isFinishing) requestStoragePermission()
        } else if (!hasLocationPermission()) {
            if (!isFinishing) requestLocationPermission()
        } else {
            initScheduler()
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val nearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return fineLocation && nearbyWifi
    }

    private fun requestLocationPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions,
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    private fun initScheduler() {
        if (isSchedulerInitialized) return
        isSchedulerInitialized = true

        SmbBackupWorker.scheduleDailyBackup(applicationContext)

        val msg = if (BackupConfig.TEST)
            "SMB Backup Test Triggered"
        else
            "SMB Backup Scheduled for ${BackupConfig.SYNC_HOUR}:00"

        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(BackupConfig.PERIODIC_WORK_NAME)
            .observe(this) { workInfoList ->
                val workInfo = workInfoList?.firstOrNull()
                if (workInfo != null) {
                    AppLogger.d("Daily Periodic Work State: ${workInfo.state}")
                }
            }

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(BackupConfig.MANUAL_WORK_NAME)
            .observe(this) { workInfoList ->
                val workInfo = workInfoList?.firstOrNull()
                if (workInfo != null) {
                    AppLogger.d("Manual Work State: ${workInfo.state}")
                }
            }
    }

    companion object {
        private const val STORAGE_PERMISSION_REQUEST_CODE = 100
        private const val LOCATION_PERMISSION_REQUEST_CODE = 101
    }
}