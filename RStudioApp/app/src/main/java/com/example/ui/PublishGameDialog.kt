package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.models.Place

private val PublishBg = Color(0xFF171A20)
private val PublishPanel = Color(0xFF12151A)
private val PublishSidebar = Color(0xFF242832)
private val PublishInput = Color(0xFF262A34)
private val PublishBorder = Color(0xFF343945)
private val PublishText = Color(0xFFE7E9EF)
private val PublishMuted = Color(0xFFB8BDC8)
private val PublishDim = Color(0xFF848A96)
private val PublishAccent = Color(0xFF293A8E)

@Composable
fun PublishGameDialog(
    place: Place?,
    initialRoblosecurityCookie: String = "",
    onDismiss: () -> Unit,
    onPublish: (
        name: String,
        description: String,
        roblosecurityCookie: String,
        openCloudApiKey: String,
        targetPlaceId: Long?
    ) -> Unit
) {
    var name by remember(place?.id) { mutableStateOf(place?.name.orEmpty().take(50)) }
    var description by remember(place?.id) { mutableStateOf(place?.description.orEmpty().take(1000)) }
    var roblosecurityCookie by remember(initialRoblosecurityCookie) { mutableStateOf(initialRoblosecurityCookie) }
    var openCloudApiKey by remember { mutableStateOf("") }
    var updateExisting by remember(place?.id) { mutableStateOf(place?.robloxPlaceId != null) }
    var targetPlaceId by remember(place?.id) { mutableStateOf(place?.robloxPlaceId?.toString().orEmpty()) }
    val resolvedTargetPlaceId = targetPlaceId.trim().toLongOrNull()
    val canPublish = name.isNotBlank() &&
        name.length <= 50 &&
        description.length <= 1000 &&
        roblosecurityCookie.isNotBlank() &&
        (!updateExisting || resolvedTargetPlaceId != null)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(min = 480.dp, max = 640.dp)
                .widthIn(max = 760.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(PublishBg)
                .border(BorderStroke(1.dp, Color(0xFF3A3F4C)), RoundedCornerShape(6.dp))
        ) {
            PublishTitleBar(onDismiss)

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .width(136.dp)
                        .fillMaxHeight()
                        .background(PublishSidebar)
                        .border(BorderStroke(0.5.dp, Color(0xFF101216)))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(PublishAccent)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("Basic Info", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                PublishBasicInfo(
                    name = name,
                    onNameChange = { name = it.take(50) },
                    description = description,
                    onDescriptionChange = { description = it.take(1000) },
                    roblosecurityCookie = roblosecurityCookie,
                    onRoblosecurityCookieChange = { roblosecurityCookie = it },
                    openCloudApiKey = openCloudApiKey,
                    onOpenCloudApiKeyChange = { openCloudApiKey = it },
                    updateExisting = updateExisting,
                    targetPlaceId = targetPlaceId,
                    onTargetPlaceIdChange = { input -> targetPlaceId = input.filter { it.isDigit() } },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }

            PublishFooter(
                canPublish = canPublish,
                updateExisting = updateExisting,
                onUpdateExisting = { updateExisting = !updateExisting },
                onCancel = onDismiss,
                onCreate = {
                    if (canPublish) {
                        onPublish(
                            name.trim(),
                            description.trim(),
                            roblosecurityCookie.trim(),
                            openCloudApiKey.trim(),
                            if (updateExisting) resolvedTargetPlaceId else null
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun PublishTitleBar(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(Color(0xFFE9ECF2))
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Publish, contentDescription = null, tint = Color(0xFF262A34), modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Publish Game",
            color = Color(0xFF1A1D23),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF1A1D23), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PublishBasicInfo(
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    roblosecurityCookie: String,
    onRoblosecurityCookieChange: (String) -> Unit,
    openCloudApiKey: String,
    onOpenCloudApiKeyChange: (String) -> Unit,
    updateExisting: Boolean,
    targetPlaceId: String,
    onTargetPlaceIdChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(PublishPanel)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text("Basic Info", color = PublishText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(14.dp))

        PublishRow(label = "Name") {
            Column {
                PublishTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("${name.length}/50", color = PublishDim, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        PublishRow(label = "Description") {
            Column {
                PublishTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("${description.length}/1000", color = PublishDim, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(PublishBorder))

        Spacer(modifier = Modifier.height(16.dp))
        PublishRow(label = "Creator") {
            Text("Me", color = PublishMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(18.dp))
        PublishRow(label = "Cookie") {
            Column {
                PublishTextField(
                    value = roblosecurityCookie,
                    onValueChange = onRoblosecurityCookieChange,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = ".ROBLOSECURITY is used for create and settings requests.",
                    color = PublishDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        PublishRow(label = "API Key") {
            Column {
                PublishTextField(
                    value = openCloudApiKey,
                    onValueChange = onOpenCloudApiKeyChange,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Optional. Uses Open Cloud when filled; otherwise uses Roblox user-auth publishing.",
                    color = PublishDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        if (updateExisting) {
            Spacer(modifier = Modifier.height(18.dp))
            PublishRow(label = "Place ID") {
                Column {
                    PublishTextField(
                        value = targetPlaceId,
                        onValueChange = onTargetPlaceIdChange,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(28.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Existing Roblox place to update.", color = PublishDim, fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        PublishRow(label = "Devices") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DeviceLabel("Computer")
                    DeviceLabel("Tablet")
                    DeviceLabel("VR")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DeviceLabel("Phone")
                    DeviceLabel("Console")
                    Spacer(modifier = Modifier.width(80.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(PublishBorder))

        Spacer(modifier = Modifier.height(16.dp))
        PublishRow(label = "Team Create") {
            Text(
                text = "Enables collaboration and autosave to cloud Learn more",
                color = PublishMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PublishRow(
    label: String,
    content: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            color = PublishText,
            fontSize = 14.sp,
            modifier = Modifier.width(132.dp).padding(top = 4.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun PublishTextField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    modifier: Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = PublishText, fontSize = 12.sp, lineHeight = 17.sp),
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(PublishInput)
            .border(BorderStroke(1.dp, Color(0xFF20242C)), RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

@Composable
private fun DeviceLabel(text: String) {
    Text(
        text = text,
        color = PublishMuted,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier.width(82.dp)
    )
}

@Composable
private fun PublishFooter(
    canPublish: Boolean,
    updateExisting: Boolean,
    onUpdateExisting: () -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(PublishBg)
            .border(BorderStroke(0.5.dp, PublishBorder))
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (updateExisting) "Create new game..." else "Update existing game...",
            color = Color(0xFF4E83FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onUpdateExisting)
        )
        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onCancel,
            border = BorderStroke(1.dp, PublishBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PublishText),
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier.width(96.dp).height(34.dp)
        ) {
            Text("Cancel", fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Button(
            onClick = onCreate,
            enabled = canPublish,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2B60D3),
                disabledContainerColor = Color(0xFF23262D),
                disabledContentColor = Color(0xFF666B75)
            ),
            shape = RoundedCornerShape(3.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
            modifier = Modifier.width(96.dp).height(34.dp)
        ) {
            Text(if (updateExisting) "Publish" else "Create", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
