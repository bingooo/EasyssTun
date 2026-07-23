package com.musan.easysstun

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.github.nange.easyss.config.Config
import io.github.nange.easyss.config.SimpleConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class Pref(private val ctx: Context) {
    companion object {
        const val SERVICE_ENABLED = "enable"
        const val VERSION = "version"
    }

    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

    var version: String
        get() {
            val currentTimestamp: String = System.currentTimeMillis().toString()
            return prefs.getString(VERSION, currentTimestamp) ?: currentTimestamp
        }
        set(value) {
            prefs.edit().putString(VERSION, value).apply()
        }

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(SERVICE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(SERVICE_ENABLED, value) }

    val localSocksPort: Int
        get() = prefs.getString("local_socks_port", "2080")?.toIntOrNull() ?: 2080

    val localHttpPort: Int
        get() = prefs.getString("local_http_port", (localSocksPort + 1000).toString())?.toIntOrNull() ?: (localSocksPort + 1000)

    var isTailscaleEnabled: Boolean
        get() = prefs.getBoolean("tailscale_enable", false)
        set(value) = prefs.edit { putBoolean("tailscale_enable", value) }

    var tailscaleAuthKey: String
        get() = prefs.getString("tailscale_auth_key", "") ?: ""
        set(value) = prefs.edit { putString("tailscale_auth_key", value) }

    var tailscaleControlUrl: String
        get() = prefs.getString("tailscale_control_url", "https://controlplane.tailscale.com") ?: "https://controlplane.tailscale.com"
        set(value) = prefs.edit { putString("tailscale_control_url", value) }

    var tailscaleHostname: String
        get() = prefs.getString("tailscale_hostname", "easyss-android") ?: "easyss-android"
        set(value) = prefs.edit { putString("tailscale_hostname", value) }

    val tailscaleRouterPort: Int
        get() = prefs.getString("tailscale_router_port", "10800")?.toIntOrNull() ?: 10800

    val easyssSocksPort: Int
        get() = prefs.getString("easyss_socks_port", "10801")?.toIntOrNull() ?: 10801

    fun getApps(): Set<String>? {
        return prefs.getStringSet("selected_apps", HashSet<String>())
    }

    fun all(): Map<String, *> {
        return prefs.all
    }

    fun saveServerList(servers: List<ServerConfig>) {
        val array = JSONArray()
        for (server in servers) {
            val obj = JSONObject()
            obj.put("id", server.id)
            obj.put("name", server.name)
            array.put(obj)
        }
        prefs.edit().putString("server_list", array.toString()).apply()
    }

    fun getServerList(): List<ServerConfig> {
        val json = prefs.getString("server_list", null) ?: return emptyList()
        val list = mutableListOf<ServerConfig>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(ServerConfig(obj.getString("id"), obj.getString("name")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun migrateIfNeeded() {
        val serverListJson = prefs.getString("server_list", null)
        if (serverListJson == null) {
            val oldServer = prefs.getString("easyss_server", "")
            if (!oldServer.isNullOrBlank()) {
                val newId = java.util.UUID.randomUUID().toString()
                val serverName = "Default"

                val serverPrefs = ctx.getSharedPreferences("server_$newId", Context.MODE_PRIVATE)
                val edit = serverPrefs.edit()

                val keysToMigrate = listOf(
                    "easyss_server", "easyss_serverport", "easyss_password",
                    "easyss_encryption", "easyss_proxyrule", "easyss_outbound",
                    "easyss_custom_ca", "easyss_loglevel", "easyss_disable_quic"
                )
                for (key in keysToMigrate) {
                    if (prefs.contains(key)) {
                        val value = prefs.all[key]
                        if (value is String) {
                            edit.putString(key, value)
                        } else if (value is Boolean) {
                            edit.putBoolean(key, value)
                        }
                    }
                }
                edit.apply()

                val servers = listOf(ServerConfig(newId, serverName))
                saveServerList(servers)
                prefs.edit().putString("active_server_id", newId).apply()
            }
        }
    }

    fun getSimpleConfig(): SimpleConfig? {
        migrateIfNeeded()

        val activeId = prefs.getString("active_server_id", "")
        if (activeId.isNullOrBlank()) {
            return null
        }

        val serverPrefs = ctx.getSharedPreferences("server_$activeId", Context.MODE_PRIVATE)

        val server = serverPrefs.getString("easyss_server", "") ?: ""
        val serverPortStr = serverPrefs.getString("easyss_serverport", "") ?: ""
        val password = serverPrefs.getString("easyss_password", "") ?: ""

        if (server.isBlank() || serverPortStr.isBlank() || password.isBlank()) {
            return null
        }

        val encryption = serverPrefs.getString("easyss_encryption", "chacha20-poly1305") ?: "chacha20-poly1305"
        val proxyrule = serverPrefs.getString("easyss_proxyrule", "auto") ?: "auto"
        val outbound = serverPrefs.getString("easyss_outbound", "native") ?: "native"
        val loglevel = serverPrefs.getString("easyss_loglevel", "info") ?: "info"
        val disableQuic = serverPrefs.getString("easyss_disable_quic", "false") ?: "false"
        val customCa = serverPrefs.getString("easyss_custom_ca", "")

        val simpleConfig = Config.newSimpleConfig()
        simpleConfig.server = server
        simpleConfig.serverPort = serverPortStr.toLongOrNull() ?: 8882L
        simpleConfig.password = password
        simpleConfig.method = encryption
        simpleConfig.localPort = localSocksPort.toLong()
        simpleConfig.httpPort = localHttpPort.toLong()
        simpleConfig.proxyRule = proxyrule
        simpleConfig.outboundProto = outbound
        simpleConfig.logLevel = loglevel
        simpleConfig.enableQUIC = !disableQuic.toBoolean()
        simpleConfig.disableSysProxy = true
        simpleConfig.bindAll = false
        simpleConfig.enableForwardDNS = false
        simpleConfig.enableTun2socks = false
        simpleConfig.timeout = 60L

        if (!customCa.isNullOrBlank()) {
            val caFile = File(ctx.cacheDir, "easyss_custom_ca.conf")
            try {
                caFile.writeText(customCa)
                simpleConfig.caPath = caFile.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val directDomains = serverPrefs.getString("easyss_direct_domains", "") ?: ""
        val proxyDomains = serverPrefs.getString("easyss_proxy_domains", "") ?: ""

        if (directDomains.isNotBlank()) {
            val directFile = File(ctx.cacheDir, "direct_$activeId.txt")
            try {
                directFile.writeText(directDomains)
                simpleConfig.directFile = directFile.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (proxyDomains.isNotBlank()) {
            val proxyFile = File(ctx.cacheDir, "proxy_$activeId.txt")
            try {
                proxyFile.writeText(proxyDomains)
                simpleConfig.proxyFile = proxyFile.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return simpleConfig
    }

    fun getEasyssInfo(): easyssInfo {
        migrateIfNeeded()

        val activeId = prefs.getString("active_server_id", "")
        if (activeId.isNullOrBlank()) {
            return easyssInfo(valid = false)
        }

        val serverPrefs = ctx.getSharedPreferences("server_$activeId", Context.MODE_PRIVATE)

        var easyssInfo = easyssInfo()
        val coreVersion = serverPrefs.getString("easyss_version", "2") ?: "2"
        easyssInfo.coreVersion = coreVersion

        var easyss_server = serverPrefs.getString("easyss_server", "") ?: ""
        var easyss_serverport = serverPrefs.getString("easyss_serverport", "") ?: ""
        var easyss_password = serverPrefs.getString("easyss_password", "") ?: ""

        if (!easyss_server.isNullOrBlank() && !easyss_serverport.isNullOrBlank() && !easyss_password.isNullOrBlank()){
            easyssInfo.valid = true
            val serverList = getServerList()
            val serverName = serverList.find { it.id == activeId }?.name ?: "Server"
            easyssInfo.info = "$serverName ($easyss_server:$easyss_serverport)"
        }

        var easyss_encryption = serverPrefs.getString("easyss_encryption", "chacha20-poly1305") ?: "chacha20-poly1305"
        var easyss_proxyrule = serverPrefs.getString("easyss_proxyrule", "auto") ?: "auto"
        var easyss_outbound = serverPrefs.getString("easyss_outbound", "native") ?: "native"
        var easyss_loglevel = serverPrefs.getString("easyss_loglevel", "info") ?: "info"
        var easyss_disable_quic = serverPrefs.getString("easyss_disable_quic", "false") ?: "false"

        val isV3 = (coreVersion == "3")

        var cmdList = mutableListOf("-s", easyss_server,
            "-p", easyss_serverport,
            "-k", easyss_password,
            "-m", easyss_encryption,
            "-proxy-rule", easyss_proxyrule,
            "-outbound-proto", easyss_outbound,
            "-l", localSocksPort.toString(),
            "-t", "60",
            "-log-level", easyss_loglevel,
            "-enable-quic=${!(easyss_disable_quic.toBoolean())}",
            "-daemon=false")

        if (!isV3) {
            cmdList.add("-bind-all=false")
            cmdList.add("-enable-forward-dns=false")
        }
        cmdList.add("-enable-tun2socks=false")

        var easyss_custom_ca = serverPrefs.getString("easyss_custom_ca", "")
        if (!isV3 && !easyss_custom_ca.isNullOrBlank()){
            val easyss_custom_ca_file = File(ctx.cacheDir, "easyss_custom_ca.conf")
            try {
                easyss_custom_ca_file.createNewFile()
                val fos = FileOutputStream(easyss_custom_ca_file, false)
                fos.write(easyss_custom_ca.toByteArray())
                fos.close()
            } catch (e: IOException) {

            }

            cmdList.add("-ca-path")
            cmdList.add(easyss_custom_ca_file.absolutePath)
        }

        val easyss_direct_domains = serverPrefs.getString("easyss_direct_domains", "") ?: ""
        val easyss_proxy_domains = serverPrefs.getString("easyss_proxy_domains", "") ?: ""

        if (isV3) {
            if (easyss_direct_domains.isNotBlank()) {
                val directFile = File(ctx.cacheDir, "direct_$activeId.txt")
                try {
                    directFile.writeText(easyss_direct_domains)
                    cmdList.add("-direct-file")
                    cmdList.add(directFile.absolutePath)
                } catch (e: IOException) {
                }
            } else {
                val directFile = File(ctx.cacheDir, "direct_$activeId.txt")
                if (directFile.exists()) directFile.delete()
            }

            if (easyss_proxy_domains.isNotBlank()) {
                val proxyFile = File(ctx.cacheDir, "proxy_$activeId.txt")
                try {
                    proxyFile.writeText(easyss_proxy_domains)
                    cmdList.add("-proxy-file")
                    cmdList.add(proxyFile.absolutePath)
                } catch (e: IOException) {
                }
            } else {
                val proxyFile = File(ctx.cacheDir, "proxy_$activeId.txt")
                if (proxyFile.exists()) proxyFile.delete()
            }
        } else {
            val domainsBuilder = StringBuilder()
            val ipsBuilder = StringBuilder()
            easyss_direct_domains.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    if (trimmed.matches(Regex("^[0-9./:]+$"))) {
                        ipsBuilder.append(trimmed).append("\n")
                    } else {
                        domainsBuilder.append(trimmed).append("\n")
                    }
                }
            }

            val v2DomainsFile = File(ctx.cacheDir, "direct_domains.txt")
            if (domainsBuilder.length > 0) {
                try {
                    v2DomainsFile.writeText(domainsBuilder.toString())
                } catch (e: IOException) {}
            } else {
                if (v2DomainsFile.exists()) v2DomainsFile.delete()
            }

            val v2IpsFile = File(ctx.cacheDir, "direct_ips.txt")
            if (ipsBuilder.length > 0) {
                try {
                    v2IpsFile.writeText(ipsBuilder.toString())
                } catch (e: IOException) {}
            } else {
                if (v2IpsFile.exists()) v2IpsFile.delete()
            }
        }

        easyssInfo.cmdList = cmdList

        return easyssInfo
    }

}

data class easyssInfo(
    var valid: Boolean = false,
    var info: String = "",
    var cmdList: List<String> = listOf(),
    var coreVersion: String = "2",
)