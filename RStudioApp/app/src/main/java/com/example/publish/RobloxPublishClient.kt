package com.example.publish

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

data class RobloxPublishResult(
    val universeId: Long?,
    val placeId: Long,
    val versionNumber: Long?,
    val uploadMethod: String,
    val settingsWarning: String? = null
)

class RobloxPublishClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    suspend fun publish(
        roblosecurityCookie: String,
        openCloudApiKey: String,
        name: String,
        description: String,
        rbxlxBytes: ByteArray,
        existingPlaceId: Long? = null,
        existingUniverseId: Long? = null,
        templatePlaceId: Long = DEFAULT_BASEPLATE_TEMPLATE_PLACE_ID
    ): RobloxPublishResult {
        val cookie = normalizeCookie(roblosecurityCookie)
        require(cookie.isNotBlank()) { ".ROBLOSECURITY cookie is required." }

        val state = CsrfState(cookie = cookie, csrfToken = getCsrfToken(cookie))
        val target = if (existingPlaceId != null) {
            PublishTarget(
                universeId = existingUniverseId ?: getUniverseForPlace(state, existingPlaceId),
                placeId = existingPlaceId
            )
        } else {
            createUniverse(state, name, templatePlaceId)
        }

        val upload = publishPlaceContent(
            state = state,
            openCloudApiKey = openCloudApiKey,
            universeId = target.universeId,
            placeId = target.placeId,
            rbxlxBytes = rbxlxBytes,
            versionType = "Published"
        )
        val settingsWarning = runCatching {
            updatePublishSettings(state, target.universeId, target.placeId, name, description)
        }.exceptionOrNull()?.let { error ->
            "Place content was published, but place settings update failed: ${error.message ?: error.javaClass.simpleName}"
        }
        return RobloxPublishResult(
            universeId = target.universeId,
            placeId = target.placeId,
            versionNumber = upload.versionNumber,
            uploadMethod = upload.method,
            settingsWarning = settingsWarning
        )
    }

    private suspend fun getCsrfToken(cookie: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$AUTH_BASE/v2/logout")
            .post(ByteArray(0).toRequestBody(null))
            .header("Cookie", ".ROBLOSECURITY=$cookie")
            .build()

        client.newCall(request).execute().use { response ->
            response.header("x-csrf-token")
                ?: throw IOException("Roblox did not return an X-CSRF-TOKEN. Check the cookie.")
        }
    }

    private suspend fun createUniverse(
        state: CsrfState,
        name: String,
        templatePlaceId: Long
    ): PublishTarget {
        val body = JSONObject()
            .put("name", name)
            .put("templateId", templatePlaceId)
            .put("templatePlaceId", templatePlaceId)
            .toString()
        val response = callApi(state, "POST", "/universes/v1/universes/create", body)
        val json = JSONObject(response)
        return PublishTarget(
            universeId = json.getLong("universeId"),
            placeId = json.getLong("rootPlaceId")
        )
    }

    private suspend fun getUniverseForPlace(state: CsrfState, placeId: Long): Long? {
        return runCatching {
            val response = callApi(state, "GET", "/universes/v1/places/$placeId/universe")
            JSONObject(response).optLong("universeId").takeIf { it > 0L }
        }.getOrNull()
    }

    private suspend fun publishPlaceContent(
        state: CsrfState,
        openCloudApiKey: String,
        universeId: Long?,
        placeId: Long,
        rbxlxBytes: ByteArray,
        versionType: String
    ): PublishUploadResult {
        if (openCloudApiKey.isNotBlank() && universeId != null) {
            runCatching {
                publishPlaceContentOpenCloud(openCloudApiKey, universeId, placeId, rbxlxBytes, versionType)
            }.onSuccess { version ->
                return PublishUploadResult(versionNumber = version, method = "Open Cloud")
            }.onFailure { error ->
                if (!shouldFallbackToUserAuth(error)) throw error
            }
        }

        return publishPlaceContentUserAuth(state, universeId, placeId, rbxlxBytes, versionType)
    }

    private fun shouldFallbackToUserAuth(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("HTTP 401") ||
            message.contains("HTTP 403") ||
            message.contains("Missing API Key", ignoreCase = true) ||
            message.contains("Invalid API Key", ignoreCase = true) ||
            message.contains("Only the x-api-key", ignoreCase = true)
    }

    private suspend fun publishPlaceContentOpenCloud(
        apiKey: String,
        universeId: Long?,
        placeId: Long,
        rbxlxBytes: ByteArray,
        versionType: String
    ): Long? = withContext(Dispatchers.IO) {
        val resolvedUniverseId = requireNotNull(universeId) {
            "universeId is required for place version publish."
        }
        val path = "/universes/v1/$resolvedUniverseId/places/$placeId/versions?versionType=$versionType"
        val response = executePlaceVersionRequest(apiKey, path, rbxlxBytes)
        response.use {
            val text = response.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IOException("POST $APIS_BASE$path failed: HTTP ${it.code} ${text.take(500)}")
            }
            parseVersionNumber(text)
        }
    }

    private suspend fun publishPlaceContentUserAuth(
        state: CsrfState,
        universeId: Long?,
        placeId: Long,
        rbxlxBytes: ByteArray,
        versionType: String
    ): PublishUploadResult = withContext(Dispatchers.IO) {
        val body = buildPublishServiceCreateAssetBody(
            placeId = placeId,
            rbxlxBytes = rbxlxBytes,
            published = versionType.equals("Published", ignoreCase = true)
        )
        val endpoints = listOf(
            UserAuthUploadEndpoint(
                path = "/assets/user-auth/v1/assets/$placeId/versions",
                method = "Roblox user-auth /versions"
            ),
            UserAuthUploadEndpoint(
                path = "/assets/user-auth/v1/assets/$placeId",
                method = "Roblox user-auth /assets fallback"
            )
        )

        var fallbackError: IOException? = null
        endpoints.forEachIndexed { index, endpoint ->
            try {
                val version = uploadPlaceContentUserAuth(
                    state = state,
                    path = endpoint.path,
                    universeId = universeId,
                    placeId = placeId,
                    body = body
                )
                return@withContext PublishUploadResult(versionNumber = version, method = endpoint.method)
            } catch (error: IOException) {
                if (index < endpoints.lastIndex && shouldTryLegacyUserAuthAssetEndpoint(error)) {
                    fallbackError = error
                } else {
                    throw error
                }
            }
        }

        throw fallbackError ?: IOException("Roblox user-auth publish did not run.")
    }

    private fun shouldTryLegacyUserAuthAssetEndpoint(error: IOException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("HTTP 400") ||
            message.contains("HTTP 404") ||
            message.contains("HTTP 405")
    }

    private suspend fun uploadPlaceContentUserAuth(
        state: CsrfState,
        path: String,
        universeId: Long?,
        placeId: Long,
        body: ByteArray
    ): Long? {
        var response = executeUserAuthPlaceUploadRequest(state, path, universeId, placeId, body)
        if (response.code == 403) {
            val csrf = response.header("x-csrf-token")
            response.close()
            if (!csrf.isNullOrBlank()) {
                state.csrfToken = csrf
                response = executeUserAuthPlaceUploadRequest(state, path, universeId, placeId, body)
            }
        }

        return response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IOException("PATCH $APIS_BASE$path failed: HTTP ${it.code} ${text.take(500)}")
            }

            val directVersion = parseVersionNumber(text)
            val operationId = extractOperationId(text)
            if (operationId.isNullOrBlank()) {
                directVersion
            } else {
                pollUserAuthOperation(state, operationId) ?: directVersion
            }
        }
    }

    private fun parseVersionNumber(responseText: String): Long? {
        if (responseText.isBlank()) return null
        return runCatching {
            JSONObject(responseText).optLong("versionNumber").takeIf { it > 0L }
        }.getOrNull()
    }

    private suspend fun pollUserAuthOperation(state: CsrfState, operationId: String): Long? {
        repeat(USER_AUTH_OPERATION_MAX_POLLS) {
            delay(USER_AUTH_OPERATION_POLL_MS)
            val response = callApi(state, "GET", "/assets/user-auth/v1/operations/$operationId")
            val json = runCatching { JSONObject(response) }.getOrNull() ?: return@repeat
            val status = json.optString("status").ifBlank {
                json.optJSONObject("operation")?.optString("status").orEmpty()
            }
            val done = json.optBoolean("done", false) || json.optJSONObject("operation")?.optBoolean("done", false) == true

            if (status.equals("Success", ignoreCase = true) ||
                status.equals("Succeeded", ignoreCase = true) ||
                status.equals("Completed", ignoreCase = true) ||
                done
            ) {
                json.optJSONObject("error")?.let { errorJson ->
                    val message = errorJson.optString("message").ifBlank { errorJson.toString() }
                    throw IOException("Roblox publish operation failed: $message")
                }
                return parseVersionNumberDeep(json)
            }

            if (status.equals("Failure", ignoreCase = true) ||
                status.equals("Failed", ignoreCase = true) ||
                status.equals("Cancelled", ignoreCase = true)
            ) {
                throw IOException("Roblox publish operation $operationId ended with status $status: ${response.take(500)}")
            }
        }

        throw IOException("Timed out waiting for Roblox publish operation $operationId.")
    }

    private fun parseVersionNumberDeep(json: JSONObject): Long? {
        sequenceOf("versionNumber", "assetVersionNumber", "version")
            .mapNotNull { key -> json.optLong(key).takeIf { it > 0L } }
            .firstOrNull()
            ?.let { return it }

        listOf("response", "result", "metadata", "asset", "assetVersion")
            .mapNotNull { key -> json.optJSONObject(key) }
            .forEach { nested ->
                parseVersionNumberDeep(nested)?.let { return it }
            }
        return null
    }

    private fun extractOperationId(responseText: String): String? {
        if (responseText.isBlank()) return null
        val json = runCatching { JSONObject(responseText) }.getOrNull() ?: return null
        listOf("operationId", "id")
            .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?.let { return it.substringAfterLast("/") }

        listOf("path", "operationPath", "name")
            .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?.let { return it.substringAfterLast("/") }

        json.optJSONObject("operation")?.let { operation ->
            listOf("operationId", "id", "path", "name")
                .mapNotNull { key -> operation.optString(key).takeIf { it.isNotBlank() } }
                .firstOrNull()
                ?.let { return it.substringAfterLast("/") }
        }
        return null
    }

    private fun executePlaceVersionRequest(
        apiKey: String,
        path: String,
        body: ByteArray
    ): okhttp3.Response {
        val request = Request.Builder()
            .url("$APIS_BASE$path")
            .header("x-api-key", apiKey.trim())
            .header("Content-Type", OCTET_STREAM.toString())
            .post(body.toRequestBody(OCTET_STREAM))
            .build()
        return client.newCall(request).execute()
    }

    private fun executeUserAuthPlaceUploadRequest(
        state: CsrfState,
        path: String,
        universeId: Long?,
        placeId: Long,
        body: ByteArray
    ): okhttp3.Response {
        val builder = Request.Builder()
            .url("$APIS_BASE$path")
            .header("Cookie", ".ROBLOSECURITY=${state.cookie}")
            .header("X-CSRF-TOKEN", state.csrfToken)
            .header("Roblox-Place-Id", placeId.toString())
            .header("Content-Type", "multipart/form-data; boundary=$PUBLISH_SERVICE_CREATE_ASSET_BOUNDARY")
            .patch(body.toRequestBody(MULTIPART_FORM))

        if (universeId != null) {
            builder.header("Roblox-Universe-Id", universeId.toString())
        }

        return client.newCall(builder.build()).execute()
    }

    private fun buildPublishServiceCreateAssetBody(
        placeId: Long,
        rbxlxBytes: ByteArray,
        published: Boolean
    ): ByteArray {
        val requestJson = JSONObject()
            .put("assetType", "Place")
            .put("assetId", placeId)
            .put("published", published)
            .put("creationContext", JSONObject())
            .toString()

        return ByteArrayOutputStream().use { out ->
            fun text(value: String) {
                out.write(value.toByteArray(Charsets.UTF_8))
            }

            text("--$PUBLISH_SERVICE_CREATE_ASSET_BOUNDARY\r\n")
            text("Content-Disposition: form-data; name=\"request\"\r\n\r\n")
            text(requestJson)
            text("\r\n--$PUBLISH_SERVICE_CREATE_ASSET_BOUNDARY\r\n")
            text("Content-Disposition: form-data; name=\"fileContent\"; filename=\"contentToUpload\"\r\n")
            text("Content-Type: application/octet-stream\r\n\r\n")
            out.write(rbxlxBytes)
            text("\r\n--$PUBLISH_SERVICE_CREATE_ASSET_BOUNDARY--\r\n")
            out.toByteArray()
        }
    }

    private suspend fun updatePublishSettings(
        state: CsrfState,
        universeId: Long?,
        placeId: Long,
        name: String,
        description: String
    ) {
        val body = JSONObject()
            .put("name", name)
            .put("description", description)
            .toString()

        val errors = mutableListOf<String>()
        if (universeId != null) {
            listOf(APIS_BASE, DEVELOP_BASE).forEach { baseUrl ->
                runCatching {
                    callApi(state, "PATCH", "/v2/universes/$universeId/configuration", body, baseUrl)
                }.onSuccess {
                    return
                }.onFailure { error ->
                    errors += "${baseUrl}/v2/universes/$universeId/configuration: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }

        runCatching {
            callApi(state, "POST", "/places/$placeId/update", body)
        }.onSuccess {
            return
        }.onFailure { error ->
            errors += "$APIS_BASE/places/$placeId/update: ${error.message ?: error.javaClass.simpleName}"
        }

        throw IOException(errors.joinToString("; "))
    }

    private suspend fun callApi(
        state: CsrfState,
        method: String,
        path: String,
        body: String? = null,
        baseUrl: String = APIS_BASE
    ): String = withContext(Dispatchers.IO) {
        var response = executeApiRequest(state, baseUrl, method, path, body)
        if (response.code == 403) {
            val csrf = response.header("x-csrf-token")
            response.close()
            if (!csrf.isNullOrBlank()) {
                state.csrfToken = csrf
                response = executeApiRequest(state, baseUrl, method, path, body)
            }
        }

        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IOException("$method $baseUrl$path failed: HTTP ${it.code} ${text.take(500)}")
            }
            text
        }
    }

    private fun executeApiRequest(
        state: CsrfState,
        baseUrl: String,
        method: String,
        path: String,
        body: String?
    ): okhttp3.Response {
        val requestBody = body?.toRequestBody(JSON)
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .header("Cookie", ".ROBLOSECURITY=${state.cookie}")
            .header("X-CSRF-TOKEN", state.csrfToken)

        if (requestBody != null) {
            builder.header("Content-Type", "application/json")
        }

        val request = when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: ByteArray(0).toRequestBody(null))
            "PATCH" -> builder.patch(requestBody ?: ByteArray(0).toRequestBody(null))
            else -> error("Unsupported method: $method")
        }.build()
        return client.newCall(request).execute()
    }

    private fun normalizeCookie(input: String): String {
        val trimmed = input.trim()
            .removePrefix("Cookie:")
            .trim()
        return when {
            ".ROBLOSECURITY=" in trimmed -> trimmed.substringAfter(".ROBLOSECURITY=").substringBefore(";").trim()
            trimmed.startsWith(".ROBLOSECURITY=", ignoreCase = true) -> trimmed.substringAfter("=").substringBefore(";").trim()
            else -> trimmed.substringBefore(";").trim()
        }
    }

    private data class CsrfState(
        val cookie: String,
        var csrfToken: String
    )

    private data class PublishTarget(
        val universeId: Long?,
        val placeId: Long
    )

    private data class PublishUploadResult(
        val versionNumber: Long?,
        val method: String
    )

    private data class UserAuthUploadEndpoint(
        val path: String,
        val method: String
    )

    private companion object {
        private const val APIS_BASE = "https://apis.roblox.com"
        private const val AUTH_BASE = "https://auth.roblox.com"
        private const val DEVELOP_BASE = "https://develop.roblox.com"
        private const val DEFAULT_BASEPLATE_TEMPLATE_PLACE_ID = 95206881L
        private const val PUBLISH_SERVICE_CREATE_ASSET_BOUNDARY = "-------PublishServiceCreateAsset"
        private const val USER_AUTH_OPERATION_MAX_POLLS = 60
        private const val USER_AUTH_OPERATION_POLL_MS = 2_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val MULTIPART_FORM = "multipart/form-data; boundary=$PUBLISH_SERVICE_CREATE_ASSET_BOUNDARY".toMediaType()
    }
}
