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
    private fun call(key: String, body: JSONObject, model: String): Pair<Int, String> {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        return Pair(c.responseCode, txt)
    }

    fun ask(key: String, bmp: Bitmap, cb: (String) -> Unit) {
        thread {
            val out = try {
                val k = minOf(1f, 1024f / maxOf(bmp.width, bmp.height))
                val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
                val bo = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 80, bo)
                val b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                fun body(fast: Boolean): JSONObject {
                    val o = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("text", "Jawab soal pada gambar. Tulis jawaban akhir dulu, lalu penjelasan sangat singkat. Bahasa Indonesia."))
                        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
                    val g = JSONObject().put("maxOutputTokens", 800)
                    if (fast) g.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    return o.put("generationConfig", g)
                }
                var r = Pair(0, "")
                loop@ for (m in listOf("gemini-flash-latest", "gemini-flash-lite-latest")) {
                    for (i in 0..1) {
                        r = call(key, body(true), m)
                        if (r.first == 400) r = call(key, body(false), m)
                        if (r.first !in listOf(500, 503, 429)) break@loop
                        Thread.sleep(1500)
                    }
                }
                if (r.first !in 200..299) "Error ${r.first}: ${r.second}"
                else JSONObject(r.second).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            } catch (e: Exception) { "Gagal: ${e.message}" }
            cb(out)
        }
    }
}
