package com.noobventure.menkerud.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noobventure.menkerud.R
import com.noobventure.menkerud.setup.PhoneSetup
import com.noobventure.menkerud.setup.SetupItem
import com.noobventure.menkerud.setup.canRing
import com.noobventure.menkerud.setup.missing
import com.noobventure.menkerud.ui.Texts
import com.noobventure.menkerud.ui.setupText
import com.noobventure.menkerud.ui.theme.MenkerudColors

/**
 * The home screen of a paired phone: who it is, whether it is ready to ring, «Ring hjem», and the last call.
 * It is a column of sections, so that another one – messages – can be added below without redesigning it.
 */
@Composable
fun HomeScreen(
    who: String, label: String,
    setup: PhoneSetup, samsung: Boolean,
    lastCall: String?, notice: String?, version: String, callOn: Boolean,
    onRingHome: () -> Unit, onFix: (SetupItem) -> Unit,
    extra: @Composable () -> Unit = {}
) {
    NightBackground {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Image(
                painterResource(if (who == "mor") R.drawable.avatar_mor else R.drawable.avatar_far),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(112.dp).clip(CircleShape)
            )
            Text(
                Texts.homeAs.format(label), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                color = MenkerudColors.Text, textAlign = TextAlign.Center
            )

            Ready(setup, samsung, onFix)

            // While a call is on, the same button leads back to it.
            BigButton(if (callOn) Texts.backToCall else Texts.ringHome, MenkerudColors.GreenMid, onRingHome, Modifier.fillMaxWidth().heightIn(min = 88.dp))
            if (notice != null) {
                Text(notice, style = MaterialTheme.typography.titleMedium, color = MenkerudColors.Amber, textAlign = TextAlign.Center)
            }
            if (lastCall != null) {
                Text(
                    Texts.homeLast.format(lastCall), style = MaterialTheme.typography.bodyLarge,
                    color = MenkerudColors.TextSoft, textAlign = TextAlign.Center
                )
            }

            extra()
            Text(version, style = MaterialTheme.typography.bodySmall, color = MenkerudColors.TextMuted)
        }
    }
}

/** «Klar …», or what the phone still needs – one row for each missing piece; a tap opens what decides it. */
@Composable
private fun Ready(setup: PhoneSetup, samsung: Boolean, onFix: (SetupItem) -> Unit) {
    val lacking = missing(setup)
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(
                if (lacking.isEmpty()) Texts.homeReady else Texts.homeNotReady,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    lacking.isEmpty() -> MenkerudColors.Green
                    canRing(setup) -> MenkerudColors.Amber          // it rings, but not as surely or as well as it could
                    else -> MenkerudColors.CallRed                  // it will not ring at all
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            for (item in lacking) {
                HorizontalDivider(Modifier.padding(top = 12.dp), color = MenkerudColors.GlassLine)
                val tappable = item != SetupItem.PUSH_MISSING       // nothing on the phone can supply what the build lacks
                Row(
                    (if (tappable) Modifier.clickable { onFix(item) } else Modifier)
                        .fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(setupText(item), style = MaterialTheme.typography.bodyLarge, color = MenkerudColors.Text)
                        if (item == SetupItem.BATTERY && samsung) {
                            Text(Texts.samsungHelp, style = MaterialTheme.typography.bodySmall, color = MenkerudColors.TextMuted)
                        }
                    }
                    if (tappable) Text("›", style = MaterialTheme.typography.headlineSmall, color = MenkerudColors.TextSoft)
                }
            }
        }
    }
}
