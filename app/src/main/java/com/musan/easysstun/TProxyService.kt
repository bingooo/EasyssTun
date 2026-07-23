package com.musan.easysstun

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import io.github.nange.easyss.mobile.Mobile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

class TProxyService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null

    private lateinit var pref: Pref
    private val easyJob = Job()
    private val easyScope = CoroutineScope(Dispatchers.Default + easyJob)
    private var processEasyJob: Job? = null
    private var v2Process: Process? = null
    private var startServiceJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if ("RELOAD_TUN_ROUTES" == intent?.action) {
            Log.i("TProxyService", "Reloading TUN session to apply updated Tailscale Subnet routes...")
            startServiceJob?.cancel()
            startServiceJob = easyScope.launch(Dispatchers.IO) {
                try {
                    reloadTunOnly()
                } catch (e: Exception) {
                    Log.e("TProxyService", "Failed to reload TUN session: ${e.message}")
                }
            }
            return START_STICKY
        }
        if (ACTION_DISCONNECT == intent?.action) {
            stopService()
            return START_NOT_STICKY
        }
        startServiceJob?.cancel()
        startServiceJob = easyScope.launch(Dispatchers.IO) {
            try {
                startService()
            } catch (e: IOException) {
                Log.e("TProxyService", "startService failed: ${e.message}", e)
            }
        }
        return START_STICKY
    }

    private val prefsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (tunFd != null) {
                stopService()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            prefsUpdatedReceiver,
            IntentFilter("prefs_updated"),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(prefsUpdatedReceiver)
        stopSelf()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopService()
    }

    @Throws(IOException::class)
    fun startService() {
        if (tunFd != null) return

        pref = Pref(this)
        val easyssInfo = pref.getEasyssInfo()
        if (!easyssInfo.valid) {
            pref.isServiceEnabled = false
            return
        }

        /* VPN */
        var session = String()
        val builder: Builder = Builder()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.setBlocking(false)
        builder.setMtu(8500)

        builder.addAddress("198.18.0.1", 32)
        builder.addDnsServer("1.0.0.1")

        resources.getStringArray(R.array.bypass_private_route).forEach {
            val parts = it.split('/', limit = 2)
            builder.addRoute(parts[0], parts[1].toInt())
        }
        // Dynamically add Headscale approved Subnet Router routes to TUN
        if (pref.isTailscaleEnabled) {
            try {
                val statusPrefs = getSharedPreferences("tailscale_status", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
                val subnetsStr = statusPrefs.getString("active_subnets", "") ?: ""
                if (subnetsStr.isNotEmpty()) {
                    subnetsStr.split(",").forEach { subnet ->
                        val trimmed = subnet.trim()
                        if (trimmed.contains("/")) {
                            val parts = trimmed.split('/', limit = 2)
                            try {
                                builder.addRoute(parts[0], parts[1].toInt())
                                Log.i("TProxyService", "Added active Subnet route to TUN: $trimmed")
                            } catch (e: Exception) {
                                Log.e("TProxyService", "Failed to add route $trimmed: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TProxyService", "Error reading active subnets: ${e.message}")
            }
        }
        session += "IPv4"

        val appProxyMode = pref.prefs.getString("app_proxy_mode", "bypass") ?: "bypass"
        val selectedApps = pref.getApps() ?: emptySet()

        if (appProxyMode == "proxy") {
            for (appName in selectedApps) {
                try {
                    builder.addAllowedApplication(appName)
                } catch (e: PackageManager.NameNotFoundException) {
                }
            }
        } else {
            for (appName in selectedApps) {
                try {
                    builder.addDisallowedApplication(appName)
                } catch (e: PackageManager.NameNotFoundException) {
                }
            }
            val selfName = applicationContext.packageName
            try {
                builder.addDisallowedApplication(selfName)
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }
        session += "/per-App"

        builder.setSession(session)
        tunFd = builder.establish()
        if (tunFd == null) {
            stopSelf()
            return
        }

        // Port orchestration for Tailscale and Easyss
        val isTailscaleEnabled = pref.isTailscaleEnabled
        val easyssPort = pref.localSocksPort
        val routerPort = pref.tailscaleRouterPort

        // Branching according to coreVersion ("3" -> libeasyss.aar, "2" -> libeasyss.so)
        val isV3 = (easyssInfo.coreVersion == "3")
        if (isV3) {
            val simpleConfig = pref.getSimpleConfig()
            if (simpleConfig != null) {
                simpleConfig.localPort = easyssPort.toLong()
                processEasyJob = easyScope.launch {
                    try {
                        val version = Mobile.version()
                        Log.i("easyss", "Starting Easyss v3 core via libeasyss.aar (version $version)...")
                        Mobile.start(simpleConfig)
                        Log.i("easyss", "Easyss v3 core started successfully via AAR.")
                    } catch (e: Exception) {
                        Log.e("easyss", "Mobile.start exception: ${e.message}", e)
                    }
                }
            } else {
                Log.e("easyss", "Failed to generate SimpleConfig for Easyss v3")
            }
        } else {
            processEasyJob = easyScope.launch {
                try {
                    val libraryPath = applicationInfo.nativeLibraryDir + "/libeasyss.so"
                    val cmdList = mutableListOf(libraryPath)
                    cmdList.addAll(easyssInfo.cmdList)
                    val lIdx = cmdList.indexOf("-l")
                    if (lIdx != -1 && lIdx + 1 < cmdList.size) {
                        cmdList[lIdx + 1] = easyssPort.toString()
                    }
                    Log.i("easyss", "Starting Easyss v2 core via ProcessBuilder: $cmdList")
                    val proc = ProcessBuilder(cmdList).directory(cacheDir).redirectErrorStream(true).start()
                    v2Process = proc
                    val bufferedReader = BufferedReader(InputStreamReader(proc.inputStream))
                    while (processEasyJob?.isActive == true) {
                        val line = bufferedReader.readLine() ?: break
                        Log.i("easyss", line)
                    }
                } catch (e: Exception) {
                    Log.e("easyss", "Easyss v2 process exception: ${e.message}", e)
                } finally {
                    try { v2Process?.destroy() } catch (e: Exception) {}
                }
            }
        }
        // Start Tailscale tsrouter process if enabled
        val targetTProxyPort = if (isTailscaleEnabled) {
            val success = TailscaleManager.start(
                context = this,
                routerPort = routerPort,
                easyssPort = easyssPort,
                authKey = pref.tailscaleAuthKey,
                controlUrl = pref.tailscaleControlUrl,
                hostname = pref.tailscaleHostname
            )
            if (success) routerPort else easyssPort
        } else {
            easyssPort
        }

        /* TProxy */
        val tproxy_file = File(cacheDir, "tproxy.conf")
        try {
            tproxy_file.createNewFile()
            val fos = FileOutputStream(tproxy_file, false)
            var tproxy_conf = """misc:
  task-stack-size: 86016
  tcp-buffer-size: 65536
  connect-timeout: 5000
  tcp-read-write-timeout: 600000
  udp-read-write-timeout: 60000
  log-file: stdout
  log-level: info
tunnel:
  mtu: 8500
"""
            tproxy_conf += """socks5:
  port: $targetTProxyPort
  address: '127.0.0.1'
  udp: 'udp'
"""
            fos.write(tproxy_conf.toByteArray())
            fos.close()
        } catch (e: IOException) {
            return
        }
        TProxyStartService(tproxy_file.absolutePath, tunFd!!.fd)
        pref.prefs.edit { apply { putBoolean("enable", true) } }
        val channelName = "easysstun"
        initNotificationChannel(channelName)
        createNotification(channelName)
    }

    private fun reloadTunOnly() {
        if (tunFd == null) return
        Log.i("TProxyService", "Closing existing TUN fd and stopping hev-socks5-tunnel...")
        try { TProxyStopService() } catch (_: Exception) {}
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null

        Log.i("TProxyService", "Rebuilding VPN Builder routes with updated subnets...")
        startService()
    }

    fun stopService() {
        if (tunFd == null) return
        stopForeground(true)

        /* TProxy */
        try {
            TProxyStopService()
        } catch (e: Exception) {
            Log.e("TProxyStopService", e.message.toString())
        }

        /* Stop Tailscale Manager */
        try {
            TailscaleManager.stop()
        } catch (e: Exception) {
            Log.e("tailscale", "TailscaleManager.stop error: ${e.message}")
        }

        /* Stop Easyss Core */
        try {
            Mobile.stop()
        } catch (e: Exception) {
            Log.e("easyss", "Mobile.stop error: ${e.message}")
        }
        try {
            v2Process?.destroy()
            v2Process = null
        } catch (e: Exception) {
            Log.e("easyss", "v2Process destroy error: ${e.message}")
        }
        processEasyJob?.cancel()

        /* VPN */
        try {
            tunFd!!.close()
        } catch (e: Exception) {
            Log.e("tunFd", e.message.toString())
        }
        tunFd = null

        stopSelf()
        System.exit(0)
    }

    private fun createNotification(channelName: String) {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelName)
        val notify = notification
            .setContentTitle(getString(R.string.service_running))
            .setSilent(true)
            .setSmallIcon(R.drawable.ic_launcher_foreground_big)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notify)
        } else {
            startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private fun initNotificationChannel(channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val name: CharSequence = getString(R.string.app_name)
            val channel =
                NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        @JvmStatic
        private external fun TProxyStartService(config_path: String, fd: Int)
        @JvmStatic
        private external fun TProxyStopService()
        @JvmStatic
        private external fun TProxyGetStats(): LongArray

        const val ACTION_CONNECT = "CONNECT"
        const val ACTION_DISCONNECT = "DISCONNECT"

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}