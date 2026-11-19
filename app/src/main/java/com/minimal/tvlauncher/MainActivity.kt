package com.minimal.tvlauncher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager
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
import android.widget.Toast

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
    private lateinit var rootLayout: View
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
        rootLayout = findViewById(R.id.root_layout)
        grid = findViewById(R.id.app_grid)
        ramText = findViewById(R.id.ram_info)
        btnHidden = findViewById(R.id.btn_hidden)

        findViewById<TextView>(R.id.btn_hdmi).setOnClickListener { showHdmiMenu() }
        findViewById<TextView>(R.id.btn_ram_clean).setOnClickListener { cleanRam() }
        findViewById<TextView>(R.id.btn_theme).setOnClickListener { showThemeMenu() }
        findViewById<TextView>(R.id.btn_settings).setOnClickListener {
            openIntent(Intent(Settings.ACTION_SETTINGS))
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

        applyAppearance()
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

    private fun getFreeRamMb(): Pair<Long, Long> {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return Pair(mi.availMem / (1024 * 1024), mi.totalMem / (1024 * 1024))
    }

    private fun updateRamInfo() {
        val (freeMb, totalMb) = getFreeRamMb()
        ramText.text = "RAM: $freeMb / $totalMb MB"
    }

    private fun cleanRam() {
        val beforeFree = getFreeRamMb().first
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val pm = packageManager

        for (appInfo in pm.getInstalledApplications(0)) {
            val pkg = appInfo.packageName
            if (pkg != packageName && !pkg.startsWith("com.android.systemui")) {
                try {
                    am.killBackgroundProcesses(pkg)
                } catch (_: Exception) {}
            }
        }
        System.gc()

        ramText.postDelayed({
            val (afterFree, totalMb) = getFreeRamMb()
            ramText.text = "RAM: $afterFree / $totalMb MB"
            val diff = afterFree - beforeFree
            val msg = if (diff > 0) "RAM temizlendi (+${diff} MB)" else "RAM güncel ($afterFree MB boş)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }, 350)
    }

    private fun showHdmiMenu() {
        val tvManager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val inputs = tvManager?.tvInputList?.filter { it.isPassthroughInput } ?: emptyList()

        if (inputs.isEmpty()) {
            // Donanım listesi boş dönerse doğrudan Canlı TV / Kaynak ekranını aç
            val fallback = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
            openIntent(fallback)
            return
        }

        val labels = inputs.map { it.loadLabel(this).toString() }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("HDMI / Giriş Seç")
            .setItems(labels) { _, which ->
                val uri = TvContract.buildChannelUriForPassthroughInput(inputs[which].id)
                openIntent(Intent(Intent.ACTION_VIEW, uri))
            }
            .show()
    }

    private fun showThemeMenu() {
        val options = arrayOf(
            "Sütun Sayısı (Şu an: ${prefs.getInt("cols", 5)})",
            "Arka Plan Rengi",
            "Seçim Çerçevesi Rengi"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Görünüm")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickColumns()
                    1 -> pickBgColor()
                    2 -> pickAccentColor()
                }
            }
            .show()
    }

    private fun pickColumns() {
        val cols = arrayOf("4 Sütun (Büyük)", "5 Sütun (Standart)", "6 Sütun (Kompakt)")
        val vals = intArrayOf(4, 5, 6)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sütun Sayısı")
            .setItems(cols) { _, w ->
                prefs.edit().putInt("cols", vals[w]).apply()
                applyAppearance()
            }
            .show()
    }

    private fun pickBgColor() {
        val names = arrayOf("Saf Siyah (En Hafif)", "Koyu Gri", "Gece Mavisi", "Antrasit")
        val hexes = arrayOf("#000000", "#0B0D10", "#09111F", "#141414")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Arka Plan")
            .setItems(names) { _, w ->
                prefs.edit().putString("bg_color", hexes[w]).apply()
                applyAppearance()
            }
            .show()
    }

    private fun pickAccentColor() {
        val names = arrayOf("Mavi", "Yeşil", "Turuncu", "Beyaz")
        val hexes = arrayOf("#3B82F6", "#10B981", "#F59E0B", "#F3F4F6")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Seçim Çerçevesi")
            .setItems(names) { _, w ->
                prefs.edit().putString("accent_color", hexes[w]).apply()
                applyAppearance()
            }
            .show()
    }

    private fun applyAppearance() {
        val cols = prefs.getInt("cols", 5)
        val bgHex = prefs.getString("bg_color", "#0B0D10") ?: "#0B0D10"
        val accentHex = prefs.getString("accent_color", "#3B82F6") ?: "#3B82F6"

        grid.numColumns = cols
        rootLayout.setBackgroundColor(Color.parseColor(bgHex))
        grid.selector = buildFocusSelector(Color.parseColor(accentHex))
    }

    private fun buildFocusSelector(accentColor: Int): StateListDrawable {
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setStroke(6, accentColor)
            setColor(Color.argb(35, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(), normal)
        }
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
