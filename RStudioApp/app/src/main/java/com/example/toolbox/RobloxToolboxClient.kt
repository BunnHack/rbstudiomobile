package com.example.toolbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RobloxToolboxClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun searchMarketplace(
        query: String,
        assetType: ToolboxAssetType,
        cursor: String? = null,
        pageSize: Int = 40
    ): ToolboxSearchPage = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (url in marketplaceUrls(query, assetType, cursor, pageSize)) {
            val result = runCatching { getJson(url) }
            if (result.isFailure) {
                lastError = result.exceptionOrNull() as? IOException
                    ?: IOException(result.exceptionOrNull()?.message)
                continue
            }

            val page = parseMarketplaceResponse(result.getOrThrow())
            if (page.assets.isNotEmpty() || page.nextPageCursor != null) {
                return@withContext page.withResolvedThumbnails()
            }
        }

        throw lastError ?: IOException("Toolbox marketplace returned no usable results.")
    }

    suspend fun downloadAsset(assetId: Long, roblosecurityCookie: String = ""): ByteArray = withContext(Dispatchers.IO) {
        val cookie = normalizeCookie(roblosecurityCookie)
        val url = "$ASSET_DELIVERY_BASE/v1/asset/".toHttpUrl().newBuilder()
            .addQueryParameter("id", assetId.toString())
            .build()
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("User-Agent", USER_AGENT)
            .get()
        if (cookie.isNotBlank()) {
            builder.header("Cookie", ".ROBLOSECURITY=$cookie")
        }
        val request = builder.build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val text = body.toString(Charsets.UTF_8).take(500)
                if (response.code == 401) {
                    val reason = if (cookie.isBlank()) {
                        "This model requires Roblox login. Add your .ROBLOSECURITY cookie in Toolbox Auth, then insert again."
                    } else {
                        "Roblox rejected the Toolbox auth cookie or this account cannot access the asset."
                    }
                    throw IOException("$reason HTTP 401 $text")
                }
                throw IOException("GET $url failed: HTTP ${response.code} $text")
            }
            if (body.isEmpty()) {
                throw IOException("AssetDelivery returned an empty body for asset $assetId.")
            }
            body
        }
    }

    private fun marketplaceUrls(
        query: String,
        assetType: ToolboxAssetType,
        cursor: String?,
        pageSize: Int
    ): List<HttpUrl> {
        val keyword = query.trim()
        val marketplaceBase = "$APIS_BASE/toolbox-service/v1/marketplace".toHttpUrl()
        val creatorStoreBase = "$APIS_BASE/toolbox-service/v2/assets:search".toHttpUrl()

        val primary = marketplaceBase.newBuilder()
            .addQueryParameter("assetTypeId", assetType.assetTypeId.toString())
            .addQueryParameter("pageSize", pageSize.toString())
            .apply {
                if (keyword.isNotBlank()) addQueryParameter("keyword", keyword)
                if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor)
            }
            .build()

        val studioModelStyle = marketplaceBase.newBuilder()
            .addQueryParameter("model.assetTypeIds", assetType.assetTypeId.toString())
            .addQueryParameter("model.includeNotForSale", "true")
            .addQueryParameter("limit", pageSize.toString())
            .apply {
                if (keyword.isNotBlank()) addQueryParameter("model.keyword", keyword)
                if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor)
            }
            .build()

        val creatorStoreSearch = creatorStoreBase.newBuilder()
            .addQueryParameter("searchCategoryType", assetType.creatorStoreCategory)
            .addQueryParameter("maxPageSize", pageSize.toString())
            .apply {
                if (keyword.isNotBlank()) addQueryParameter("query", keyword)
                if (!cursor.isNullOrBlank()) addQueryParameter("pageToken", cursor)
            }
            .build()

        return listOf(primary, studioModelStyle, creatorStoreSearch)
    }

    private fun getJson(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GET $url failed: HTTP ${response.code} ${text.take(500)}")
            }
            return text
        }
    }

    private fun parseMarketplaceResponse(text: String): ToolboxSearchPage {
        val root = JSONObject(text)
        val cursor = firstString(listOf(root), "nextPageCursor", "nextCursor", "nextPageToken", "pageToken", "cursor")
            ?: firstNestedObject(root, "data", "result", "response")?.let {
                firstString(listOf(it), "nextPageCursor", "nextCursor", "nextPageToken", "pageToken", "cursor")
            }

        val arrays = candidateItemArrays(root)
        val byId = linkedMapOf<Long, ToolboxAsset>()
        arrays.forEach { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val asset = parseAsset(obj) ?: continue
                byId.putIfAbsent(asset.assetId, asset)
            }
        }

        if (byId.isEmpty()) {
            parseAsset(root)?.let { byId[it.assetId] = it }
        }

        return ToolboxSearchPage(byId.values.toList(), cursor)
    }

    private suspend fun ToolboxSearchPage.withResolvedThumbnails(): ToolboxSearchPage {
        val missing = assets
            .filter { it.thumbnailUrl.isNullOrBlank() }
            .map { it.assetId }
            .distinct()
        if (missing.isEmpty()) return this

        val thumbnails = fetchAssetThumbnails(missing)
        return copy(
            assets = assets.map { asset ->
                if (asset.thumbnailUrl.isNullOrBlank()) {
                    asset.copy(thumbnailUrl = thumbnails[asset.assetId])
                } else {
                    asset
                }
            }
        )
    }

    private suspend fun fetchAssetThumbnails(assetIds: List<Long>): Map<Long, String> = withContext(Dispatchers.IO) {
        val resolved = mutableMapOf<Long, String>()
        assetIds.chunked(100).forEach { chunk ->
            val payload = JSONArray()
            chunk.forEach { id ->
                payload.put(
                    JSONObject()
                        .put("requestId", "asset:$id")
                        .put("type", "Asset")
                        .put("targetId", id)
                        .put("format", "Png")
                        .put("size", "420x420")
                )
            }

            val request = Request.Builder()
                .url("$THUMBNAILS_BASE/v1/batch")
                .header("Accept", "application/json")
                .header("Content-Type", JSON.toString())
                .header("User-Agent", USER_AGENT)
                .post(payload.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@forEach
                val data = JSONObject(text).optJSONArray("data") ?: return@forEach
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optLongFlexible("targetId")
                        ?: item.optString("requestId").substringAfter("asset:", "").toLongOrNull()
                    val imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() }
                    if (id != null && !imageUrl.isNullOrBlank()) {
                        resolved[id] = imageUrl
                    }
                }
            }
        }
        resolved
    }

    private fun candidateItemArrays(root: JSONObject): List<JSONArray> {
        val arrays = mutableListOf<JSONArray>()
        val keys = listOf("data", "items", "results", "assets", "creatorStoreAssets")

        keys.forEach { key ->
            root.optJSONArray(key)?.let { arrays += it }
        }

        listOf("data", "result", "response").forEach { objectKey ->
            val obj = root.optJSONObject(objectKey) ?: return@forEach
            keys.forEach { key ->
                obj.optJSONArray(key)?.let { arrays += it }
            }
        }

        return arrays
    }

    private fun parseAsset(container: JSONObject): ToolboxAsset? {
        val creatorStoreAsset = container.optJSONObject("creatorStoreAsset")
        val sources = listOfNotNull(
            container,
            container.optJSONObject("item"),
            container.optJSONObject("asset"),
            creatorStoreAsset,
            creatorStoreAsset?.optJSONObject("asset"),
            container.optJSONObject("model"),
            container.optJSONObject("details")
        )

        val assetId = firstLong(sources, "assetId", "assetID", "targetId", "id") ?: return null
        val name = firstString(sources, "name", "displayName", "title")
            ?: "Asset $assetId"
        val creatorName = firstString(sources, "creatorName", "creator")
            ?: sources.firstNotNullOfOrNull { obj ->
                obj.optJSONObject("creator")?.let { creator ->
                    firstString(listOf(creator), "name", "displayName", "username")
                }
            }
            ?: ""
        val assetTypeId = firstLong(sources, "assetTypeId", "assetTypeID", "typeId")
            ?.toInt()
            ?: sources.firstNotNullOfOrNull { obj ->
                obj.opt("assetType")?.let { if (it is Number) it.toInt() else null }
            }
        val assetTypeName = firstString(sources, "assetType", "type", "assetTypeName").orEmpty()
        val thumbnailUrl = firstString(sources, "thumbnailUrl", "iconUrl", "imageUrl")
            ?: firstThumbnailUrl(sources)

        return ToolboxAsset(
            assetId = assetId,
            name = name,
            creatorName = creatorName,
            assetTypeId = assetTypeId,
            assetTypeName = assetTypeName,
            thumbnailUrl = thumbnailUrl
        )
    }

    private fun firstThumbnailUrl(sources: List<JSONObject>): String? {
        sources.forEach { obj ->
            listOf("thumbnail", "icon", "image", "thumbnailAsset").forEach { key ->
                val nested = obj.optJSONObject(key) ?: return@forEach
                firstString(listOf(nested), "imageUrl", "url", "thumbnailUrl", "iconUrl")?.let { return it }
            }

            listOf("thumbnails", "images").forEach { key ->
                val array = obj.optJSONArray(key) ?: return@forEach
                for (i in 0 until array.length()) {
                    val nested = array.optJSONObject(i) ?: continue
                    firstString(listOf(nested), "imageUrl", "url", "thumbnailUrl", "iconUrl")?.let { return it }
                }
            }
        }
        return null
    }

    private fun firstNestedObject(root: JSONObject, vararg keys: String): JSONObject? {
        keys.forEach { key ->
            root.optJSONObject(key)?.let { return it }
        }
        return null
    }

    private fun firstString(sources: List<JSONObject>, vararg keys: String): String? {
        sources.forEach { obj ->
            keys.forEach { key ->
                val value = obj.opt(key)
                when (value) {
                    is String -> value.takeIf { it.isNotBlank() }?.let { return it }
                    is Number -> return value.toString()
                }
            }
        }
        return null
    }

    private fun firstLong(sources: List<JSONObject>, vararg keys: String): Long? {
        sources.forEach { obj ->
            keys.forEach { key ->
                obj.optLongFlexible(key)?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.optLongFlexible(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toLong().takeIf { it > 0L }
            is String -> value.toLongOrNull()?.takeIf { it > 0L }
            else -> null
        }
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

    private companion object {
        private const val APIS_BASE = "https://apis.roblox.com"
        private const val THUMBNAILS_BASE = "https://thumbnails.roblox.com"
        private const val ASSET_DELIVERY_BASE = "https://assetdelivery.roblox.com"
        private const val USER_AGENT = "RobloxStudio/WinInet RStudioApp"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val ToolboxAssetType.creatorStoreCategory: String
            get() = when (key) {
                ToolboxAssetTypes.Images.key -> "Decal"
                ToolboxAssetTypes.Meshes.key -> "MeshPart"
                ToolboxAssetTypes.Audio.key -> "Audio"
                ToolboxAssetTypes.Plugins.key -> "Plugin"
                else -> "Model"
            }
    }
}
