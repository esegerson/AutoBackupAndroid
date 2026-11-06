package net.esegerson.autobackup

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object AppLogger {
    private const val TAG = "SmbBackup"
    private val _logData = MutableLiveData<String>()
    val logData: LiveData<String> = _logData

    fun v(message: String) {
        Log.v(TAG, message)
        appendLog("[INFO] $message")
    }

    fun d(message: String) {
        Log.d(TAG, message)
        appendLog("[INFO] $message")
    }

    fun i(message: String) {
        Log.i(TAG, message)
        appendLog("[INFO] $message")
    }

    fun w(message: String) {
        Log.w(TAG, message)
        appendLog("[INFO] $message")
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        appendLog("[ERROR] $message ${throwable?.message ?: ""}")
    }

    private fun appendLog(text: String) {
        val current = _logData.value ?: ""
        _logData.postValue("$current\n$text")
    }
}