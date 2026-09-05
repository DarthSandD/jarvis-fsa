package com.darrenai.jarvis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.darrenai.jarvis.OmniClient
import com.darrenai.jarvis.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home: greeting, connection status, entry points. */
class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btn_open_face)?.setOnClickListener {
            runCatching { FaceActivity.show(requireContext()) }
        }
        view.findViewById<View>(R.id.btn_hold_talk)?.setOnClickListener {
            selectTab(R.id.nav_talk)
        }
        view.findViewById<TextView>(R.id.txt_stack)?.text =
            "MIND ..... vault/ (plain markdown, no ceiling)\n" +
                "MOUTH .... hold-to-talk voice loop\n" +
                "FACE ..... circuit board, live signal bus\n" +
                "HANDS .... web console (webcam + Chrome)"

        val status = view.findViewById<TextView>(R.id.txt_home_status)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val client = client()
            val err = try {
                client.healthCheck()
            } catch (e: Exception) {
                e.message ?: "failed"
            }
            val ms = System.currentTimeMillis() - t0
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (err == null) {
                    status?.text = "● online · ${ms}ms"
                    status?.setTextColor(resources.getColor(R.color.fsa_green, null))
                } else {
                    status?.text = "● offline — check Wi-Fi / Settings"
                    status?.setTextColor(resources.getColor(R.color.fsa_amber, null))
                }
            }
        }
    }

    private fun client(): OmniClient {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return OmniClient(
            endpoint = prefs.getString("endpoint", OmniClient.DEFAULT_ENDPOINT)
                ?: OmniClient.DEFAULT_ENDPOINT,
            apiKey = prefs.getString("api_key", "") ?: ""
        )
    }

    private fun selectTab(itemId: Int) {
        (activity as? androidx.appcompat.app.AppCompatActivity)?.let { app ->
            app.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                ?.selectedItemId = itemId
        }
    }
}
