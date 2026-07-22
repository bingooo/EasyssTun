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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class TProxyService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null

    private lateinit var pref: Pref
    private val easyJob = Job()
    private val easyScope = CoroutineScope(Dispatchers.Default + easyJob)
    private var processEasyJob: Job? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (ACTION_DISCONNECT == intent.action) {
            stopService()
            return START_NOT_STICKY
        }
        try {
            startService()
        } catch (e: IOException) {
            throw RuntimeException(e)
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
        val simpleConfig = pref.getSimpleConfig()
        if (simpleConfig == null) {
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

        // Launch Easyss core via libeasyss.aar Mobile.start(simpleConfig)
        processEasyJob = easyScope.launch {
            try {
                val easyssVersion = Mobile.version()
                Log.i("easyss", "Starting easyss core via Mobile.start() (version $easyssVersion)...")
                Mobile.start(simpleConfig)
                Log.i("easyss", "Easyss core started successfully via AAR.")
            } catch (e: Exception) {
                Log.e("easyss", "Mobile.start exception: ${e.message}", e)
            }
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
  port: ${pref.localSocksPort}
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

    fun stopService() {
        if (tunFd == null) return
        stopForeground(true)

        /* TProxy */
        try {
            TProxyStopService()
        } catch (e: Exception) {
            Log.e("TProxyStopService", e.message.toString())
        }

        /* Easyss Mobile AAR */
        try {
            Mobile.stop()
            processEasyJob?.cancel()
            Log.i("easyss", "Easyss core stopped via Mobile.stop().")
        } catch (e: Exception) {
            Log.e("easyss", "Mobile.stop error: ${e.message}")
        }

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