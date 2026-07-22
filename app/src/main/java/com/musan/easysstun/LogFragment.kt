package com.musan.easysstun

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

class LogFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var logViewModel: LogViewModel
    private lateinit var logAdapter: LogAdapter
    private var logJob: Job? = null

    private var isAtBottom = true
    private var changingState = false

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
    }

    override fun onDestroy() {
        super.onDestroy()
        logJob?.cancel()
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
