package com.musan.easysstun

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat.getDrawable
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL


class MainFragment : Fragment() {
    private lateinit var mContext: Context
    private lateinit var pref: Pref
    private lateinit var easyssInfo: easyssInfo

    private var rotateAnimation: RotateAnimation? = null
    private lateinit var speed_test_icon: ImageView
    private var speedTesting = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_main, container, false)
        setHasOptionsMenu(true)
        easyssInfo = pref.getEasyssInfo()
        setup(view)
        updateServiceStatu(view)

        GitTagTask(view, requireContext()).execute()

        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_tips -> {
                findNavController().navigate(R.id.action_main_to_log)
//                Toast.makeText(mContext, list.shuffled().first(), Toast.LENGTH_SHORT).show()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mContext = context
        pref = Pref(context)

    }

    override fun onResume() {
        super.onResume()
    }

    private fun setup(view: View) {
        var service_button =
            view.findViewById<MaterialButton>(R.id.service_button)
        service_button.let {
            it.setOnClickListener {
                if (pref.isServiceEnabled) {
                    pref.isServiceEnabled = false
                    true
                } else {
                    pref.isServiceEnabled = true
                    true
                }
                updateServiceStatu(view)
            }
        }

        var selected_apps = pref.getApps()!!
        view.findViewById<TextView>(R.id.text1).let {
            it.text = getString(R.string.skipped_app_list, selected_apps.size.toString())
        }

        if (easyssInfo.valid){
            view.findViewById<TextView>(R.id.service_summary).let {
                it.text = easyssInfo.info
            }
        }else{
            pref.isServiceEnabled = false
        }

        view.findViewById<MaterialButton>(R.id.service_setting)
            .let {
                it.setOnClickListener {
                    findNavController().navigate(R.id.action_main_to_setting)
                    true
                }
            }

        view.findViewById<LinearLayout>(R.id.applist).let {
            it.setOnClickListener {
                findNavController().navigate(R.id.action_main_to_applist)
                true
            }
        }

        speed_test_icon = view.findViewById<ImageView>(R.id.speed_test_icon)
        initRotateAnimation()

        view.findViewById<LinearLayout>(R.id.speed_test).let {
            it.setOnClickListener {
                if (!speedTesting){
                    getResponseTimeUsingSocksProxy("https://www.google.com", "127.0.0.1", 2080)
                }

                true
            }
        }
    }

    private inner class GitTagTask(private val rootView: View, private val context: Context) : AsyncTask<Void, Void, String>() {

        override fun doInBackground(vararg params: Void): String {
            val libraryPath = context.applicationInfo.nativeLibraryDir.toString() + "/libeasyss.so"
            val command = listOf(libraryPath, "--version")
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var gitTag = "Easyss"
            while (reader.readLine().also { line = it } != null) {
                // 在输出中查找包含 "Git tag:" 的行
                if (line!!.startsWith("Git tag:")) {
                    gitTag += ": " + line!!.substringAfter(":").trim()
                    break
                }
            }

            process.waitFor()
            return gitTag
        }

        override fun onPostExecute(gitTag: String) {
            // 将 Git tag 设置到 TextView 上
            val versionPlaceholder = rootView.findViewById<TextView>(R.id.version_placeholder)
            versionPlaceholder.text = gitTag
        }
    }


    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (pref.isServiceEnabled) {
            startVPNService()
        }
    }




    private fun startVPNService() {
        val intent = VpnService.prepare(mContext)
        if (intent != null) {
            startActivityForResult(intent, 0)
        } else {
        }
        try {
            val intent2 = Intent(mContext, TProxyService::class.java)
            mContext.startService(intent2.setAction(TProxyService.ACTION_CONNECT))
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

    }

    private fun stopVPNService() {
        val intent2 = Intent(mContext, TProxyService::class.java)
        mContext.startService(intent2.setAction(TProxyService.ACTION_DISCONNECT))
    }

    private fun statuVPNService(): Boolean {
        return isServiceRunning(mContext, TProxyService::class.java)
    }

    private fun updateServiceStatu(view: View) {
        var service_button =
            view.findViewById<MaterialButton>(R.id.service_button)
        var service_title = view.findViewById<TextView>(R.id.service_title)
        var service_icon = view.findViewById<ImageView>(R.id.service_icon)
        var service_card = view.findViewById<MaterialCardView>(R.id.service_card)
        when {
            pref.isServiceEnabled -> {
                if(!easyssInfo.valid){
                    Toast.makeText(mContext, getString(R.string.easyss_need_config), Toast.LENGTH_SHORT).show()
                    pref.isServiceEnabled = false
                    return
                }

                startVPNService()
//                if(statuVPNService()){
                    service_button.text = getString(R.string.service_disable)

                    service_card.setCardBackgroundColor(mContext.getColor(R.color.home_card_background_color_active))
                    service_icon.setImageDrawable(getDrawable(mContext, R.drawable.ic_launcher_foreground_big))
                    service_button.icon = getDrawable(mContext, R.drawable.ic_close_24)
                    service_button.setBackgroundColor(mContext.getColor(R.color.button_disable))
                    service_title.text = getString(R.string.service_running)
//                }

                true
            }

            else -> {
                stopVPNService()
                service_button.text = getString(R.string.service_enable)
                service_card.setCardBackgroundColor(mContext.getColor(R.color.home_card_background_color))
                service_icon.setImageDrawable(getDrawable(mContext, R.drawable.ic_close_24))
                service_button.icon = getDrawable(mContext, R.drawable.ic_outline_play_arrow_24)
                service_button.setBackgroundColor(mContext.getColor(R.color.button_enable))
                service_title.text = getString(R.string.service_stopped)
            }
        }
    }

    fun getResponseTimeUsingSocksProxy(
        urlString: String,
        socksProxyHost: String,
        socksProxyPort: Int
    ) {
        speed_test_icon.startAnimation(rotateAnimation)
        speedTesting = true

        var res = ""
        CoroutineScope(Dispatchers.Default).launch {
            if (!pref.isServiceEnabled){
                res = getString(R.string.service_stopped)
            }else {
                val url = URL(urlString)
                val proxy =
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost, socksProxyPort))
                val startTime = System.currentTimeMillis()
                try {
                    (url.openConnection(proxy) as? HttpURLConnection)?.run {
                        requestMethod = "GET"
                        connectTimeout = 3000

                        val responseCode = responseCode
                        if (responseCode == HttpURLConnection.HTTP_OK) {
                        } else {
                        }
                        disconnect()
                    }
                    val endTime = System.currentTimeMillis()
                    var responseTime = endTime - startTime
                    res = "$responseTime ms"
                } catch (e: Exception) {
                    Log.e("test", e.message.toString())
                    res = getString(R.string.delay_test_fail)
                }
            }

            withContext(Dispatchers.Main) {
                speedTesting = false
                speed_test_icon.clearAnimation()

                view?.findViewById<TextView>(R.id.speed_result).let {

                    it?.setText(getString(R.string.delay_test_result, res))
                }
                Toast.makeText(mContext, res, Toast.LENGTH_SHORT).show()
            }

        }
    }


    private fun initRotateAnimation() {
        rotateAnimation = RotateAnimation(
            0f,
            360f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f
        ).apply {
            duration = 1000 // 旋转一周的时间，单位毫秒
            repeatCount = Animation.INFINITE // 无限循环
        }
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = mContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = manager.getRunningServices(Int.MAX_VALUE)
        for (service in services) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

}