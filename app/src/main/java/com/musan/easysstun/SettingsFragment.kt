package com.musan.easysstun

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val serverId = arguments?.getString("server_id") ?: "default"
        preferenceManager.sharedPreferencesName = "server_$serverId"

        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        val passwordPreference = findPreference<EditTextPreference>("easyss_password")

        passwordPreference?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        passwordPreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        passwordPreference?.summaryProvider = object :
            Preference.SummaryProvider<EditTextPreference> {
            override fun provideSummary(preference: EditTextPreference): CharSequence {
                val password = preference.text
                return if (password.isNullOrEmpty()) {
                    getString(R.string.not_set)
                } else {
                    "******"
                }
            }
        }

        val directPreference = findPreference<EditTextPreference>("easyss_direct_domains")
        directPreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            editText.isSingleLine = false
            editText.setLines(5)
        }
        val proxyPreference = findPreference<EditTextPreference>("easyss_proxy_domains")
        proxyPreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            editText.isSingleLine = false
            editText.setLines(5)
        }

        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val activeServerId = defaultPrefs.getString("active_server_id", "") ?: ""
        val isEditingActive = (serverId == activeServerId)

        // 获取所有设置项，并为每个设置项添加监听器
        for (i in 0 until preferenceScreen.preferenceCount) {
            val preference = preferenceScreen.getPreference(i)
            preference.setOnPreferenceChangeListener { _, newValue ->
                if (preference.key == "easyss_name") {
                    val prefHelper = Pref(requireContext())
                    val servers = prefHelper.getServerList().map {
                        if (it.id == serverId) ServerConfig(it.id, newValue.toString().trim()) else it
                    }
                    prefHelper.saveServerList(servers)
                }

                if (isEditingActive) {
                    // 发送广播通知Service
                    val intent = Intent("prefs_updated")
                    intent.putExtra("preference_key", preference.key)
                    intent.putExtra("new_value", newValue.toString())
                    context?.sendBroadcast(intent)
                }
                true
            }
        }

    }
}