package com.musan.easysstun


import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.io.File


class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
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

        // 获取所有设置项，并为每个设置项添加监听器
        for (i in 0 until preferenceScreen.preferenceCount) {
            val preference = preferenceScreen.getPreference(i)
            preference.setOnPreferenceChangeListener { _, newValue ->
                // 发送广播通知Service
                val intent = Intent("prefs_updated")
                intent.putExtra("preference_key", preference.key)
                intent.putExtra("new_value", newValue.toString())
                context?.sendBroadcast(intent)
                true
            }
        }

    }
}