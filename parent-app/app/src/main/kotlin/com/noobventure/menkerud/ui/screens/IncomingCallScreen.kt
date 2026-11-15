package com.noobventure.menkerud.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noobventure.menkerud.R
import com.noobventure.menkerud.ui.Texts
import com.noobventure.menkerud.ui.theme.MenkerudColors

/** «Det ringer hjemmefra»: the screen Android opens over the lock screen, and the one behind the banner. */
@Composable
fun IncomingCallScreen(needCamMic: Boolean, onAnswer: () -> Unit, onDecline: () -> Unit, onOpenSettings: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "ringing").animateFloat(
        initialValue = 1f, targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse"
    )
    NightBackground {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.badge_home), contentDescription = null, modifier = Modifier.size(200.dp).scale(pulse))
                Spacer(Modifier.height(20.dp))
                Text(
                    Texts.ringsFromHome,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MenkerudColors.Text,
                    textAlign = TextAlign.Center
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (needCamMic) {
                    Text(
                        Texts.needCamMic,
                        style = MaterialTheme.typography.titleMedium,
                        color = MenkerudColors.Amber,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    QuietButton(Texts.openSettings, onOpenSettings)
                    Spacer(Modifier.height(20.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigButton(Texts.notNow, MenkerudColors.CallRed, onDecline, Modifier.weight(1f))
                    BigButton(Texts.answer, MenkerudColors.CallGreen, onAnswer, Modifier.weight(1f))
                }
            }
        }
    }
}
