package com.slashnote.app.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun FormattingToolbar(
    onTriggerSlashMenu: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        color = StitchBackground,
        border = BorderStroke(1.dp, StitchBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onTriggerSlashMenu,
                shape = RoundedCornerShape(8.dp),
                color = StitchAccentCoral,
                contentColor = Color.White,
                modifier = Modifier.size(width = 36.dp, height = 32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "/",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
