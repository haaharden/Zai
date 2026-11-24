package com.self.zai.pi

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Executes HTTP requests for the hidden Pi WebView via OkHttp so CORS never masks API errors
 * and SSE streaming arrives chunk-by-chunk.
 */
class NativeHttp(
    private val scope: CoroutineScope,
    private val onHead: (id: String, status: Int, statusText: String, headers: JSONObject) -> Unit,
    private val onChunk: (id: String, base64: String) -> Unit,
    private val onEnd: (id: String) -> Unit,
    private val onError: (id: String, message: String) -> Unit,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // streaming SSE
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun execute(
        id: String,
        method: String,
        url: String,
        headersJson: JSONObject?,
        bodyBase64: String?,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url)
                var contentType: String? = null
                if (headersJson != null) {
                    val keys = headersJson.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = headersJson.optString(k)
                        if (k.equals("content-type", ignoreCase = true)) {
                            contentType = v
                        }
                        if (!k.equals("content-length", ignoreCase = true)) {
                            builder.header(k, v)
                        }
                    }
                }

                val bodyBytes = bodyBase64?.takeIf { it.isNotEmpty() }?.let {
                    Base64.decode(it, Base64.DEFAULT)
                }
                val requestBody = when {
                    method.equals("GET", true) || method.equals("HEAD", true) -> null
                    bodyBytes != null -> bodyBytes.toRequestBody(contentType?.toMediaTypeOrNull())
                    else -> ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
                }
                builder.method(method.uppercase(), requestBody)

                val call = client.newCall(builder.build())
                activeCalls[id] = call

                call.execute().use { resp ->
                    val respHeaders = JSONObject()
                    for (i in 0 until resp.headers.size) {
                        val name = resp.headers.name(i).lowercase()
                        val value = resp.headers.value(i)
                        if (respHeaders.has(name)) {
                            respHeaders.put(name, "${respHeaders.getString(name)}, $value")
                        } else {
                            respHeaders.put(name, value)
                        }
                    }
                    onHead(id, resp.code, resp.message, respHeaders)

                    val stream = resp.body?.byteStream()
                    if (stream != null) {
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (n > 0) {
                                val chunk = Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP)
                                onChunk(id, chunk)
                            }
                        }
                    }
                    onEnd(id)
                }
            } catch (e: Throwable) {
                if (activeCalls.containsKey(id)) {
                    onError(id, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                activeCalls.remove(id)
            }
        }
    }

    fun abort(id: String) {
        activeCalls.remove(id)?.cancel()
    }

    fun cancelAll() {
        for ((id, call) in activeCalls) {
            activeCalls.remove(id)
            call.cancel()
        }
    }
}
