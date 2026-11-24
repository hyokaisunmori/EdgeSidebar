package com.example.edgesidebar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var tvOverlay: TextView
    private lateinit var btnOverlay: Button
    private lateinit var swService: Switch
    private lateinit var seekAlpha: SeekBar
    private lateinit var tvAlpha: TextView
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: AppAdapter

    private val selected = HashSet<String>()
    private val executor = Executors.newSingleThreadExecutor()

    private val serviceListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) {
            if (!Settings.canDrawOverlays(this)) {
                setSwitchSilently(false)
                Toast.makeText(this, "Berikan izin Display over other apps dulu", Toast.LENGTH_SHORT).show()
                openOverlaySettings()
                return@OnCheckedChangeListener
            }
            requestNotificationPermission()
            ContextCompat.startForegroundService(
                this, Intent(this, FloatingSidebarService::class.java)
            )
        } else {
            stopService(Intent(this, FloatingSidebarService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOverlay = findViewById(R.id.tvOverlayStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        swService = findViewById(R.id.swService)
        seekAlpha = findViewById(R.id.seekAlpha)
        tvAlpha = findViewById(R.id.tvAlpha)
        etSearch = findViewById(R.id.etSearch)
        tvCount = findViewById(R.id.tvCount)
        rvApps = findViewById(R.id.rvApps)

        selected.addAll(Prefs.apps(this))

        // a. izin overlay
        btnOverlay.setOnClickListener { openOverlaySettings() }

        // b. switch service
        swService.setOnCheckedChangeListener(serviceListener)

        // c. slider alpha (5% .. 100%)
        seekAlpha.max = 95
        val alpha = Prefs.alphaPercent(this)
        seekAlpha.progress = alpha - 5
        tvAlpha.text = "$alpha%"
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress + 5
                tvAlpha.text = "$pct%"
                if (fromUser) Prefs.setAlphaPercent(this@MainActivity, pct)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // d. daftar aplikasi
        adapter = AppAdapter(selected) { newSet ->
            Prefs.setApps(this, newSet)
            updateCount()
        }
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.setHasFixedSize(true)
        rvApps.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateCount()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        tvOverlay.text = if (granted) "Izin aktif ✓" else "Belum diizinkan"
        btnOverlay.visibility = if (granted) View.GONE else View.VISIBLE
        setSwitchSilently(granted && FloatingSidebarService.isRunning)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun setSwitchSilently(v: Boolean) {
        swService.setOnCheckedChangeListener(null)
        swService.isChecked = v
        swService.setOnCheckedChangeListener(serviceListener)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun updateCount() {
        tvCount.text = "Aplikasi di sidebar · ${selected.size} dipilih"
    }

    @Suppress("DEPRECATION")
    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(launcher, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map {
                    AppInfo(
                        label = it.loadLabel(pm).toString(),
                        pkg = it.activityInfo.packageName,
                        icon = it.loadIcon(pm)
                    )
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) adapter.submit(list)
            }
        }
    }
}
