package com.musan.easysstun

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class ServersFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ServersAdapter
    private lateinit var pref: Pref

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_servers, container, false)
        pref = Pref(requireContext())

        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val activeServerId = pref.prefs.getString("active_server_id", "") ?: ""

        adapter = ServersAdapter(
            requireContext(),
            activeServerId,
            onServerSelected = { server ->
                selectServer(server)
            },
            onServerEdit = { server ->
                editServer(server)
            },
            onServerDelete = { server ->
                confirmDeleteServer(server)
            },
            onServerRename = { server ->
                showRenameDialog(server)
            }
        )
        recyclerView.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            addNewServer()
        }

        loadServers()

        return view
    }

    private fun loadServers() {
        val servers = pref.getServerList()
        val activeServerId = pref.prefs.getString("active_server_id", "") ?: ""
        adapter.setServers(servers, activeServerId)
    }

    private fun selectServer(server: ServerConfig) {
        val currentActive = pref.prefs.getString("active_server_id", "")
        if (currentActive != server.id) {
            pref.prefs.edit().putString("active_server_id", server.id).apply()
            
            // Notify service to reload
            val intent = Intent("prefs_updated")
            requireContext().sendBroadcast(intent)
            
            loadServers()
        }
    }

    private fun editServer(server: ServerConfig) {
        val bundle = Bundle().apply {
            putString("server_id", server.id)
        }
        findNavController().navigate(R.id.action_servers_to_setting, bundle)
    }

    private fun confirmDeleteServer(server: ServerConfig) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_delete_title)
            .setMessage(R.string.dialog_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                deleteServer(server)
            }
            .show()
    }

    private fun deleteServer(server: ServerConfig) {
        val servers = pref.getServerList().toMutableList()
        servers.removeAll { it.id == server.id }
        pref.saveServerList(servers)

        // Clear associated shared preferences
        val serverPrefs = requireContext().getSharedPreferences("server_${server.id}", Context.MODE_PRIVATE)
        serverPrefs.edit().clear().apply()

        val activeId = pref.prefs.getString("active_server_id", "")
        if (activeId == server.id) {
            val newActive = servers.firstOrNull()?.id ?: ""
            pref.prefs.edit().putString("active_server_id", newActive).apply()
            
            val intent = Intent("prefs_updated")
            requireContext().sendBroadcast(intent)
        }

        loadServers()
    }

    private fun showRenameDialog(server: ServerConfig) {
        val input = EditText(requireContext())
        input.setText(server.name)
        input.setSelection(server.name.length)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_edit_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val servers = pref.getServerList().map {
                        if (it.id == server.id) ServerConfig(it.id, newName) else it
                    }
                    pref.saveServerList(servers)
                    loadServers()
                }
            }
            .show()
    }

    private fun addNewServer() {
        val newId = java.util.UUID.randomUUID().toString()
        val newServer = ServerConfig(newId, "")
        
        val servers = pref.getServerList().toMutableList()
        servers.add(newServer)
        pref.saveServerList(servers)
        
        // Auto-select as active
        pref.prefs.edit().putString("active_server_id", newId).apply()
        
        // Stop any running service to reload the new configuration
        val intent = Intent("prefs_updated")
        requireContext().sendBroadcast(intent)

        loadServers()

        // Immediately navigate to config page for this server
        editServer(newServer)
    }
}
