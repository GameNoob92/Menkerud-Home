package com.noobventure.menkerud.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noobventure.menkerud.R
import com.noobventure.menkerud.ui.Texts
import com.noobventure.menkerud.ui.theme.MenkerudColors

/**
 * The first screen of a phone that is not paired: a key, typed or scanned.
 * `notice`: why the phone is asking (again); `message`: what was wrong with the last attempt.
 */
@Composable
fun PairingScreen(
    key: String, onKey: (String) -> Unit,
    notice: String?, message: String?, busy: Boolean,
    onGo: () -> Unit, onScan: () -> Unit
) {
    NightBackground {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(painterResource(R.drawable.badge_home), contentDescription = null, modifier = Modifier.size(120.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                Texts.pairTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
                color = MenkerudColors.Text, textAlign = TextAlign.Center
            )
            if (notice != null) {
                Spacer(Modifier.height(12.dp))
                Text(notice, style = MaterialTheme.typography.titleMedium, color = MenkerudColors.Amber, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            Text(Texts.pairHint, style = MaterialTheme.typography.bodyLarge, color = MenkerudColors.TextSoft, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = onKey,
                        label = { Text(Texts.pairKeyLabel) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { onGo() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (message != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(message, style = MaterialTheme.typography.titleMedium, color = MenkerudColors.Amber, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(14.dp))
                    BigButton(
                        if (busy) Texts.connecting else Texts.pairGo, MenkerudColors.GreenMid, onGo,
                        Modifier.fillMaxWidth(), enabled = !busy && key.isNotBlank()
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            QuietButton(Texts.pairScan, onScan)
        }
    }
}
