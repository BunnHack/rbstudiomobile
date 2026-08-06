package com.example.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.models.StudioNode
import com.example.ui.viewport.resolveRobloxTextureAssetPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.floor
import kotlin.math.roundToInt

private val guiImageHttpClient = OkHttpClient()

private data class UDim2Value(
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float
)

private data class GuiVector2(
    val x: Float,
    val y: Float
)

private data class GuiRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

private data class GuiGridLayout(
    val cellSize: UDim2Value,
    val cellPadding: UDim2Value
)

@Composable
fun RobloxGuiOverlay(
    nodes: List<StudioNode>,
    selectedNodeId: String?,
    roblosecurityCookie: String,
    onSelectNode: (StudioNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val screenGuis = remember(nodes) {
        nodes
            .filter { it.className == StudioNode.CLASS_SCREEN_GUI && it.prop("Enabled").isNotFalse() }
            .sortedWith(compareBy<StudioNode> { it.prop("DisplayOrder").toIntRoblox() }.thenBy { it.name })
    }
    if (screenGuis.isEmpty()) return

    val childrenByParent = remember(nodes) { nodes.groupBy { it.parentId } }
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        screenGuis.forEachIndexed { index, screenGui ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(screenGui.prop("DisplayOrder").toIntRoblox().toFloat() * 1000f + index)
            ) {
                if (selectedNodeId == screenGui.id) {
                    ScreenGuiSelectionBox()
                }

                childrenByParent[screenGui.id]
                    .orEmpty()
                    .filter { it.isRenderableGuiObject() }
                    .sortedWith(compareBy<StudioNode> { it.prop("ZIndex").toIntRoblox(default = 1) }.thenBy { it.name })
                    .forEach { child ->
                        RobloxGuiNode(
                            node = child,
                            childrenByParent = childrenByParent,
                            parentWidthPx = widthPx,
                            parentHeightPx = heightPx,
                            selectedNodeId = selectedNodeId,
                            roblosecurityCookie = roblosecurityCookie,
                            onSelectNode = onSelectNode,
                            layoutRect = null
                        )
                    }
            }
        }
    }
}

@Composable
private fun RobloxGuiNode(
    node: StudioNode,
    childrenByParent: Map<String?, List<StudioNode>>,
    parentWidthPx: Float,
    parentHeightPx: Float,
    selectedNodeId: String?,
    roblosecurityCookie: String,
    onSelectNode: (StudioNode) -> Unit,
    layoutRect: GuiRect?
) {
    if (node.prop("Visible").isFalse()) return

    val density = LocalDensity.current
    val size = parseUDim2(node.prop("Size"), defaultOffsetX = 100f, defaultOffsetY = 100f)
    val position = parseUDim2(node.prop("Position"), defaultOffsetX = 0f, defaultOffsetY = 0f)
    val anchor = parseVector2(node.prop("AnchorPoint"))
    val widthPx = layoutRect?.width ?: (parentWidthPx * size.scaleX + size.offsetX).coerceAtLeast(0f)
    val heightPx = layoutRect?.height ?: (parentHeightPx * size.scaleY + size.offsetY).coerceAtLeast(0f)
    if (widthPx <= 0f || heightPx <= 0f) return

    val xPx = layoutRect?.x ?: (parentWidthPx * position.scaleX + position.offsetX - widthPx * anchor.x)
    val yPx = layoutRect?.y ?: (parentHeightPx * position.scaleY + position.offsetY - heightPx * anchor.y)
    val borderSizePx = node.prop("BorderSizePixel").toFloatRoblox(default = 0f).coerceAtLeast(0f)
    val borderModifier = if (borderSizePx > 0f) {
        Modifier.border(
            width = with(density) { borderSizePx.toDp() },
            color = parseColor(node.prop("BorderColor3"), Color.Black)
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .zIndex(node.prop("ZIndex").toIntRoblox(default = 1).toFloat())
            .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .pointerInput(node.id) {
                detectTapGestures {
                    onSelectNode(node)
                }
            }
            .then(guiBackgroundModifier(node))
            .then(borderModifier)
            .then(if (node.prop("ClipsDescendants").isTrue() || node.className == StudioNode.CLASS_SCROLLING_FRAME) Modifier.clipToBounds() else Modifier)
    ) {
        when (node.className) {
            StudioNode.CLASS_TEXT_LABEL,
            StudioNode.CLASS_TEXT_BUTTON,
            StudioNode.CLASS_TEXT_BOX -> GuiTextContent(node)

            StudioNode.CLASS_IMAGE_LABEL,
            StudioNode.CLASS_IMAGE_BUTTON -> GuiImageContent(
                node = node,
                roblosecurityCookie = roblosecurityCookie
            )
        }

        if (selectedNodeId == node.id) {
            GuiSelectionBox()
        }

        RenderGuiChildren(
            parentNode = node,
            childrenByParent = childrenByParent,
            parentWidthPx = widthPx,
            parentHeightPx = heightPx,
            selectedNodeId = selectedNodeId,
            roblosecurityCookie = roblosecurityCookie,
            onSelectNode = onSelectNode
        )
    }
}

@Composable
private fun RenderGuiChildren(
    parentNode: StudioNode,
    childrenByParent: Map<String?, List<StudioNode>>,
    parentWidthPx: Float,
    parentHeightPx: Float,
    selectedNodeId: String?,
    roblosecurityCookie: String,
    onSelectNode: (StudioNode) -> Unit
) {
    val children = childrenByParent[parentNode.id].orEmpty()
    val renderableChildren = children
        .filter { it.isRenderableGuiObject() }
        .sortedWith(compareBy<StudioNode> { it.prop("LayoutOrder").toIntRoblox(default = 0) }
            .thenBy { it.prop("ZIndex").toIntRoblox(default = 1) }
            .thenBy { it.name })
    val grid = children.firstOrNull { it.className == "UIGridLayout" }?.toGuiGridLayout()
    val canvasPosition = if (parentNode.className == StudioNode.CLASS_SCROLLING_FRAME) {
        parseVector2(parentNode.prop("CanvasPosition"))
    } else {
        GuiVector2(0f, 0f)
    }

    if (grid != null) {
        val cellWidth = (parentWidthPx * grid.cellSize.scaleX + grid.cellSize.offsetX).takeIf { it > 1f }
            ?: renderableChildren.firstOrNull()?.absoluteWidthIn(parentWidthPx)
            ?: 100f
        val cellHeight = (parentHeightPx * grid.cellSize.scaleY + grid.cellSize.offsetY).takeIf { it > 1f }
            ?: renderableChildren.firstOrNull()?.absoluteHeightIn(parentHeightPx)
            ?: 100f
        val paddingX = (parentWidthPx * grid.cellPadding.scaleX + grid.cellPadding.offsetX).coerceAtLeast(0f)
        val paddingY = (parentHeightPx * grid.cellPadding.scaleY + grid.cellPadding.offsetY).coerceAtLeast(0f)
        val columns = floor(((parentWidthPx + paddingX) / (cellWidth + paddingX)).coerceAtLeast(1f)).roundToInt().coerceAtLeast(1)

        renderableChildren.forEachIndexed { index, child ->
            val column = index % columns
            val row = index / columns
            val rect = GuiRect(
                x = column * (cellWidth + paddingX) - canvasPosition.x,
                y = row * (cellHeight + paddingY) - canvasPosition.y,
                width = cellWidth,
                height = cellHeight
            )
            RobloxGuiNode(
                node = child,
                childrenByParent = childrenByParent,
                parentWidthPx = parentWidthPx,
                parentHeightPx = parentHeightPx,
                selectedNodeId = selectedNodeId,
                roblosecurityCookie = roblosecurityCookie,
                onSelectNode = onSelectNode,
                layoutRect = rect
            )
        }
    } else {
        renderableChildren
            .sortedWith(compareBy<StudioNode> { it.prop("ZIndex").toIntRoblox(default = 1) }.thenBy { it.name })
            .forEach { child ->
                RobloxGuiNode(
                    node = child,
                    childrenByParent = childrenByParent,
                    parentWidthPx = parentWidthPx,
                    parentHeightPx = parentHeightPx,
                    selectedNodeId = selectedNodeId,
                    roblosecurityCookie = roblosecurityCookie,
                    onSelectNode = onSelectNode,
                    layoutRect = null
                )
            }
    }
}

@Composable
private fun BoxScope.ScreenGuiSelectionBox() {
    Box(
        modifier = Modifier
            .matchParentSize()
            .border(1.dp, Color(0xFF00A2FF).copy(alpha = 0.7f))
            .zIndex(20_000f)
    )
}

@Composable
private fun BoxScope.GuiSelectionBox() {
    Box(
        modifier = Modifier
            .matchParentSize()
            .border(1.dp, Color(0xFF00A2FF))
            .zIndex(20_000f)
    )

    val handleAlignments = listOf(
        Alignment.TopStart,
        Alignment.TopCenter,
        Alignment.TopEnd,
        Alignment.CenterStart,
        Alignment.CenterEnd,
        Alignment.BottomStart,
        Alignment.BottomCenter,
        Alignment.BottomEnd
    )
    handleAlignments.forEach { alignment ->
        Box(
            modifier = Modifier
                .align(alignment)
                .size(7.dp)
                .background(Color.White)
                .border(1.dp, Color(0xFF0078D4))
                .zIndex(20_001f)
        )
    }
}

@Composable
private fun GuiTextContent(node: StudioNode) {
    val textColor = parseColor(node.prop("TextColor3"), Color.Black)
        .copy(alpha = 1f - node.prop("TextTransparency").toFloatRoblox(default = 0f).coerceIn(0f, 1f))
    val textSize = node.prop("TextSize").toFloatRoblox(default = 14f).coerceIn(6f, 72f)
    val align = when (node.prop("TextXAlignment").lowercase()) {
        "left" -> TextAlign.Left
        "right" -> TextAlign.Right
        else -> TextAlign.Center
    }
    val contentAlignment = when (node.prop("TextYAlignment").lowercase()) {
        "top" -> when (align) {
            TextAlign.Left -> Alignment.TopStart
            TextAlign.Right -> Alignment.TopEnd
            else -> Alignment.TopCenter
        }
        "bottom" -> when (align) {
            TextAlign.Left -> Alignment.BottomStart
            TextAlign.Right -> Alignment.BottomEnd
            else -> Alignment.BottomCenter
        }
        else -> when (align) {
            TextAlign.Left -> Alignment.CenterStart
            TextAlign.Right -> Alignment.CenterEnd
            else -> Alignment.Center
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp),
        contentAlignment = contentAlignment
    ) {
        Text(
            text = node.prop("Text").ifBlank { node.name },
            color = textColor,
            fontSize = textSize.sp,
            fontWeight = if (node.prop("FontFace").contains("700")) FontWeight.Bold else FontWeight.Normal,
            textAlign = align,
            maxLines = if (node.prop("TextWrapped").isTrue()) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun GuiImageContent(
    node: StudioNode,
    roblosecurityCookie: String
) {
    val context = LocalContext.current
    val source = node.prop("Image")
        .ifBlank { node.prop("ImageId") }
        .ifBlank { node.prop("Texture") }
    val alpha = 1f - node.prop("ImageTransparency").toFloatRoblox(default = 0f).coerceIn(0f, 1f)
    var bitmap by remember(source, roblosecurityCookie) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(source, roblosecurityCookie) { mutableStateOf(false) }

    LaunchedEffect(source, roblosecurityCookie) {
        bitmap = null
        failed = false
        if (source.isNotBlank()) {
            runCatching {
                withContext(Dispatchers.IO) {
                    loadGuiImage(context, source, roblosecurityCookie)
                }
            }.onSuccess {
                bitmap = it
                failed = it == null
            }.onFailure {
                failed = true
            }
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = when (node.prop("ScaleType").lowercase()) {
                "fit" -> ContentScale.Fit
                "crop" -> ContentScale.Crop
                else -> ContentScale.FillBounds
            },
            alpha = alpha
        )
    } else if (source.isNotBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (failed) Color(0x55333333) else Color(0x33222222)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = node.name,
                color = Color(0xFFB8C4D8).copy(alpha = 0.75f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

private fun guiBackgroundModifier(node: StudioNode): Modifier {
    val transparency = node.prop("BackgroundTransparency").toFloatRoblox(default = 0f).coerceIn(0f, 1f)
    if (transparency >= 1f) return Modifier
    val color = parseColor(node.prop("BackgroundColor3"), Color.White).copy(alpha = 1f - transparency)
    return Modifier.background(color)
}

private fun loadGuiImage(
    context: Context,
    source: String,
    roblosecurityCookie: String
): ImageBitmap? {
    val path = resolveRobloxTextureAssetPath(source) ?: return null
    val bytes = when {
        path.startsWith("data:", ignoreCase = true) -> decodeDataUri(path)
        path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) ->
            resolveRobloxImageBytes(downloadBytes(path, roblosecurityCookie), roblosecurityCookie, linkedSetOf(path))
        else -> context.assets.open(path).use { it.readBytes() }
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

private fun decodeDataUri(uri: String): ByteArray {
    val payload = uri.substringAfter(",", "")
    return if (";base64" in uri.substringBefore(",", "")) {
        Base64.decode(payload, Base64.DEFAULT)
    } else {
        payload.toByteArray(Charsets.UTF_8)
    }
}

private fun resolveRobloxImageBytes(
    bytes: ByteArray,
    roblosecurityCookie: String,
    visited: MutableSet<String>
): ByteArray {
    if (looksLikeImage(bytes)) return bytes

    val nested = extractNestedTextureUri(bytes.toString(Charsets.UTF_8)) ?: return bytes
    val nestedPath = resolveRobloxTextureAssetPath(nested) ?: return bytes
    if (!visited.add(nestedPath)) return bytes
    return resolveRobloxImageBytes(downloadBytes(nestedPath, roblosecurityCookie), roblosecurityCookie, visited)
}

private fun downloadBytes(url: String, roblosecurityCookie: String): ByteArray {
    val request = Request.Builder()
        .url(url)
        .header("Accept", "*/*")
        .header("User-Agent", "RobloxStudio/WinInet RStudioApp/1.0")
        .apply {
            if (roblosecurityCookie.isNotBlank() && url.contains("assetdelivery.roblox.com", ignoreCase = true)) {
                header("Cookie", ".ROBLOSECURITY=$roblosecurityCookie")
            }
        }
        .get()
        .build()

    guiImageHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return ByteArray(0)
        return response.body?.bytes() ?: ByteArray(0)
    }
}

private fun extractNestedTextureUri(text: String): String? {
    Regex(
        """<Content\s+name=["'](?:Texture|Image)["'][\s\S]*?<url>(.*?)</url>""",
        setOf(RegexOption.IGNORE_CASE)
    ).find(text)?.groupValues?.getOrNull(1)?.trim()?.let {
        if (it.isNotBlank()) return it
    }

    Regex("""rbxassetid://(\d+)""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return "rbxassetid://$it" }

    Regex("""https?://(?:www\.)?roblox\.com/asset/\?id=(\d+)""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return "rbxassetid://$it" }

    return null
}

private fun looksLikeImage(bytes: ByteArray): Boolean =
    bytes.size >= 4 && (
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() ||
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ||
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        )

private fun StudioNode.isRenderableGuiObject(): Boolean =
    className == StudioNode.CLASS_FRAME ||
        className == StudioNode.CLASS_TEXT_LABEL ||
        className == StudioNode.CLASS_TEXT_BUTTON ||
        className == StudioNode.CLASS_TEXT_BOX ||
        className == StudioNode.CLASS_IMAGE_LABEL ||
        className == StudioNode.CLASS_IMAGE_BUTTON ||
        className == StudioNode.CLASS_SCROLLING_FRAME

private fun StudioNode.toGuiGridLayout(): GuiGridLayout =
    GuiGridLayout(
        cellSize = parseUDim2(prop("CellSize"), defaultOffsetX = 100f, defaultOffsetY = 100f),
        cellPadding = parseUDim2(prop("CellPadding"), defaultOffsetX = 5f, defaultOffsetY = 5f)
    )

private fun StudioNode.absoluteWidthIn(parentWidthPx: Float): Float {
    val size = parseUDim2(prop("Size"), defaultOffsetX = 100f, defaultOffsetY = 100f)
    return parentWidthPx * size.scaleX + size.offsetX
}

private fun StudioNode.absoluteHeightIn(parentHeightPx: Float): Float {
    val size = parseUDim2(prop("Size"), defaultOffsetX = 100f, defaultOffsetY = 100f)
    return parentHeightPx * size.scaleY + size.offsetY
}

private fun StudioNode.prop(name: String): String =
    nodeProperties.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

private fun String.isFalse(): Boolean =
    trim().lowercase() in setOf("false", "0", "no", "off")

private fun String.isNotFalse(): Boolean = !isFalse()

private fun String.isTrue(): Boolean =
    trim().lowercase() in setOf("true", "1", "yes", "on")

private fun String.toFloatRoblox(default: Float = 0f): Float =
    trim().toFloatOrNull() ?: Regex("""-?\d+(?:\.\d+)?""").find(this)?.value?.toFloatOrNull() ?: default

private fun String.toIntRoblox(default: Int = 0): Int =
    trim().toIntOrNull() ?: trim().toFloatOrNull()?.roundToInt() ?: default

private fun parseUDim2(
    raw: String,
    defaultOffsetX: Float,
    defaultOffsetY: Float
): UDim2Value {
    val scaleX = raw.namedFloat("scaleX", "xScale", "xs")
    val scaleY = raw.namedFloat("scaleY", "yScale", "ys")
    val offsetX = raw.namedFloat("offsetX", "xOffset", "xo")
    val offsetY = raw.namedFloat("offsetY", "yOffset", "yo")
    if (scaleX != null || scaleY != null || offsetX != null || offsetY != null) {
        return UDim2Value(
            scaleX = scaleX ?: 0f,
            scaleY = scaleY ?: 0f,
            offsetX = offsetX ?: defaultOffsetX,
            offsetY = offsetY ?: defaultOffsetY
        )
    }

    val numbers = Regex("""-?\d+(?:\.\d+)?""")
        .findAll(raw)
        .mapNotNull { it.value.toFloatOrNull() }
        .toList()

    return when {
        numbers.size >= 4 && raw.contains("UDim2", ignoreCase = true) -> UDim2Value(
            scaleX = numbers[0],
            scaleY = numbers[2],
            offsetX = numbers[1],
            offsetY = numbers[3]
        )
        numbers.size >= 4 -> UDim2Value(
            scaleX = numbers[0],
            scaleY = numbers[2],
            offsetX = numbers[1],
            offsetY = numbers[3]
        )
        else -> UDim2Value(0f, 0f, defaultOffsetX, defaultOffsetY)
    }
}

private fun parseVector2(raw: String): GuiVector2 {
    val x = raw.namedFloat("x")
    val y = raw.namedFloat("y")
    if (x != null || y != null) return GuiVector2(x ?: 0f, y ?: 0f)

    val numbers = Regex("""-?\d+(?:\.\d+)?""")
        .findAll(raw)
        .mapNotNull { it.value.toFloatOrNull() }
        .toList()
    return GuiVector2(numbers.getOrNull(0) ?: 0f, numbers.getOrNull(1) ?: 0f)
}

private fun String.namedFloat(vararg names: String): Float? {
    names.forEach { name ->
        Regex("""(?:^|[,\s;])${Regex.escape(name)}\s*=\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let { return it }
    }
    return null
}

private fun parseColor(raw: String, fallback: Color): Color {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return fallback

    if (trimmed.startsWith("#")) {
        val hex = trimmed.removePrefix("#")
        val expanded = if (hex.length == 3) {
            hex.map { "$it$it" }.joinToString("")
        } else {
            hex
        }
        expanded.toLongOrNull(16)?.let { value ->
            return Color(
                red = ((value shr 16) and 0xFF) / 255f,
                green = ((value shr 8) and 0xFF) / 255f,
                blue = (value and 0xFF) / 255f
            )
        }
    }

    val numbers = Regex("""-?\d+(?:\.\d+)?""")
        .findAll(trimmed)
        .mapNotNull { it.value.toFloatOrNull() }
        .take(3)
        .toList()
    if (numbers.size == 3) {
        val scale = if (numbers.any { it > 1f }) 255f else 1f
        return Color(
            red = (numbers[0] / scale).coerceIn(0f, 1f),
            green = (numbers[1] / scale).coerceIn(0f, 1f),
            blue = (numbers[2] / scale).coerceIn(0f, 1f)
        )
    }

    return fallback
}
