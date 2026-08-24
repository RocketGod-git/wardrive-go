package com.rocketgod.warble.net

import android.util.Base64
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

object WigleApi {

    private const val UPLOAD_URL = "https://api.wigle.net/api/v2/file/upload"

    data class Result(val ok: Boolean, val message: String)

    fun upload(apiName: String, apiToken: String, csv: String, filename: String, donate: Boolean): Result {
        val bytes = csv.toByteArray(Charsets.UTF_8)
        return post(apiName, apiToken, filename, donate, "text/csv", bytes.size.toLong()) { out -> out.write(bytes) }
    }

    fun uploadFile(apiName: String, apiToken: String, file: java.io.File, filename: String, donate: Boolean, contentType: String = "application/octet-stream"): Result =
        post(apiName, apiToken, filename, donate, contentType, file.length()) { out -> file.inputStream().use { it.copyTo(out) } }

    private fun post(apiName: String, apiToken: String, filename: String, donate: Boolean, fileContentType: String, bodyLen: Long, writeFileBody: (DataOutputStream) -> Unit): Result {
        if (apiName.isBlank() || apiToken.isBlank())
            return Result(false, "Missing WiGLE API Name / Token")
        val boundary = "----WardriveGo${System.currentTimeMillis()}"

        val preamble = buildString {
            if (donate) append("--$boundary\r\nContent-Disposition: form-data; name=\"donate\"\r\n\r\non\r\n")
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
            append("Content-Type: $fileContentType\r\n\r\n")
        }
        val trailer = "\r\n--$boundary--\r\n"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true

                setFixedLengthStreamingMode(preamble.length.toLong() + bodyLen + trailer.length.toLong())
                connectTimeout = 30_000
                readTimeout = 600_000
                val basic = Base64.encodeToString("$apiName:$apiToken".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $basic")
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

            com.rocketgod.warble.usb.BeastDiag.log("WiGLE upload HTTP $code: ${body.take(300)}")
            val json = try { org.json.JSONObject(body) } catch (e: Exception) { null }

            val explicitFail = json != null && json.has("success") && !json.optBoolean("success", true)
            when {
                code == 401 || code == 403 ->
                    Result(false, "WiGLE rejected the credentials (HTTP $code). Check your API Name and Token.")
                code !in 200..299 ->
                    Result(false, "WiGLE upload failed (HTTP $code). ${body.take(180)}")
                explicitFail -> {
                    val msg = json?.optString("message")?.ifBlank { null } ?: body.take(180)
                    Result(false, "WiGLE did not accept the file: $msg")
                }
                else -> {
                    val trans = json?.optJSONArray("results")?.optJSONObject(0)?.optString("transid")?.ifBlank { null }
                        ?: json?.optString("transid")?.ifBlank { null }
                    Result(true, if (trans == null) "Uploaded to WiGLE ✓" else "Uploaded to WiGLE ✓ ($trans)")
                }
            }
        } catch (e: Exception) {
            Result(false, "WiGLE upload error: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
