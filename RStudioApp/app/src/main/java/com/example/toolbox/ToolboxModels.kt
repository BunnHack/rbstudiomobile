package com.example.toolbox

data class ToolboxAssetType(
    val key: String,
    val label: String,
    val assetTypeId: Int
)

object ToolboxAssetTypes {
    val Models = ToolboxAssetType("models", "Models", 10)
    val Images = ToolboxAssetType("images", "Images", 13)
    val Meshes = ToolboxAssetType("meshes", "Meshes", 4)
    val Audio = ToolboxAssetType("audio", "Audio", 3)
    val Plugins = ToolboxAssetType("plugins", "Plugins", 38)

    val all = listOf(Models, Images, Meshes, Audio, Plugins)
}

data class ToolboxAsset(
    val assetId: Long,
    val name: String,
    val creatorName: String = "",
    val assetTypeId: Int? = null,
    val assetTypeName: String = "",
    val thumbnailUrl: String? = null
) {
    val canInsertAsModel: Boolean
        get() = assetTypeId == null || assetTypeId == ToolboxAssetTypes.Models.assetTypeId
}

data class ToolboxSearchPage(
    val assets: List<ToolboxAsset>,
    val nextPageCursor: String?
)

data class ToolboxSearchState(
    val query: String = "",
    val selectedType: ToolboxAssetType = ToolboxAssetTypes.Models,
    val roblosecurityCookie: String = "",
    val results: List<ToolboxAsset> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val insertingAssetId: Long? = null,
    val error: String? = null,
    val nextPageCursor: String? = null
)
