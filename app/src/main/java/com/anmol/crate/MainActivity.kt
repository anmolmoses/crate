package com.anmol.crate

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import kotlin.math.abs
import kotlin.random.Random

class MainActivity : Activity(), CrateView.Listener {

    private lateinit var albums: MutableList<Album>
    private lateinit var crate: CrateView
    private lateinit var wave: WaveformView
    private lateinit var bgA: ImageView
    private lateinit var bgB: ImageView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var chip: TextView
    private lateinit var playBtn: ImageView
    private lateinit var bookmarkBtn: ImageView
    private lateinit var player: PreviewPlayer

    private var current = 0
    private var shownBackdrop: Album? = null
    private var picking = false
    private val prefs by lazy { getSharedPreferences("crate", Context.MODE_PRIVATE) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()

        albums = Catalog.loadBundled(this)
        player = PreviewPlayer(this)
        player.onStateChanged = { playing ->
            playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            wave.playing = playing
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        bgA = backdropView().also { root.addView(it, match()) }
        bgB = backdropView().also { root.addView(it, match()); it.alpha = 0f }
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x8C000000.toInt(), 0x33000000, 0x40000000, 0xD9000000.toInt())
            )
        }, match())

        crate = CrateView(this)
        crate.albums = albums
        crate.listener = this
        root.addView(crate, match())

        val top = buildTopBar()
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        wave = WaveformView(this, crate)
        crate.onPositionChanged = { wave.position = it }
        val bottom = buildBottomBar()
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val (t, b) = systemBarInsets(insets)
            top.setPadding(dpi(16f), t + dpi(8f), dpi(16f), 0)
            bottom.setPadding(dpi(16f), 0, dpi(16f), b + dpi(14f))
            insets
        }
        setContentView(root)

        current = savedInstanceState?.getInt("current") ?: 8
        crate.setPositionImmediate(current.toFloat())
        Catalog.prepare(this, albums) { album -> onArtReady(album) }

        // Entrance: the rack rises into place, then the chrome fades in.
        crate.playIntro()
        for (v in listOf(top, bottom)) {
            v.alpha = 0f
            v.animate().alpha(1f).setStartDelay(350).setDuration(700).start()
        }
        crate.postDelayed({ if (!picking) playCurrent() }, 1300)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("current", current)
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    // ---- Views ------------------------------------------------------------------------

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun backdropView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        scaleX = 1.3f; scaleY = 1.3f
        if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(dp(40f), dp(40f), Shader.TileMode.CLAMP))
    }

    private fun glassButton(icon: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        val pad = dpi(11f)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dpi(42f), dpi(42f))
        setOnClickListener { onClick() }
    }

    private fun bold(sizeSp: Float) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = sizeSp
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun buildTopBar(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        row.addView(glassButton(R.drawable.ic_search) { showSearch() })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        title = bold(23f).apply { maxLines = 2; setLineSpacing(0f, 0.95f); letterSpacing = -0.01f }
        meta = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt()); textSize = 12.5f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        }
        chip = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x2EFFFFFF) }
            setPadding(dpi(10f), dpi(5f), dpi(12f), dpi(5f))
            compoundDrawablePadding = dpi(5f)
            maxLines = 1
            visibility = View.INVISIBLE
        }
        col.addView(title, LinearLayout.LayoutParams(-1, -2))
        col.addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(5f) })
        col.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(10f) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(8f); rightMargin = dpi(8f); topMargin = dpi(2f) })

        playBtn = glassButton(R.drawable.ic_play) { player.toggle() }
        row.addView(playBtn)
        return row
    }

    private fun buildBottomBar(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(wave, LinearLayout.LayoutParams(-1, dpi(58f)).apply { bottomMargin = dpi(14f) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bookmarkBtn = glassButton(R.drawable.ic_bookmark_border) { toggleFavorite() }
        row.addView(bookmarkBtn)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val pick = TextView(this).apply {
            text = "Pick for me"
            textSize = 15f
            setTextColor(0xFF15171A.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(26f); setColor(0xF2EEF0F3.toInt()) }
            val icon = getDrawable(R.drawable.ic_shuffle)!!.mutate().apply {
                setTint(0xFF15171A.toInt()); setBounds(0, 0, dpi(18f), dpi(18f))
            }
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dpi(8f)
            setPadding(dpi(22f), 0, dpi(24f), 0)
            setOnClickListener { pickForMe() }
        }
        row.addView(pick, LinearLayout.LayoutParams(-2, dpi(48f)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        lateinit var tune: View
        tune = glassButton(R.drawable.ic_tune) { showSortMenu(tune) }
        row.addView(tune)
        col.addView(row)
        return col
    }

    // ---- State ------------------------------------------------------------------------

    private fun onArtReady(album: Album) {
        crate.invalidate()
        if (albums.getOrNull(current) === album) showBackdrop(album, animate = shownBackdrop != null)
    }

    override fun onCenterChanged(index: Int, fromUser: Boolean) {
        current = index
        val album = albums[index]
        if (!picking) showInfo(album)
        showBackdrop(album, animate = true)
        // Warm the cache around the selection so the next change plays instantly.
        for (k in index - 1..index + 1) albums.getOrNull(k)?.let { Catalog.prefetch(this, it.previewUrl) }
    }

    override fun onSettled(index: Int) {
        if (picking) {
            picking = false
            setTitleBlur(false)
            showInfo(albums[index])
            setChip("Tonight's pick", R.drawable.ic_sparkle)
        }
        playCurrent()
    }

    override fun onSelectedTapped() {
        player.toggle()
    }

    private fun playCurrent() {
        val album = albums.getOrNull(current) ?: return
        player.play(album.previewUrl)
        if (!picking && chip.text?.startsWith("Tonight") != true) setChip(album.track, R.drawable.ic_play)
    }

    private fun showInfo(album: Album) {
        title.text = album.title
        meta.text = album.meta
        title.alpha = 0.55f
        title.animate().alpha(1f).setDuration(220).start()
        bookmarkBtn.setImageResource(if (isFavorite(album)) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        chip.visibility = View.INVISIBLE
        chip.text = ""
    }

    private fun setChip(text: String, icon: Int) {
        chip.text = text
        val d = getDrawable(icon)!!.mutate().apply { setBounds(0, 0, dpi(12f), dpi(12f)) }
        chip.setCompoundDrawables(d, null, null, null)
        if (chip.visibility != View.VISIBLE) {
            chip.visibility = View.VISIBLE
            chip.alpha = 0f
            chip.translationY = -dp(4f)
            chip.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
    }

    private fun showBackdrop(album: Album, animate: Boolean) {
        val bmp = album.backdrop ?: return
        if (shownBackdrop === album) return
        shownBackdrop = album
        if (!animate) {
            bgA.setImageBitmap(bmp); bgA.alpha = 1f; bgB.alpha = 0f
            return
        }
        // Crossfade: the incoming image fades in on top, then becomes the base layer.
        bgB.animate().cancel()
        bgB.setImageBitmap(bmp)
        bgB.alpha = 0f
        bgB.animate().alpha(1f).setDuration(380).withEndAction {
            bgA.setImageBitmap(bmp)
            bgB.alpha = 0f
        }.start()
    }

    private fun setTitleBlur(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val r = if (on) dp(7f) else 0f
            val fx = if (on) RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) else null
            title.setRenderEffect(fx); meta.setRenderEffect(fx)
        }
        title.animate().alpha(if (on) 0.45f else 1f).setDuration(200).start()
        meta.animate().alpha(if (on) 0.45f else 1f).setDuration(200).start()
    }

    // ---- Actions ----------------------------------------------------------------------

    private fun pickForMe() {
        if (albums.size < 2) return
        // Always spin forward far enough to feel like a real shuffle.
        val steps = if (albums.size > 12) Random.nextInt(8, 8 + albums.size) else Random.nextInt(1, albums.size)
        picking = true
        setTitleBlur(true)
        chip.visibility = View.INVISIBLE
        setChip("Picking…", R.drawable.ic_sparkle)
        crate.spinBy(steps, (1300 + steps * 25L).coerceAtMost(2400), DecelerateInterpolator(2.4f))
    }

    private fun isFavorite(a: Album) = prefs.getStringSet("fav", emptySet())!!.contains(a.key)

    private fun toggleFavorite() {
        val a = albums.getOrNull(current) ?: return
        val set = HashSet(prefs.getStringSet("fav", emptySet())!!)
        if (!set.remove(a.key)) set.add(a.key)
        prefs.edit().putStringSet("fav", set).apply()
        bookmarkBtn.setImageResource(if (isFavorite(a)) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        bookmarkBtn.animate().scaleX(1.18f).scaleY(1.18f).setDuration(110).withEndAction {
            bookmarkBtn.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
    }

    private fun showSortMenu(anchor: View) {
        val menu = PopupMenu(this, anchor, Gravity.END)
        val options = listOf("Rack order", "Newest first", "Oldest first", "Artist A–Z", "Saved first")
        options.forEachIndexed { i, s -> menu.menu.add(0, i, i, s) }
        menu.setOnMenuItemClickListener { item ->
            val keep = albums[current]
            val bundledOrder = Catalog.loadBundled(this).map { it.key }
            val sorted = when (item.itemId) {
                1 -> albums.sortedByDescending { it.year }
                2 -> albums.sortedBy { it.year }
                3 -> albums.sortedBy { it.artist.removePrefix("The ").lowercase() }
                4 -> albums.sortedByDescending { isFavorite(it) }
                else -> albums.sortedBy { bundledOrder.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            }
            albums.clear(); albums.addAll(sorted)
            crate.albums = albums
            crate.setPositionImmediate(albums.indexOf(keep).toFloat())
            true
        }
        menu.show()
    }

    private fun showSearch() {
        val input = EditText(this).apply {
            hint = "Album or artist"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Find music")
            .setView(box)
            .setPositiveButton("Search") { _, _ -> runSearch(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> dialog.dismiss(); runSearch(input.text.toString()); true }
        dialog.show()
        input.requestFocus()
    }

    private fun runSearch(raw: String) {
        val q = raw.trim().lowercase()
        if (q.isEmpty()) return
        val local = albums.indexOfFirst { q in it.title.lowercase() || q in it.artist.lowercase() }
        if (local >= 0) {
            crate.animateTo(local, (350 + 40 * abs(local - current)).toLong().coerceAtMost(1600))
            return
        }
        setChip("Searching…", R.drawable.ic_search)
        Catalog.search(raw.trim()) { found ->
            val fresh = found.filter { f -> albums.none { it.key == f.key } }
            if (fresh.isEmpty()) {
                setChip("Nothing found", R.drawable.ic_search)
                return@search
            }
            // New albums slot in right after the current one.
            val at = current + 1
            albums.addAll(at, fresh)
            crate.albums = albums
            crate.setPositionImmediate(current.toFloat())
            Catalog.prepare(this, fresh) { a -> onArtReady(a) }
            crate.animateTo(at, 600)
        }
    }

    // ---- Window -----------------------------------------------------------------------

    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun systemBarInsets(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars())
            i.top to i.bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }
}
