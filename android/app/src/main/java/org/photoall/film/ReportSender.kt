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

    /** Blocking (a worker thread): the report's number, or the reason it did not go. */
    fun send(title: String, text: String, fatal: Boolean): Result<String> = runCatching {
        val body = JSONObject()
            .put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("title", title)
            .put("fatal", fatal)
            .put("text", text.take(60_000))
            .toString().toByteArray(Charsets.UTF_8)
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
                else -> error("服务器没有收下（$code）")
            }
        } finally {
            c.disconnect()
        }
    }
}
