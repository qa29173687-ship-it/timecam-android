package tw.timecam.upload

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Google Drive REST（drive.file）。不依賴 google-api-client，以免加大 APK。 */
class DriveClient(private val token: String) {
    fun findOrCreateFolder(name: String, knownId: String?): String {
        if (!knownId.isNullOrBlank() && folderExists(knownId)) return knownId
        val q = "mimeType='application/vnd.google-apps.folder' and name='${esc(name)}' and trashed=false"
        val list = get("https://www.googleapis.com/drive/v3/files?spaces=drive&fields=files(id,name)&q=" + URLEncoder.encode(q, "UTF-8"))
        val files = list.optJSONArray("files")
        if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")
        val body = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
        val created = postJson("https://www.googleapis.com/drive/v3/files?fields=id", body)
        return created.getString("id")
    }

    fun upload(folderId: String, name: String, mime: String, file: File): String {
        return if (file.length() >= 5L * 1024 * 1024) resumable(folderId, name, mime, file)
        else multipart(folderId, name, mime, file)
    }

    private fun folderExists(id: String): Boolean = try {
        get("https://www.googleapis.com/drive/v3/files/$id?fields=id,trashed")
        true
    } catch (_: Exception) { false }

    private fun multipart(folderId: String, name: String, mime: String, file: File): String {
        val boundary = "timecam_" + System.currentTimeMillis()
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(folderId)).toString()
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: $mime\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setFixedLengthStreamingMode(head.size + file.length() + tail.size)
            connectTimeout = 30_000; readTimeout = 120_000
        }
        conn.outputStream.use { out ->
            out.write(head)
            file.inputStream().use { it.copyTo(out) }
            out.write(tail)
            out.flush()
        }
        return readJson(conn).getString("id")
    }

    private fun resumable(folderId: String, name: String, mime: String, file: File): String {
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(folderId))
        val init = (URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", mime)
            setRequestProperty("X-Upload-Content-Length", file.length().toString())
            connectTimeout = 30_000; readTimeout = 30_000
        }
        init.outputStream.use { it.write(meta.toString().toByteArray(StandardCharsets.UTF_8)) }
        if (init.responseCode !in 200..299) throw apiErr(init)
        val session = init.getHeaderField("Location") ?: throw IllegalStateException("雲端沒有回傳上傳位置")
        init.disconnect()
        val put = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", mime)
            setFixedLengthStreamingMode(file.length())
            connectTimeout = 30_000; readTimeout = 300_000
        }
        put.outputStream.use { out -> file.inputStream().use { it.copyTo(out) }; out.flush() }
        return readJson(put).getString("id")
    }

    private fun get(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20_000; readTimeout = 20_000
        }
        return readJson(c)
    }
    private fun postJson(url: String, body: JSONObject): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connectTimeout = 20_000; readTimeout = 20_000
        }
        c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        return readJson(c)
    }
    private fun readJson(c: HttpURLConnection): JSONObject {
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (!ok) throw IllegalStateException(driveErr(c.responseCode, text))
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
    private fun apiErr(c: HttpURLConnection): IllegalStateException {
        val text = (c.errorStream ?: c.inputStream)?.bufferedReader()?.readText().orEmpty()
        return IllegalStateException(driveErr(c.responseCode, text))
    }
    private fun driveErr(code: Int, text: String): String {
        if (code == 401) return "Google 授權已過期，請重新連結"
        val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
        return msg?.takeIf { it.isNotBlank() } ?: "雲端硬碟回應 $code"
    }
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
}
