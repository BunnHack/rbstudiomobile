package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodels.StudioViewModel
import kotlin.math.max

/**
 * Code editor for one script document. The editable text lives in [state], owned by
 * the screen, so switching to the Viewport tab and back preserves it (and preserves
 * cursor/scroll position via the shared scroll states below).
 *
 * The document is persisted automatically: 600ms after the last edit, and when the
 * editor leaves the composition (tab switch / close) with unsaved changes.
 */
@Composable
fun ScriptEditor(
    viewModel: StudioViewModel,
    documentId: String,
    title: String,
    targetPath: String,
    state: ScriptEditorState,
    onSaveSource: (String) -> Unit
) {
    val code = state.code
    var dirty by remember(documentId) { mutableStateOf(false) }
    val lineCount = max(1, code.lines().size)

    // Debounced auto-save while editing.
    LaunchedEffect(code) {
        if (dirty) {
            kotlinx.coroutines.delay(600)
            onSaveSource(state.code)
            dirty = false
        }
    }

    // Flush pending changes when leaving the composition (tab switch / close).
    androidx.compose.runtime.DisposableEffect(documentId) {
        onDispose {
            if (dirty) {
                onSaveSource(state.code)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EditorChrome
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CodeEditorPane(
                code = code,
                lineCount = lineCount,
                onCodeChange = {
                    state.code = it
                    dirty = true
                },
                modifier = Modifier.weight(1f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(StatusBarBackground)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = MutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Lua  |  $lineCount lines  |  $targetPath",
                    color = MutedText,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun CodeEditorPane(
    code: String,
    lineCount: Int,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val horizontalScroll = rememberScrollState()
    val lineNumbers = remember(lineCount) {
        (1..lineCount).joinToString("\n") { it.toString() }
    }

    // Gutter + code scroll vertically TOGETHER on one shared ScrollState, so the line
    // numbers can never drift from the text. The code field wraps its full content
    // height (no LazyColumn / no heightIn(min)), which is what made earlier versions
    // clip to a fixed number of lines.
    val sharedVerticalScroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(EditorBackground)
            .verticalScroll(sharedVerticalScroll)
    ) {
        Text(
            text = lineNumbers,
            color = LineNumberText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier
                .width(43.dp)
                .background(LineGutterBackground)
                .padding(top = 8.dp, end = 8.dp)
        )

        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            cursorBrush = SolidColor(Color(0xFF4FB2FF)),
            textStyle = TextStyle(
                color = CodeText,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp
            ),
            modifier = Modifier
                .horizontalScroll(horizontalScroll)
                .padding(top = 8.dp, start = 10.dp, end = 20.dp, bottom = 18.dp)
                .widthIn(min = 2000.dp)
        )
    }
}

private val EditorChrome = Color(0xFF1B1B1B)
private val EditorBackground = Color(0xFF1F1F1F)
private val LineGutterBackground = Color(0xFF181818)
private val StatusBarBackground = Color(0xFF202020)
private val LineNumberText = Color(0xFF9D9D9D)
private val MutedText = Color(0xFFA8A8A8)
private val CodeText = Color(0xFFB6F4C4)
