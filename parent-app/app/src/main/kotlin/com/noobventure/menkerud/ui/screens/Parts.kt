package com.noobventure.menkerud.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noobventure.menkerud.R
import com.noobventure.menkerud.ui.theme.MenkerudColors

/** The kiosk's night picture under a dark veil: what every screen of the app stands on. */
@Composable
fun NightBackground(modifier: Modifier = Modifier, veil: Color = MenkerudColors.Veil, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_night),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(veil))
        content()
    }
}

/** The kiosk's big round-ended button: a strong colour, a light rim, heavy white letters. */
@Composable
fun BigButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
        modifier = modifier.heightIn(min = 60.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
    }
}

/** A quieter button for the things beside the main one. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MenkerudColors.Glass, contentColor = Color.White),
        border = BorderStroke(1.dp, MenkerudColors.GlassLine),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
