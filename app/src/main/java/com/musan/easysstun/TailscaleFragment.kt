package com.musan.easysstun

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.musan.easysstun.databinding.FragmentTailscaleBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class TailscaleFragment : Fragment() {

    private var _binding: FragmentTailscaleBinding? = null
    private val binding get() = _binding!!
    private lateinit var pref: Pref

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTailscaleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pref = Pref(requireContext())

        // Load existing settings
        binding.switchTailscaleEnable.isChecked = pref.isTailscaleEnabled
        binding.etAuthKey.setText(pref.tailscaleAuthKey)
        binding.etControlUrl.setText(pref.tailscaleControlUrl)
        binding.etHostname.setText(pref.tailscaleHostname)

        // Switch toggle
        binding.switchTailscaleEnable.setOnCheckedChangeListener { _, isChecked ->
            pref.isTailscaleEnabled = isChecked
            val state = if (isChecked) "已启用" else "已禁用"
            Toast.makeText(context, "Tailscale $state (服务重启后生效)", Toast.LENGTH_SHORT).show()
        }

        // Save config button
        binding.btnSaveConfig.setOnClickListener {
            val authKey = binding.etAuthKey.text?.toString()?.trim() ?: ""
            val controlUrl = binding.etControlUrl.text?.toString()?.trim() ?: "https://controlplane.tailscale.com"
            val hostname = binding.etHostname.text?.toString()?.trim() ?: "easyss-android"

            pref.tailscaleAuthKey = authKey
            pref.tailscaleControlUrl = controlUrl
            pref.tailscaleHostname = hostname

            Toast.makeText(context, "Tailscale 配置已保存", Toast.LENGTH_SHORT).show()
            updateStatusDisplay()
        }

        // Refresh status button
        binding.btnRefreshStatus.setOnClickListener {
            updateStatusDisplay()
        }

        updateStatusDisplay()
    }

    private var refreshJob: Job? = null

    override fun onResume() {
        super.onResume()
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                updateStatusDisplay()
                delay(1500)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun updateStatusDisplay() {
        val jsonStr = TailscaleManager.getStatusJSON(requireContext())
        try {
            val obj = JSONObject(jsonStr)
            val state = obj.optString("state", "Stopped")
            val ipsArr = obj.optJSONArray("ips")
            val magicDns = obj.optString("magic_dns_suffix", "--")
            val errMsg = obj.optString("err_message", "")
            val peerCount = obj.optInt("peer_count", 0)

            val ipsList = mutableListOf<String>()
            if (ipsArr != null) {
                for (i in 0 until ipsArr.length()) {
                    ipsList.add(ipsArr.getString(i))
                }
            }

            val statusText = if (errMsg.isNotEmpty()) {
                "状态: $state (错误: $errMsg)"
            } else {
                "状态: $state  节点数: $peerCount"
            }

            binding.tvTailscaleState.text = statusText
            binding.tvTailscaleIps.text = if (ipsList.isNotEmpty()) "IP: ${ipsList.joinToString(", ")}" else "IP: --"
            binding.tvMagicDns.text = "MagicDNS: $magicDns"
        } catch (e: Exception) {
            binding.tvTailscaleState.text = "状态: 未知 ($jsonStr)"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
