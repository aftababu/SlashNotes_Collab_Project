package com.slashnote.app.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slashnote.app.ui.theme.*

@Composable
fun InNoteSearchBar(
    query: String,
    matchCount: Int,
    currentMatchIndex: Int,
    onQueryChange: (String) -> Unit,
    onNextMatch: () -> Unit,
    onPrevMatch: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            color = StitchSurfaceSecondary,
            border = BorderStroke(1.dp, StitchBorder),
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search Input Field
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("search...", fontSize = 13.sp, color = StitchTextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = StitchTextMuted, modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StitchCardBg,
                        unfocusedContainerColor = StitchCardBg,
                        focusedBorderColor = StitchBorder,
                        unfocusedBorderColor = StitchBorder
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Divider 1
                VerticalDivider(color = StitchBorder, modifier = Modifier.height(24.dp))

                // Match Count Display (e.g., 1/3)
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (query.isNotEmpty() && matchCount > 0) "${currentMatchIndex + 1}/$matchCount" else "0/0",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                // Divider 2
                VerticalDivider(color = StitchBorder, modifier = Modifier.height(24.dp))

                // Prev Match Button
                IconButton(
                    onClick = onPrevMatch,
                    enabled = query.isNotEmpty() && matchCount > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match", tint = if (query.isNotEmpty() && matchCount > 0) Color.White else StitchTextMuted, modifier = Modifier.size(18.dp))
                }

                // Divider 3
                VerticalDivider(color = StitchBorder, modifier = Modifier.height(24.dp))

                // Next Match Button
                IconButton(
                    onClick = onNextMatch,
                    enabled = query.isNotEmpty() && matchCount > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match", tint = if (query.isNotEmpty() && matchCount > 0) Color.White else StitchTextMuted, modifier = Modifier.size(18.dp))
                }

                // Divider 4
                VerticalDivider(color = StitchBorder, modifier = Modifier.height(24.dp))

                // Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Close search", tint = StitchTextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
