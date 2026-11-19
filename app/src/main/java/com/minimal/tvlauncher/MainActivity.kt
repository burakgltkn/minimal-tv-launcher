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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.random.Random

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
    private lateinit var dimOverlay: View
    private lateinit var screensaverOverlay: View
    private lateinit var screensaverClockBox: View
    private lateinit var grid: GridView
    private lateinit var ramText: TextView
    private lateinit var netText: TextView
    private lateinit var btnDim: TextView
    private lateinit var adapter: AppAdapter

    private val apps = mutableListOf<AppItem>()
    private var showHidden = false
    private var dimLevel = 0
    private var isBlackScreen = false
    private var isScreensaverActive = false
    private var lastKeyCode = -1
    private var lastKeyTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { showScreensaver(true) }
    private val shiftRunnable = object : Runnable {
        override fun run() {
            if (!isScreensaverActive) return
            screensaverClockBox.translationX = Random.nextInt(-140, 141).toFloat()
            screensaverClockBox.translationY = Random.nextInt(-90, 91).toFloat()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        rootLayout = findViewById(R.id.root_layout)
        dimOverlay = findViewById(R.id.dim_overlay)
        screensaverOverlay = findViewById(R.id.screensaver_overlay)
        screensaverClockBox = findViewById(R.id.screensaver_clock_box)
        grid = findViewById(R.id.app_grid)
        ramText = findViewById(R.id.ram_info)
        netText = findViewById(R.id.net_info)
        btnDim = findViewById(R.id.btn_dim)

        dimLevel = prefs.getInt("dim_level", 0)

        findViewById<TextView>(R.id.btn_hdmi).setOnClickListener { showHdmiMenu() }
        findViewById<TextView>(R.id.btn_ram_clean).setOnClickListener { cleanRam() }
        btnDim.setOnClickListener { cycleBrightness() }
        findViewById<TextView>(R.id.btn_blackout).setOnClickListener { toggleBlackScreen(true) }
        findViewById<TextView>(R.id.btn_tools).setOnClickListener { showToolsMenu() }

        adapter = AppAdapter()
        grid.adapter = adapter

        grid.setOnItemClickListener { _, _, position, _ -> launchPackage(apps[position].packageName) }
        grid.setOnItemLongClickListener { _, _, position, _ -> showAppOptions(apps[position]); true }

        applyAppearance()
        applyBrightness()
        loadApps()
        grid.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updateRamInfo()
        updateNetworkInfo()
        resetIdleTimer()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(shiftRunnable)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isScreensaverActive) {
            if (event.action == KeyEvent.ACTION_DOWN) showScreensaver(false)
            return true
        }
        if (isBlackScreen) {
            if (event.action == KeyEvent.ACTION_DOWN) toggleBlackScreen(false)
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            resetIdleTimer()
            if (event.repeatCount == 0 && handleColorKey(event.keyCode)) return true

            val debounceMs = prefs.getInt("debounce_ms", 140)
            if (debounceMs > 0) {
                val now = SystemClock.uptimeMillis()
                if (event.repeatCount == 0) {
                    if (event.keyCode == lastKeyCode && (now - lastKeyTime) < debounceMs) return true
                } else if ((now - lastKeyTime) < 200L) {
                    return true
                }
                lastKeyCode = event.keyCode
                lastKeyTime = now
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleColorKey(keyCode: Int): Boolean {
        val prefKey = when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> "color_red"
            KeyEvent.KEYCODE_PROG_GREEN -> "color_green"
            KeyEvent.KEYCODE_PROG_YELLOW -> "color_yellow"
            KeyEvent.KEYCODE_PROG_BLUE -> "color_blue"
            else -> return false
        }
        val assignedPkg = prefs.getString(prefKey, null)
        if (!assignedPkg.isNullOrEmpty()) {
            launchPackage(assignedPkg)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> showHdmiMenu()
            KeyEvent.KEYCODE_PROG_GREEN -> cleanRam()
            KeyEvent.KEYCODE_PROG_YELLOW -> cycleBrightness()
            KeyEvent.KEYCODE_PROG_BLUE -> showScreensaver(true)
        }
        return true
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleRunnable)
        val mins = prefs.getInt("screensaver_min", 5)
        if (mins > 0 && !isBlackScreen && !isScreensaverActive) {
            handler.postDelayed(idleRunnable, mins * 60_000L)
        }
    }

    private fun showScreensaver(enable: Boolean) {
        isScreensaverActive = enable
        handler.removeCallbacks(shiftRunnable)
        if (enable) {
            screensaverOverlay.visibility = View.VISIBLE
            screensaverClockBox.translationX = 0f
            screensaverClockBox.translationY = 0f
            handler.postDelayed(shiftRunnable, 60_000L)
        } else {
            screensaverOverlay.visibility = View.GONE
            resetIdleTimer()
        }
    }

    override fun onBackPressed() {
        if (isScreensaverActive) { showScreensaver(false); return }
        if (isBlackScreen) { toggleBlackScreen(false); return }
        if (showHidden) { showHidden = false; loadApps() }
    }

    private fun launchPackage(pkg: String) {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) openIntent(launch)
    }

    private fun cycleBrightness() {
        dimLevel = (dimLevel + 1) % 3
        prefs.edit().putInt("dim_level", dimLevel).apply()
        applyBrightness()
    }

    private fun applyBrightness() {
        if (isBlackScreen) return
        dimOverlay.alpha = when (dimLevel) { 1 -> 0.35f; 2 -> 0.62f; else -> 0.0f }
        btnDim.text = when (dimLevel) { 1 -> "Parlak: %70"; 2 -> "Parlak: %40"; else -> "Parlaklık" }
        val lp = window.attributes
        lp.screenBrightness = when (dimLevel) { 1 -> 0.6f; 2 -> 0.25f; else -> -1.0f }
        window.attributes = lp
    }

    private fun toggleBlackScreen(enable: Boolean) {
        isBlackScreen = enable
        if (enable) {
            handler.removeCallbacks(idleRunnable)
            dimOverlay.alpha = 1.0f
        } else {
            applyBrightness()
            resetIdleTimer()
        }
    }

    private fun updateNetworkInfo() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> "Çevrimdışı"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> "Ağ"
        }
        val ip = getLocalIpAddress()
        netText.text = if (ip != null && type != "Çevrimdışı") "$type  $ip" else type
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun runPingTest() {
        Toast.makeText(this, "Ağ test ediliyor...", Toast.LENGTH_SHORT).show()
        Thread {
            val localIp = getLocalIpAddress() ?: "Yok"
            val gateway = if (localIp.contains(".")) localIp.substringBeforeLast(".") + ".1" else "192.168.1.1"
            val gwPing = measureTcpOrPing(gateway, 80)
            val cfPing = measureTcpOrPing("1.1.1.1", 53)
            val googlePing = measureTcpOrPing("8.8.8.8", 53)
            val report = "TV IP: $localIp\n" +
                "Modem ($gateway): $gwPing\n" +
                "Cloudflare (1.1.1.1): $cfPing\n" +
                "Google (8.8.8.8): $googlePing"
            runOnUiThread {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Ağ / Ping Testi")
                    .setMessage(report)
                    .setPositiveButton("Tamam", null)
                    .show()
            }
        }.start()
    }

    private fun measureTcpOrPing(host: String, port: Int): String {
        return try {
            val start = SystemClock.elapsedRealtime()
            Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
            "${SystemClock.elapsedRealtime() - start} ms"
        } catch (_: Exception) {
            try {
                val start = SystemClock.elapsedRealtime()
                val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/ping", "-c", "1", "-W", "2", host))
                if (proc.waitFor() == 0) "${SystemClock.elapsedRealtime() - start} ms" else "Erişilemiyor"
            } catch (_: Exception) { "Erişilemiyor" }
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
        for (appInfo in packageManager.getInstalledApplications(0)) {
            val pkg = appInfo.packageName
            if (pkg != packageName && !pkg.startsWith("com.android.systemui")) {
                try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
            }
        }
        try { cacheDir.deleteRecursively() } catch (_: Exception) {}
        System.gc()

        ramText.postDelayed({
            val (afterFree, totalMb) = getFreeRamMb()
            ramText.text = "RAM: $afterFree / $totalMb MB"
            val diff = afterFree - beforeFree
            val msg = if (diff > 0) "RAM temizlendi (+${diff} MB)" else "RAM güncel ($afterFree MB boş)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }, 350)
    }

    private fun showToolsMenu() {
        val hiddenLabel = if (showHidden) "Gizli Uygulamaları Sakla" else "Gizli Uygulamaları Göster"
        val options = arrayOf(
            "Görünüm, Ekran Koruyucu & Kumanda",
            "Renkli Tuş Kısayolları",
            "Ağ / Ping Testi",
            "Saatli Ekran Koruyucuyu Başlat",
            "Önbellek / Depolama Ayarları",
            "Geliştirici Seçenekleri",
            "Sistem Ayarları",
            hiddenLabel
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Araçlar")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showThemeMenu()
                    1 -> showColorKeyMenu()
                    2 -> runPingTest()
                    3 -> showScreensaver(true)
                    4 -> openIntent(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_APPLICATION_SETTINGS))
                    5 -> openIntent(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                    6 -> openIntent(Intent(Settings.ACTION_SETTINGS))
                    7 -> { showHidden = !showHidden; loadApps() }
                }
            }.show()
    }

    private fun showColorKeyMenu() {
        val keys = arrayOf("color_red", "color_green", "color_yellow", "color_blue")
        val names = arrayOf("Kırmızı Tuş", "Yeşil Tuş", "Sarı Tuş", "Mavi Tuş")
        val defaults = arrayOf("Varsayılan (HDMI)", "Varsayılan (RAM)", "Varsayılan (Parlaklık)", "Varsayılan (Saat)")
        val items = Array(4) { i ->
            val pkg = prefs.getString(keys[i], null)
            val label = apps.firstOrNull { it.packageName == pkg }?.label ?: defaults[i]
            "${names[i]}: $label"
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Renkli Tuş Kısayolları")
            .setItems(items) { _, i -> pickAppForColorKey(keys[i], names[i]) }
            .show()
    }

    private fun pickAppForColorKey(prefKey: String, title: String) {
        val labels = mutableListOf("Varsayılana Sıfırla")
        labels.addAll(apps.map { it.label })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$title Seç")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) prefs.edit().remove(prefKey).apply()
                else prefs.edit().putString(prefKey, apps[which - 1].packageName).apply()
            }.show()
    }

    private fun showHdmiMenu() {
        val tvManager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val inputs = tvManager?.tvInputList?.filter { it.isPassthroughInput } ?: emptyList()
        if (inputs.isEmpty()) {
            openIntent(Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI))
            return
        }
        val labels = inputs.map { it.loadLabel(this).toString() }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("HDMI / Giriş Seç")
            .setItems(labels) { _, which ->
                openIntent(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputs[which].id)))
            }.show()
    }

    private fun showThemeMenu() {
        val simpleIcon = prefs.getBoolean("simple_icon", false)
        val debounceMs = prefs.getInt("debounce_ms", 140)
        val ssMin = prefs.getInt("screensaver_min", 5)
        val options = arrayOf(
            "Sütun Sayısı (${prefs.getInt("cols", 5)})",
            "Sade İkon Modu: ${if (simpleIcon) "Açık (Az RAM)" else "Kapalı (Banner)"}",
            "Ekran Koruyucu Süresi: ${if (ssMin == 0) "Kapalı" else "$ssMin dk"}",
            "Kumanda Filtresi: ${if (debounceMs == 0) "Kapalı" else "${debounceMs} ms"}",
            "Arka Plan Rengi",
            "Seçim Çerçevesi Rengi"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Görünüm & Kumanda")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickColumns()
                    1 -> { prefs.edit().putBoolean("simple_icon", !simpleIcon).apply(); loadApps() }
                    2 -> pickScreensaverTime()
                    3 -> pickDebounce()
                    4 -> pickBgColor()
                    5 -> pickAccentColor()
                }
            }.show()
    }

    private fun pickScreensaverTime() {
        val labels = arrayOf("Kapalı", "2 Dakika", "5 Dakika", "10 Dakika")
        val vals = intArrayOf(0, 2, 5, 10)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Ekran Koruyucu Süresi")
            .setItems(labels) { _, w -> prefs.edit().putInt("screensaver_min", vals[w]).apply(); resetIdleTimer() }.show()
    }

    private fun pickDebounce() {
        val labels = arrayOf("Kapalı", "Hafif (120 ms)", "Standart (140 ms)", "Güçlü (200 ms)")
        val vals = intArrayOf(0, 120, 140, 200)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Çift Basma Filtresi")
            .setItems(labels) { _, w -> prefs.edit().putInt("debounce_ms", vals[w]).apply() }.show()
    }

    private fun pickColumns() {
        val cols = arrayOf("4 Sütun (Büyük)", "5 Sütun (Standart)", "6 Sütun (Kompakt)")
        val vals = intArrayOf(4, 5, 6)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sütun Sayısı")
            .setItems(cols) { _, w -> prefs.edit().putInt("cols", vals[w]).apply(); applyAppearance() }.show()
    }

    private fun pickBgColor() {
        val names = arrayOf("Saf Siyah (En Hafif)", "Koyu Gri", "Gece Mavisi", "Antrasit")
        val hexes = arrayOf("#000000", "#0B0D10", "#09111F", "#141414")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Arka Plan")
            .setItems(names) { _, w -> prefs.edit().putString("bg_color", hexes[w]).apply(); applyAppearance() }.show()
    }

    private fun pickAccentColor() {
        val names = arrayOf("Mavi", "Yeşil", "Turuncu", "Beyaz")
        val hexes = arrayOf("#3B82F6", "#10B981", "#F59E0B", "#F3F4F6")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Seçim Çerçevesi")
            .setItems(names) { _, w -> prefs.edit().putString("accent_color", hexes[w]).apply(); applyAppearance() }.show()
    }

    private fun applyAppearance() {
        grid.numColumns = prefs.getInt("cols", 5)
        rootLayout.setBackgroundColor(Color.parseColor(prefs.getString("bg_color", "#0B0D10") ?: "#0B0D10"))
        grid.selector = buildFocusSelector(Color.parseColor(prefs.getString("accent_color", "#3B82F6") ?: "#3B82F6"))
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
        try { startActivity(primary) } catch (_: Exception) {
            if (fallback != null) try { startActivity(fallback) } catch (_: Exception) {}
        }
    }

    private fun getSet(key: String): MutableSet<String> = HashSet(prefs.getStringSet(key, emptySet()) ?: emptySet())
    private fun saveSet(key: String, set: Set<String>) { prefs.edit().putStringSet(key, set).apply() }

    private fun loadApps() {
        val pm = packageManager
        val pinnedSet = getSet("pinned")
        val hiddenSet = getSet("hidden")
        val simpleIcon = prefs.getBoolean("simple_icon", false)
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
                val banner = if (simpleIcon) null else (info.activityInfo.loadBanner(pm) ?: info.activityInfo.applicationInfo.loadBanner(pm))
                temp.add(AppItem(info.loadLabel(pm).toString(), pkg, info.loadIcon(pm), banner, pinnedSet.contains(pkg), isHidden))
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
            "Uygulama Bilgisi (Önbellek / Zorla Durdur)"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { val s = getSet("pinned"); if (!s.add(app.packageName)) s.remove(app.packageName); saveSet("pinned", s); loadApps() }
                    1 -> { val s = getSet("hidden"); if (!s.add(app.packageName)) s.remove(app.packageName); saveSet("hidden", s); loadApps() }
                    2 -> openIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${app.packageName}") })
                }
            }.show()
    }

    inner class AppAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(p: Int) = apps[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_app, parent, false)
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
            badgeView.text = when { app.isHidden -> "GİZLİ"; app.isPinned -> "★"; else -> "" }
            badgeView.visibility = if (badgeView.text.isEmpty()) View.GONE else View.VISIBLE
            view.alpha = if (app.isHidden) 0.45f else 1.0f
            return view
        }
    }
}
