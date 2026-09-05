package com.darrenai.jarvis

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.darrenai.jarvis.databinding.ActivityMainBinding
import com.darrenai.jarvis.ui.FaceActivity

/**
 * Single-activity shell: bottom nav across Face / Talk / Chat / Memory.
 * The FAB opens the fullscreen face, like the desktop gallery's default face.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        binding.fabFace.setOnClickListener {
            FaceActivity.show(this)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
