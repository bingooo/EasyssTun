package com.musan.easysstun

import android.net.TrafficStats
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

data class ConnectionStats(
    val activeTcp: Int,
    val totalTcp: Int,
    val activeUdp: Int
)

class LogFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var logViewModel: LogViewModel
    private lateinit var logAdapter: LogAdapter
    private var logJob: Job? = null
    private var statsJob: Job? = null

    private var isAtBottom = true
    private var changingState = false

    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var peakDownSpeed = 0L
    private var peakUpSpeed = 0L

    private val dnsDirectCount = AtomicInteger(0)
    private val dnsProxyCount = AtomicInteger(0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.logRecyclerView)
        logAdapter = LogAdapter()

        val layoutManager = LinearLayoutManager(requireContext())
        recyclerView.layoutManager = layoutManager
        recyclerView.recycledViewPool.setMaxRecycledViews(0, 50)
        recyclerView.adapter = logAdapter
        logViewModel = ViewModelProvider(this).get(LogViewModel::class.java)

        logViewModel.logItems.observe(viewLifecycleOwner) { logItems ->
            changingState = true
            logAdapter.submitList(logItems)
            recyclerView.stopScroll()
            if (isAtBottom && logItems.size > 1) {
                recyclerView.scrollToPosition(logItems.size - 1)
            }
            changingState = false
        }

        val fabToBotton = view.findViewById<FloatingActionButton>(R.id.fabToBotton)
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (changingState) return
                if (!recyclerView.canScrollVertically(1)) {
                    isAtBottom = true
                    fabToBotton.hide()
                } else {
                    isAtBottom = false
                    fabToBotton.show()
                }
            }
        })

        fabToBotton.setOnClickListener {
            isAtBottom = !isAtBottom
            if (isAtBottom && logAdapter.itemCount > 1) {
                recyclerView.stopScroll()
                recyclerView.scrollToPosition(logAdapter.itemCount - 1)
                fabToBotton.hide()
            }
        }

        readLogs()
        startStatsTicker(view)
    }

    override fun onDestroy() {
        super.onDestroy()
        logJob?.cancel()
        statsJob?.cancel()
    }

    private fun startStatsTicker(view: View) {
        val tvServiceStatus = view.findViewById<TextView>(R.id.tvServiceStatus)
        val tvCoreInfo = view.findViewById<TextView>(R.id.tvCoreInfo)
        val tvDownSpeed = view.findViewById<TextView>(R.id.tvDownSpeed)
        val tvUpSpeed = view.findViewById<TextView>(R.id.tvUpSpeed)
        val tvPeakDown = view.findViewById<TextView>(R.id.tvPeakDown)
        val tvPeakUp = view.findViewById<TextView>(R.id.tvPeakUp)
        val tvActiveTcp = view.findViewById<TextView>(R.id.tvActiveTcp)
        val tvTotalTcp = view.findViewById<TextView>(R.id.tvTotalTcp)
        val tvActiveUdp = view.findViewById<TextView>(R.id.tvActiveUdp)
        val tvTotalConnections = view.findViewById<TextView>(R.id.tvTotalConnections)
        val tvDnsDirect = view.findViewById<TextView>(R.id.tvDnsDirect)
        val tvDnsProxy = view.findViewById<TextView>(R.id.tvDnsProxy)
        val tvRxTotal = view.findViewById<TextView>(R.id.tvRxTotal)
        val tvTxTotal = view.findViewById<TextView>(R.id.tvTxTotal)

        val pref = Pref(requireContext())
        val uid = android.os.Process.myUid()

        statsJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val isServiceRunning = pref.isServiceEnabled
                val easyssInfo = pref.getEasyssInfo()
                val coreVersion = easyssInfo.coreVersion

                if (isServiceRunning) {
                    tvServiceStatus.text = "服务状态: 运行中"
                    tvServiceStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                    tvCoreInfo.text = if (coreVersion == "3") "Easyss v3 (AAR)" else "Easyss v2 (SO)"
                } else {
                    tvServiceStatus.text = "服务状态: 已停止"
                    tvServiceStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                    tvCoreInfo.text = if (coreVersion == "3") "Easyss v3" else "Easyss v2"
                }

                // Traffic stats
                val rxBytes = TrafficStats.getUidRxBytes(uid)
                val txBytes = TrafficStats.getUidTxBytes(uid)

                val validRx = if (rxBytes != TrafficStats.UNSUPPORTED.toLong() && rxBytes >= 0) rxBytes else 0L
                val validTx = if (txBytes != TrafficStats.UNSUPPORTED.toLong() && txBytes >= 0) txBytes else 0L

                val downSpeed = if (lastRxBytes >= 0 && validRx >= lastRxBytes) validRx - lastRxBytes else 0L
                val upSpeed = if (lastTxBytes >= 0 && validTx >= lastTxBytes) validTx - lastTxBytes else 0L

                lastRxBytes = validRx
                lastTxBytes = validTx

                if (downSpeed > peakDownSpeed) peakDownSpeed = downSpeed
                if (upSpeed > peakUpSpeed) peakUpSpeed = upSpeed

                tvDownSpeed.text = formatSpeed(downSpeed)
                tvUpSpeed.text = formatSpeed(upSpeed)
                tvPeakDown.text = formatSpeed(peakDownSpeed)
                tvPeakUp.text = formatSpeed(peakUpSpeed)
                tvRxTotal.text = formatBytes(validRx)
                tvTxTotal.text = formatBytes(validTx)

                // Socket Connection stats from /proc/net
                val connStats = withContext(Dispatchers.IO) {
                    getAppConnectionStats(uid)
                }

                tvActiveTcp.text = "${connStats.activeTcp}"
                tvTotalTcp.text = "${connStats.totalTcp}"
                tvActiveUdp.text = "${connStats.activeUdp}"
                tvTotalConnections.text = "${connStats.totalTcp + connStats.activeUdp}"

                // DNS Query Counters
                tvDnsDirect.text = "${dnsDirectCount.get()}"
                tvDnsProxy.text = "${dnsProxyCount.get()}"

                delay(1000)
            }
        }
    }

    private fun getAppConnectionStats(uid: Int): ConnectionStats {
        var activeTcp = 0
        var totalTcp = 0
        var activeUdp = 0
        val uidStr = uid.toString()

        fun parseTcpFile(filePath: String) {
            try {
                val file = File(filePath)
                if (!file.exists()) return
                file.forEachLine { line ->
                    val tokens = line.trim().split(Regex("\\s+"))
                    if (tokens.size >= 8) {
                        val st = tokens[3]
                        val lineUid = tokens[7]
                        if (lineUid == uidStr) {
                            totalTcp++
                            if (st == "01") { // ESTABLISHED
                                activeTcp++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore missing file or permission restriction
            }
        }

        fun parseUdpFile(filePath: String) {
            try {
                val file = File(filePath)
                if (!file.exists()) return
                file.forEachLine { line ->
                    val tokens = line.trim().split(Regex("\\s+"))
                    if (tokens.size >= 8) {
                        val lineUid = tokens[7]
                        if (lineUid == uidStr) {
                            activeUdp++
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore missing file or permission restriction
            }
        }

        parseTcpFile("/proc/net/tcp")
        parseTcpFile("/proc/net/tcp6")
        parseUdpFile("/proc/net/udp")
        parseUdpFile("/proc/net/udp6")

        return ConnectionStats(activeTcp, totalTcp, activeUdp)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return "${formatBytes(bytesPerSec)}/s"
    }

    private fun readLogs() {
        logJob = lifecycleScope.launch(Dispatchers.IO) {
            var inputStream: InputStream? = null
            var bufferedReader: BufferedReader? = null
            var process: Process? = null
            try {
                val cmd = arrayOf("logcat", "-v", "time", "GoLog:V", "easyss:V", "*:S")
                process = Runtime.getRuntime().exec(cmd)
                inputStream = process.inputStream
                bufferedReader = BufferedReader(InputStreamReader(inputStream))

                val timePattern = Pattern.compile("(\\d{2}:\\d{2}:\\d{2})\\.\\d{3}")
                val levelPattern = Pattern.compile("level=([A-Z]+)")
                val msgPattern = Pattern.compile("msg=\"?([^\"]+)\"?")
                val formatBPattern = Pattern.compile("\\[\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\]\\s\\[([A-Z])\\]\\s(?:0x[0-9a-fA-F]+\\s)?(.*)")

                while (coroutineContext.isActive) {
                    val line = bufferedReader.readLine() ?: break
                    if (line.isNotBlank()) {
                        // Count DNS Direct & Proxy queries from log stream
                        if (line.contains("DNS_DIRECT")) {
                            dnsDirectCount.incrementAndGet()
                        } else if (line.contains("DNS_PROXY")) {
                            dnsProxyCount.incrementAndGet()
                        }

                        val timeMatcher = timePattern.matcher(line)
                        val timeStr = if (timeMatcher.find()) timeMatcher.group(1) else ""

                        val formatBMatcher = formatBPattern.matcher(line)
                        val cleanMessage = if (formatBMatcher.find()) {
                            val lvl = when (formatBMatcher.group(1)) {
                                "I" -> "[INFO]"
                                "W" -> "[WARN]"
                                "E" -> "[ERROR]"
                                "D" -> "[DEBUG]"
                                else -> "[INFO]"
                            }
                            "$lvl ${formatBMatcher.group(2)}"
                        } else {
                            val levelMatcher = levelPattern.matcher(line)
                            val levelStr = if (levelMatcher.find()) "[${levelMatcher.group(1)}] " else ""

                            val msgMatcher = msgPattern.matcher(line)
                            val rawMessage = if (msgMatcher.find()) {
                                msgMatcher.group(1)
                            } else {
                                val colonIdx = line.indexOf(':')
                                if (colonIdx != -1 && colonIdx < line.length - 1) {
                                    line.substring(colonIdx + 1).trim()
                                } else {
                                    line.trim()
                                }
                            }
                            "$levelStr$rawMessage".trim()
                        }

                        if (cleanMessage.isNotBlank()) {
                            val logItem = LogItem(cleanMessage, timeStr)
                            logViewModel.addLog(logItem)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LogFragment", "Error reading logcat logs: ${e.message}", e)
            } finally {
                try { inputStream?.close() } catch (e: Exception) {}
                try { bufferedReader?.close() } catch (e: Exception) {}
                try { process?.destroy() } catch (e: Exception) {}
            }
        }
    }
}

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {
    private var logItems: List<LogItem> = emptyList()

    fun submitList(newList: List<LogItem>) {
        logItems = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val logItem = logItems[position]
        holder.bind(logItem)
    }

    override fun getItemCount(): Int {
        return logItems.size
    }

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logTextView: TextView = itemView.findViewById(R.id.logTextView)
        private val logTimestampTextView: TextView =
            itemView.findViewById(R.id.logTimestampTextView)

        fun bind(logItem: LogItem) {
            logTextView.text = logItem.message
            logTimestampTextView.text = logItem.time
        }
    }
}

data class LogItem(val message: String, var time: String)

class LogViewModel : ViewModel() {
    private val _logItems = MutableLiveData<List<LogItem>>()
    val logItems: LiveData<List<LogItem>> get() = _logItems

    fun addLog(logItem: LogItem) {
        val currentList = _logItems.value.orEmpty().toMutableList()
        currentList.add(logItem)
        _logItems.postValue(currentList)
    }

    fun clearLogs() {
        _logItems.postValue(emptyList())
    }
}
