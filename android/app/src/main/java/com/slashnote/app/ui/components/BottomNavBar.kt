package com.slashnote.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slashnote.app.ui.theme.StitchAccentCoral
import com.slashnote.app.ui.theme.StitchBackground
import com.slashnote.app.ui.theme.StitchBorder
import com.slashnote.app.ui.theme.StitchTextMuted

enum class StitchBottomTab {
    EDITOR,
    SEARCH,
    SETTINGS
}

@Composable
fun BottomNavBar(
    selectedTab: StitchBottomTab,
    onSelectTab: (StitchBottomTab) -> Unit
) {
    Surface(
        color = StitchBackground,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        tonalElevation = 12.dp
    ) {
        Column {
            HorizontalDivider(color = StitchBorder, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavItem(
                    isSelected = selectedTab == StitchBottomTab.EDITOR,
                    label = "Editor",
                    icon = Icons.Default.Edit,
                    contentDescription = "Editor",
                    onClick = { onSelectTab(StitchBottomTab.EDITOR) }
                )
                BottomNavItem(
                    isSelected = selectedTab == StitchBottomTab.SEARCH,
                    label = "Search",
                    icon = Icons.Default.Search,
                    contentDescription = "Search",
                    onClick = { onSelectTab(StitchBottomTab.SEARCH) }
                )
                BottomNavItem(
                    isSelected = selectedTab == StitchBottomTab.SETTINGS,
                    label = "Settings",
                    icon = Icons.Default.Settings,
                    contentDescription = "Settings",
                    onClick = { onSelectTab(StitchBottomTab.SETTINGS) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    isSelected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isSelected) StitchAccentCoral else StitchTextMuted,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) StitchAccentCoral else StitchTextMuted
        )
    }
}
