package com.noobventure.menkerud.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noobventure.menkerud.call.LiveKitCallSession
import com.noobventure.menkerud.ui.Texts
import com.noobventure.menkerud.ui.theme.MenkerudColors
import kotlinx.coroutines.launch

/**
 * The call: the picture from home, one's own small in a corner, and the buttons. The picture from home is wider than
 * a phone held upright, so it is shown whole, as a band across the screen – and the screen turns with the phone, so
 * that it fills the screen when the phone is turned.
 */
@Composable
fun InCallScreen(session: LiveKitCallSession?, onHangUp: () -> Unit) {
    val theirs = session?.remoteVideo?.collectAsStateWithLifecycle()?.value
    val own = session?.localVideo?.collectAsStateWithLifecycle()?.value
    val microphoneOn = session?.microphoneOn?.collectAsStateWithLifecycle()?.value ?: false
    val cameraOn = session?.cameraOn?.collectAsStateWithLifecycle()?.value ?: false
    val front = session?.frontCamera?.collectAsStateWithLifecycle()?.value ?: true
    val upright = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (session != null) {
            VideoView(session.room, theirs, mirror = false, modifier = Modifier.fillMaxSize())
        }
        if (theirs == null) {
            Text(
                Texts.connecting,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MenkerudColors.Text,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            if (session != null && cameraOn && own != null) {
                val shape = RoundedCornerShape(16.dp)
                VideoView(
                    session.room, own, mirror = front, fill = true,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .size(width = if (upright) 96.dp else 128.dp, height = if (upright) 128.dp else 96.dp)
                        .clip(shape)
                        .border(BorderStroke(1.dp, MenkerudColors.GlassLine), shape)
                )
            }
            val buttons: @Composable () -> Unit = {
                ToggleButton(if (microphoneOn) Texts.micOn else Texts.micOff, on = microphoneOn) {
                    scope.launch { session?.setMicrophone(!microphoneOn) }
                }
                ToggleButton(if (cameraOn) Texts.camOn else Texts.camOff, on = cameraOn) {
                    scope.launch { session?.setCamera(!cameraOn) }
                }
                QuietButton(Texts.camFlip, onClick = { session?.switchCamera() })
            }
            if (upright) {
                Column(
                    Modifier.align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (theirs != null) Text(Texts.turnPhone, style = MaterialTheme.typography.bodyMedium, color = MenkerudColors.TextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
                    BigButton(Texts.hangUp, MenkerudColors.CallRed, onHangUp)
                }
            } else {
                Row(
                    Modifier.align(Alignment.BottomCenter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    buttons()
                    BigButton(Texts.hangUp, MenkerudColors.CallRed, onHangUp)
                }
            }
        }
    }
}

/** A button that says how things are («Lyd på» / «Lyd av») and turns red when its thing is off. */
@Composable
private fun ToggleButton(text: String, on: Boolean, onClick: () -> Unit) {
    if (on) QuietButton(text, onClick) else BigButtonSmall(text, onClick)
}

@Composable
private fun BigButtonSmall(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MenkerudColors.CallRed, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
