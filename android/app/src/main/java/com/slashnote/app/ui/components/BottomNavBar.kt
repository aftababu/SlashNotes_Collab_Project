package com.slashnote.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class StitchBottomTab {
    EDITOR,
    COMMAND_PALETTE,
    SETTINGS
}

@Composable
fun BottomNavBar(
    selectedTab: StitchBottomTab,
    onSelectTab: (StitchBottomTab) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        tonalElevation = 12.dp
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
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
                    activeIcon = Icons.Filled.Edit,
                    inactiveIcon = Icons.Outlined.Edit,
                    contentDescription = "Editor",
                    onClick = { onSelectTab(StitchBottomTab.EDITOR) }
                )
                BottomNavItem(
                    isSelected = selectedTab == StitchBottomTab.COMMAND_PALETTE,
                    label = "Cmds",
                    activeIcon = Icons.Filled.Terminal,
                    inactiveIcon = Icons.Outlined.Terminal,
                    contentDescription = "Command Palette",
                    onClick = { onSelectTab(StitchBottomTab.COMMAND_PALETTE) }
                )
                BottomNavItem(
                    isSelected = selectedTab == StitchBottomTab.SETTINGS,
                    label = "Settings",
                    activeIcon = Icons.Filled.Settings,
                    inactiveIcon = Icons.Outlined.Settings,
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
    activeIcon: androidx.compose.ui.graphics.vector.ImageVector,
    inactiveIcon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.onSurface
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isSelected) activeIcon else inactiveIcon,
            contentDescription = contentDescription,
            tint = if (isSelected) activeColor else inactiveColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isSelected) activeColor else inactiveColor
        )
    }
}
