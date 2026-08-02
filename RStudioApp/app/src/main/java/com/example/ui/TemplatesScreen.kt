package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Games
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.models.Place
import com.example.viewmodels.StudioViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LauncherBg = Color(0xFF191A1F)
private val LauncherTop = Color(0xFF2A2A2B)
private val LauncherSidebar = Color(0xFF1E1F24)
private val LauncherCard = Color(0xFF2A2C33)
private val LauncherBorder = Color(0xFF383A43)
private val LauncherText = Color(0xFFE8E8EA)
private val LauncherMuted = Color(0xFFB4B6BE)
private val LauncherDim = Color(0xFF777A83)
private val LauncherAccent = Color(0xFF3D7BFF)

private enum class LauncherSection {
    Home,
    Experiences,
    Templates,
    Archive
}

private data class StartTemplateData(
    val id: String,
    val title: String,
    val templateId: String,
    val kind: String,
    val subtitle: String,
    val accent: Color
)

private data class DiscoverCardData(
    val title: String,
    val body: String,
    val kind: String,
    val accent: Color
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(viewModel: StudioViewModel) {
    val places by viewModel.places.collectAsState()
    val formatter = remember { SimpleDateFormat("MM/dd/yyyy h:mm a", Locale.getDefault()) }
    val templates = remember { launcherTemplates() }
    val discoverCards = remember { discoverCards() }

    var activeSection by remember { mutableStateOf(LauncherSection.Home) }
    var createTemplate by remember { mutableStateOf<StartTemplateData?>(null) }
    var newPlaceName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Place?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var menuPlaceId by remember { mutableStateOf<Int?>(null) }

    fun openCreateDialog(template: StartTemplateData) {
        createTemplate = template
        newPlaceName = template.title
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LauncherBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LauncherTopBar()
            Row(modifier = Modifier.fillMaxSize()) {
                LauncherNav(
                    selected = activeSection,
                    onSelected = { activeSection = it },
                    onNewFile = { openCreateDialog(templates.first()) }
                )

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (activeSection) {
                        LauncherSection.Home -> HomeLauncherContent(
                            places = places,
                            formatter = formatter,
                            templates = templates,
                            discoverCards = discoverCards,
                            onSeeExperiences = { activeSection = LauncherSection.Experiences },
                            onSeeTemplates = { activeSection = LauncherSection.Templates },
                            onOpenPlace = viewModel::openPlace,
                            onCreate = ::openCreateDialog,
                            menuPlaceId = menuPlaceId,
                            onMenuPlaceId = { menuPlaceId = it },
                            onRename = { place ->
                                renameTarget = place
                                renameValue = place.name
                            },
                            onDelete = viewModel::deletePlace
                        )
                        LauncherSection.Experiences -> ExperiencesContent(
                            places = places,
                            formatter = formatter,
                            onOpenPlace = viewModel::openPlace,
                            menuPlaceId = menuPlaceId,
                            onMenuPlaceId = { menuPlaceId = it },
                            onRename = { place ->
                                renameTarget = place
                                renameValue = place.name
                            },
                            onDelete = viewModel::deletePlace
                        )
                        LauncherSection.Templates -> TemplatesContent(
                            templates = templates,
                            onCreate = ::openCreateDialog
                        )
                        LauncherSection.Archive -> ArchiveContent()
                    }
                }
            }
        }

        val selectedTemplate = createTemplate
        if (selectedTemplate != null) {
            CreatePlaceDialog(
                template = selectedTemplate,
                name = newPlaceName,
                onNameChange = { newPlaceName = it },
                onDismiss = { createTemplate = null },
                onCreate = {
                    if (newPlaceName.isNotBlank()) {
                        viewModel.createNewPlace(newPlaceName.trim(), selectedTemplate.templateId)
                        createTemplate = null
                    }
                }
            )
        }

        val target = renameTarget
        if (target != null) {
            RenamePlaceDialog(
                name = renameValue,
                onNameChange = { renameValue = it },
                onDismiss = { renameTarget = null },
                onSave = {
                    if (renameValue.isNotBlank()) {
                        viewModel.updatePlace(target.copy(name = renameValue.trim()))
                        renameTarget = null
                    }
                }
            )
        }
    }
}

@Composable
private fun LauncherTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(LauncherTop)
            .border(BorderStroke(0.5.dp, Color(0xFF151515))),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FILE",
            color = LauncherMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .height(24.dp)
                .background(Color(0xFFF3F0A7))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Roblox Internal", color = Color(0xFF151515), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "MeshOfPaul",
            color = LauncherMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun LauncherNav(
    selected: LauncherSection,
    onSelected: (LauncherSection) -> Unit,
    onNewFile: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(264.dp)
            .fillMaxHeight()
            .background(LauncherSidebar)
            .border(BorderStroke(0.5.dp, Color(0xFF30323A)))
            .padding(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onNewFile)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(LauncherAccent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text("New File", color = LauncherText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(28.dp))
        LauncherNavItem(
            label = "Home",
            icon = Icons.Default.Home,
            selected = selected == LauncherSection.Home,
            modifier = Modifier.testTag("home_tab"),
            onClick = { onSelected(LauncherSection.Home) }
        )
        LauncherNavItem(
            label = "Experiences",
            icon = Icons.Default.Games,
            selected = selected == LauncherSection.Experiences,
            modifier = Modifier.testTag("saved_places_tab"),
            onClick = { onSelected(LauncherSection.Experiences) }
        )
        LauncherNavItem(
            label = "Templates",
            icon = Icons.Default.Layers,
            selected = selected == LauncherSection.Templates,
            modifier = Modifier.testTag("templates_tab"),
            onClick = { onSelected(LauncherSection.Templates) }
        )
        LauncherNavItem(
            label = "Archive",
            icon = Icons.Default.Archive,
            selected = selected == LauncherSection.Archive,
            onClick = { onSelected(LauncherSection.Archive) }
        )
    }
}

@Composable
private fun LauncherNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) Color.Black else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Color.White else LauncherMuted, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = if (selected) Color.White else LauncherMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HomeLauncherContent(
    places: List<Place>,
    formatter: SimpleDateFormat,
    templates: List<StartTemplateData>,
    discoverCards: List<DiscoverCardData>,
    onSeeExperiences: () -> Unit,
    onSeeTemplates: () -> Unit,
    onOpenPlace: (Place) -> Unit,
    onCreate: (StartTemplateData) -> Unit,
    menuPlaceId: Int?,
    onMenuPlaceId: (Int?) -> Unit,
    onRename: (Place) -> Unit,
    onDelete: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        SectionHeader(
            title = "My Recent Experiences",
            action = "See All",
            onAction = onSeeExperiences
        )
        Spacer(modifier = Modifier.height(16.dp))
        RecentExperiencesRow(
            places = places,
            formatter = formatter,
            onOpenPlace = onOpenPlace,
            menuPlaceId = menuPlaceId,
            onMenuPlaceId = onMenuPlaceId,
            onRename = onRename,
            onDelete = onDelete
        )

        Spacer(modifier = Modifier.height(22.dp))
        SectionHeader(
            title = "Open a Template",
            subtitle = "Start from a ready-made workspace or a clean baseplate.",
            action = "See All",
            onAction = onSeeTemplates
        )
        Spacer(modifier = Modifier.height(16.dp))
        TemplateRow(templates = templates.take(6), onCreate = onCreate)

        Spacer(modifier = Modifier.height(22.dp))
        SectionHeader(
            title = "Discover Studio",
            subtitle = "Tutorials, documentation, and resources for building."
        )
        Spacer(modifier = Modifier.height(14.dp))
        DiscoverRow(cards = discoverCards)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ExperiencesContent(
    places: List<Place>,
    formatter: SimpleDateFormat,
    onOpenPlace: (Place) -> Unit,
    menuPlaceId: Int?,
    onMenuPlaceId: (Int?) -> Unit,
    onRename: (Place) -> Unit,
    onDelete: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        SectionHeader(
            title = "Experiences",
            subtitle = "Recent and saved places in this local workspace."
        )
        Spacer(modifier = Modifier.height(18.dp))
        if (places.isEmpty()) {
            EmptyState("No experiences yet.")
        } else {
            ResponsiveGrid(
                items = places,
                minCardWidth = 238.dp
            ) { place ->
                RecentExperienceCard(
                    place = place,
                    formatter = formatter,
                    onOpenPlace = onOpenPlace,
                    menuExpanded = menuPlaceId == place.id,
                    onMenuExpanded = { expanded -> onMenuPlaceId(if (expanded) place.id else null) },
                    onRename = onRename,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun TemplatesContent(
    templates: List<StartTemplateData>,
    onCreate: (StartTemplateData) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        SectionHeader(
            title = "Templates",
            subtitle = "Choose a starter scene and launch the editor."
        )
        Spacer(modifier = Modifier.height(18.dp))
        ResponsiveGrid(
            items = templates,
            minCardWidth = 220.dp
        ) { template ->
            TemplateCard(template = template, onCreate = onCreate)
        }
    }
}

@Composable
private fun ArchiveContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        SectionHeader(title = "Archive", subtitle = "Archived local experiences will appear here.")
        Spacer(modifier = Modifier.height(18.dp))
        EmptyState("Archive is empty.")
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = LauncherText,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = subtitle, color = LauncherMuted, fontSize = 12.sp)
            }
        }
        if (action != null && onAction != null) {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF202229)),
                border = BorderStroke(1.dp, LauncherBorder),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(action, color = LauncherText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RecentExperiencesRow(
    places: List<Place>,
    formatter: SimpleDateFormat,
    onOpenPlace: (Place) -> Unit,
    menuPlaceId: Int?,
    onMenuPlaceId: (Int?) -> Unit,
    onRename: (Place) -> Unit,
    onDelete: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (places.isEmpty()) {
            EmptyRecentCard()
        } else {
            places.take(6).forEach { place ->
                RecentExperienceCard(
                    place = place,
                    formatter = formatter,
                    onOpenPlace = onOpenPlace,
                    menuExpanded = menuPlaceId == place.id,
                    onMenuExpanded = { expanded -> onMenuPlaceId(if (expanded) place.id else null) },
                    onRename = onRename,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun RecentExperienceCard(
    place: Place,
    formatter: SimpleDateFormat,
    onOpenPlace: (Place) -> Unit,
    menuExpanded: Boolean,
    onMenuExpanded: (Boolean) -> Unit,
    onRename: (Place) -> Unit,
    onDelete: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .width(200.dp)
            .height(310.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onOpenPlace(place) },
        colors = CardDefaults.cardColors(containerColor = LauncherCard),
        border = BorderStroke(1.dp, Color(0xFF2F3139)),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ExperienceThumbnail(
                kind = place.templateId,
                title = place.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(178.dp)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    PrivacyPill(isPublic = place.templateId != "baseplate" && place.templateId != "classic-baseplate")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = place.name,
                        color = LauncherText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = "Modified ${formatter.format(Date(place.lastSaved))}",
                        color = LauncherDim,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@Local Workspace",
                        color = LauncherDim,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
                Box {
                    IconButton(
                        onClick = { onMenuExpanded(true) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Place menu", tint = LauncherMuted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuExpanded(false) },
                        modifier = Modifier.background(Color(0xFF25272E))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename", color = LauncherText, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = LauncherMuted, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                onMenuExpanded(false)
                                onRename(place)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = Color(0xFFFF6B6B), fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp)) },
                            onClick = {
                                onMenuExpanded(false)
                                onDelete(place.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyRecentCard() {
    Card(
        modifier = Modifier
            .width(200.dp)
            .height(310.dp),
        colors = CardDefaults.cardColors(containerColor = LauncherCard),
        border = BorderStroke(1.dp, Color(0xFF2F3139)),
        shape = RoundedCornerShape(6.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .border(3.dp, Color(0xFFDADCE2), RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFFDADCE2), modifier = Modifier.size(48.dp))
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text("No recent files", color = LauncherMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TemplateRow(
    templates: List<StartTemplateData>,
    onCreate: (StartTemplateData) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        templates.forEach { template ->
            TemplateCard(template = template, onCreate = onCreate)
        }
    }
}

@Composable
private fun TemplateCard(
    template: StartTemplateData,
    onCreate: (StartTemplateData) -> Unit
) {
    Card(
        modifier = Modifier
            .width(200.dp)
            .height(226.dp)
            .testTag("template_${template.id}")
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCreate(template) },
        colors = CardDefaults.cardColors(containerColor = LauncherCard),
        border = BorderStroke(1.dp, Color(0xFF2F3139)),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            ExperienceThumbnail(
                kind = template.kind,
                title = template.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = template.title,
                color = LauncherText,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = template.subtitle,
                color = LauncherDim,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(template.accent)
            )
        }
    }
}

@Composable
private fun DiscoverRow(cards: List<DiscoverCardData>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        cards.forEach { card ->
            DiscoverCard(card)
        }
    }
}

@Composable
private fun DiscoverCard(card: DiscoverCardData) {
    Card(
        modifier = Modifier
            .width(200.dp)
            .height(238.dp),
        colors = CardDefaults.cardColors(containerColor = LauncherCard),
        border = BorderStroke(1.dp, Color(0xFF2F3139)),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            DiscoverThumbnail(
                kind = card.kind,
                accent = card.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(card.title, color = LauncherText, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(5.dp))
            Text(card.body, color = LauncherMuted, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PrivacyPill(isPublic: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (isPublic) "Public" else "Private",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun <T> ResponsiveGrid(
    items: List<T>,
    minCardWidth: Dp,
    itemContent: @Composable (T) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnCount = maxOf(1, (maxWidth / minCardWidth).toInt())
        val spacing = 12.dp
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columnCount).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing), modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { item ->
                        Box(modifier = Modifier.weight(1f)) {
                            itemContent(item)
                        }
                    }
                    repeat(columnCount - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF202229))
            .border(BorderStroke(1.dp, LauncherBorder), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = LauncherMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ExperienceThumbnail(
    kind: String,
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color(0xFF15171D))
    ) {
        when {
            kind.contains("obby", ignoreCase = true) || title.contains("obby", ignoreCase = true) -> ObbyThumbnail()
            kind.contains("mars", ignoreCase = true) || title.contains("mars", ignoreCase = true) -> MarsThumbnail()
            kind.contains("terrain", ignoreCase = true) || title.contains("terrain", ignoreCase = true) -> TerrainThumbnail()
            kind.contains("fps", ignoreCase = true) || title.contains("fps", ignoreCase = true) -> FpsThumbnail()
            kind.contains("platform", ignoreCase = true) || title.contains("platform", ignoreCase = true) -> PlatformerThumbnail()
            else -> BaseplateThumbnail()
        }
    }
}

@Composable
private fun BaseplateThumbnail() {
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF59B7F0), Color(0xFF8FA0AA))))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val horizon = size.height * 0.46f
            drawRect(Color(0xFF6B707A), topLeft = Offset(0f, horizon), size = androidx.compose.ui.geometry.Size(size.width, size.height - horizon))
            val gridColor = Color(0xFF444952)
            for (i in 0..9) {
                val x = size.width * i / 9f
                drawLine(gridColor, Offset(x, horizon), Offset(size.width * 0.5f + (x - size.width * 0.5f) * 1.8f, size.height), 1f)
            }
            for (i in 0..7) {
                val y = horizon + (size.height - horizon) * i / 7f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            }
            drawRoundRect(Color(0xFFE6E8EA), topLeft = Offset(size.width * 0.38f, size.height * 0.63f), size = androidx.compose.ui.geometry.Size(size.width * 0.24f, size.height * 0.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f))
            drawLine(Color(0xFF333741), Offset(size.width * 0.5f, size.height * 0.65f), Offset(size.width * 0.5f, size.height * 0.73f), 3f, cap = StrokeCap.Round)
            drawLine(Color(0xFF333741), Offset(size.width * 0.45f, size.height * 0.68f), Offset(size.width * 0.55f, size.height * 0.68f), 3f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun ObbyThumbnail() {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF93C8E9))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFF6C7480), topLeft = Offset(0f, size.height * 0.55f), size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.45f))
            val colors = listOf(Color(0xFFFFCC00), Color(0xFF00D1FF), Color(0xFFFF4B4B), Color(0xFF9C5BFF))
            colors.forEachIndexed { index, color ->
                val x = size.width * (0.18f + index * 0.18f)
                val y = size.height * (0.72f - index * 0.07f)
                drawRoundRect(color, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(size.width * 0.14f, size.height * 0.08f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
            }
            drawCircle(Color(0xFF00FFCC), radius = size.minDimension * 0.08f, center = Offset(size.width * 0.76f, size.height * 0.42f))
        }
    }
}

@Composable
private fun MarsThumbnail() {
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFD69B7B), Color(0xFF8E4A2E))))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFF7D3B26), topLeft = Offset(0f, size.height * 0.48f), size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.52f))
            drawCircle(Color(0xFFE9EEF1).copy(alpha = 0.85f), radius = size.minDimension * 0.17f, center = Offset(size.width * 0.45f, size.height * 0.52f))
            drawRect(Color(0xFF4F5964), topLeft = Offset(size.width * 0.35f, size.height * 0.52f), size = androidx.compose.ui.geometry.Size(size.width * 0.2f, size.height * 0.16f))
            drawRoundRect(Color(0xFF00E5FF), topLeft = Offset(size.width * 0.69f, size.height * 0.28f), size = androidx.compose.ui.geometry.Size(size.width * 0.06f, size.height * 0.4f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f))
            drawCircle(Color(0xFF00E5FF).copy(alpha = 0.35f), radius = size.minDimension * 0.18f, center = Offset(size.width * 0.72f, size.height * 0.34f))
        }
    }
}

@Composable
private fun TerrainThumbnail() {
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF79C7E8), Color(0xFFB7D4B2))))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFF6CAD4E), topLeft = Offset(0f, size.height * 0.48f), size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.52f))
            for (i in 0..90) {
                val x = (i * 37 % 100) / 100f * size.width
                val y = size.height * (0.5f + ((i * 19 % 50) / 100f))
                drawLine(Color(0xFF9DDE6C), Offset(x, y + 18f), Offset(x + 4f, y), 2f)
            }
        }
    }
}

@Composable
private fun PlatformerThumbnail() {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF71C7E8))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFF86909C), topLeft = Offset(0f, size.height * 0.58f), size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.42f))
            drawRoundRect(Color(0xFF45B39D), topLeft = Offset(size.width * 0.05f, size.height * 0.45f), size = androidx.compose.ui.geometry.Size(size.width * 0.42f, size.height * 0.13f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
            drawRoundRect(Color(0xFFFFD23F), topLeft = Offset(size.width * 0.54f, size.height * 0.32f), size = androidx.compose.ui.geometry.Size(size.width * 0.32f, size.height * 0.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
            drawCircle(Color(0xFF1D1F25), size.minDimension * 0.07f, Offset(size.width * 0.31f, size.height * 0.32f))
        }
    }
}

@Composable
private fun FpsThumbnail() {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFA5B1C2))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFFCDD2D8), topLeft = Offset(0f, size.height * 0.58f), size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.42f))
            drawRoundRect(Color(0xFFFFFFFF), topLeft = Offset(size.width * 0.16f, size.height * 0.48f), size = androidx.compose.ui.geometry.Size(size.width * 0.20f, size.height * 0.18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
            drawRoundRect(Color(0xFFFFFFFF), topLeft = Offset(size.width * 0.62f, size.height * 0.42f), size = androidx.compose.ui.geometry.Size(size.width * 0.18f, size.height * 0.22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
            drawLine(Color(0xFF2D67FF), Offset(size.width * 0.46f, size.height * 0.54f), Offset(size.width * 0.74f, size.height * 0.35f), 10f, cap = StrokeCap.Round)
            drawLine(Color(0xFFFF52C7), Offset(size.width * 0.56f, size.height * 0.52f), Offset(size.width * 0.78f, size.height * 0.49f), 8f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun DiscoverThumbnail(kind: String, accent: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color(0xFF15171D))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (kind) {
                "sketch" -> {
                    drawRect(Color(0xFFF2F2F2))
                    val stroke = Stroke(width = 3f, cap = StrokeCap.Round)
                    repeat(8) { index ->
                        val x = size.width * (0.12f + index * 0.1f)
                        drawCircle(Color(0xFF202229), radius = size.minDimension * 0.035f, center = Offset(x, size.height * (0.25f + (index % 3) * 0.18f)), style = stroke)
                    }
                    drawRect(Color.Black, topLeft = Offset(size.width * 0.45f, size.height * 0.5f), size = androidx.compose.ui.geometry.Size(size.width * 0.2f, size.height * 0.18f))
                }
                "docs" -> {
                    drawRect(Color(0xFF0D1C4A))
                    drawRect(accent, topLeft = Offset(size.width * 0.2f, size.height * 0.18f), size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.46f))
                    drawLine(Color.White.copy(alpha = 0.8f), Offset(size.width * 0.3f, size.height * 0.75f), Offset(size.width * 0.7f, size.height * 0.75f), 4f)
                }
                "avatar" -> {
                    drawRect(Color(0xFF23433E))
                    val face = Path().apply {
                        moveTo(size.width * 0.32f, size.height * 0.18f)
                        cubicTo(size.width * 0.72f, size.height * 0.08f, size.width * 0.82f, size.height * 0.68f, size.width * 0.45f, size.height * 0.86f)
                        cubicTo(size.width * 0.23f, size.height * 0.6f, size.width * 0.2f, size.height * 0.32f, size.width * 0.32f, size.height * 0.18f)
                    }
                    drawPath(face, accent.copy(alpha = 0.65f))
                    drawPath(face, Color(0xFFFF6B6B), style = Stroke(width = 2f))
                }
                else -> {
                    drawRect(Brush.verticalGradient(listOf(accent.copy(alpha = 0.9f), Color(0xFF1D1F25))))
                    drawCircle(Color.Black.copy(alpha = 0.72f), size.minDimension * 0.2f, Offset(size.width * 0.52f, size.height * 0.42f))
                    drawLine(LauncherAccent, Offset(size.width * 0.36f, size.height * 0.64f), Offset(size.width * 0.72f, size.height * 0.64f), 5f, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun CreatePlaceDialog(
    template: StartTemplateData,
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Experience", color = LauncherText, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(template.title, color = LauncherMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    colors = launcherTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onCreate,
                colors = ButtonDefaults.buttonColors(containerColor = LauncherAccent),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = LauncherMuted)
            }
        },
        containerColor = Color(0xFF24262D),
        textContentColor = LauncherText
    )
}

@Composable
private fun RenamePlaceDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Experience", color = LauncherText, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                colors = launcherTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = LauncherAccent),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = LauncherMuted)
            }
        },
        containerColor = Color(0xFF24262D),
        textContentColor = LauncherText
    )
}

@Composable
private fun launcherTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LauncherText,
    unfocusedTextColor = LauncherText,
    focusedBorderColor = LauncherAccent,
    unfocusedBorderColor = LauncherBorder,
    focusedContainerColor = Color(0xFF1A1C22),
    unfocusedContainerColor = Color(0xFF1A1C22),
    cursorColor = LauncherAccent
)

private fun launcherTemplates(): List<StartTemplateData> = listOf(
    StartTemplateData(
        id = "baseplate",
        title = "Baseplate",
        templateId = "baseplate",
        kind = "baseplate",
        subtitle = "Clean starting grid",
        accent = Color(0xFF79B7EF)
    ),
    StartTemplateData(
        id = "classic-baseplate",
        title = "Classic Baseplate",
        templateId = "classic-baseplate",
        kind = "classic",
        subtitle = "Standard block plate",
        accent = Color(0xFF9AA0A6)
    ),
    StartTemplateData(
        id = "flat-terrain",
        title = "Flat Terrain",
        templateId = "mars",
        kind = "terrain",
        subtitle = "Open terrain scene",
        accent = Color(0xFF6CAD4E)
    ),
    StartTemplateData(
        id = "platformer",
        title = "Platformer",
        templateId = "obby",
        kind = "platform",
        subtitle = "Jumping course layout",
        accent = Color(0xFF45B39D)
    ),
    StartTemplateData(
        id = "laser-tag",
        title = "Laser Tag",
        templateId = "obby",
        kind = "fps",
        subtitle = "Arena-style starter",
        accent = Color(0xFFFF52C7)
    ),
    StartTemplateData(
        id = "fps-system",
        title = "FPS System",
        templateId = "baseplate",
        kind = "fps",
        subtitle = "Combat prototype base",
        accent = Color(0xFF3D7BFF)
    )
)

private fun discoverCards(): List<DiscoverCardData> = listOf(
    DiscoverCardData(
        title = "Sketch Series",
        body = "Short building lessons and workflow notes for Roblox Studio.",
        kind = "sketch",
        accent = Color(0xFFE6E6E6)
    ),
    DiscoverCardData(
        title = "Roblox Principles",
        body = "Core structure for experiences, scripts, and player sessions.",
        kind = "docs",
        accent = Color(0xFF277DFF)
    ),
    DiscoverCardData(
        title = "Avatar Documentation",
        body = "Character rigs, accessories, and avatar setup references.",
        kind = "avatar",
        accent = Color(0xFF68A878)
    ),
    DiscoverCardData(
        title = "Engine Documentation",
        body = "Engine APIs, services, physics behavior, and scripting reference.",
        kind = "engine",
        accent = Color(0xFFFF865C)
    )
)
