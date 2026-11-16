package com.sanadoy.croptop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.yalantis.ucrop.UCrop
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val PICK = 1
    private lateinit var apiKey: EditText
    private lateinit var preview: ImageView
    private lateinit var answer: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        apiKey = findViewById(R.id.apiKey)
        preview = findViewById(R.id.preview)
        answer = findViewById(R.id.answer)
        progress = findViewById(R.id.progress)
        val sp = getSharedPreferences("p", MODE_PRIVATE)
        apiKey.setText(sp.getString("key", ""))
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            sp.edit().putString("key", apiKey.text.toString().trim()).apply()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Izinkan 'Tampil di atas aplikasi lain', lalu kembali", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 3)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), 2)
        }
        findViewById<Button>(R.id.btnPick).setOnClickListener {
            sp.edit().putString("key", apiKey.text.toString().trim()).apply()
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PICK)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != Activity.RESULT_OK || data == null) return
        if (req == 2) {
            startForegroundService(Intent(this, OverlayService::class.java).putExtra("code", res).putExtra("data", data))
            moveTaskToBack(true)
        } else if (req == PICK) {
            val dest = Uri.fromFile(File(cacheDir, "crop.jpg"))
            UCrop.of(data.data!!, dest).withMaxResultSize(1600, 1600).start(this)
        } else if (req == UCrop.REQUEST_CROP) {
            val uri = UCrop.getOutput(data) ?: return
            val bmp = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
            preview.setImageBitmap(bmp)
            ask(bmp)
        }
    }

    private fun ask(bmp: Bitmap) {
        val key = apiKey.text.toString().trim()
        if (key.isEmpty()) { answer.text = "Isi API key dulu."; return }
        progress.visibility = View.VISIBLE; answer.text = ""
        thread {
            val out = try {
                val bo = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo)
                val b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", "Jawab soal pada gambar ini dalam bahasa Indonesia. Berikan jawaban akhir lebih dulu, lalu penjelasan singkat langkahnya."))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
                val c = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=$key")
                    .openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
                if (c.responseCode !in 200..299) "Error ${c.responseCode}: $txt"
                else JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            } catch (e: Exception) { "Gagal: ${e.message}" }
            runOnUiThread { progress.visibility = View.GONE; answer.text = out }
        }
    }
}
