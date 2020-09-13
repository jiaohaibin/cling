package org.fourthline.cling.transport.impl.jetty

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.eclipse.jetty.http.HttpHeaders
import org.fourthline.cling.model.message.*
import org.fourthline.cling.model.message.header.ContentTypeHeader
import org.fourthline.cling.model.message.header.UpnpHeader
import org.fourthline.cling.transport.spi.StreamClient
import org.fourthline.cling.transport.spi.StreamClientConfiguration

class OkHttpStreamClient(
    private val client: OkHttpClient,
    private val configuration: StreamClientConfigurationImpl
) : StreamClient<StreamClientConfiguration> {

    override fun stop() {
        // no-op
    }

    override fun getConfiguration(): StreamClientConfigurationImpl = configuration

    override fun sendRequest(message: StreamRequestMessage): StreamResponseMessage? {
        val request = Request.Builder()
            .url(message.operation.uri.toString())
            .addHeaders(message)
            .addBody(message).build()

        return try {
            val response = client.newCall(request).execute()
            parseResponse(response)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseResponse(response: Response): StreamResponseMessage? {
        val status = UpnpResponse.Status.getByStatusCode(response.code).statusMsg ?: return null

        val responseOperation = UpnpResponse(response.code, status)
        val responseMessage = StreamResponseMessage(responseOperation)
        val upnpHeaders = UpnpHeaders().apply {
            response.headers.forEach { header ->
                add(header.first, header.second)
            }
        }

        responseMessage.headers = upnpHeaders

        response.body
            ?.bytes()
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let { bytes ->
                val contentType: String? = response.headers[UpnpHeader.Type.CONTENT_TYPE.httpName]
                val isText = contentType == ContentTypeHeader.DEFAULT_CONTENT_TYPE.type
                if (contentType != null || isText) {
                    responseMessage.setBodyCharacters(bytes)
                } else {
                    responseMessage.setBody(UpnpMessage.BodyType.BYTES, bytes)
                }
            }

        return responseMessage
    }

    private fun Request.Builder.addHeaders(message: StreamRequestMessage): Request.Builder {
        val headers = message.headers

        if (!headers.containsKey(UpnpHeader.Type.USER_AGENT)) {
            addHeader(
                UpnpHeader.Type.USER_AGENT.httpName,
                configuration.getUserAgentValue(message.udaMajorVersion, message.udaMinorVersion)
            )
        }

        for ((headerName, headerValues) in headers) {
            for (value in headerValues) {
                addHeader(headerName, value)
            }
        }

        return this
    }

    private fun Request.Builder.addBody(message: StreamRequestMessage): Request.Builder {
        if (message.hasBody()) {
            if (message.bodyType == UpnpMessage.BodyType.STRING) {
                val contentType: String? = message.contentTypeHeader?.value?.toString()
                val requestBody = message.bodyString.toRequestBody(contentType?.toMediaType())

                addHeader(HttpHeaders.CONTENT_LENGTH, requestBody.contentLength().toString())

                method(message.operation.httpMethodName, requestBody)
            } else {
                val contentType: String? = message.contentTypeHeader?.value?.toString()
                val requestBody = message.bodyBytes?.toRequestBody(contentType?.toMediaType())

                if (requestBody != null) {
                    addHeader(HttpHeaders.CONTENT_LENGTH, requestBody.contentLength().toString())
                }

                method(message.operation.httpMethodName, requestBody)
            }
        }

        return this
    }
}