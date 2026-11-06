package net.esegerson.autobackup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Environment

// AndroidX WorkManager
import androidx.work.*

// SMBJ Library
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare

// Standard Utilities
import java.io.File
import java.util.Calendar
import java.util.EnumSet
import java.util.concurrent.TimeUnit

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

class SmbBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (BackupConfig.TEST) {
            android.os.Debug.waitForDebugger()
            AppLogger.d("Running in TEST mode: Bypassing time & SSID restrictions.")
        }

        if (!isConnectedToAllowedWifi(BackupConfig.TARGET_WIFI_SSIDS)) {
            AppLogger.w("Not connected to allowed Wi-Fi SSID. Retrying later.")
            // Retry later if connected to an unapproved network
            return Result.retry()
        }

        if (!isSmbAvailable(BackupConfig.SMB_HOST)) {
            AppLogger.w("SMB host ${BackupConfig.SMB_HOST} unavailable")
            return Result.retry()
        }

        val success = performSmbSync(
            smbHost = BackupConfig.SMB_HOST,
            shareName = BackupConfig.SMB_SHARE_NAME,
            username = BackupConfig.SMB_USERNAME,
            password = BackupConfig.SMB_PASSWORD
        )

        return if (success) Result.success() else Result.failure()
    }

    /**
     * Checks whether the device is currently connected to a Wi‑Fi network
     * whose SSID appears in the provided list of allowed SSIDs.
     *
     * Fallback mechanism:
     * If Android redacts the Wi-Fi SSID to `<unknown ssid>` due to OS privacy restrictions,
     * but the active transport is Wi-Fi AND the local SMB host is reachable, the backup is allowed to proceed.
     *
     * @param allowedSsids A list of SSIDs that are considered valid/allowed.
     * @return `true` if connected to Wi‑Fi and allowed/reachable; otherwise `false`.
     */
    private fun isConnectedToAllowedWifi(allowedSsids: List<String>): Boolean {
        if (BackupConfig.TEST) return true

        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val activeNetwork = connectivityManager.activeNetwork ?: run {
            AppLogger.w("SSID Check: No active network connection.")
            return false
        }
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: run {
            AppLogger.w("SSID Check: No network capabilities found.")
            return false
        }

        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            AppLogger.w("SSID Check: Active network is not Wi-Fi.")
            return false
        }

        // 1. Attempt to retrieve SSID via WifiManager & NetworkCapabilities
        @Suppress("DEPRECATION")
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifiInfoFromManager = wifiManager.connectionInfo
        val wifiInfoFromCap = capabilities.transportInfo as? WifiInfo

        var rawSsid = wifiInfoFromManager?.ssid
        if (rawSsid == null || rawSsid == WifiManager.UNKNOWN_SSID || rawSsid.isEmpty()) {
            rawSsid = wifiInfoFromCap?.ssid
        }

        val currentSsid = rawSsid?.replace("\"", "") ?: WifiManager.UNKNOWN_SSID
        AppLogger.d("Current SSID: $currentSsid")

        // 2. Direct match with allowed SSIDs
        if (allowedSsids.contains(currentSsid)) {
            return true
        }

        // 3. Robust Fallback: If Android OS redacted the SSID to <unknown ssid>, check local SMB server reachability over Wi-Fi
        if (currentSsid == WifiManager.UNKNOWN_SSID) {
            AppLogger.w("SSID is redacted by Android OS (<unknown ssid>). Verifying home SMB server reachability on Wi-Fi...")
            if (isSmbAvailable(BackupConfig.SMB_HOST)) {
                AppLogger.d("Device is connected to Wi-Fi and SMB host (${BackupConfig.SMB_HOST}) is reachable! Proceeding with backup.")
                return true
            } else {
                AppLogger.e("SSID is <unknown ssid> and SMB host (${BackupConfig.SMB_HOST}) is NOT reachable. Backup skipped.")
                return false
            }
        }

        AppLogger.w("Not connected to allowed Wi-Fi SSID (Connected: '$currentSsid', Allowed: $allowedSsids).")
        return false
    }

    private fun isSmbAvailable(smbHost: String): Boolean {
        val cleanHost = smbHost.trim().removePrefix("\\\\").removePrefix("//")

        return try {
            // Step 1: Check DNS/mDNS resolution
            AppLogger.d("Testing SMB resolution for '$cleanHost'...")
            val address = InetAddress.getByName(cleanHost)
            AppLogger.d("Resolved to IP: ${address.hostAddress}")

            // Step 2: Test TCP Port 445 connectivity with a 3-second timeout
            AppLogger.d("Testing TCP port 445 on ${address.hostAddress}...")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, 445), 3000)
            }
            AppLogger.d("SMB server port 445 is reachable!")
            true
        } catch (e: UnknownHostException) {
            AppLogger.e("SMB Check Failed: Could not resolve hostname '$cleanHost'")
            false
        } catch (e: Exception) {
            AppLogger.e("SMB Check Failed: Port 445 unreachable (${e.message})")
            false
        }
    }

    /**
     * Performs a one‑way SMB file sync by uploading all regular files from a local
     * directory to a remote SMB share. The method:
     *
     * 1. Connects to the SMB host and authenticates using the provided credentials.
     * 2. Opens the specified share as a DiskShare.
     * 3. Locates the target local directory under the public Documents folder.
     * 4. Iterates through each file and writes its contents to the SMB share,
     *    overwriting existing files if present.
     *
     * Any exception during connection, authentication, or file transfer results in
     * a `false` return value; otherwise the sync completes successfully and returns `true`.
     *
     * @param smbHost The hostname or IP address of the SMB server.
     * @param shareName The name of the SMB share to upload files into.
     * @param username SMB username for authentication.
     * @param password SMB password for authentication.
     * @param localDirName Name of the local directory (inside Documents) whose files are uploaded.
     * @return `true` if all operations succeed, or `false` if any error occurs.
     */
    private fun performSmbSync(
        smbHost: String,
        shareName: String,
        username: String,
        password: String
    ): Boolean {
        val client = SMBClient()
        return try {
            client.connect(smbHost).use { connection ->
                val auth = AuthenticationContext(username, password.toCharArray(), "")
                val session = connection.authenticate(auth)
                val share = session.connectShare(shareName) as DiskShare

                if (!share.folderExists(BackupConfig.SMB_SUBFOLDER))
                    share.mkdir(BackupConfig.SMB_SUBFOLDER)

                val sourceDirs = listOf(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                )

                sourceDirs.forEach { baseDir ->
                    if (!baseDir.exists() || !baseDir.isDirectory) return@forEach

                    AppLogger.i("Processing folder $baseDir...")
                    // Recursively list all files (includes Camera and subfolders)
                    baseDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { localFile ->
                            // Calculate relative SMB path (e.g., "Android/DCIM/Camera/photo.jpg")
                            val relativePath = localFile.relativeTo(baseDir.parentFile ?: baseDir).path.replace('\\', '/')
                            val remotePath = "${BackupConfig.SMB_SUBFOLDER}/$relativePath"

                            //Skip if already in SMB
                            if (share.fileExists(remotePath))
                                return@forEach

                            ensureSmbDirectories(share, remotePath)
                            uploadFileToSmb(share, localFile, remotePath)
                        }
                }
            }
            AppLogger.i("Complete.")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            AppLogger.e("Finished with error: ${e.message}")
            false
        }
    }

    /**
     * Ensures all intermediate folders exist on the SMB share before writing the file.
     */
    private fun ensureSmbDirectories(share: DiskShare, remotePath: String) {
        val folders = remotePath.substringBeforeLast('/', missingDelimiterValue = "").split('/')
        var currentPath = ""

        folders.forEach { folder ->
            if (folder.isNotEmpty()) {
                currentPath = if (currentPath.isEmpty()) folder else "$currentPath/$folder"
                if (!share.folderExists(currentPath)) {
                    share.mkdir(currentPath)
                }
            }
        }
    }

    /**
     * Streams bytes from local file to SMB file.
     */
    private fun uploadFileToSmb(share: DiskShare, localFile: File, remotePath: String) {
        localFile.inputStream().use { inputStream ->
            val smbFile = share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )

            smbFile.use { file ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var fileOffset = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    file.write(buffer, fileOffset, 0, bytesRead)
                    fileOffset += bytesRead
                }
            }
        }
    }

    companion object {
        fun runManualSyncNow(context: Context) {
            AppLogger.i("Enqueuing manual backup sync...")
            val workManager = WorkManager.getInstance(context)
            val manualWorkRequest = OneTimeWorkRequestBuilder<SmbBackupWorker>()
                .build()

            // REPLACE policy ensures clicking manual sync always triggers a new sync run under MANUAL_WORK_NAME
            workManager.enqueueUniqueWork(
                BackupConfig.MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                manualWorkRequest
            )
        }

        fun scheduleDailyBackup(context: Context) {
            val workManager = WorkManager.getInstance(context)

            if (BackupConfig.TEST) {
                AppLogger.d("TEST mode is enabled. Running manual sync now.")
                runManualSyncNow(context)
                return
            }

            // Calculate initial delay until sync time
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, BackupConfig.SYNC_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            val initialDelay = target.timeInMillis - now.timeInMillis
            val delayHours = initialDelay / (1000 * 60 * 60)
            val delayMinutes = (initialDelay / (1000 * 60)) % 60
            AppLogger.i("Scheduling daily backup for ${BackupConfig.SYNC_HOUR}:00 (in ${delayHours}h ${delayMinutes}m)")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // Unmetered Wi-Fi only
                .setRequiresCharging(false)
                .build()

            // Schedule daily recurring task
            val dailyWorkRequest = PeriodicWorkRequestBuilder<SmbBackupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                BackupConfig.PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Retains existing daily timer across app re-opens
                dailyWorkRequest
            )
        }

        fun scheduleNextDailyRun(context: Context, runImmediately: Boolean = false) {
            if (runImmediately) {
                runManualSyncNow(context)
            } else {
                scheduleDailyBackup(context)
            }
        }
    }
}