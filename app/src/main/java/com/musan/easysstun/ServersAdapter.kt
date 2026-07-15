package com.musan.easysstun

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ServersAdapter(
    private val context: Context,
    private var activeServerId: String,
    private val onServerSelected: (ServerConfig) -> Unit,
    private val onServerEdit: (ServerConfig) -> Unit,
    private val onServerDelete: (ServerConfig) -> Unit,
    private val onServerRename: (ServerConfig) -> Unit
) : RecyclerView.Adapter<ServersAdapter.ServerViewHolder>() {

    private var serverList: List<ServerConfig> = emptyList()

    fun setServers(list: List<ServerConfig>, activeId: String) {
        serverList = list
        activeServerId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(serverList[position])
    }

    override fun getItemCount(): Int = serverList.size

    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val radioActive: RadioButton = itemView.findViewById(R.id.radio_active)
        private val txtName: TextView = itemView.findViewById(R.id.server_name)
        private val txtAddress: TextView = itemView.findViewById(R.id.server_address)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btn_edit)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)

        fun bind(server: ServerConfig) {
            val serverPrefs = context.getSharedPreferences("server_${server.id}", Context.MODE_PRIVATE)
            val host = serverPrefs.getString("easyss_server", "") ?: ""
            val port = serverPrefs.getString("easyss_serverport", "") ?: ""
            val savedName = serverPrefs.getString("easyss_name", "") ?: ""

            val addressStr = if (host.isNotEmpty() && port.isNotEmpty()) "$host:$port" else ""

            if (savedName.isNotEmpty()) {
                txtName.text = savedName
                txtAddress.text = if (addressStr.isNotEmpty()) addressStr else context.getString(R.string.not_set)
            } else {
                if (addressStr.isNotEmpty()) {
                    txtName.text = addressStr
                    txtAddress.text = ""
                } else {
                    txtName.text = context.getString(R.string.easyss_need_config)
                    txtAddress.text = ""
                }
            }

            radioActive.isChecked = (server.id == activeServerId)

            itemView.setOnClickListener {
                onServerSelected(server)
            }

            txtName.setOnLongClickListener {
                onServerRename(server)
                true
            }

            btnEdit.setOnClickListener {
                onServerEdit(server)
            }

            btnDelete.setOnClickListener {
                onServerDelete(server)
            }
        }
    }
}
