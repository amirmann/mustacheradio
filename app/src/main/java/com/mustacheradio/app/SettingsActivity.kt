package com.mustacheradio.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var prefs: SharedPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        val darkModeSwitch = findViewById<SwitchMaterial>(R.id.darkModeSwitch)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        darkModeSwitch.isChecked = isDarkMode

        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dark_mode", isChecked).apply()
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        val whatsPlayingSwitch = findViewById<SwitchMaterial>(R.id.whatsPlayingSwitch)
        whatsPlayingSwitch.isChecked = prefs.getBoolean("whats_playing", false)
        whatsPlayingSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("whats_playing", isChecked).apply()
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.versionText).text = "Version $versionName"
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
