package com.sanadoy.croptop

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object Gemini {
    fun ask(key: String, bmp: Bitmap, cb: (String) -> Unit) {
        thread {
            val out = try {
                val bo = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo)
                val b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", "Jawab soal pada gambar ini dalam bahasa Indonesia. Berikan jawaban akhir lebih dulu, lalu penjelasan singkat."))
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
            cb(out)
        }
    }
}
