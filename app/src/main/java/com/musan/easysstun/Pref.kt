package com.musan.easysstun
import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
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

    fun getEasyssInfo(): easyssInfo {
        migrateIfNeeded()

        val activeId = prefs.getString("active_server_id", "")
        if (activeId.isNullOrBlank()) {
            return easyssInfo(valid = false)
        }

        val serverPrefs = ctx.getSharedPreferences("server_$activeId", Context.MODE_PRIVATE)

        var easyssInfo = easyssInfo()
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

        var cmdList = listOf("-s", easyss_server,
            "-p", easyss_serverport,
            "-k", easyss_password,
            "-m", easyss_encryption,
            "-proxy-rule", easyss_proxyrule,
            "-outbound-proto", easyss_outbound,
            "-l", "2080",
            "-t", "60",
            "-log-level", easyss_loglevel,
            "-enable-quic=${!(easyss_disable_quic.toBoolean())}",
            "-bind-all=false",
            "-enable-forward-dns=false",
            "-enable-tun2socks=false",
            "-daemon=false")

        var easyss_custom_ca = serverPrefs.getString("easyss_custom_ca", "")
        if (!easyss_custom_ca.isNullOrBlank()){
            val easyss_custom_ca_file = File(ctx.cacheDir, "easyss_custom_ca.conf")
            try {
                easyss_custom_ca_file.createNewFile()
                val fos = FileOutputStream(easyss_custom_ca_file, false)
                fos.write(easyss_custom_ca.toByteArray())
                fos.close()
            } catch (e: IOException) {

            }

            cmdList = cmdList + listOf("-ca-path", easyss_custom_ca_file.absolutePath)
        }

        easyssInfo.cmdList = cmdList

        return easyssInfo
    }

}

data class easyssInfo(
    var valid: Boolean = false,
    var info: String = "",
    var cmdList: List<String> = listOf(),
)