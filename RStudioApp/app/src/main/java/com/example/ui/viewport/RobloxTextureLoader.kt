package com.example.ui.viewport

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import io.github.sceneview.texture.ImageTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Loads Roblox decal/texture images (rbxassetid://, rbxasset://, http) into Filament
 * [Texture]s. Ported from the old kool bridge: handles the assetdelivery redirect chain
 * (asset id -> possibly an XML/manifest pointing at the real image id) and caches by
 * resolved path.
 */
class RobloxTextureLoader(
    private val engine: Engine,
    private val httpClient: OkHttpClient,
    private val assetManager: android.content.res.AssetManager
) {
    private val cache = mutableMapOf<String, Texture>()
    private val missing = mutableSetOf<String>()

    @Volatile
    var roblosecurityCookie: String = ""

    /** Returns a texture for [textureUri], or null while it loads asynchronously. */
    suspend fun load(
        textureUri: String,
        repeating: Boolean,
        tint: TintColor? = null,
        alpha: Float = 1f
    ): Texture? {
        val path = resolveRobloxTextureAssetPath(textureUri) ?: return null
        val key = "$path|repeat=$repeating|tint=${tint?.packed ?: -1}|a=$alpha"
        cache[key]?.let { return it }
        if (key in missing) return null

        return runCatching { loadInternal(path, repeating, tint, alpha) }
            .onSuccess { cache[key] = it }
            .onFailure { missing += key }
            .getOrNull()
    }

    suspend fun loadSkybox(textureUris: List<String>): Skybox? {
        if (textureUris.size != 6) return null
        return runCatching {
            val bitmaps = withContext(Dispatchers.IO) {
                textureUris.map { uri ->
                    val path = resolveRobloxTextureAssetPath(uri)
                        ?: throw IOException("Unsupported skybox texture: $uri")
                    val bytes = if (path.startsWith("http://", true) || path.startsWith("https://", true)) {
                        if (isRobloxAssetDeliveryPath(path)) {
                            val assetId = path.substringAfter("id=", "").takeWhile(Char::isDigit)
                            resolveRobloxImageBytes(downloadRobloxAssetBytes(assetId), linkedSetOf(assetId))
                        } else {
                            downloadBytes(path)
                        }
                    } else {
                        contextAssetBytes(path)
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IOException("Could not decode skybox face $uri")
                }
            }
            val size = bitmaps.minOf { minOf(it.width, it.height) }.coerceIn(1, 2048)
            val faceSize = size * size * 4
            val buffer = ByteBuffer.allocateDirect(faceSize * 6)
            bitmaps.forEach { bitmap ->
                val square = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                Canvas(square).drawBitmap(
                    bitmap,
                    Rect(0, 0, bitmap.width, bitmap.height),
                    RectF(0f, 0f, size.toFloat(), size.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
                val pixels = IntArray(size * size)
                square.getPixels(pixels, 0, size, 0, 0, size, size)
                pixels.forEach { pixel ->
                    buffer.put(((pixel ushr 16) and 0xFF).toByte())
                    buffer.put(((pixel ushr 8) and 0xFF).toByte())
                    buffer.put((pixel and 0xFF).toByte())
                    buffer.put(((pixel ushr 24) and 0xFF).toByte())
                }
                square.recycle()
            }
            buffer.flip()
            val cubemap = Texture.Builder()
                .width(size)
                .height(size)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
                .format(Texture.InternalFormat.SRGB8_A8)
                .build(engine)
            cubemap.setImage(
                engine,
                0,
                Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE),
                IntArray(6) { it * faceSize }
            )
            Skybox.Builder().environment(cubemap).build(engine)
        }.getOrNull()
    }

    data class TintColor(val r: Float, val g: Float, val b: Float) {
        val packed: Int
            get() = ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }

    private suspend fun loadInternal(
        path: String,
        repeating: Boolean,
        tint: TintColor?,
        alpha: Float
    ): Texture {
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

        // Tint + alpha are baked into the bitmap because the textured Filament material
        // has no color/tint uniform.
        val processed = if (tint != null || alpha < 1f) applyTintAndAlpha(bitmap, tint, alpha) else bitmap

        return ImageTexture.Builder()
            .bitmap(processed, ImageTexture.DEFAULT_TYPE)
            .build(engine)
    }

    private fun applyTintAndAlpha(
        src: android.graphics.Bitmap,
        tint: TintColor?,
        alpha: Float
    ): android.graphics.Bitmap {
        val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        val tr = tint?.r ?: 1f
        val tg = tint?.g ?: 1f
        val tb = tint?.b ?: 1f
        for (i in px.indices) {
            val p = px[i]
            val a = (((p ushr 24) and 0xFF) / 255f * alpha * 255f).toInt().coerceIn(0, 255)
            val r = (((p ushr 16) and 0xFF) * tr * a / 255f).toInt().coerceIn(0, 255)
            val g = (((p ushr 8) and 0xFF) * tg * a / 255f).toInt().coerceIn(0, 255)
            val b = ((p and 0xFF) * tb * a / 255f).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
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

    private fun contextAssetBytes(path: String): ByteArray =
        assetManager.open(path).use { it.readBytes() }

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
