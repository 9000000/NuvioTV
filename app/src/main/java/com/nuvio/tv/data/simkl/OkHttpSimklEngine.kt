package com.nuvio.tv.data.simkl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OkHttpSimklEngine(private val client: OkHttpClient) : SimklHttpEngine {
    override suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String
    ): SimklRawHttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        val requestBody = body.toRequestBody(JSON_MEDIA_TYPE)
        when (method) {
            SimklHttpMethod.GET.name -> builder.get()
            SimklHttpMethod.POST.name -> builder.post(requestBody)
            SimklHttpMethod.DELETE.name -> builder.delete(requestBody)
            else -> throw IllegalArgumentException("Unsupported Simkl method")
        }
        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.size > MAX_RESPONSE_BODY_BYTES) throw IOException("Simkl response body exceeds limit")
            SimklRawHttpResponse(
                status = response.code,
                body = bytes.toString(Charsets.UTF_8),
                headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") }
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val MAX_RESPONSE_BODY_BYTES = 8 * 1024 * 1024
    }
}
