package com.anmol.crate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class Album(
    val title: String,
    val artist: String,
    val year: Int,
    val genre: String,
    val track: String,
    val previewUrl: String,
    val artUrl: String,
) {
    var art: Bitmap? = null
    /** The jewel-case insert: spine band with vertical title on the left, cover on the right. */
    var insert: Bitmap? = null
    /** Small, heavily downscaled art used for the blurred backdrop. */
    var backdrop: Bitmap? = null
    var tint: Int = Color.rgb(30, 50, 80)

    val key get() = "$artist|$title"
    val meta get() = listOf(artist, year.toString(), genre).filter { it.isNotBlank() }.joinToString("  ·  ")
}

object Catalog {
    const val INSERT_W = 440
    const val INSERT_H = 380
    const val SPINE_FRAC = 0.16f

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun loadBundled(ctx: Context): MutableList<Album> {
        val json = ctx.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Album(
                o.getString("title"), o.getString("artist"), o.getInt("year"),
                o.optString("genre"), o.getString("track"), o.getString("preview"), o.getString("artUrl"),
            )
        }
    }

    /** Downloads (or reads cached) art and builds insert/backdrop bitmaps off the main thread. */
    fun prepare(ctx: Context, albums: List<Album>, onReady: (Album) -> Unit) {
        for (a in albums) {
            if (a.insert != null) continue
            io.execute {
                val art = runCatching {
                    download(ctx, a.artUrl)?.let { BitmapFactory.decodeFile(it.path) }
                }.getOrNull()
                build(a, art)
                main.post { onReady(a) }
            }
        }
    }

    private fun build(a: Album, art: Bitmap?) {
        a.art = art
        if (art != null) {
            val px = Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0)
            a.tint = px
            a.backdrop = Bitmap.createScaledBitmap(art, 24, 24, true)
        } else {
            val h = (a.title.hashCode() and 0xffff) / 65535f * 360f
            a.tint = Color.HSVToColor(floatArrayOf(h, 0.55f, 0.55f))
        }
        a.insert = renderInsert(a, art)
    }

    private fun renderInsert(a: Album, art: Bitmap?): Bitmap {
        val w = INSERT_W
        val h = INSERT_H
        val spine = (w * SPINE_FRAC).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hsv = FloatArray(3)
        Color.colorToHSV(a.tint, hsv)
        hsv[2] = (hsv[2] * 0.28f).coerceAtMost(0.16f)
        hsv[1] = hsv[1] * 0.6f
        c.drawColor(Color.HSVToColor(hsv))

        val dst = Rect(spine, 0, w, h)
        if (art != null) {
            // Center-crop the square art into the remaining area.
            val srcAspect = art.width.toFloat() / art.height
            val dstAspect = dst.width().toFloat() / dst.height()
            val src = if (srcAspect > dstAspect) {
                val sw = (art.height * dstAspect).toInt()
                Rect((art.width - sw) / 2, 0, (art.width + sw) / 2, art.height)
            } else {
                val sh = (art.width / dstAspect).toInt()
                Rect(0, (art.height - sh) / 2, art.width, (art.height + sh) / 2)
            }
            c.drawBitmap(art, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val p = Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a.tint, Color.BLACK, Shader.TileMode.CLAMP)
            }
            c.drawRect(RectF(dst), p)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 34f; typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            c.drawText(a.title, spine + 20f, h - 30f, tp)
        }

        // Thin seam between spine band and artwork.
        c.drawRect(spine - 1f, 0f, spine + 1f, h.toFloat(), Paint().apply { color = 0x33000000 })

        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.06f
        }
        val label = a.title.uppercase()
        tp.textSize = 22f
        val maxLen = h - 80f
        while (tp.measureText(label) > maxLen && tp.textSize > 11f) tp.textSize -= 1f
        c.save()
        c.rotate(-90f)
        // After rotating, x runs up the band and y runs across it.
        c.drawText(label, -h + 70f, spine * 0.5f + tp.textSize * 0.36f, tp)
        c.restore()

        val yp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3FFFFFF.toInt(); textSize = 13f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        c.drawText(a.year.toString(), spine / 2f, h - 22f, yp)
        return bmp
    }

    /** Searches the iTunes catalog and returns one album per collection, with a playable preview. */
    fun search(term: String, cb: (List<Album>) -> Unit) {
        io.execute {
            val found = runCatching {
                val q = URLEncoder.encode(term, "UTF-8")
                val text = httpText("https://itunes.apple.com/search?term=$q&entity=song&limit=40")
                val rows = JSONObject(text).getJSONArray("results")
                val seen = HashSet<Long>()
                val out = ArrayList<Album>()
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    val preview = r.optString("previewUrl")
                    if (preview.isBlank() || !seen.add(r.optLong("collectionId"))) continue
                    out += Album(
                        title = r.optString("collectionName").replace(Regex("\\s*[(\\[].*?[)\\]]"), ""),
                        artist = r.optString("artistName"),
                        year = r.optString("releaseDate").take(4).toIntOrNull() ?: 0,
                        genre = r.optString("primaryGenreName"),
                        track = r.optString("trackName"),
                        previewUrl = preview,
                        artUrl = r.optString("artworkUrl100").replace("100x100bb", "600x600bb"),
                    )
                    if (out.size >= 8) break
                }
                out
            }.getOrDefault(emptyList())
            main.post { cb(found) }
        }
    }

    private fun cacheFile(ctx: Context, url: String): File {
        val dir = File(ctx.cacheDir, "media").apply { mkdirs() }
        return File(dir, Integer.toHexString(url.hashCode()) + "_" + url.substringAfterLast('.').take(4))
    }

    /** Returns a cached copy of [url], downloading it first if needed. Blocking. */
    fun download(ctx: Context, url: String): File? {
        val f = cacheFile(ctx, url)
        if (f.exists() && f.length() > 0) return f
        return runCatching {
            val tmp = File(f.path + ".part")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(f)
            f
        }.getOrNull()
    }

    fun cached(ctx: Context, url: String): File? = cacheFile(ctx, url).takeIf { it.exists() && it.length() > 0 }

    fun prefetch(ctx: Context, url: String) {
        if (cached(ctx, url) == null) io.execute { download(ctx, url) }
    }

    private fun httpText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
