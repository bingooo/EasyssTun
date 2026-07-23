package com.musan.easysstun

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object TailscaleManager {
    private const val TAG = "TailscaleManager"
    private const val PREFS_NAME = "tailscale_status"
    private const val KEY_STATUS_JSON = "status_json"

    @Volatile
    private var process: Process? = null

    @Volatile
    private var processJob: Job? = null

    @Volatile
    private var monitorJob: Job? = null

    @Volatile
    private var appContext: Context? = null

    private val scope = CoroutineScope(Dispatchers.Default)

    /** Write status JSON to SharedPreferences for cross-process IPC. */
    @Suppress("DEPRECATION")
    private fun writeStatus(json: String) {
        appContext?.getSharedPreferences(PREFS_NAME, MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
            ?.edit()?.putString(KEY_STATUS_JSON, json)?.apply()
    }

    /** Read status JSON from SharedPreferences (works across processes). */
    @Suppress("DEPRECATION")
    fun getStatusJSON(context: Context? = null): String {
        val ctx = context ?: appContext
        return ctx?.getSharedPreferences(PREFS_NAME, MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
            ?.getString(KEY_STATUS_JSON, "{\"state\":\"Stopped\"}") ?: "{\"state\":\"Stopped\"}"
    }

    @Synchronized
    fun start(
        context: Context,
        routerPort: Int,
        easyssPort: Int,
        authKey: String,
        controlUrl: String,
        hostname: String
    ): Boolean {
        appContext = context.applicationContext
        if (process != null) {
            Log.d(TAG, "tsrouter process is already running.")
            return true
        }

        val libraryPath = context.applicationInfo.nativeLibraryDir + "/libtsrouter.so"
        val binFile = File(libraryPath)
        if (!binFile.exists()) {
            Log.e(TAG, "Binary libtsrouter.so not found at $libraryPath")
            writeStatus("{\"state\":\"Error\", \"err_message\":\"libtsrouter.so missing\"}")
            return false
        }

        val stateDir = File(context.filesDir, "tailscale").apply { mkdirs() }.absolutePath
        val cmdList = listOf(
            libraryPath,
            "-router-port", routerPort.toString(),
            "-easyss-port", easyssPort.toString(),
            "-auth-key", authKey,
            "-control-url", controlUrl,
            "-state-dir", stateDir,
            "-hostname", hostname
        )

        Log.i(TAG, "Starting tsrouter sub-process: $cmdList")

        try {
            val proc = ProcessBuilder(cmdList)
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .start()

            process = proc
            writeStatus("{\"state\":\"Starting\"}")

            processJob = scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    while (processJob?.isActive == true) {
                        val line = reader.readLine() ?: break
                        Log.i("tsrouter", line)
                        if (line.contains("[tsrouter-status]")) {
                            val jsonPart = line.substringAfter("[tsrouter-status]").trim()
                            if (jsonPart.isNotEmpty()) {
                                writeStatus(jsonPart)
                                try {
                                    val obj = org.json.JSONObject(jsonPart)
                                    val subnetsArr = obj.optJSONArray("subnets")
                                    val subnetsList = mutableListOf<String>()
                                    if (subnetsArr != null) {
                                        for (i in 0 until subnetsArr.length()) {
                                            subnetsList.add(subnetsArr.getString(i))
                                        }
                                    }
                                    appContext?.getSharedPreferences(PREFS_NAME, MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
                                        ?.edit()?.putString("active_subnets", subnetsList.joinToString(","))?.apply()
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading tsrouter output: ${e.message}")
                } finally {
                    Log.i(TAG, "tsrouter sub-process output loop ended.")
                }
            }

            Log.i(TAG, "tsrouter process started. Waiting for port $routerPort to be ready...")

            val ready = waitForPort("127.0.0.1", routerPort, timeoutMs = 5000)
            if (!ready) {
                Log.e(TAG, "tsrouter did not bind port $routerPort within 5s, aborting")
                proc.destroy()
                process = null
                writeStatus("{\"state\":\"Error\", \"err_message\":\"tsrouter startup timeout\"}")
                return false
            }

            Log.i(TAG, "tsrouter sub-process is ready on port $routerPort")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start tsrouter sub-process: ${e.message}", e)
            writeStatus("{\"state\":\"Error\", \"err_message\":\"${e.message}\"}")
            return false
        }
    }

    /** Poll TCP port until connectable or timeout. */
    private fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(host, port), 200)
                }
                return true
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        return false
    }

    @Synchronized
    fun stop() {
        val proc = process ?: return
        Log.i(TAG, "Stopping tsrouter sub-process gracefully...")
        try {
            monitorJob?.cancel()
            monitorJob = null
            processJob?.cancel()
            processJob = null
            proc.destroy() // Sends SIGTERM on Linux/Android
            val exited = try {
                proc.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                false
            }
            if (!exited) {
                Log.w(TAG, "tsrouter sub-process did not exit in 1s, forcibly destroying")
                proc.destroyForcibly()
            }
            process = null
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping tsrouter sub-process: ${e.message}", e)
        } finally {
            writeStatus("{\"state\":\"Stopped\"}")
        }
    }

    fun isReady(): Boolean {
        return getStatusJSON().contains("\"Running\"")
    }

    fun isRunning(): Boolean = process != null
}
