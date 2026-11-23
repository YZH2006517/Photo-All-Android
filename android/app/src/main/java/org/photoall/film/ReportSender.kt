package org.photoall.film

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A problem report to the developer's cloud function (cloud/report), when the user sends it.
 * Nothing else ever leaves the phone: no photos, no automatic upload. The address comes from
 * local.properties (report.url) at build time; a build without it has no send button.
 */
object ReportSender {
    val enabled: Boolean get() = BuildConfig.REPORT_URL.startsWith("http")

    /** What the function takes, with room under its 64 KB. */
    private const val MAX_BYTES = 60_000

    /** Blocking (a worker thread): the report's number, or the reason it did not go. */
    fun send(title: String, text: String, fatal: Boolean): Result<String> = runCatching {
        fun json(t: String) = JSONObject()
            .put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("title", title)
            .put("fatal", fatal)
            .put("text", t)
            .toString().toByteArray(Charsets.UTF_8)
        // the function takes 64 KB at most, counted in bytes: a Chinese log is three bytes a
        // character, so a long one was refused whole (413) and every send failed. Too long, it
        // keeps its head (the phone, the settings) and its tail (the latest errors).
        var body = json(text)
        var keep = text.length
        while (body.size > MAX_BYTES && keep > 2000) {
            keep = (keep * MAX_BYTES.toDouble() / body.size * 0.95).toInt()
            val head = keep / 3
            val cut = "\n\n……（中间 ${text.length - keep} 字过长未发送）……\n\n"
            body = json(text.take(head) + cut + text.takeLast(keep - head))
        }
        val c = URL(BuildConfig.REPORT_URL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (BuildConfig.REPORT_TOKEN.isNotEmpty()) c.setRequestProperty("X-PhotoAll-Token", BuildConfig.REPORT_TOKEN)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val answer = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            when (code) {
                in 200..299 -> JSONObject(answer).getString("id")
                429 -> error("今天发送的报告太多了，请改用复制")
                413 -> error("报告太长")
                else -> error("服务器没有收下（$code）")
            }
        } finally {
            c.disconnect()
        }
    }
}
