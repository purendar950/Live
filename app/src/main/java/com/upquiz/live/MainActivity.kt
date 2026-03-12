package com.upquiz.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQ_OVERLAY = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nameInput   = findViewById<EditText>(R.id.etName)
        val btnJoin     = findViewById<Button>(R.id.btnJoin)
        val btnStop     = findViewById<Button>(R.id.btnStop)
        val tvStatus    = findViewById<TextView>(R.id.tvStatus)
        val tvChannel   = findViewById<TextView>(R.id.tvChannel)

        tvChannel.text = "📺 UTTAR PRADESH LEKHPAL"

        // Check if service already running
        updateStatusUI(FloatingService.isRunning)

        btnJoin.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "नाम डालो पहले!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                // Ask overlay permission
                tvStatus.text = "⚙️ Permission दो — 'Display over other apps' ON करो"
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQ_OVERLAY)
                // Save name for after permission
                getSharedPreferences("upquiz", MODE_PRIVATE)
                    .edit().putString("name", name).apply()
            } else {
                startFloatingService(name)
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            updateStatusUI(false)
        }
    }

    private fun startFloatingService(name: String) {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val intent   = Intent(this, FloatingService::class.java)
        intent.putExtra("player_name", name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatusUI(true)
        tvStatus.text = "✅ Connected! Quiz का इंतज़ार है...\nYouTube Stream खोलो अब!"
        // Minimise app so YouTube is visible
        moveTaskToBack(true)
    }

    private fun updateStatusUI(running: Boolean) {
        val btnJoin  = findViewById<Button>(R.id.btnJoin)
        val btnStop  = findViewById<Button>(R.id.btnStop)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        if (running) {
            btnJoin.isEnabled  = false
            btnStop.isEnabled  = true
            btnStop.visibility = android.view.View.VISIBLE
            tvStatus.text      = "✅ Active — Question आने पर Card दिखेगा"
        } else {
            btnJoin.isEnabled  = true
            btnStop.isEnabled  = false
            btnStop.visibility = android.view.View.GONE
            tvStatus.text      = "👆 नाम डालो और Join करो"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                val name = getSharedPreferences("upquiz", MODE_PRIVATE)
                    .getString("name", "Student") ?: "Student"
                startFloatingService(name)
            } else {
                findViewById<TextView>(R.id.tvStatus).text =
                    "❌ Permission नहीं मिली — Settings में जाकर ON करो"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI(FloatingService.isRunning)
    }
}
