package com.minimal.tvlauncher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView

data class AppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val banner: Drawable?,
    val isPinned: Boolean,
    val isHidden: Boolean
)

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var grid: GridView
    private lateinit var ramText: TextView
    private lateinit var btnHidden: TextView
    private lateinit var adapter: AppAdapter

    private val apps = mutableListOf<AppItem>()
    private var showHidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        grid = findViewById(R.id.app_grid)
        ramText = findViewById(R.id.ram_info)
        btnHidden = findViewById(R.id.btn_hidden)

        findViewById<TextView>(R.id.btn_settings).setOnClickListener {
            openIntent(Intent(Settings.ACTION_SETTINGS))
        }
        findViewById<TextView>(R.id.btn_wifi).setOnClickListener {
            openIntent(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
        btnHidden.setOnClickListener {
            showHidden = !showHidden
            btnHidden.text = if (showHidden) "Gizliler: Açık" else "Gizliler"
            loadApps()
        }

        adapter = AppAdapter()
        grid.adapter = adapter

        grid.setOnItemClickListener { _, _, position, _ ->
            val pkg = apps[position].packageName
            val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) openIntent(launch)
        }

        grid.setOnItemLongClickListener { _, _, position, _ ->
            showAppOptions(apps[position])
            true
        }

        loadApps()
        grid.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updateRamInfo()
    }

    override fun onBackPressed() {
        if (showHidden) {
            showHidden = false
            btnHidden.text = "Gizliler"
            loadApps()
        }
    }

    private fun updateRamInfo() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val freeMb = mi.availMem / (1024 * 1024)
        val totalMb = mi.totalMem / (1024 * 1024)
        ramText.text = "RAM: $freeMb / $totalMb MB"
    }

    private fun openIntent(primary: Intent, fallback: Intent? = null) {
        try {
            startActivity(primary)
        } catch (_: Exception) {
            if (fallback != null) try { startActivity(fallback) } catch (_: Exception) {}
        }
    }

    private fun getSet(key: String): MutableSet<String> =
        HashSet(prefs.getStringSet(key, emptySet()) ?: emptySet())

    private fun saveSet(key: String, set: Set<String>) {
        prefs.edit().putStringSet(key, set).apply()
    }

    private fun loadApps() {
        val pm = packageManager
        val pinnedSet = getSet("pinned")
        val hiddenSet = getSet("hidden")
        val seen = HashSet<String>()
        val temp = mutableListOf<AppItem>()

        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )

        for (query in queries) {
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue

                val isHidden = hiddenSet.contains(pkg)
                if (isHidden && !showHidden) continue

                val banner = info.activityInfo.loadBanner(pm)
                    ?: info.activityInfo.applicationInfo.loadBanner(pm)

                temp.add(
                    AppItem(
                        label = info.loadLabel(pm).toString(),
                        packageName = pkg,
                        icon = info.loadIcon(pm),
                        banner = banner,
                        isPinned = pinnedSet.contains(pkg),
                        isHidden = isHidden
                    )
                )
            }
        }

        temp.sortWith(compareByDescending<AppItem> { it.isPinned }.thenBy { it.label.lowercase() })
        apps.clear()
        apps.addAll(temp)
        adapter.notifyDataSetChanged()
    }

    private fun showAppOptions(app: AppItem) {
        val options = arrayOf(
            if (app.isPinned) "Sabitlemeyi Kaldır" else "Üste Sabitle",
            if (app.isHidden) "Gizlemeyi Kaldır" else "Gizle",
            "Uygulama Bilgisi (Zorla Durdur)"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val set = getSet("pinned")
                        if (!set.add(app.packageName)) set.remove(app.packageName)
                        saveSet("pinned", set)
                        loadApps()
                    }
                    1 -> {
                        val set = getSet("hidden")
                        if (!set.add(app.packageName)) set.remove(app.packageName)
                        saveSet("hidden", set)
                        loadApps()
                    }
                    2 -> {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${app.packageName}")
                        }
                        openIntent(intent)
                    }
                }
            }
            .show()
    }

    inner class AppAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(p: Int) = apps[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_app, parent, false)

            val app = apps[pos]
            val bannerView = view.findViewById<ImageView>(R.id.app_banner)
            val iconBox = view.findViewById<View>(R.id.icon_box)
            val iconView = view.findViewById<ImageView>(R.id.app_icon)
            val nameView = view.findViewById<TextView>(R.id.app_name)
            val badgeView = view.findViewById<TextView>(R.id.app_badge)

            if (app.banner != null) {
                bannerView.visibility = View.VISIBLE
                iconBox.visibility = View.GONE
                bannerView.setImageDrawable(app.banner)
            } else {
                bannerView.visibility = View.GONE
                iconBox.visibility = View.VISIBLE
                iconView.setImageDrawable(app.icon)
                nameView.text = app.label
            }

            badgeView.text = when {
                app.isHidden -> "GİZLİ"
                app.isPinned -> "★"
                else -> ""
            }
            badgeView.visibility = if (badgeView.text.isEmpty()) View.GONE else View.VISIBLE
            view.alpha = if (app.isHidden) 0.45f else 1.0f

            return view
        }
    }
}
