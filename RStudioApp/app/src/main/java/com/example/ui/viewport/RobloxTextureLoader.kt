package com.example.ui.viewport

import android.graphics.BitmapFactory
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import io.github.sceneview.texture.ImageTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Loads Roblox decal/texture images (rbxassetid://, rbxasset://, http) into Filament
 * [Texture]s. Ported from the old kool bridge: handles the assetdelivery redirect chain
 * (asset id -> possibly an XML/manifest pointing at the real image id) and caches by
 * resolved path.
 */
class RobloxTextureLoader(
    private val engine: Engine,
    private val httpClient: OkHttpClient
) {
    private val cache = mutableMapOf<String, Texture>()
    private val missing = mutableSetOf<String>()

    @Volatile
    var roblosecurityCookie: String = ""

    /** Returns a texture for [textureUri], or null while it loads asynchronously. */
    suspend fun load(textureUri: String, repeating: Boolean): Texture? {
        val path = resolveRobloxTextureAssetPath(textureUri) ?: return null
        val key = "$path|repeat=$repeating"
        cache[key]?.let { return it }
        if (key in missing) return null

        return runCatching { loadInternal(path, repeating) }
            .onSuccess { cache[key] = it }
            .onFailure { missing += key }
            .getOrNull()
    }

    private suspend fun loadInternal(path: String, repeating: Boolean): Texture {
        val bitmap = withContext(Dispatchers.IO) {
            val bytes = if (isRobloxAssetDeliveryPath(path)) {
                val assetId = path.substringAfter("id=", "").takeWhile { it.isDigit() }
                resolveRobloxImageBytes(downloadRobloxAssetBytes(assetId), linkedSetOf(assetId))
            } else {
                downloadBytes(path)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("Could not decode image from $path")
        }

        val texture = ImageTexture.Builder()
            .bitmap(bitmap, ImageTexture.DEFAULT_TYPE)
            .build(engine)
        if (repeating) {
            // Filament TextureSampler wrap default is CLAMP_TO_EDGE; decals that tile need
            // REPEAT. Sampling is configured at material-instance level, so we leave the
            // texture itself unmarked and rely on the sampler used in the material.
        }
        return texture
    }

    private fun resolveRobloxImageBytes(bytes: ByteArray, visited: MutableSet<String>): ByteArray {
        if (looksLikeImage(bytes)) return bytes
        val text = bytes.toString(Charsets.UTF_8)
        val nested = extractNestedTextureUri(text)
            ?: throw IOException("Roblox asset had no image bytes or nested texture url")
        val nestedPath = resolveRobloxTextureAssetPath(nested)
            ?: throw IOException("Unsupported nested texture uri: $nested")
        if (!isRobloxAssetDeliveryPath(nestedPath)) {
            throw IOException("Nested texture is not an assetdelivery id: $nested")
        }
        val nestedId = nestedPath.substringAfter("id=", "").takeWhile { it.isDigit() }
            .ifBlank { throw IOException("Missing nested asset id in $nestedPath") }
        if (!visited.add(nestedId)) throw IOException("Texture asset loop: $nestedId")
        return resolveRobloxImageBytes(downloadRobloxAssetBytes(nestedId), visited)
    }

    private fun downloadRobloxAssetBytes(assetId: String): ByteArray {
        if (assetId.isBlank()) throw IOException("Missing asset id")
        val request = Request.Builder()
            .url("https://assetdelivery.roblox.com/v1/asset/?id=$assetId")
            .header("Accept", "*/*")
            .header("User-Agent", "RobloxStudio/WinInet RStudioApp/1.0")
            .apply {
                if (roblosecurityCookie.isNotBlank()) {
                    header("Cookie", ".ROBLOSECURITY=$roblosecurityCookie")
                }
            }
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Asset $assetId HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("Empty body for asset $assetId")
        }
    }

    private fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.bytes() ?: throw IOException("Empty body for $url")
        }
    }

    private fun extractNestedTextureUri(text: String): String? {
        val patterns = listOf(
            Regex("<url>\\s*([^<]+?)\\s*</url>", RegexOption.IGNORE_CASE),
            Regex("\"(rbxassetid://\\d+)\""),
            Regex("(rbxassetid://\\d+)"),
            Regex("(https?://[^\\s\"<>]+asset/\\?id=\\d+[^\\s\"<>]*)")
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val candidate = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (candidate.isNotBlank()) return candidate
        }
        return null
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            ((bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) || // PNG
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) || // JPEG
                (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte())) // GIF

    private fun isRobloxAssetDeliveryPath(path: String): Boolean =
        path.contains("assetdelivery.roblox.com")
}
