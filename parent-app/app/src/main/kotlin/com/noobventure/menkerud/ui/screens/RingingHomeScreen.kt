package com.noobventure.menkerud.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noobventure.menkerud.call.LiveKitCallSession
import com.noobventure.menkerud.ui.Texts
import com.noobventure.menkerud.ui.theme.MenkerudColors

/** «Ringer hjem…», over the parent's own picture: they are already in the room, waiting for a child to tap «Svar». */
@Composable
fun RingingHomeScreen(session: LiveKitCallSession?, onCancel: () -> Unit) {
    val own = session?.localVideo?.collectAsStateWithLifecycle()?.value
    val front = session?.frontCamera?.collectAsStateWithLifecycle()?.value ?: true
    Box(Modifier.fillMaxSize().background(MenkerudColors.Night)) {
        if (session != null && own != null) {
            VideoView(session.room, own, mirror = front, modifier = Modifier.fillMaxSize(), fill = true)
        } else {
            NightBackground { }
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                Texts.ringingHome,
                style = MaterialTheme.typography.headlineLarge.copy(shadow = Shadow(Color.Black, blurRadius = 16f)),
                fontWeight = FontWeight.Black,
                color = MenkerudColors.Text,
                modifier = Modifier.padding(top = 32.dp)
            )
            BigButton(Texts.cancel, MenkerudColors.CallRed, onCancel)
        }
    }
}
