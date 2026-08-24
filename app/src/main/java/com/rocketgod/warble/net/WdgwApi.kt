package com.rocketgod.warble.net

import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

object WdgwApi {

    private const val UPLOAD_URL = "https://wdgwars.pl/api/upload-csv"

    data class Result(val ok: Boolean, val message: String)

    fun upload(apiKey: String, csv: String, filename: String): Result {
        val bytes = csv.toByteArray(Charsets.UTF_8)
        return post(apiKey, filename, bytes.size.toLong()) { out -> out.write(bytes) }
    }

    fun uploadFile(apiKey: String, file: java.io.File, filename: String): Result =
        post(apiKey, filename, file.length()) { out -> file.inputStream().use { it.copyTo(out) } }

    private fun post(apiKey: String, filename: String, bodyLen: Long, writeFileBody: (DataOutputStream) -> Unit): Result {
        if (apiKey.isBlank()) return Result(false, "Missing WDGWars API key")
        val boundary = "----WardriveGo${System.currentTimeMillis()}"
        val preamble = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: text/csv\r\n\r\n"
        val trailer = "\r\n--$boundary--\r\n"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(preamble.length.toLong() + bodyLen + trailer.length.toLong())
                connectTimeout = 30_000
                readTimeout = 600_000
                setRequestProperty("X-API-Key", apiKey)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes(preamble)
                writeFileBody(out)
                out.writeBytes(trailer)
                out.flush()
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            com.rocketgod.warble.usb.BeastDiag.log("WDGW upload HTTP $code: ${body.take(300)}")
            val json = try { org.json.JSONObject(body) } catch (e: Exception) { null }
            val explicitFail = json != null && json.has("ok") && !json.optBoolean("ok", true)
            when {
                code == 401 || code == 403 ->
                    Result(false, "WDGWars rejected the API key (HTTP $code). Check the key in your profile.")
                code !in 200..299 ->
                    Result(false, "WDGWars upload failed (HTTP $code). ${body.take(180)}")
                explicitFail -> {
                    val msg = json?.optString("error")?.ifBlank { null }
                        ?: json?.optString("message")?.ifBlank { null } ?: body.take(180)
                    Result(false, "WDGWars did not accept the file: $msg")
                }
                else -> {

                    val imported = json?.optInt("imported", -1) ?: -1
                    val captured = json?.optInt("captured", -1) ?: -1
                    val detail = if (imported >= 0 || captured >= 0)
                        " (${maxOf(imported, 0)} imported${if (captured >= 0) ", $captured captured" else ""})" else ""
                    Result(true, "Uploaded to WDGWars ✓$detail")
                }
            }
        } catch (e: Exception) {
            Result(false, "WDGWars upload error: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
