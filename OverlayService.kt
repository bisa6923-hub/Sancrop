package com.sanadoy.croptop

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.widget.*

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var button: ImageView? = null
    private var cropRoot: View? = null
    private var resultRoot: View? = null
    private var sw = 0
    private var sh = 0
    private var dens = 1f

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (projection != null || intent == null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sc", "Sanadoy Croptop", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "sc")
            .setContentTitle("Sanadoy Croptop aktif")
            .setContentText("Tombol melayang berjalan. Tahan logo 1 detik atau tekan Tutup.")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tutup",
                PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels; sh = dm.heightPixels; dens = dm.density

        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, h)
        projection = mp
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        vd = mp.createVirtualDisplay("sc", sw, sh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, h)
        showButton()
        return START_NOT_STICKY
    }

    private fun overlayParams(w: Int, hh: Int, flags: Int) = WindowManager.LayoutParams(
        w, hh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)

    private fun showButton() {
        val size = (56 * dens).toInt()
        val lp = overlayParams(size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 20; lp.y = (sh * 0.3).toInt()
        val v = ImageView(this)
        v.setImageResource(R.mipmap.ic_launcher)
        v.alpha = 0.92f
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false; var t0 = 0L
        val closeRun = Runnable {
            if (!moved) { Toast.makeText(this, "Tombol ditutup", Toast.LENGTH_SHORT).show(); stopSelf() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false
                    t0 = System.currentTimeMillis(); h.postDelayed(closeRun, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - dx; val my = e.rawY - dy
                    if (Math.abs(mx) > 25 || Math.abs(my) > 25) { moved = true; h.removeCallbacks(closeRun) }
                    if (moved) { lp.x = (sx + mx).toInt(); lp.y = (sy + my).toInt(); wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    h.removeCallbacks(closeRun)
                    if (!moved && System.currentTimeMillis() - t0 < 700) capture()
                }
                MotionEvent.ACTION_CANCEL -> h.removeCallbacks(closeRun)
            }
            true
        }
        wm.addView(v, lp)
        button = v
    }

    private fun capture() {
        removeResult()
        button?.visibility = View.INVISIBLE
        h.postDelayed({ grab(true) }, 350)
    }

    private fun grab(retry: Boolean) {
        val img = reader?.acquireLatestImage()
        if (img == null) {
            if (retry) { h.postDelayed({ grab(false) }, 400); return }
            button?.visibility = View.VISIBLE
            Toast.makeText(this, "Gagal mengambil layar, coba lagi", Toast.LENGTH_SHORT).show()
            return
        }
        val p = img.planes[0]
        val rowPad = p.rowStride - p.pixelStride * sw
        val wide = Bitmap.createBitmap(sw + rowPad / p.pixelStride, sh, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(p.buffer)
        img.close()
        showCrop(Bitmap.createBitmap(wide, 0, 0, sw, sh))
    }

    inner class CropView(ctx: Context, val bmp: Bitmap) : View(ctx) {
        var sel: RectF? = null
        private var sx = 0f; private var sy = 0f
        private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        private val line = Paint().apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 4f }
        private val src = Rect(0, 0, bmp.width, bmp.height)
        override fun onDraw(c: Canvas) {
            val dst = Rect(0, 0, width, height)
            c.drawBitmap(bmp, src, dst, null)
            c.drawRect(dst, dim)
            sel?.let {
                c.save(); c.clipRect(it); c.drawBitmap(bmp, src, dst, null); c.restore()
                c.drawRect(it, line)
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; sel = RectF(sx, sy, sx, sy) }
                MotionEvent.ACTION_MOVE -> sel = RectF(minOf(sx, e.x), minOf(sy, e.y), maxOf(sx, e.x), maxOf(sy, e.y))
            }
            invalidate()
            return true
        }
        fun cropped(): Bitmap? {
            val r = sel ?: return null
            val kx = bmp.width.toFloat() / width; val ky = bmp.height.toFloat() / height
            val x = (r.left * kx).toInt().coerceIn(0, bmp.width - 1)
            val y = (r.top * ky).toInt().coerceIn(0, bmp.height - 1)
            val w = ((r.right - r.left) * kx).toInt().coerceAtMost(bmp.width - x)
            val hh = ((r.bottom - r.top) * ky).toInt().coerceAtMost(bmp.height - y)
            if (w < 30 || hh < 30) return null
            return Bitmap.createBitmap(bmp, x, y, w, hh)
        }
    }

    private fun showCrop(bmp: Bitmap) {
        val root = FrameLayout(this)
        val cv = CropView(this, bmp)
        root.addView(cv, FrameLayout.LayoutParams(-1, -1))
        val hint = TextView(this).apply {
            text = "Geser jari untuk memilih area soal"
            setTextColor(Color.WHITE); setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(24, 16, 24, 16)
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (48 * dens).toInt() })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val cancel = Button(this).apply { text = "Batal" }
        val ok = Button(this).apply { text = "Jawab" }
        bar.addView(cancel); bar.addView(ok)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = (40 * dens).toInt() })
        cancel.setOnClickListener { removeCrop(); button?.visibility = View.VISIBLE }
        ok.setOnClickListener {
            val c = cv.cropped()
            if (c == null) { Toast.makeText(this, "Pilih area soal dulu", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            removeCrop(); button?.visibility = View.VISIBLE
            val key = getSharedPreferences("p", MODE_PRIVATE).getString("key", "") ?: ""
            if (key.isEmpty()) { showResult("Isi API key dulu di aplikasi Sanadoy Croptop."); return@setOnClickListener }
            showResult("Memproses...")
            Gemini.ask(key, c) { t -> h.post { showResult(t) } }
        }
        wm.addView(root, overlayParams(-1, -1,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS))
        cropRoot = root
    }

    private fun showResult(text: String) {
        removeResult()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 20, 24, 40)); setPadding(32, 24, 32, 24)
        }
        val tv = TextView(this).apply { this.text = text; setTextColor(Color.WHITE); textSize = 16f }
        val sc = ScrollView(this).apply { addView(tv) }
        box.addView(sc, LinearLayout.LayoutParams(-1, (sh * 0.33).toInt()))
        val close = Button(this).apply { this.text = "Tutup"; setOnClickListener { removeResult() } }
        box.addView(close)
        val lp = overlayParams(-1, -2, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        lp.gravity = Gravity.BOTTOM
        wm.addView(box, lp)
        resultRoot = box
    }

    private fun removeCrop() { cropRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }; cropRoot = null }
    private fun removeResult() { resultRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }; resultRoot = null }

    override fun onDestroy() {
        removeCrop(); removeResult()
        button?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vd?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
