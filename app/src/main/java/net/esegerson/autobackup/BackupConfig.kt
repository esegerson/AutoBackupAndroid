package net.esegerson.autobackup

object BackupConfig {
    val TARGET_WIFI_SSIDS: List<String> = BuildConfig.TARGET_WIFI_SSIDS
        .split(",")
        .map { it.trim().removeSurrounding("\"") }
        .filter { it.isNotEmpty() }

    const val TEST = false //When true will ignore time window and run immediately
    const val SYNC_HOUR = 20 //8PM
    val SMB_HOST: String = BuildConfig.SMB_HOST
    const val SMB_SHARE_NAME = "Backups"
    const val SMB_SUBFOLDER = "Android"
    val SMB_USERNAME: String = BuildConfig.SMB_USERNAME
    val SMB_PASSWORD: String = BuildConfig.SMB_PASSWORD
    const val PERIODIC_WORK_NAME = "DailySmbBackupPeriodicWork"
    const val MANUAL_WORK_NAME = "ManualSmbBackupWork"
    const val WORK_NAME = PERIODIC_WORK_NAME
}