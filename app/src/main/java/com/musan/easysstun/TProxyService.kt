package com.musan.easysstun

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import engine.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

class TProxyService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null

    private lateinit var pref: Pref
    private val easyJob = Job()
    private val easyScope = CoroutineScope(Dispatchers.Default + easyJob)
    private lateinit var processEasyJob: Job
    lateinit var process: Process

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_DISCONNECT == intent.action) {
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
        registerReceiver(prefsUpdatedReceiver, IntentFilter("prefs_updated"))
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
        var easyssInfo = pref.getEasyssInfo()
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
        builder.addDnsServer("223.6.6.6")

        resources.getStringArray(R.array.bypass_private_route).forEach {
            val parts = it.split('/', limit = 2)
            builder.addRoute(parts[0], parts[1].toInt())
        }
        session += "IPv4"

//        if (prefs.getIpv6()) {
//            val addr: String = prefs.getTunnelIpv6Address()
//            val prefix: Int = prefs.getTunnelIpv6Prefix()
//            val dns: String = prefs.getDnsIpv6()
//            builder.addAddress(addr, prefix)
//            builder.addRoute("::", 0)
//            if (!dns.isEmpty()) builder.addDnsServer(dns)
//            if (!session.isEmpty()) session += " + "
//            session += "IPv6"
//        }
        for (appName in pref.getApps()!!) {
            try {
                builder.addDisallowedApplication(appName)
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }
        session += "/per-App"


        val selfName = applicationContext.packageName
        try {
            builder.addDisallowedApplication(selfName)
        } catch (e: PackageManager.NameNotFoundException) {
        }

        // debug wechat
//        builder.addAllowedApplication("com.tencent.mm")

        builder.setSession(session)
        tunFd = builder.establish()
        if (tunFd == null) {
            stopSelf()
            return
        }

        processEasyJob = easyScope.launch {
            while (true) {

                try {
                    val libraryPath = applicationInfo.nativeLibraryDir.toString() + "/libeasyss.so"
                    var cmdList = listOf(libraryPath) + easyssInfo.cmdList
                    Log.i("easyss", cmdList.toString())
                    process = ProcessBuilder(cmdList).start()

                    Log.d("easyss", "msg=[EasyssTun] Connected to the service successfully.")
                    val bufferedReader =
                        BufferedReader(InputStreamReader(process.inputStream))

                    while (!processEasyJob.isCancelled) {
                        var line: String = bufferedReader.readLine()
                        if (line != null) {
                            Log.i("easyss", line)
                        }
                    }

                } catch (e: IOException) {
                    Log.e("easyss", "msg=[EasyssTun] IOException: " + e.message)
                } catch (e: InterruptedException) {
                    Log.e("easyss", "msg=[EasyssTun] InterruptedException: " + e.message)
                } finally {
                    process.destroy()
                    val exitCode = process.waitFor()
                    Log.i("easyss", "msg=[EasyssTun] Command exited with code: $exitCode")
                    break
                }
            }
        }

        var tun = pref.prefs.getString("tunmode", "heiher/hev-socks5-tunnel")
        var loglevel = pref.prefs.getString("easyss_loglevel", "info")
        when (tun) {
            "heiher/hev-socks5-tunnel" -> {
                /* TProxy */
                val tproxy_file = File(cacheDir, "tproxy.conf")
                try {
                    tproxy_file.createNewFile()

                    var logfile = File(cacheDir, "tproxy.log")
                    logfile.createNewFile()
                    val fos = FileOutputStream(tproxy_file, false)
                    var tproxy_conf = """misc:
  task-stack-size: 20480
  connect-timeout: 5000
  limit-nofile: 65535
  read-write-timeout: 60000
  log-file: ${logfile.absolutePath}
  log-level: ${loglevel}
tunnel:
  mtu: 8500
"""
                    tproxy_conf += """socks5:
  port: ${pref.prefs.getString("socks_port", "2080")?.toInt()}
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

            "xjasonlyu/tun2socks" -> {
                /* xjasonlyu/tun2socks */
                /* issue #123 */
                val key: engine.Key = Key()
                key.setMark(0)
                key.setMTU(0)
                key.setDevice("fd://" + tunFd!!.getFd())
                key.setInterface("")
                key.setLogLevel(loglevel)
                key.setProxy("socks5://127.0.0.1:2080")
                key.setRestAPI("")
                key.setTCPSendBufferSize("")
                key.setTCPReceiveBufferSize("")
                key.setTCPModerateReceiveBuffer(false)
                engine.Engine.insert(key)
                engine.Engine.start()
            }

            else -> {
//                /* Tun2proxy */
//                /* Issue #7 */
//                val proxyUrl = "socks5://127.0.0.1:2080"
//                val ptunFd = tunFd!!.fd
//                val tunMtu = 8500
//                val verbose = false
//                val dnsOverTcp = false
//
//                var sScope = CoroutineScope(Dispatchers.Default)
//                sScope.launch {
//                    Tun2proxy.run(proxyUrl, ptunFd, tunMtu, verbose, dnsOverTcp)
//                }
//
//                /* BadVPN */
//                /* so file from ssrDroid */
//                var sScope = CoroutineScope(Dispatchers.Default)
//                sScope.launch {
//                    val sfile = File(cacheDir, "sock_path")
//                    sfile.createNewFile()
//                    val cmd = listOf<String>(
//                        applicationInfo.nativeLibraryDir.toString() + "/libtun2socks_bad.so",
//                        "--netif-ipaddr", "198.18.0.2",
//                        "--netif-netmask", "255.255.255.0",
//                        "--socks-server-addr", "127.0.0.1:2080",
//                        "--tunmtu", "8500",
//                        "--dnsgw", "198.18.0.1",
//                        "--tunfd", tunFd!!.fd.toString(),
//                        "--sock", sfile.absolutePath,
//                        "--loglevel", "3"
//                    )
//                    println(cmd.toString())
//                    try {
//                        val processBuilder = ProcessBuilder(cmd)
//                        var process = processBuilder.start()
//
//                        var tries = 0
//                        while (true) try {
//                            delay(50L shl tries)
//                            LocalSocket().use { localSocket ->
//                                localSocket.connect(
//                                    LocalSocketAddress(
//                                        sfile.absolutePath,
//                                        LocalSocketAddress.Namespace.FILESYSTEM
//                                    )
//                                )
//                                localSocket.setFileDescriptorsForSend(arrayOf(tunFd!!.fileDescriptor))
//                                localSocket.outputStream.write(42)
//                            }
//                            continue
//                        } catch (e: IOException) {
//                            println(e.message)
//                            if (tries > 5) throw e
//                            tries += 1
//                        }
//                    } catch (e: IOException) {
//                        Log.e("sc", "msg=[sc] IOException: " + e.message)
//                    } catch (e: InterruptedException) {
//                        Log.e("sc", "msg=[sc] InterruptedException: " + e.message)
//                    }
//                }

            }

        }
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
        try {
            process.destroy()
            processEasyJob.cancel()
        } catch (e: Exception) {
            Log.e("easyJob", e.message.toString())
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
        startForeground(1, notify)
    }

    //     create NotificationChannel
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

        const val ACTION_CONNECT = "CONNECT"
        const val ACTION_DISCONNECT = "DISCONNECT"

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}