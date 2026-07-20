package com.musan.easysstun

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.Manifest
import android.content.Intent
import android.content.pm.PackageInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_app_list, container, false)
        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        progressBar = view.findViewById(R.id.progressBar)

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val currentMode = sharedPrefs.getString("app_proxy_mode", "bypass") ?: "bypass"

        adapter = AppListAdapter(requireContext(), lifecycleScope)
        recyclerView.recycledViewPool.setMaxRecycledViews(0, 100)
        recyclerView.adapter = adapter

        // 1. Bind Routing Mode Spinner
        val modeSpinner = view.findViewById<Spinner>(R.id.spinnerRoutingMode)

        val modes = arrayOf(
            getString(R.string.routing_mode_bypass),
            getString(R.string.routing_mode_proxy)
        )
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner.adapter = spinnerAdapter

        if (currentMode == "proxy") {
            modeSpinner.setSelection(1)
            (requireActivity() as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.applist_title_proxy)
        } else {
            modeSpinner.setSelection(0)
            (requireActivity() as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.applist_title)
        }

        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = if (position == 1) "proxy" else "bypass"
                val titleRes = if (position == 1) R.string.applist_title_proxy else R.string.applist_title

                (requireActivity() as? AppCompatActivity)?.supportActionBar?.setTitle(titleRes)

                if (sharedPrefs.getString("app_proxy_mode", "bypass") != mode) {
                    sharedPrefs.edit().putString("app_proxy_mode", mode).apply()

                    // Notification to service
                    val intent = Intent("prefs_updated")
                    requireContext().sendBroadcast(intent)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadAppList()

        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_applist, menu)
        val showSystem = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean("show_system_apps", false)
        menu.findItem(R.id.action_show_system_apps)?.isChecked = showSystem
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_show_system_apps -> {
                val newChecked = !item.isChecked
                item.isChecked = newChecked
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit().putBoolean("show_system_apps", newChecked).apply()
                loadAppList()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadAppList() {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            val appList = withContext(Dispatchers.IO) {
                getInstalledApps()
            }
            adapter.setAppList(appList)
            progressBar.visibility = View.GONE
        }
    }

    private fun getInstalledApps(): List<PackageInfo> {
        val pm = requireContext().packageManager
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val showSystem = sharedPrefs.getBoolean("show_system_apps", false)

        val packages = pm.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
        )

        val prefHelper = Pref(requireContext())
        val selectedApps = prefHelper.getApps()

        var appList = packages
            .filter { packageInfo ->
                val isSystemApp = (packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val shouldKeep = if (showSystem) true else !isSystemApp
                shouldKeep &&
                        packageInfo.requestedPermissions?.contains(Manifest.permission.INTERNET) == true &&
                        packageInfo.packageName != requireActivity().applicationContext.packageName
            }

        appList = appList.sortedWith(
            compareBy<PackageInfo>{
                selectedApps?.contains(it.packageName) != true
            }.thenBy { it.packageName }
        )

        return appList
    }
}